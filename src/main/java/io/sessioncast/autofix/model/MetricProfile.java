package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AI가 프로젝트를 분석하여 생성한 메트릭 프로파일.
 * - 어떤 메트릭을 모니터링할지
 * - 각 메트릭의 임계값
 * - 상세 조회에 사용할 MXQL 쿼리
 */
@Data
@Builder
public class MetricProfile {
    private String projectType;       // java, browser, nodejs, db 등
    private String projectName;
    private List<MonitorTarget> targets;  // AI가 선정한 모니터링 대상
    private Instant discoveredAt;

    @Data
    @Builder
    public static class MonitorTarget {
        private String key;           // 메트릭 키 (예: cpu, rtime, dbconn_act)
        private String label;         // 표시명 (예: "CPU 사용률")
        private double warnThreshold; // WARNING 임계값
        private double critThreshold; // CRITICAL 임계값
        private String unit;          // %, ms, count 등
        private String mxql;          // 상세 조회 MXQL (nullable)
        private String category;      // MXQL 카테고리 (app_counter, db_pool 등)
    }
}
