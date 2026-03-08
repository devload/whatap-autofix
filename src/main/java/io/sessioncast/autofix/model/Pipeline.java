package io.sessioncast.autofix.model;

import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class Pipeline {
    private String id = UUID.randomUUID().toString().substring(0, 8);
    private String issueType;
    private Severity severity;
    private PipelineStatus status = PipelineStatus.IN_PROGRESS;
    private Stage currentStage = Stage.SCOUT;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    private Issue issue;
    private AnalysisResult analysis;
    private FixProposal fix;
    private DeployResult deploy;
    private VerificationResult verification;

    private List<StageLog> logs = new ArrayList<>();

    public void addLog(Stage stage, String message) {
        logs.add(new StageLog(stage, message, Instant.now()));
        this.updatedAt = Instant.now();
    }

    public void advanceTo(Stage stage) {
        this.currentStage = stage;
        this.updatedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = PipelineStatus.FAILED;
        addLog(currentStage, "실패: " + reason);
        this.updatedAt = Instant.now();
    }

    public void complete() {
        this.status = PipelineStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public enum Stage {
        SCOUT, ANALYZER, FIXER, DEPLOYER, VERIFIER
    }

    public enum PipelineStatus {
        IN_PROGRESS, COMPLETED, FAILED
    }

    public enum Severity {
        CRITICAL, WARNING, INFO
    }

    @Data
    public static class StageLog {
        private final Stage stage;
        private final String message;
        private final Instant timestamp;
    }
}
