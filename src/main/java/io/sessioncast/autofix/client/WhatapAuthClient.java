package io.sessioncast.autofix.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WhatapAuthClient {

    private final WebClient serviceClient;

    // 로그인 후 유지되는 쿠키들
    private String sessionCookies = "";
    // 로그인 후 획득한 계정 레벨 API 토큰
    private String accountApiToken = "";

    public String getAccountApiToken() {
        return accountApiToken;
    }

    public WhatapAuthClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        this.serviceClient = WebClient.builder()
                .baseUrl("https://service.whatap.io")
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * 웹 로그인: CSRF 토큰 획득 → form POST → 세션 쿠키 → 모바일 API로 토큰 획득
     */
    public Mono<LoginResult> login(String email, String password) {
        log.info("WhaTap 로그인 시도: {}", email);

        // Step 1: GET /account/login → CSRF + JSESSIONID 쿠키 획득
        return serviceClient.get()
                .uri("/account/login?lang=en")
                .exchangeToMono(response -> {
                    List<String> cookies = response.headers().header("Set-Cookie");
                    String initialCookies = extractCookies(cookies);
                    log.debug("초기 쿠키 획득: {}", maskCookies(initialCookies));

                    return response.bodyToMono(String.class).flatMap(html -> {
                        String csrf = extractCsrf(html);
                        log.debug("CSRF 토큰: {}", csrf != null ? csrf.substring(0, Math.min(8, csrf.length())) + "..." : "null");

                        if (csrf == null) {
                            // CSRF 없는 경우 — 직접 JSON 로그인 시도
                            return jsonLogin(email, password);
                        }

                        // Step 2: POST /account/login (form)
                        String formBody = "email=" + urlEncode(email)
                                + "&password=" + urlEncode(password)
                                + "&_csrf=" + urlEncode(csrf)
                                + "&rememberMe=on";

                        return serviceClient.post()
                                .uri("/account/login")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .header("Cookie", initialCookies)
                                .header("Referer", "https://service.whatap.io/account/login")
                                .bodyValue(formBody)
                                .exchangeToMono(loginResp -> handleLoginResponse(loginResp, email, password, initialCookies));
                    });
                })
                .doOnError(e -> log.error("WhaTap 로그인 에러", e));
    }

    private Mono<LoginResult> handleLoginResponse(ClientResponse loginResp, String email, String password, String prevCookies) {
        List<String> newCookies = loginResp.headers().header("Set-Cookie");
        String allCookies = mergeCookies(prevCookies, extractCookies(newCookies));
        int status = loginResp.statusCode().value();
        log.debug("로그인 응답 status={}, cookies={}", status, maskCookies(allCookies));

        // 302 리다이렉트 = 로그인 성공
        if (status == 302 || status == 200) {
            // Location 헤더 확인
            String location = loginResp.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION);
            if (location != null && location.contains("mfa")) {
                LoginResult result = new LoginResult();
                result.setSuccess(false);
                result.setMessage("MFA 인증이 필요합니다. WhaTap 콘솔에서 API 토큰을 직접 발급해주세요.");
                return loginResp.releaseBody().thenReturn(result);
            }

            if (allCookies.contains("wa=") || status == 302) {
                this.sessionCookies = allCookies;
                // Step 3: 모바일 API로 API 토큰 획득
                return loginResp.releaseBody()
                        .then(getApiTokenViaMobileLogin(email, password, allCookies));
            }
        }

        return loginResp.bodyToMono(String.class).map(body -> {
            log.warn("로그인 응답 body: {}", body.substring(0, Math.min(200, body.length())));
            LoginResult result = new LoginResult();
            result.setSuccess(false);
            result.setMessage("로그인 실패 - 이메일/비밀번호를 확인해주세요 (status=" + status + ")");
            return result;
        });
    }

    /**
     * JSON 방식 직접 로그인 (fallback)
     */
    private Mono<LoginResult> jsonLogin(String email, String password) {
        log.info("JSON 로그인 방식 시도");
        return serviceClient.post()
                .uri("/api/account/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchangeToMono(resp -> {
                    List<String> cookies = resp.headers().header("Set-Cookie");
                    String cookieStr = extractCookies(cookies);

                    return resp.bodyToMono(Map.class).map(body -> {
                        LoginResult result = new LoginResult();
                        if (body.containsKey("apiToken")) {
                            result.setApiToken((String) body.get("apiToken"));
                            result.setAccountId(body.get("accountId") != null
                                    ? ((Number) body.get("accountId")).longValue() : 0L);
                            result.setSuccess(true);
                            this.accountApiToken = result.getApiToken();
                            this.sessionCookies = cookieStr;
                        } else {
                            result.setSuccess(false);
                            result.setMessage("로그인 실패: " + body);
                        }
                        return result;
                    }).onErrorResume(e -> {
                        log.warn("JSON 로그인 응답 파싱 실패", e);
                        LoginResult result = new LoginResult();
                        result.setSuccess(false);
                        result.setMessage("JSON 로그인 실패");
                        return Mono.just(result);
                    });
                });
    }

    /**
     * 모바일 API로 API 토큰 획득
     */
    private Mono<LoginResult> getApiTokenViaMobileLogin(String email, String password, String cookieHeader) {
        log.info("모바일 API로 토큰 획득 시도");
        return serviceClient.post()
                .uri("/mobile/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", cookieHeader)
                .bodyValue(Map.of(
                        "email", email,
                        "password", password,
                        "appVersion", "autofix-agent-1.0",
                        "deviceInfo", "autofix-server",
                        "deviceModel", "server",
                        "deviceType", "server",
                        "fcmToken", "",
                        "mobileDeviceToken", "",
                        "osVersion", "server"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    LoginResult result = new LoginResult();
                    result.setApiToken((String) body.get("apiToken"));
                    result.setAccountId(body.get("accountId") != null
                            ? ((Number) body.get("accountId")).longValue() : 0L);
                    result.setSuccess(result.getApiToken() != null && !result.getApiToken().isBlank());
                    if (result.isSuccess()) {
                        accountApiToken = result.getApiToken();
                        log.info("WhaTap API 토큰 획득 성공 (accountId: {})", result.getAccountId());
                    } else {
                        result.setMessage("모바일 API에서 토큰을 받지 못했습니다");
                        log.warn("모바일 API 응답에 apiToken 없음: {}", body.keySet());
                    }
                    return result;
                })
                .onErrorResume(e -> {
                    log.warn("모바일 API 토큰 획득 실패: {}", e.getMessage());
                    // 쿠키 기반으로 프로젝트 목록 직접 접근 시도
                    return fetchTokenFromProjectList(cookieHeader);
                });
    }

    /**
     * 쿠키 기반으로 프로젝트 목록을 가져와서 프로젝트 토큰을 획득하는 폴백
     */
    private Mono<LoginResult> fetchTokenFromProjectList(String cookieHeader) {
        log.info("프로젝트 목록에서 토큰 획득 시도 (쿠키 기반)");
        return serviceClient.post()
                .uri("/account/api/v4/project/list")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", cookieHeader)
                .bodyValue(Map.of(
                        "page", 1,
                        "max", 50,
                        "favorite", false,
                        "search", "",
                        "product", "[apm, cpm, db, browser, sms, wpm]",
                        "group", "",
                        "layerUuid", "",
                        "onlyNoGroup", false
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    LoginResult result = new LoginResult();
                    // 프로젝트 목록 자체를 가져올 수 있으면 로그인 성공으로 간주
                    result.setSuccess(true);
                    result.setMessage("쿠키 기반 로그인 성공 — 프로젝트 목록 접근 가능");
                    // apiToken은 없지만 쿠키로 동작 가능
                    log.info("쿠키 기반 로그인 성공, 프로젝트 목록 접근 가능");
                    return result;
                })
                .onErrorResume(e -> {
                    LoginResult result = new LoginResult();
                    result.setSuccess(false);
                    result.setMessage("로그인 성공했으나 API 토큰 획득에 실패했습니다. WhaTap 콘솔에서 직접 토큰을 발급해주세요.");
                    return Mono.just(result);
                });
    }

    /**
     * 프로젝트 목록 조회.
     * 쿠키 기반 API를 우선 사용 (프로젝트별 apiToken 포함).
     * 실패 시 Open API 폴백.
     */
    public Mono<List<ProjectInfo>> getProjects(String accountApiToken) {
        if (!sessionCookies.isBlank()) {
            return getProjectsViaCookies()
                    .flatMap(list -> {
                        if (list.isEmpty() && accountApiToken != null && !accountApiToken.isBlank()) {
                            return getProjectsViaOpenApi(accountApiToken);
                        }
                        return Mono.just(list);
                    })
                    .onErrorResume(e -> {
                        log.warn("쿠키 기반 프로젝트 목록 실패, Open API 폴백: {}", e.getMessage());
                        if (accountApiToken != null && !accountApiToken.isBlank()) {
                            return getProjectsViaOpenApi(accountApiToken);
                        }
                        return Mono.just(List.of());
                    });
        }
        if (accountApiToken != null && !accountApiToken.isBlank()) {
            return getProjectsViaOpenApi(accountApiToken);
        }
        return Mono.just(List.of());
    }

    @SuppressWarnings("unchecked")
    private Mono<List<ProjectInfo>> getProjectsViaOpenApi(String accountApiToken) {
        return WebClient.builder()
                .baseUrl("https://api.whatap.io")
                .defaultHeader("x-whatap-token", accountApiToken)
                .build()
                .get()
                .uri("/open/api/json/projects")
                .retrieve()
                .bodyToMono(Map.class)
                .<List<ProjectInfo>>map(this::parseProjectList)
                .doOnError(e -> log.error("Open API 프로젝트 목록 조회 실패", e));
    }

    private Mono<List<ProjectInfo>> getProjectsViaCookies() {
        if (sessionCookies.isBlank()) {
            return Mono.just(List.of());
        }
        return serviceClient.post()
                .uri("/account/api/v4/project/list")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", sessionCookies)
                .bodyValue(Map.of(
                        "page", 1, "max", 50, "favorite", false,
                        "search", "", "product", "[apm, cpm, db, browser, sms, wpm]",
                        "group", "", "layerUuid", "", "onlyNoGroup", false
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    List<Map<String, Object>> records = (List<Map<String, Object>>) body.get("records");
                    if (records == null) return List.<ProjectInfo>of();
                    return records.stream().map(p -> {
                        ProjectInfo info = new ProjectInfo();
                        info.setPcode(p.get("pcode") != null ? ((Number) p.get("pcode")).longValue() : 0L);
                        info.setName((String) p.get("name"));
                        info.setPlatform((String) p.get("platform"));
                        info.setProductType((String) p.get("productType"));
                        info.setApiToken((String) p.get("apiToken"));
                        info.setStatus((String) p.get("status"));
                        return info;
                    }).toList();
                })
                .doOnError(e -> log.error("쿠키 기반 프로젝트 목록 조회 실패", e));
    }

    @SuppressWarnings("unchecked")
    private List<ProjectInfo> parseProjectList(Map<String, Object> body) {
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        if (data == null) return List.of();

        return data.stream().map(p -> {
            ProjectInfo info = new ProjectInfo();
            info.setPcode(p.get("projectCode") != null
                    ? ((Number) p.get("projectCode")).longValue() : 0L);
            info.setName((String) p.get("projectName"));
            info.setPlatform((String) p.get("platform"));
            info.setProductType((String) p.get("productType"));
            info.setApiToken((String) p.get("apiToken"));
            info.setStatus((String) p.get("status"));
            return info;
        }).toList();
    }

    private String extractCookies(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String header : setCookieHeaders) {
            String cookie = header.split(";")[0];
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(cookie);
        }
        return sb.toString();
    }

    private String mergeCookies(String existing, String newCookies) {
        if (existing.isBlank()) return newCookies;
        if (newCookies.isBlank()) return existing;
        return existing + "; " + newCookies;
    }

    private String extractCsrf(String html) {
        int idx = html.indexOf("name=\"_csrf\"");
        if (idx < 0) idx = html.indexOf("id=\"_csrf\"");
        if (idx < 0) return null;

        int valueIdx = html.indexOf("value=\"", idx);
        if (valueIdx < 0) {
            // value가 앞에 있을 수 있음
            int searchStart = Math.max(0, idx - 200);
            valueIdx = html.indexOf("value=\"", searchStart);
        }
        if (valueIdx < 0) return null;
        int start = valueIdx + 7;
        int end = html.indexOf("\"", start);
        if (end < 0) return null;
        return html.substring(start, end);
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String maskCookies(String cookies) {
        if (cookies == null || cookies.length() < 10) return "***";
        return cookies.substring(0, 10) + "...(" + cookies.length() + " chars)";
    }

    @Data
    public static class LoginResult {
        private boolean success;
        private String apiToken;
        private long accountId;
        private String message;
    }

    @Data
    public static class ProjectInfo {
        private long pcode;
        private String name;
        private String platform;
        private String productType;
        private String apiToken;
        private String status;
    }
}
