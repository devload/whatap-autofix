package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final PipelineService pipelineService;
    private final WhatapApiClient whatapClient;
    private final RuleEngine ruleEngine;

    @GetMapping("/metrics/latest")
    public Metric getLatestMetric() {
        return pipelineService.getLatestMetric();
    }

    @GetMapping("/metrics/history")
    public List<Metric> getMetricHistory(@RequestParam(defaultValue = "60") int limit) {
        return pipelineService.getMetricHistory(limit);
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Metric latest = pipelineService.getLatestMetric();
        Map<String, Object> stats = pipelineService.getStats();
        return Map.of(
                "metrics", latest != null ? latest : Map.of(),
                "pipelines", stats,
                "agents", Map.of(
                        "scout", "polling",
                        "analyzer", stats.getOrDefault("active", 0),
                        "fixer", stats.getOrDefault("active", 0),
                        "deployer", "idle",
                        "verifier", "idle"
                )
        );
    }

    @GetMapping("/rules")
    public List<Rule> getRules() {
        return ruleEngine.getRules();
    }
}
