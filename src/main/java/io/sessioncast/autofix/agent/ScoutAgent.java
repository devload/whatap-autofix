package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.Issue;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.MetricProfile;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.model.Pipeline.Severity;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import io.sessioncast.core.SessionCastClient;
import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;
import io.sessioncast.autofix.controller.SettingsController;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Scout Agent — 2단계 AI 기반 메트릭 분석
 *
 * 1단계 (Discovery): 프로젝트 연결 시 spot 데이터를 AI에게 보내
 *    어떤 메트릭이 있고 무엇을 모니터링해야 하는지 판단.
 *    AI가 MXQL 쿼리와 임계값을 포함한 MetricProfile을 생성.
 *
 * 2단계 (Monitoring): AI가 생성한 프로파일의 메트릭만 선별적으로 조회.
 *    MXQL로 필요한 시계열 데이터를 가져와 이상 탐지.
 *    전체 raw 데이터를 매번 보내지 않아 경량화.
 */
@Slf4j
@Component
public class ScoutAgent {

    private final WhatapApiClient whatapClient;
    private final RuleEngine ruleEngine;
    private final PipelineService pipelineService;
    private final AnalyzerAgent analyzerAgent;
    private final AutofixProperties props;
    private final SessionCastClient sessionCastClient;

    private int pollCount = 0;
    @Getter
    private volatile MetricProfile currentProfile;
    private volatile boolean discoveryInProgress = false;
    private int discoveryRetries = 0;
    private static final int REDISCOVERY_CYCLES = 60; // 60폴링(30분)마다 재탐색

    public ScoutAgent(WhatapApiClient whatapClient,
                      RuleEngine ruleEngine,
                      PipelineService pipelineService,
                      AnalyzerAgent analyzerAgent,
                      AutofixProperties props,
                      @Autowired(required = false) SessionCastClient sessionCastClient) {
        this.whatapClient = whatapClient;
        this.ruleEngine = ruleEngine;
        this.pipelineService = pipelineService;
        this.analyzerAgent = analyzerAgent;
        this.props = props;
        this.sessionCastClient = sessionCastClient;
    }

    /** 프로젝트 변경 시 프로파일 초기화 */
    public void resetProfile() {
        this.currentProfile = null;
        this.discoveryRetries = 0;
        this.pollCount = 0;
        log.info("Scout Agent: 메트릭 프로파일 초기화됨 (재탐색 예정)");
    }

    @Scheduled(fixedDelayString = "${autofix.whatap.polling-interval-seconds:30}000")
    public void poll() {
        log.debug("Scout Agent: polling metrics...");

        whatapClient.getSpotMetrics()
                .subscribe(metric -> {
                    metric.setProjectType(props.getWhatap().getProductType());
                    pipelineService.recordMetric(metric);
                    pollCount++;

                    if (sessionCastClient == null) {
                        // SessionCast 미연결: 기존 룰 기반
                        fallbackRuleBased(metric);
                        return;
                    }

                    // 1단계: 프로파일이 없으면 Discovery
                    if (currentProfile == null && !discoveryInProgress) {
                        performDiscovery(metric);
                        return;
                    }

                    // 주기적 재탐색
                    if (pollCount % REDISCOVERY_CYCLES == 0) {
                        performDiscovery(metric);
                        return;
                    }

                    // 2단계: 프로파일 기반 모니터링
                    int aiInterval = props.getWhatap().getAiScoutIntervalCycles();
                    if (currentProfile != null && pollCount % aiInterval == 0) {
                        performProfileBasedMonitoring(metric);
                    } else if (currentProfile == null) {
                        fallbackRuleBased(metric);
                    }
                });
    }

    // ==================== 1단계: Discovery ====================

