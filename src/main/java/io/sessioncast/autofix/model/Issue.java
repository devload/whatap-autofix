package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class Issue {
    private String type;
    private String metric;
    private double value;
    private double threshold;
    private String description;
    private Instant detectedAt;
    private Map<String, Object> rawData;
}
