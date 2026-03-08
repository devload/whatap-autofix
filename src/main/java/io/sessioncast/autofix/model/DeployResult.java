package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeployResult {
    private String commitSha;
    private String prUrl;
    private Integer prNumber;
    private String deployTarget;
    private String sessionId;
    private String executionLog;
    private boolean success;
    private boolean simulated;   // GitHub 미연결 시 시뮬레이션 모드
}
