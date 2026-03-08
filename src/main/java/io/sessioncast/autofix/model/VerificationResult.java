package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VerificationResult {
    private Metric beforeMetrics;
    private Metric afterMetrics;
    private boolean passed;
    private boolean simulated;   // 시뮬레이션 배포 후 검증
    private String summary;
}
