package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class Metric {
    // APM 메트릭 (기존 호환)
    private double cpu;
    private double memory;
    private double disk;
    private int tps;
    private double errorRate;
    private int activeTransaction;
    private long responseTime;
    private int actAgent;
    private double dbPoolUsage;

    // WhaTap raw 응답 데이터 — 프로젝트 타입 무관하게 저장
    @Builder.Default
    private Map<String, Object> rawData = new HashMap<>();

    // 프로젝트 타입 (java, browser, nodejs, python 등)
    private String projectType;

    private Instant collectedAt;
}
