package io.sessioncast.autofix.client;

import io.sessioncast.autofix.config.WebClientConfig;
import io.sessioncast.autofix.model.Metric;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
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
     * 프로젝트 타입에 따라 적절한 메트릭 조회 방법 선택.
     * 1차: /open/api/json/spot 시도
     * 2차: spot이 비어있거나 DB 프로젝트면 MXQL 폴백
     */
    public Mono<Metric> getSpotMetrics(String productType) {
        if (isDbProject(productType) || isBrowserProject(productType)) {
            // DB/Browser 프로젝트는 spot API가 APM 메트릭만 반환하므로 바로 MXQL
            return getMxqlMetrics(productType);
        }
        // 그 외: spot 시도 → 비어있으면 MXQL 폴백
        return webClient().get()
                .uri("/open/api/json/spot")
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(data -> {
                    // pcode만 있고 다른 데이터가 없으면 MXQL 폴백
                    long dataFields = data.keySet().stream()
                            .filter(k -> !"pcode".equals(k) && !"key".equals(k))
                            .count();
                    if (dataFields == 0) {
                        log.info("Spot API 빈 응답 → MXQL 폴백 (productType: {})", productType);
                        return getMxqlMetrics(productType);
                    }
                    return Mono.just(parseMetricWithRaw(data));
                })
                .doOnError(e -> log.error("Failed to fetch WhaTap spot metrics", e))
                .onErrorResume(e -> {
                    log.info("Spot API 실패 → MXQL 폴백 ({})", e.getMessage());
                    return getMxqlMetrics(productType);
                });
    }

    /**
     * MXQL 기반 메트릭 조회 — 프로젝트 타입에 맞는 카테고리를 자동 선택.
     * spot API가 없거나 비어있는 프로젝트에 사용.
     */
    @SuppressWarnings("unchecked")
    private Mono<Metric> getMxqlMetrics(String productType) {
        long etime = System.currentTimeMillis();
        // Browser/RUM은 수집 간격이 길어 5분, 그 외는 10초
        long window = isBrowserProject(productType) ? 300_000 : 10_000;
        long stime = etime - window;

        String[] categories = getMxqlCategories(productType);
        Map<String, Object> combined = new java.util.concurrent.ConcurrentHashMap<>();

        return reactor.core.publisher.Flux.fromArray(categories)
                .flatMap(cat -> {
                    String mxql = "CATEGORY " + cat + "\nTAGLOAD\nSELECT";
                    return executeMxql(mxql, stime, etime)
                            .map(result -> {
                                if (result.containsKey("data")) {
                                    Object data = result.get("data");
                                    if (data instanceof List && !((List<?>) data).isEmpty()) {
                                        Object first = ((List<?>) data).get(0);
                                        if (first instanceof Map) {
                                            combined.putAll((Map<String, Object>) first);
                                        }
                                    }
                                }
                                result.forEach((key, v) -> {
                                    String k = String.valueOf(key);
                                    if (!"data".equals(k) && v instanceof Number) {
                                        combined.put(k, v);
                                    }
                                });
                                return result;
                            })
                            .onErrorResume(e -> {
                                log.debug("MXQL {} 조회 실패: {}", cat, e.getMessage());
                                return Mono.just(Map.of());
                            });
                })
                .collectList()
                .map(results -> {
                    if (combined.isEmpty()) {
                        log.debug("MXQL 폴백: 데이터 없음 (categories: {})", String.join(",", categories));
                        return buildEmptyMetric();
                    }
                    log.debug("MXQL 폴백: {} 필드 수집됨 (productType: {})", combined.size(), productType);
                    return parseMetricWithRaw(combined);
                });
    }

    /** 프로젝트 타입별 MXQL 카테고리 목록 */
    private String[] getMxqlCategories(String productType) {
        if (productType == null) return new String[]{"app_counter"};
        String pt = productType.toLowerCase();
        if (isDbProject(productType)) {
            return new String[]{"db_real_counter"};
        } else if (pt.contains("browser") || pt.contains("rum")) {
            return new String[]{"rum_page_load_each_page", "rum_error_each_page"};
        } else if (pt.contains("server") || pt.contains("infra")) {
            return new String[]{"server_stat"};
        } else {
            // APM 계열 폴백
            return new String[]{"app_counter", "app_host_resource"};
        }
    }

    private boolean isDbProject(String productType) {
        if (productType == null) return false;
        String pt = productType.toLowerCase();
        return pt.contains("db") || pt.contains("mysql") || pt.contains("postgres")
                || pt.contains("oracle") || pt.contains("mssql") || pt.contains("redis")
                || pt.contains("mongo");
    }

    private boolean isBrowserProject(String productType) {
        if (productType == null) return false;
        String pt = productType.toLowerCase();
        return pt.contains("browser") || pt.contains("rum");
    }

    /**
     * Raw spot 데이터만 반환 (AI Scout용)
     */
    public Mono<Map<String, Object>> getSpotRaw(String productType) {
        return getSpotMetrics(productType)
                .map(m -> m.getRawData() != null ? m.getRawData() : Map.of());
    }

    public Mono<Map> getActiveAgents() {
        return webClient().get()
                .uri("/open/api/act_agent")
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to fetch active agents", e));
    }

    @SuppressWarnings("unchecked")
    public Mono<Map> executeMxql(String mxql, long stime, long etime) {
        Map<String, Object> body = Map.of(
                "stime", stime,
                "etime", etime,
                "mql", mxql,
                "limit", 10
        );

        return webClient().post()
                .uri("/open/api/flush/mxql/text")
                .bodyValue(body)
                .exchangeToMono(response -> {
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(raw -> {
                                try {
                                    raw = raw.trim();
                                    if (raw.isEmpty()) {
                                        log.debug("MXQL 빈 응답 (status: {})", response.statusCode().value());
                                        return (Map) Map.of();
                                    }
                                    log.debug("MXQL 응답 ({}자): {}", raw.length(),
                                            raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
                                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                                    if (raw.startsWith("[")) {
                                        List<Map<String, Object>> list = om.readValue(raw,
                                                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                                        if (list.isEmpty()) return (Map) Map.of();
                                        Map<String, Object> result = new java.util.HashMap<>(list.get(0));
                                        result.put("data", list);
                                        return (Map) result;
                                    } else {
                                        return (Map) om.readValue(raw, Map.class);
                                    }
                                } catch (Exception e) {
                                    log.warn("MXQL 응답 파싱 실패: {}", e.getMessage());
                                    return (Map) Map.of();
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to execute/parse MXQL: {}", mxql, e);
                    return Mono.just(Map.of());
                });
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
