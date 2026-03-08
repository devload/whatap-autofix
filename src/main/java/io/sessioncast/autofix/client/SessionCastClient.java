package io.sessioncast.autofix.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component("sessionCastApiClient")
public class SessionCastClient {

    private final WebClient webClient;

    public SessionCastClient(@Qualifier("sessioncastWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Map> createSession(String name, Map<String, String> metadata) {
        Map<String, Object> body = Map.of(
                "name", name,
                "metadata", metadata
        );
        return webClient.post()
                .uri("/api/sessions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("Failed to create SessionCast session: {}", name, e));
    }

    public Mono<Map> getSession(String sessionId) {
        return webClient.get()
                .uri("/api/sessions/{id}", sessionId)
                .retrieve()
                .bodyToMono(Map.class);
    }

    public Mono<Void> appendLog(String sessionId, String content) {
        Map<String, Object> body = Map.of("content", content);
        return webClient.post()
                .uri("/api/sessions/{id}/logs", sessionId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class);
    }
}
