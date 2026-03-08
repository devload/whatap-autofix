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

    /**
     * WhaTap spot 메트릭을 가져오면서 raw 데이터도 함께 저장.
     * APM/Browser/DB 등 프로젝트 타입 무관하게 raw JSON을 보존한다.
     */
    public Mono<Metric> getSpotMetrics() {
        return webClient().get()
                .uri("/open/api/json/spot")
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseMetricWithRaw)
                .doOnError(e -> log.error("Failed to fetch WhaTap spot metrics", e))
                .onErrorReturn(buildEmptyMetric());
    }

    /**
     * Raw spot 데이터만 반환 (AI Scout용)
     */
    public Mono<Map<String, Object>> getSpotRaw() {
        return webClient().get()
                .uri("/open/api/json/spot")
                .retrieve()
                .bodyToMono(Map.class)
                .map(data -> (Map<String, Object>) data)
                .doOnError(e -> log.error("Failed to fetch WhaTap raw spot", e))
                .onErrorReturn(Map.of());
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

    @SuppressWarnings("unchecked")
    private Metric parseMetricWithRaw(Map<String, Object> data) {
        // raw 데이터를 숫자 변환하여 저장 (WhaTap이 문자열로 보내는 경우가 많음)
        Map<String, Object> normalizedRaw = new java.util.HashMap<>();
        data.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof Number) {
                normalizedRaw.put(k, v);
            } else if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) {
                    try { normalizedRaw.put(k, Double.parseDouble(s)); }
                    catch (NumberFormatException e) { normalizedRaw.put(k, s); }
                }
            } else {
                normalizedRaw.put(k, v);
            }
        });

        return Metric.builder()
                .cpu(toDouble(data.get("cpu")))
                .memory(toDouble(data.get("mem")))
                .tps(toInt(data.get("tps")))
                .errorRate(toDouble(data.get("error_rate")))
                .activeTransaction(toInt(data.get("actx")))
                .responseTime(toLong(data.get("rtime")))
                .actAgent(toInt(data.get("act_agent")))
                .rawData(normalizedRaw)
                .collectedAt(Instant.now())
                .build();
    }

    private Metric parseMetric(Map<String, Object> data) {
        return parseMetricWithRaw(data);
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
