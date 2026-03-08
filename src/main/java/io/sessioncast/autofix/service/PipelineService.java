package io.sessioncast.autofix.service;

import io.sessioncast.autofix.model.*;
import io.sessioncast.autofix.model.Pipeline.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PipelineService {

    private final Map<String, Pipeline> pipelines = new ConcurrentHashMap<>();
    private final List<Metric> metricHistory = Collections.synchronizedList(new ArrayList<>());

    public Pipeline createPipeline(Issue issue, Severity severity) {
        // Check if active pipeline for same issue type already exists
        Optional<Pipeline> existing = pipelines.values().stream()
                .filter(p -> p.getIssueType().equals(issue.getType())
                        && p.getStatus() == PipelineStatus.IN_PROGRESS)
                .findFirst();

        if (existing.isPresent()) {
            log.debug("Pipeline for {} already in progress: {}", issue.getType(), existing.get().getId());
            return existing.get();
        }

        Pipeline pipeline = new Pipeline();
        pipeline.setIssueType(issue.getType());
        pipeline.setSeverity(severity);
        pipeline.setIssue(issue);
        pipeline.addLog(Stage.SCOUT, "이슈 감지: " + issue.getDescription());

        pipelines.put(pipeline.getId(), pipeline);
        log.info("Pipeline {} created for {}", pipeline.getId(), issue.getType());
        return pipeline;
    }

    public Pipeline getPipeline(String id) {
        return pipelines.get(id);
    }

    public List<Pipeline> getAllPipelines() {
        return new ArrayList<>(pipelines.values());
    }

    public List<Pipeline> getPipelinesByStatus(PipelineStatus status) {
        return pipelines.values().stream()
                .filter(p -> p.getStatus() == status)
                .collect(Collectors.toList());
    }

    public Pipeline updateStage(String pipelineId, Stage stage) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.advanceTo(stage);
            pipeline.addLog(stage, stage.name() + " 에이전트 시작");
            log.info("Pipeline {} advanced to {}", pipelineId, stage);
        }
        return pipeline;
    }

    public Pipeline setAnalysis(String pipelineId, AnalysisResult analysis) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.setAnalysis(analysis);
            pipeline.addLog(Stage.ANALYZER,
                    String.format("근본 원인: %s (신뢰도: %.0f%%)", analysis.getRootCause(), analysis.getConfidence() * 100));
        }
        return pipeline;
    }

    public Pipeline setFix(String pipelineId, FixProposal fix) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.setFix(fix);
            pipeline.addLog(Stage.FIXER, "수정안 제안: " + fix.getDescription());
        }
        return pipeline;
    }

    public Pipeline setDeploy(String pipelineId, DeployResult deploy) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.setDeploy(deploy);
            pipeline.addLog(Stage.DEPLOYER,
                    deploy.isSuccess() ? deploy.getDeployTarget() + "에 배포 완료"
                            : "배포 실패");
        }
        return pipeline;
    }

    public Pipeline setVerification(String pipelineId, VerificationResult verification) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.setVerification(verification);
            if (verification.isPassed()) {
                pipeline.complete();
                pipeline.addLog(Stage.VERIFIER, "통과: " + verification.getSummary());
            } else {
                pipeline.fail("검증 실패: " + verification.getSummary());
            }
        }
        return pipeline;
    }

    public Pipeline failPipeline(String pipelineId, String reason) {
        Pipeline pipeline = pipelines.get(pipelineId);
        if (pipeline != null) {
            pipeline.fail(reason);
        }
        return pipeline;
    }

    public void recordMetric(Metric metric) {
        metricHistory.add(metric);
        // Keep last 1000 entries
        if (metricHistory.size() > 1000) {
            metricHistory.subList(0, metricHistory.size() - 1000).clear();
        }
    }

    public List<Metric> getMetricHistory(int limit) {
        int size = metricHistory.size();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(metricHistory.subList(from, size));
    }

    public Metric getLatestMetric() {
        if (metricHistory.isEmpty()) return null;
        return metricHistory.get(metricHistory.size() - 1);
    }

    public void clearAll() {
        int count = pipelines.size();
        pipelines.clear();
        metricHistory.clear();
        log.info("모든 파이프라인({}) 및 메트릭 히스토리 초기화 완료", count);
    }

    public Map<String, Object> getStats() {
        long active = pipelines.values().stream().filter(p -> p.getStatus() == PipelineStatus.IN_PROGRESS).count();
        long completed = pipelines.values().stream().filter(p -> p.getStatus() == PipelineStatus.COMPLETED).count();
        long failed = pipelines.values().stream().filter(p -> p.getStatus() == PipelineStatus.FAILED).count();
        return Map.of(
                "total", pipelines.size(),
                "active", active,
                "completed", completed,
                "failed", failed,
                "activeAgents", countActiveAgents()
        );
    }

    private int countActiveAgents() {
        Set<Stage> activeStages = pipelines.values().stream()
                .filter(p -> p.getStatus() == PipelineStatus.IN_PROGRESS)
                .map(Pipeline::getCurrentStage)
                .collect(Collectors.toSet());
        return activeStages.size() + 1; // +1 for Scout (always polling)
    }
}
