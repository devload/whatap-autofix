package io.sessioncast.autofix.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "autofix")
public class AutofixProperties {
    private WhatapProps whatap = new WhatapProps();
    private GithubProps github = new GithubProps();
    private ThresholdProps thresholds = new ThresholdProps();

    @Data
    public static class WhatapProps {
        private String apiUrl = "https://api.whatap.io";
        private String apiToken;
        private String pcode;
        private String productType = "java";  // java, browser, nodejs, python, etc.
        private String projectName;
        private int pollingIntervalSeconds = 30;
        private int aiScoutIntervalCycles = 4; // AI Scout: N번 폴링마다 AI 분석
    }

    @Data
    public static class GithubProps {
        private String apiUrl = "https://api.github.com";
        private String token;
        private String owner;
        private String repo;
        private String defaultBranch = "main";
    }

    @Data
    public static class ThresholdProps {
        private double cpu = 95.0;
        private double memory = 85.0;
        private double disk = 85.0;
        private double errorRate = 3.0;
        private double tpsDrop = 0.5;
        private long responseTimeMs = 3000;
        private double anrRate = 1.0;
        private double crashFree = 99.0;
        private long coldStartMs = 3000;
    }
}
