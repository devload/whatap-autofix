package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.model.DeployResult;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.model.VerificationResult;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerifierAgent {

    private final WhatapApiClient whatapClient;
    private final PipelineService pipelineService;

    public void verify(Pipeline pipeline) {
        log.info("Verifier Agent: verifying fix for {} (pipeline {})", pipeline.getIssueType(), pipeline.getId());

        DeployResult deploy = pipeline.getDeploy();
        boolean simulated = deploy != null && deploy.isSimulated();

        if (simulated) {
            pipeline.addLog(Pipeline.Stage.VERIFIER, "시뮬레이션 배포 후 메트릭 모니터링 — 10초 대기");
        } else {
            pipeline.addLog(Pipeline.Stage.VERIFIER, "배포 후 메트릭 검증 시작 — 10초 대기");
        }

        // Save before-metrics
        Metric beforeMetrics = pipelineService.getLatestMetric();

        // Wait and re-check
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Metric afterMetrics = null;
        try {
            afterMetrics = whatapClient.getSpotMetrics()
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (Exception e) {
            log.warn("Verifier Agent: failed to fetch post-deploy metrics: {}", e.getMessage());
        }

        if (afterMetrics == null) {
            afterMetrics = pipelineService.getLatestMetric();
        }

        if (afterMetrics == null) {
            // 메트릭 수집 실패 시
            VerificationResult result = VerificationResult.builder()
                    .beforeMetrics(beforeMetrics)
                    .passed(false)
                    .simulated(simulated)
                    .summary("메트릭 수집 실패 — 수동 확인 필요")
                    .build();
            pipelineService.setVerification(pipeline.getId(), result);
            return;
        }

        // 메트릭 저장
        pipelineService.recordMetric(afterMetrics);

        checkMetrics(pipeline, beforeMetrics, afterMetrics, simulated);
    }

    private void checkMetrics(Pipeline pipeline, Metric before, Metric after, boolean simulated) {
        String issueType = pipeline.getIssueType();
        double threshold = pipeline.getIssue().getThreshold();
        String metricName = pipeline.getIssue().getMetric();
        double beforeValue = getRelevantMetric(before, metricName);
        double afterValue = getRelevantMetric(after, metricName);

        boolean belowThreshold = afterValue < threshold;
        boolean improved = afterValue < beforeValue;

        String status;
        boolean passed;

        if (simulated) {
            // 시뮬레이션 배포: 메트릭이 개선되었는지, 또는 안정적인지 확인
            if (belowThreshold) {
                status = "해결됨 (메트릭이 임계값 이내로 회복됨)";
                passed = true;
            } else if (improved) {
                status = "개선 중 (메트릭 개선 중이나 아직 임계값 초과)";
                passed = false;
            } else {
                status = "대기 중 (수동 조치 적용 대기 — 권장 사항을 적용해주세요)";
                passed = false;
            }
        } else {
            passed = belowThreshold;
            status = passed ? "해결됨" : "임계값 초과 지속";
        }

        String summary = String.format("%s: %.1f → %.1f (임계값: %.1f) — %s",
                issueType, beforeValue, afterValue, threshold, status);

        VerificationResult result = VerificationResult.builder()
                .beforeMetrics(before)
                .afterMetrics(after)
                .passed(passed)
                .simulated(simulated)
                .summary(summary)
                .build();

        pipelineService.setVerification(pipeline.getId(), result);

        if (passed) {
            log.info("Verifier Agent: PASS — {} for pipeline {}", summary, pipeline.getId());
        } else {
            log.warn("Verifier Agent: FAIL — {} for pipeline {}", summary, pipeline.getId());
        }
    }

    private double getRelevantMetric(Metric metric, String metricName) {
        if (metric == null) return 0.0;
        return switch (metricName) {
            case "cpu" -> metric.getCpu();
            case "memory" -> metric.getMemory();
            case "disk" -> metric.getDisk();
            case "error_rate" -> metric.getErrorRate();
            case "response_time" -> metric.getResponseTime();
            case "db_pool_usage" -> metric.getDbPoolUsage();
            default -> 0.0;
        };
    }
}
