package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.agent.ScoutAgent;
import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.MetricProfile;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final PipelineService pipelineService;
    private final WhatapApiClient whatapClient;
    private final RuleEngine ruleEngine;
    private final AutofixProperties props;
    private final ScoutAgent scoutAgent;

    private String currentPcode() {
        return props.getWhatap().getPcode();
    }

    @GetMapping("/metrics/latest")
    public Metric getLatestMetric() {
        return pipelineService.getLatestMetric(currentPcode());
    }

    @GetMapping("/metrics/history")
    public List<Metric> getMetricHistory(@RequestParam(defaultValue = "60") int limit) {
        return pipelineService.getMetricHistory(limit, currentPcode());
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        String pcode = currentPcode();
        Metric latest = pipelineService.getLatestMetric(pcode);
        Map<String, Object> stats = pipelineService.getStats(pcode);
        Map<String, Object> result = new HashMap<>();
        result.put("metrics", latest != null ? latest : Map.of());
        result.put("pipelines", stats);
        result.put("agents", Map.of(
                "scout", "polling",
                "analyzer", stats.getOrDefault("active", 0),
                "fixer", stats.getOrDefault("active", 0),
                "deployer", "idle",
                "verifier", "idle"
        ));
        result.put("productType", props.getWhatap().getProductType());
        result.put("projectName", props.getWhatap().getProjectName());
        return result;
    }

    @GetMapping("/rules")
    public List<Rule> getRules() {
        return ruleEngine.getRules();
    }

    @GetMapping("/test-mxql")
    public Map<String, Object> testMxql(@RequestParam String category) {
        long etime = System.currentTimeMillis();
        long stime = etime - 300_000; // 5분
        String mxql = "CATEGORY " + category + "\nTAGLOAD\nSELECT";
        try {
            Map result = whatapClient.executeMxql(mxql, stime, etime).block(java.time.Duration.ofSeconds(10));
            return Map.of("category", category, "result", result != null ? result : Map.of(), "status", "ok");
        } catch (Exception e) {
            return Map.of("category", category, "error", e.getMessage(), "status", "error");
        }
    }

    @GetMapping("/profile")
    public Map<String, Object> getMetricProfile() {
        MetricProfile profile = scoutAgent.getCurrentProfile();
        if (profile == null) {
            return Map.of("status", "discovering", "message", "AI가 메트릭을 탐색 중입니다...");
        }
        return Map.of(
                "status", "active",
                "projectType", profile.getProjectType() != null ? profile.getProjectType() : "",
                "projectName", profile.getProjectName() != null ? profile.getProjectName() : "",
                "discoveredAt", profile.getDiscoveredAt().toString(),
                "targets", profile.getTargets()
        );
    }
}
