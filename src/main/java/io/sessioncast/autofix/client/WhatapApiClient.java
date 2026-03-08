package io.sessioncast.autofix.client;

import io.sessioncast.autofix.config.WebClientConfig;
import io.sessioncast.autofix.model.Metric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
public class WhatapApiClient {

    private final WebClientConfig webClientConfig;

    public WhatapApiClient(WebClientConfig webClientConfig) {
        this.webClientConfig = webClientConfig;
    }

    private WebClient webClient() {
        return webClientConfig.getWhatapClient();
    }

    public Mono<Metric> getSpotMetrics() {
        return webClient().get()
                .uri("/open/api/json/spot")
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseMetric)
                .doOnError(e -> log.error("Failed to fetch WhaTap spot metrics", e))
                .onErrorReturn(buildEmptyMetric());
    }

    public Mono<Map> getActiveAgents() {
        return webClient().get()
                .uri("/open/api/act_agent")
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to fetch active agents", e));
    }

    public Mono<Map> executeMxql(String mxql, long stime, long etime) {
        Map<String, Object> body = Map.of(
                "stime", stime,
                "etime", etime,
                "mql", mxql,
                "limit", 100
        );

        return webClient().post()
                .uri("/open/api/flush/mxql/text")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to execute MXQL: {}", mxql, e));
    }

    public Mono<Map> getMetricTimeSeries(String metricType, long stime, long etime) {
        return webClient().get()
                .uri("/open/api/json/{metricType}/{stime}/{etime}", metricType, stime, etime)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to fetch metric time series: {}", metricType, e));
    }

    private Metric parseMetric(Map<String, Object> data) {
        return Metric.builder()
                .cpu(toDouble(data.get("cpu")))
                .memory(toDouble(data.get("mem")))
                .tps(toInt(data.get("tps")))
                .errorRate(toDouble(data.get("error_rate")))
                .activeTransaction(toInt(data.get("actx")))
                .responseTime(toLong(data.get("rtime")))
                .actAgent(toInt(data.get("act_agent")))
                .collectedAt(Instant.now())
                .build();
    }

    private Metric buildEmptyMetric() {
        return Metric.builder().collectedAt(Instant.now()).build();
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return 0.0; }
        }
        return 0.0;
    }

    private int toInt(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return (int) Double.parseDouble((String) val); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return (long) Double.parseDouble((String) val); } catch (Exception e) { return 0L; }
        }
        return 0L;
    }
}
