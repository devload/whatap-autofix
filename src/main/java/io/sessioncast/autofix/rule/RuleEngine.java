package io.sessioncast.autofix.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.Issue;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline.Severity;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final AutofixProperties props;
    private List<Rule> rules = new ArrayList<>();

    @PostConstruct
    public void loadRules() {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            InputStream is = new ClassPathResource("rules/default-rules.yml").getInputStream();
            RulesConfig config = yamlMapper.readValue(is, RulesConfig.class);
            this.rules = config.getRules();
            log.info("Loaded {} rules", rules.size());
        } catch (Exception e) {
            log.error("Failed to load rules", e);
        }
    }

    public List<Issue> evaluate(Metric metric) {
        List<Issue> issues = new ArrayList<>();
        AutofixProperties.ThresholdProps t = props.getThresholds();

        for (Rule rule : rules) {
            double value = getMetricValue(metric, rule.getMetric());
            double threshold = getThreshold(rule.getMetric(), t);

            if (threshold > 0 && value > threshold) {
                issues.add(Issue.builder()
                        .type(rule.getName())
                        .metric(rule.getMetric())
                        .value(value)
                        .threshold(threshold)
                        .description(String.format("%s: %.1f (임계값: %.1f)", rule.getDescription(), value, threshold))
                        .detectedAt(Instant.now())
                        .rawData(Map.of("rule", rule.getId(), "severity", rule.getSeverity().name()))
                        .build());
            }
        }
        return issues;
    }

    public Rule findRule(String issueType) {
        return rules.stream()
                .filter(r -> r.getName().equals(issueType))
                .findFirst()
                .orElse(null);
    }

    public List<Rule> getRules() {
        return rules;
    }

    private double getMetricValue(Metric m, String metricName) {
        return switch (metricName) {
            case "cpu" -> m.getCpu();
            case "memory" -> m.getMemory();
            case "disk" -> m.getDisk();
            case "error_rate" -> m.getErrorRate();
            case "response_time" -> m.getResponseTime();
            case "db_pool_usage" -> m.getDbPoolUsage();
            default -> 0.0;
        };
    }

    private double getThreshold(String metricName, AutofixProperties.ThresholdProps t) {
        return switch (metricName) {
            case "cpu" -> t.getCpu();
            case "memory" -> t.getMemory();
            case "disk" -> t.getDisk();
            case "error_rate" -> t.getErrorRate();
            case "response_time" -> t.getResponseTimeMs();
            case "db_pool_usage" -> 80.0;
            default -> 0.0;
        };
    }

    @Data
    public static class RulesConfig {
        private List<Rule> rules;
    }
}
