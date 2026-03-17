package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.model.AnalysisResult;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.controller.SettingsController;
import io.sessioncast.autofix.service.ClaudeLocalService;
import io.sessioncast.autofix.service.FeedbackService;
import io.sessioncast.autofix.service.GlmService;
import io.sessioncast.autofix.service.PipelineService;
import io.sessioncast.core.SessionCastClient;
import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class AnalyzerAgent {

    private final WhatapApiClient whatapClient;
    private final PipelineService pipelineService;
    private final FixerAgent fixerAgent;
    private final SessionCastClient sessionCastClient;
    private final GlmService glmService;
    private final FeedbackService feedbackService;
    private final ClaudeLocalService claudeLocalService;

    public AnalyzerAgent(WhatapApiClient whatapClient,
                         PipelineService pipelineService,
                         FixerAgent fixerAgent,
                         GlmService glmService,
                         FeedbackService feedbackService,
                         ClaudeLocalService claudeLocalService,
                         @Autowired(required = false) SessionCastClient sessionCastClient) {
        this.whatapClient = whatapClient;
        this.pipelineService = pipelineService;
        this.fixerAgent = fixerAgent;
        this.glmService = glmService;
        this.feedbackService = feedbackService;
        this.claudeLocalService = claudeLocalService;
        this.sessionCastClient = sessionCastClient;
    }

    public void analyze(Pipeline pipeline) {
        log.info("Analyzer Agent: analyzing {} (pipeline {})", pipeline.getIssueType(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.ANALYZER, "분석 시작: " + pipeline.getIssueType());

        // 1) 메트릭 히스토리 수집 (최근 10건 = 약 5분)
        String timeSeriesData = buildTimeSeriesContext();

        // 2) 트랜잭션 TOP 조회 (MXQL)
        String txTopData = fetchTransactionTop();

        Map<String, Object> correlationData = Map.of();

        try {
            String aiProvider = SettingsController.getAiProvider();
            boolean hasGlm = "glm".equals(aiProvider) && !SettingsController.getGlmApiToken().isBlank();
            boolean hasLocal = "claude-local".equals(aiProvider) && claudeLocalService.isAvailable();
            boolean hasSc = sessionCastClient != null && sessionCastClient.isConnected();

            if (hasGlm || hasLocal || hasSc) {
                performLlmAnalysis(pipeline, correlationData, timeSeriesData, txTopData);
            } else {
                performFallbackAnalysis(pipeline, correlationData);
            }
        } catch (Exception e) {
            log.error("Analyzer: analysis failed, falling back for pipeline {}", pipeline.getId(), e);
            performFallbackAnalysis(pipeline, correlationData);
        }
    }

    /**
     * 메트릭 히스토리에서 최근 10건의 핵심 메트릭 추이를 텍스트로 구성.
     * 10건 이상이면 트렌드 분석도 추가.
     */
    private String buildTimeSeriesContext() {
        List<io.sessioncast.autofix.model.Metric> history = pipelineService.getMetricHistory(10, null);
        if (history.isEmpty()) return "히스토리 없음 (스냅샷 기반 분석)";

        StringBuilder sb = new StringBuilder();
        sb.append("시간 | cpu | actx | rtime | tps | dbconn_act | act_method | act_httpc | error_rate\n");
        sb.append("--- | --- | --- | --- | --- | --- | --- | --- | ---\n");
        for (var m : history) {
            Map<String, Object> raw = m.getRawData();
            if (raw == null) continue;
            String time = m.getCollectedAt() != null
                    ? java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(m.getCollectedAt())
                    : "?";
            sb.append(String.format("%s | %s | %s | %s | %s | %s | %s | %s | %s\n",
                    time,
                    raw.getOrDefault("cpu", "-"),
                    raw.getOrDefault("actx", "-"),
                    raw.getOrDefault("rtime", "-"),
                    raw.getOrDefault("tps", "-"),
                    raw.getOrDefault("dbconn_act", "-"),
                    raw.getOrDefault("act_method", "-"),
                    raw.getOrDefault("act_httpc", "-"),
                    raw.getOrDefault("error_rate", raw.getOrDefault("error", "-"))
            ));
        }

        // 트렌드 감지: 히스토리 3건 이상이면 변화량 계산
        if (history.size() >= 3) {
            sb.append("\n### 트렌드 감지 (최초 → 최신)\n");
            var first = history.get(0).getRawData();
            var last = history.get(history.size() - 1).getRawData();
            if (first != null && last != null) {
                for (String key : List.of("cpu", "actx", "rtime", "tps", "dbconn_act")) {
                    double v1 = toDouble(first.get(key));
                    double v2 = toDouble(last.get(key));
                    if (v1 > 0 || v2 > 0) {
                        double change = v2 - v1;
                        double pct = v1 > 0 ? (change / v1) * 100 : 0;
                        String trend = change > 0 ? "↑ 증가" : change < 0 ? "↓ 감소" : "→ 유지";
                        sb.append(String.format("  %s: %.1f → %.1f (%s, %.1f%%)\n", key, v1, v2, trend, pct));
                    }
                }
            }
        }

        return sb.toString();
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) { try { return Double.parseDouble((String) val); } catch (Exception e) {} }
        return 0.0;
    }

    /**
     * MXQL로 슬로우 트랜잭션/HTTP 호출/SQL 정보 조회.
     */
    private String fetchTransactionTop() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        long fiveMinAgo = now - 300_000;

        // 1) app_counter (기본 메트릭)
        queryMxql(sb, "app_counter", fiveMinAgo, now);

        // 2) app_host_resource (호스트 리소스)
        queryMxql(sb, "app_host_resource", fiveMinAgo, now);

        return sb.toString();
    }

    private void queryMxql(StringBuilder sb, String category, long stime, long etime) {
        try {
            String mxql = "CATEGORY " + category + "\nTAGLOAD\nSELECT";
            Map result = reactor.core.publisher.Mono.fromCallable(() ->
                    whatapClient.executeMxql(mxql, stime, etime)
                            .timeout(Duration.ofSeconds(10))
                            .block()
            ).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
             .block(Duration.ofSeconds(15));
            if (result != null && !result.isEmpty()) {
                String str = result.toString();
                sb.append(String.format("[%s] %s\n", category, str.substring(0, Math.min(300, str.length()))));
            }
        } catch (Exception e) {
            log.debug("Analyzer: MXQL {} query failed: {}", category, e.getMessage());
        }
    }

    /**
     * SessionCast Relay → Request analysis from local Claude Code
     */
    private void performLlmAnalysis(Pipeline pipeline, Map<String, Object> correlationData,
                                      String timeSeriesData, String txTopData) {
        String issueType = pipeline.getIssueType();
        double value = pipeline.getIssue().getValue();
        double threshold = pipeline.getIssue().getThreshold();

        Metric latest = pipelineService.getLatestMetric();
        String metricsSnapshot = formatMetrics(latest);

        // Build prompt with time series + correlation context
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(String.format("""
                ## 감지된 이슈
                타입: %s | 현재: %.1f | 임계값: %.1f

                ## 현재 메트릭 스냅샷
                %s

                ## 최근 5분 메트릭 추이 (30초 간격)
                %s
                """, issueType, value, threshold, metricsSnapshot, timeSeriesData));

        if (txTopData != null && !txTopData.isBlank()) {
            promptBuilder.append("\n## 추가 조회 데이터\n").append(txTopData).append("\n");
        }

        // 과거 피드백 반영
        String feedbackCtx = feedbackService.buildFeedbackContext(issueType);
        if (!feedbackCtx.isBlank()) {
            promptBuilder.append(feedbackCtx);
        }

        promptBuilder.append("""

                ## 분석 요청
                위 데이터를 기반으로 연관 분석을 수행하세요.

                반드시 한국어, 아래 JSON으로 응답. 코드펜스(```) 절대 금지:

                {
                  "rootCause": "A → B → C 형태의 인과관계 체인으로 근본 원인 설명",
                  "confidence": 0.85,
                  "correlatedMetrics": [
                    "원인메트릭:값 → 결과메트릭:값",
                    "원인메트릭:값 → 결과메트릭:값"
                  ],
                  "reasoning": [
                    "시계열 추이에서 관찰된 사실과 선후관계",
                    "메트릭 간 인과관계 추론 근거",
                    "최종 결론 및 근거의 한계점"
                  ],
                  "recommendation": "권장 조치"
                }

                ### 필드 규칙
                - rootCause: 반드시 "A → B → C → D" 화살표(→)로 **최소 3단계** 인과관계 체인. 짧게 쓰지 마세요.
                - correlatedMetrics: 최소 6개. 3단계 이상 체인을 포함할 것.
                  - 1차 인과: 직접 원인 (예: "tps:215 → actx:502 → cpu:87%")
                  - 2차 파급: 간접 영향 (예: "rtime:2500ms → apdex:0.68", "dbconn_act:600 → dbconn_idle:400")
                  - **3단계+ 체인 필수**: 예: "user:1010 → tps:230 → actx:500 → rtime:2500ms"
                  - 제공된 메트릭 데이터에 있는 **모든 메트릭**(cpucore, dbconn_idle, act_sql, act_socket, txcount, inact_agent, threadpool_queue, host, error 등)을 분석에 참조하고, 이상치가 아니더라도 정상 범위 확인 결과를 reasoning에 포함하세요.
                - reasoning: 문자열 배열. 3~5개.
                  - 1번: 시계열 추이 또는 스냅샷에서 관찰된 사실 (모든 핵심 메트릭 값 언급)
                  - 2번: 메트릭 간 인과관계 추론 (왜 A가 B를 유발했는지)
                  - 3번: 정상 범위 메트릭 확인 (error, act_sql, threadpool 등이 정상인지)
                  - 4번: 결론 + 근거의 한계 (데이터 부족 시 명시)
                - confidence: 시계열 히스토리가 충분하면 0.8+, 스냅샷만이면 0.5~0.7
                """);

        String prompt = promptBuilder.toString();

        String aiProvider = SettingsController.getAiProvider();
        String aiModel = SettingsController.getAiModel();
        pipeline.addLog(Pipeline.Stage.ANALYZER, "LLM 분석 요청 중... (provider: " + aiProvider + ")");

        var requestBuilder = LlmChatRequest.builder()
                .system("서버 모니터링 전문가. 시계열 데이터에서 메트릭 간 선후관계와 인과관계를 분석. 한국어 JSON만 응답. 코드펜스 금지.")
                .user(prompt);
        if (aiModel != null && !aiModel.isBlank()) {
            requestBuilder.model(aiModel);
        }
        LlmChatRequest request = requestBuilder.build();

        try {
            LlmChatResponse response;
            
            if ("glm".equals(aiProvider)) {
                // GLM 직접 호출
                String glmToken = SettingsController.getGlmApiToken();
                String glmUrl = SettingsController.getGlmBaseUrl();
                if (glmToken == null || glmToken.isBlank()) {
                    throw new IllegalStateException("GLM API 토큰이 설정되지 않았습니다");
                }
                response = glmService.chat(glmUrl, glmToken, request).get(5, java.util.concurrent.TimeUnit.MINUTES);
            } else if ("claude-local".equals(aiProvider)) {
                // Claude Code 로컬 CLI 직접 호출 (토큰 불필요)
                response = claudeLocalService.chat(request).get(5, java.util.concurrent.TimeUnit.MINUTES);
            } else {
                // SessionCast Relay 호출
                if (sessionCastClient == null) {
                    throw new IllegalStateException("SessionCast가 연결되지 않았습니다");
                }
                response = sessionCastClient.llmChat(request)
                        .get(5, java.util.concurrent.TimeUnit.MINUTES);
            }

            if (response.hasError()) {
                log.warn("LLM response error: {}", response.error().message());
                performFallbackAnalysis(pipeline, correlationData);
                return;
            }

            String content = response.content();
            log.info("Analyzer: LLM response received ({} chars)", content != null ? content.length() : 0);
            pipeline.addLog(Pipeline.Stage.ANALYZER, "LLM 분석 완료");

            // Parse LLM response
            AnalysisResult result = parseLlmResponse(content, pipeline);
            pipelineService.setAnalysis(pipeline.getId(), result);

            log.info("Analyzer: LLM root cause='{}' confidence={}% for pipeline {}",
                    result.getRootCause(), Math.round(result.getConfidence() * 100), pipeline.getId());

        } catch (Exception e) {
            log.warn("LLM analysis failed, using fallback: {}", e.getMessage());
            pipeline.addLog(Pipeline.Stage.ANALYZER, "LLM 분석 실패 — 폴백 분석으로 전환");
            performFallbackAnalysis(pipeline, correlationData);
            return;
        }

        // Advance to Fixer
        pipeline.advanceTo(Pipeline.Stage.FIXER);
        pipeline.addLog(Pipeline.Stage.ANALYZER, "Fixer 에이전트로 전달");
        fixerAgent.fix(pipeline);
    }

    private AnalysisResult parseLlmResponse(String content, Pipeline pipeline) {
        try {
            // Strip code fences if present
            String json = content;
            if (json.contains("```json")) {
                int start = json.indexOf("```json") + 7;
                int end = json.indexOf("```", start);
                json = (end > start) ? json.substring(start, end).trim() : json.substring(start).trim();
            } else if (json.contains("```")) {
                int start = json.indexOf("```") + 3;
                int end = json.indexOf("```", start);
                json = (end > start) ? json.substring(start, end).trim() : json.substring(start).trim();
            }
            int jsonStart = json.indexOf('{');
            int jsonEnd = json.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = json.substring(jsonStart, jsonEnd + 1);
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String rootCause = node.has("rootCause") ? node.get("rootCause").asText() : "LLM 분석 결과";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.80;

            // reasoning: 배열이면 join, 문자열이면 그대로
            List<String> reasoningSteps = new ArrayList<>();
            if (node.has("reasoning")) {
                var rNode = node.get("reasoning");
                if (rNode.isArray()) {
                    rNode.forEach(n -> reasoningSteps.add(n.asText()));
                } else {
                    reasoningSteps.add(rNode.asText());
                }
            }

            List<String> correlated = new ArrayList<>();
            if (node.has("correlatedMetrics") && node.get("correlatedMetrics").isArray()) {
                node.get("correlatedMetrics").forEach(n -> correlated.add(n.asText()));
            }

            Map<String, Object> details = new HashMap<>();
            details.put("llm_response", content);
            if (!reasoningSteps.isEmpty()) {
                details.put("reasoning", reasoningSteps);
            }

            return AnalysisResult.builder()
                    .rootCause(rootCause)
                    .confidence(confidence)
                    .correlatedMetrics(correlated)
                    .linkedIssues(findLinkedIssues(pipeline))
                    .details(details)
                    .build();

        } catch (Exception e) {
            log.warn("LLM response parsing failed, using raw text as rootCause");
            return AnalysisResult.builder()
                    .rootCause(content != null && content.length() > 200 ? content.substring(0, 200) : content)
                    .confidence(0.70)
                    .correlatedMetrics(List.of())
                    .linkedIssues(findLinkedIssues(pipeline))
                    .details(Map.of("llm_response", content != null ? content : ""))
                    .build();
        }
    }

    private String formatMetrics(Metric m) {
        if (m == null) return "메트릭 데이터 없음";

        // raw 데이터가 있으면 전체를 보여줌 (프로젝트 타입 무관)
        Map<String, Object> raw = m.getRawData();
        if (raw != null && !raw.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            if (m.getProjectType() != null) {
                sb.append("프로젝트 타입: ").append(m.getProjectType()).append("\n");
            }
            raw.forEach((k, v) -> sb.append(String.format("%s: %s\n", k, v)));
            return sb.toString();
        }

        // 폴백: APM 형식
        return String.format(
                "CPU: %.1f%%, Memory: %.1f%%, TPS: %d, ErrorRate: %.1f%%, ActiveTx: %d, ResponseTime: %dms, DBPool: %.1f%%",
                m.getCpu(), m.getMemory(), m.getTps(), m.getErrorRate(),
                m.getActiveTransaction(), m.getResponseTime(), m.getDbPoolUsage());
    }

    /**
     * Local rule-based analysis when SessionCast is not connected (existing logic)
     */
    private void performFallbackAnalysis(Pipeline pipeline, Map<String, Object> correlationData) {
        String issueType = pipeline.getIssueType();
        double value = pipeline.getIssue().getValue();

        Metric latest = pipelineService.getLatestMetric();
        List<String> correlated = new ArrayList<>();
        if (latest != null) {
            if (latest.getCpu() > 80) correlated.add("cpu:" + latest.getCpu());
            if (latest.getMemory() > 70) correlated.add("memory:" + latest.getMemory());
            if (latest.getErrorRate() > 1) correlated.add("error_rate:" + latest.getErrorRate());
            if (latest.getActiveTransaction() > 50) correlated.add("active_tx:" + latest.getActiveTransaction());
            if (latest.getResponseTime() > 2000) correlated.add("resp_time:" + latest.getResponseTime());
            if (latest.getDbPoolUsage() > 70) correlated.add("db_pool:" + latest.getDbPoolUsage());
        }

        String rootCause = determineRootCause(issueType, value, correlated);
        double confidence = calculateConfidence(issueType, correlated.size());

        AnalysisResult result = AnalysisResult.builder()
                .rootCause(rootCause)
                .confidence(confidence)
                .correlatedMetrics(correlated)
                .linkedIssues(findLinkedIssues(pipeline))
                .details(Map.of("correlation_data", correlationData, "mode", "fallback"))
                .build();

        pipelineService.setAnalysis(pipeline.getId(), result);
        log.info("Analyzer (fallback): root cause='{}' confidence={}% for pipeline {}",
                rootCause, Math.round(confidence * 100), pipeline.getId());

        pipeline.advanceTo(Pipeline.Stage.FIXER);
        pipeline.addLog(Pipeline.Stage.ANALYZER, "Fixer 에이전트로 전달");
        fixerAgent.fix(pipeline);
    }

    private String determineRootCause(String issueType, double value, List<String> correlated) {
        return switch (issueType) {
            case "CPU_HIGH" -> correlated.stream().anyMatch(c -> c.startsWith("active_tx"))
                    ? "Active Transaction 급증으로 인한 CPU 포화"
                    : "CPU 사용량 과다 — 연산 집약적 작업 가능성";
            case "MEMORY_HIGH" -> "메모리 사용량 과다 — 메모리 누수 또는 캐시 오버플로우 가능성";
            case "DISK_FULL" -> "로그 누적 또는 임시 파일 축적";
            case "ERROR_SPIKE" -> correlated.stream().anyMatch(c -> c.startsWith("cpu"))
                    ? "높은 CPU로 인한 에러 급증 가능성"
                    : "에러율 증가 — 코드 회귀 또는 의존성 장애 가능성";
            case "RESPONSE_SLOW" -> correlated.stream().anyMatch(c -> c.startsWith("db_pool"))
                    ? "DB 풀 포화로 인한 응답 지연"
                    : "응답 시간 저하";
            case "DB_POOL_EXHAUSTED" -> "커넥션 풀 고갈 임박 — 커넥션 누수 가능성";
            default -> issueType + "에 대한 원인 불명";
        };
    }

    private double calculateConfidence(String issueType, int correlationCount) {
        double base = switch (issueType) {
            case "DISK_FULL" -> 0.90;
            case "CPU_HIGH", "ERROR_SPIKE" -> 0.75;
            case "DB_POOL_EXHAUSTED" -> 0.70;
            default -> 0.60;
        };
        return Math.min(0.99, base + correlationCount * 0.03);
    }

    private List<String> findLinkedIssues(Pipeline pipeline) {
        List<String> linked = new ArrayList<>();
        for (Pipeline p : pipelineService.getPipelinesByStatus(Pipeline.PipelineStatus.IN_PROGRESS)) {
            if (!p.getId().equals(pipeline.getId())) {
                linked.add(p.getIssueType() + ":" + p.getId());
            }
        }
        return linked;
    }
}
