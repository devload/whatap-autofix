package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class Metric {
    private double cpu;
    private double memory;
    private double disk;
    private int tps;
    private double errorRate;
    private int activeTransaction;
    private long responseTime;
    private int actAgent;
    private double dbPoolUsage;
    private Instant collectedAt;
}
