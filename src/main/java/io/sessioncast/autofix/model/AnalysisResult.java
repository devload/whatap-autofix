package io.sessioncast.autofix.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnalysisResult {
    private String rootCause;
    private double confidence;
    private List<String> correlatedMetrics;
    private List<String> linkedIssues;
    private Map<String, Object> details;
}