    /**
     * 1단계 — AI에게 spot 데이터를 보내 프로젝트 메트릭을 탐색.
     * AI가 어떤 메트릭이 중요한지, 임계값은 얼마인지,
     * MXQL 쿼리는 어떻게 작성할지 결정.
     */
    private void performDiscovery(Metric spotMetric) {
        discoveryInProgress = true;
        try {
            Map<String, Object> raw = spotMetric.getRawData();
            if (raw == null || raw.isEmpty()) {
                log.warn("Discovery: raw 데이터가 비어있음, 다음 폴링에서 재시도");
                discoveryInProgress = false;
                return;
            }

            String projectType = props.getWhatap().getProductType();
            String projectName = props.getWhatap().getProjectName();

            StringBuilder spotData = new StringBuilder();
            raw.forEach((k, v) -> spotData.append(String.format("  %s: %s\n", k, v)));

            String prompt = String.format("""
                    당신은 WhaTap 모니터링 전문가입니다.
                    아래 WhaTap Open API `/open/api/json/spot` 응답 데이터를 분석하여,
                    이 프로젝트에서 모니터링해야 할 핵심 메트릭을 선정하고 프로파일을 생성해주세요.

                    ## 프로젝트 정보
                    - 이름: %s
                    - 타입: %s

                    ## Spot API 응답 데이터
                    %s

                    ## 요청사항
                    위 데이터를 분석하여 아래 JSON 형식으로 응답하세요.
                    - 중요한 메트릭만 선별 (최대 10개)
                    - 각 메트릭의 적절한 WARNING/CRITICAL 임계값 설정
                    - 가능하면 MXQL 쿼리 제안 (WhaTap MXQL 문법: CATEGORY, TAGLOAD, SELECT 등)

                    ```json
                    {
                      "targets": [
                        {
                          "key": "메트릭키",
                          "label": "표시명",
                          "warnThreshold": 70.0,
                          "critThreshold": 90.0,
                          "unit": "%%|ms|count|ratio",
                          "mxql": "CATEGORY app_counter\\nTAGLOAD\\nSELECT ...  또는 null",
                          "category": "app_counter|db_pool|browser 등"
                        }
                      ]
                    }
                    ```

                    ### 메트릭 선정 기준
                    - **APM**: cpu, memory, rtime(응답시간), tps, error_rate, actx(활성트랜잭션), dbconn_act(DB풀), apdex
                    - **Browser**: page_load, ajax_time, js_error, lcp, fid, cls
                    - **DB**: active_sessions, lock_wait, slow_query, buffer_hit_ratio
                    - **Server**: cpu, memory, disk_usage, network
                    - 값이 null이거나 0인 메트릭은 제외

                    반드시 JSON만 응답하세요.
                    """,
                    projectName != null ? projectName : "unknown",
                    projectType != null ? projectType : "unknown",
                    spotData.toString());

            String aiModel = SettingsController.getAiModel();
            log.info("Discovery: 메트릭 탐색 요청 중... (projectType: {})", projectType);

            var requestBuilder = LlmChatRequest.builder()
                    .system("WhaTap 모니터링 전문가. 프로젝트 메트릭을 분석하여 모니터링 프로파일을 JSON으로 생성합니다.")
                    .user(prompt);
            if (aiModel != null && !aiModel.isBlank()) {
                requestBuilder.model(aiModel);
            }

            LlmChatResponse response = sessionCastClient.llmChat(requestBuilder.build())
                    .get(3, TimeUnit.MINUTES);

            if (response.hasError()) {
                log.warn("Discovery: LLM 에러: {}", response.error().message());
                discoveryInProgress = false;
                return;
            }

            String content = response.content();
            log.info("Discovery: LLM 응답 수신 ({} chars)", content != null ? content.length() : 0);

            MetricProfile profile = parseDiscoveryResponse(content, projectType, projectName);
            if (profile != null && profile.getTargets() != null && !profile.getTargets().isEmpty()) {
                this.currentProfile = profile;
                this.discoveryRetries = 0;
                log.info("Discovery 완료: {} 메트릭 대상 선정 [{}]",
                        profile.getTargets().size(),
                        profile.getTargets().stream()
                                .map(t -> t.getKey() + "(" + t.getUnit() + ")")
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            } else {
                discoveryRetries++;
                log.warn("Discovery: 프로파일 파싱 실패 (재시도 {}/3)", discoveryRetries);
            }
        } catch (Exception e) {
            log.warn("Discovery 실패: {}", e.getMessage());
            discoveryRetries++;
        } finally {
            discoveryInProgress = false;
        }
    }

    private MetricProfile parseDiscoveryResponse(String content, String projectType, String projectName) {
        try {
            String json = extractJson(content);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);

            var targetsNode = root.has("targets") ? root.get("targets") : root;
            if (!targetsNode.isArray()) return null;

            List<MetricProfile.MonitorTarget> targets = new ArrayList<>();
            for (var node : targetsNode) {
                targets.add(MetricProfile.MonitorTarget.builder()
                        .key(node.has("key") ? node.get("key").asText() : "unknown")
                        .label(node.has("label") ? node.get("label").asText() : "")
                        .warnThreshold(node.has("warnThreshold") ? node.get("warnThreshold").asDouble() : 0)
                        .critThreshold(node.has("critThreshold") ? node.get("critThreshold").asDouble() : 0)
                        .unit(node.has("unit") ? node.get("unit").asText() : "")
                        .mxql(node.has("mxql") && !node.get("mxql").isNull() ? node.get("mxql").asText() : null)
                        .category(node.has("category") ? node.get("category").asText() : "")
                        .build());
            }

            return MetricProfile.builder()
                    .projectType(projectType)
                    .projectName(projectName)
                    .targets(targets)
                    .discoveredAt(Instant.now())
                    .build();
        } catch (Exception e) {
            log.warn("Discovery 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 2단계: Profile-based Monitoring ====================

    /**
     * 2단계 — 프로파일 기반 모니터링.
     * 최신 spot 데이터에서 프로파일의 대상 메트릭만 추출하여
     * 임계값 비교 + AI 분석.
     * 전체 raw 데이터를 보내지 않고 선별된 메트릭만 분석.
     */
    private void performProfileBasedMonitoring(Metric latestMetric) {
        try {
            Map<String, Object> raw = latestMetric.getRawData();
            if (raw == null || raw.isEmpty()) return;

            List<MetricProfile.MonitorTarget> targets = currentProfile.getTargets();
            String projectType = currentProfile.getProjectType();
            String projectName = currentProfile.getProjectName();

            // 프로파일 대상 메트릭만 추출 (경량화)
            StringBuilder selectedMetrics = new StringBuilder();
            List<String> breachedMetrics = new ArrayList<>();

            for (MetricProfile.MonitorTarget target : targets) {
                Object val = raw.get(target.getKey());
                if (val == null) continue;

                double numVal = toDouble(val);
                String status;
                if (target.getCritThreshold() > 0 && numVal >= target.getCritThreshold()) {
                    status = "CRITICAL";
                    breachedMetrics.add(target.getKey());
                } else if (target.getWarnThreshold() > 0 && numVal >= target.getWarnThreshold()) {
                    status = "WARNING";
                    breachedMetrics.add(target.getKey());
                } else if ("ratio".equals(target.getUnit()) && target.getWarnThreshold() > 0 && numVal <= target.getWarnThreshold()) {
                    // apdex 같은 비율 메트릭은 낮을수록 나쁨
                    status = numVal <= target.getCritThreshold() ? "CRITICAL" : "WARNING";
                    breachedMetrics.add(target.getKey());
                } else {
                    status = "OK";
                }

                selectedMetrics.append(String.format("  %s (%s): %s %s [warn:%s, crit:%s] → %s\n",
                        target.getLabel(), target.getKey(),
                        formatValue(numVal, target.getUnit()), target.getUnit(),
                        target.getWarnThreshold(), target.getCritThreshold(), status));
            }

            // MXQL 시계열 데이터 보강 (임계값 초과 항목만)
            StringBuilder mxqlResults = new StringBuilder();
            if (!breachedMetrics.isEmpty()) {
                long etime = System.currentTimeMillis();
                long stime = etime - 300_000; // 최근 5분
                for (MetricProfile.MonitorTarget target : targets) {
                    if (target.getMxql() != null && breachedMetrics.contains(target.getKey())) {
                        try {
                            Map mxqlResult = whatapClient.executeMxql(target.getMxql(), stime, etime)
                                    .block(java.time.Duration.ofSeconds(10));
                            if (mxqlResult != null && !mxqlResult.isEmpty()) {
                                mxqlResults.append(String.format("  [MXQL: %s] %s\n", target.getKey(), mxqlResult));
                            }
                        } catch (Exception e) {
                            log.debug("MXQL 조회 실패 ({}): {}", target.getKey(), e.getMessage());
                        }
                    }
                }
            }

            // 이상 항목이 없으면 AI 호출 생략 (비용 절감)
            if (breachedMetrics.isEmpty()) {
                log.debug("Scout Agent: 프로파일 기준 모든 메트릭 정상 (AI 호출 생략)");
                return;
            }

            // AI에게 선별된 데이터만 전달
            String prompt = String.format("""
                    프로젝트 "%s" (%s)의 메트릭 이상을 분석해주세요.

                    ## 프로파일 기반 모니터링 결과 (선별된 메트릭)
                    %s

                    %s

                    ## 요청
                    WARNING 또는 CRITICAL 상태인 항목에 대해 이슈를 생성하세요.
                    JSON 배열로만 응답하세요:
                    [
                      {
                        "type": "이슈타입_대문자",
                        "metric": "메트릭키",
                        "value": 현재값,
                        "threshold": 기준값,
                        "severity": "CRITICAL|WARNING",
                        "description": "한국어 1줄 설명"
                      }
                    ]
                    """,
                    projectName != null ? projectName : "unknown",
                    projectType != null ? projectType : "unknown",
                    selectedMetrics,
                    mxqlResults.length() > 0 ? "## MXQL 시계열 보강 데이터\n" + mxqlResults : "");

            String aiModel = SettingsController.getAiModel();
            log.info("AI Scout(프로파일): {} 이상 메트릭 분석 요청 ({}건)", projectType, breachedMetrics.size());

            var requestBuilder = LlmChatRequest.builder()
                    .system("WhaTap 모니터링 전문가. 선별된 메트릭 이상을 분석합니다. JSON 배열만 응답하세요.")
                    .user(prompt);
            if (aiModel != null && !aiModel.isBlank()) {
                requestBuilder.model(aiModel);
            }

            LlmChatResponse response = sessionCastClient.llmChat(requestBuilder.build())
                    .get(2, TimeUnit.MINUTES);

            if (response.hasError()) {
                log.warn("AI Scout(프로파일): LLM 에러: {}", response.error().message());
                return;
            }

            String content = response.content();
            log.info("AI Scout(프로파일): LLM 응답 ({} chars)", content != null ? content.length() : 0);

            List<Issue> issues = parseAiScoutResponse(content);
            if (issues.isEmpty()) {
                log.info("AI Scout(프로파일): AI 판단 이상 없음");
            } else {
                log.info("AI Scout(프로파일): {} 이슈 감지", issues.size());
                for (Issue issue : issues) {
                    processIssue(issue);
                }
            }

        } catch (Exception e) {
            log.warn("AI Scout(프로파일) 실패: {}", e.getMessage());
            fallbackRuleBased(latestMetric);
        }
    }

    // ==================== 공통 유틸 ====================

    private void fallbackRuleBased(Metric metric) {
        List<Issue> issues = ruleEngine.evaluate(metric);
        if (issues.isEmpty()) {
            log.debug("Scout Agent: all metrics normal (rule-based)");
        } else {
            log.info("Scout Agent: detected {} issues (rule-based)", issues.size());
            for (Issue issue : issues) {
                processIssue(issue);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Issue> parseAiScoutResponse(String content) {
        List<Issue> issues = new ArrayList<>();
        try {
            String json = extractJson(content);
            int arrStart = json.indexOf('[');
            int arrEnd = json.lastIndexOf(']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                json = json.substring(arrStart, arrEnd + 1);
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var arr = mapper.readTree(json);

            for (var node : arr) {
                String type = node.has("type") ? node.get("type").asText() : "UNKNOWN";
                String metric = node.has("metric") ? node.get("metric").asText() : "unknown";
                double value = node.has("value") ? node.get("value").asDouble() : 0;
                double threshold = node.has("threshold") ? node.get("threshold").asDouble() : 0;
                String severity = node.has("severity") ? node.get("severity").asText() : "WARNING";
                String description = node.has("description") ? node.get("description").asText() : type;

                issues.add(Issue.builder()
                        .type(type)
                        .metric(metric)
                        .value(value)
                        .threshold(threshold)
                        .description(description)
                        .detectedAt(Instant.now())
                        .rawData(Map.of("source", "ai_scout", "severity", severity))
                        .build());
            }
        } catch (Exception e) {
            log.warn("AI Scout 응답 파싱 실패: {}", e.getMessage());
        }
        return issues;
    }

    private String extractJson(String content) {
        String json = content.trim();
        if (json.contains("```json")) {
            int start = json.indexOf("```json") + 7;
            int end = json.indexOf("```", start);
            if (end > start) json = json.substring(start, end).trim();
        } else if (json.contains("```")) {
            int start = json.indexOf("```") + 3;
            int end = json.indexOf("```", start);
            if (end > start) json = json.substring(start, end).trim();
        }
        return json;
    }

    private void processIssue(Issue issue) {
        String severityStr = issue.getRawData() != null
                ? String.valueOf(issue.getRawData().getOrDefault("severity", "WARNING"))
                : "WARNING";
        Severity severity;
        try {
            severity = Severity.valueOf(severityStr);
        } catch (Exception e) {
            Rule rule = ruleEngine.findRule(issue.getType());
            severity = rule != null ? rule.getSeverity() : Severity.WARNING;
        }

        Pipeline pipeline = pipelineService.createPipeline(issue, severity);

        if (pipeline.getCurrentStage() == Pipeline.Stage.SCOUT) {
            pipeline.advanceTo(Pipeline.Stage.ANALYZER);
            pipeline.addLog(Pipeline.Stage.SCOUT, "Analyzer 에이전트로 전달");
            analyzerAgent.analyze(pipeline);
        }
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }

    private String formatValue(double val, String unit) {
        if ("ms".equals(unit) || "count".equals(unit)) return String.valueOf((long) val);
        return String.format("%.2f", val);
    }
}
