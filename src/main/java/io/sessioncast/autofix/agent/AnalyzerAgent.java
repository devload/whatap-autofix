package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.model.AnalysisResult;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.controller.SettingsController;
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

    public AnalyzerAgent(WhatapApiClient whatapClient,
                         PipelineService pipelineService,
                         FixerAgent fixerAgent,
                         GlmService glmService,
                         @Autowired(required = false) SessionCastClient sessionCastClient) {
        this.whatapClient = whatapClient;
        this.pipelineService = pipelineService;
        this.fixerAgent = fixerAgent;
        this.glmService = glmService;
        this.sessionCastClient = sessionCastClient;
    }

    public void analyze(Pipeline pipeline) {
        log.info("Analyzer Agent: analyzing {} (pipeline {})", pipeline.getIssueType(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.ANALYZER, "분석 시작: " + pipeline.getIssueType());

        // Fetch correlated metrics
        Map<String, Object> correlationData = Map.of();
        try {
            long now = Instant.now().toEpochMilli();
            long tenMinAgo = now - 600_000;
            Map result = whatapClient.getMetricTimeSeries("cpu", tenMinAgo, now)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            if (result != null) {
                correlationData = result;
            }
        } catch (Exception e) {
            log.warn("Analyzer: failed to fetch correlation data: {}", e.getMessage());
        }

        try {
            if (sessionCastClient != null) {
                performLlmAnalysis(pipeline, correlationData);
            } else {
                performFallbackAnalysis(pipeline, correlationData);
            }
        } catch (Exception e) {
            log.error("Analyzer: analysis failed, falling back for pipeline {}", pipeline.getId(), e);
            performFallbackAnalysis(pipeline, correlationData);
        }
    }

    /**
     * SessionCast Relay → Request analysis from local Claude Code
     */
    private void performLlmAnalysis(Pipeline pipeline, Map<String, Object> correlationData) {
        String issueType = pipeline.getIssueType();
        double value = pipeline.getIssue().getValue();
        double threshold = pipeline.getIssue().getThreshold();

        Metric latest = pipelineService.getLatestMetric();
        String metricsSnapshot = formatMetrics(latest);

        // Build prompt for Claude
        String prompt = String.format("""
                당신은 서버 모니터링 전문가입니다. 다음 이슈를 분석해주세요.

                ## 감지된 이슈
                - 타입: %s
                - 현재 값: %.1f (임계값: %.1f)

                ## 현재 서버 메트릭
                %s

                ## 요청사항
                1. 근본 원인(Root Cause)을 한 문장으로 설명
                2. 관련 메트릭 상관관계 분석
                3. 신뢰도(0.0~1.0) 제시
                4. 권장 조치를 구체적으로 제시

                반드시 한국어로, 아래 JSON 형식으로 응답해주세요:
                {"rootCause": "한국어로 근본 원인 설명", "confidence": 0.85, "correlatedMetrics": ["metric:value", ...], "recommendation": "한국어로 권장 조치 설명"}
                """,
                issueType, value, threshold, metricsSnapshot);

        String aiProvider = SettingsController.getAiProvider();
        String aiModel = SettingsController.getAiModel();
        pipeline.addLog(Pipeline.Stage.ANALYZER, "LLM 분석 요청 중... (provider: " + aiProvider + ")");

        var requestBuilder = LlmChatRequest.builder()
                .system("당신은 서버 모니터링 전문가입니다. 반드시 한국어로 JSON만 응답하세요.")
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
            // Try to extract JSON from response
            String json = content;
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = content.substring(jsonStart, jsonEnd + 1);
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String rootCause = node.has("rootCause") ? node.get("rootCause").asText() : "LLM 분석 결과";
            double confidence = node.has("confidence") ? node.get("confidence").asDouble() : 0.80;

            List<String> correlated = new ArrayList<>();
            if (node.has("correlatedMetrics") && node.get("correlatedMetrics").isArray()) {
                node.get("correlatedMetrics").forEach(n -> correlated.add(n.asText()));
            }

            return AnalysisResult.builder()
                    .rootCause(rootCause)
                    .confidence(confidence)
                    .correlatedMetrics(correlated)
                    .linkedIssues(findLinkedIssues(pipeline))
                    .details(Map.of("llm_response", content))
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
