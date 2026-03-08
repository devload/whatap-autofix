package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.WhatapApiClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.Issue;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.model.Pipeline.Severity;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoutAgent {

    private final WhatapApiClient whatapClient;
    private final RuleEngine ruleEngine;
    private final PipelineService pipelineService;
    private final AnalyzerAgent analyzerAgent;

    @Scheduled(fixedDelayString = "${autofix.whatap.polling-interval-seconds:30}000")
    public void poll() {
        log.debug("Scout Agent: polling metrics...");

        whatapClient.getSpotMetrics()
                .subscribe(metric -> {
                    pipelineService.recordMetric(metric);
                    List<Issue> issues = ruleEngine.evaluate(metric);

                    if (issues.isEmpty()) {
                        log.debug("Scout Agent: all metrics normal");
                    } else {
                        log.info("Scout Agent: detected {} issues", issues.size());
                        for (Issue issue : issues) {
                            processIssue(issue);
                        }
                    }
                });
    }

    private void processIssue(Issue issue) {
        Rule rule = ruleEngine.findRule(issue.getType());
        Severity severity = rule != null ? rule.getSeverity() : Severity.WARNING;

        Pipeline pipeline = pipelineService.createPipeline(issue, severity);

        // Only hand off to Analyzer if pipeline is new (at SCOUT stage)
        if (pipeline.getCurrentStage() == Pipeline.Stage.SCOUT) {
            pipeline.advanceTo(Pipeline.Stage.ANALYZER);
            pipeline.addLog(Pipeline.Stage.SCOUT, "Analyzer 에이전트로 전달");
            analyzerAgent.analyze(pipeline);
        }
    }
}
