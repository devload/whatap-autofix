package io.sessioncast.autofix.service;

import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;
import io.sessioncast.core.api.LlmChatResponse.Choice;
import io.sessioncast.core.api.LlmChatResponse.ChoiceMessage;
import io.sessioncast.core.api.LlmChatResponse.Error;
import io.sessioncast.core.api.LlmChatResponse.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class GlmService {
    
    private WebClient webClient;
    private String currentBaseUrl;
    
    public CompletableFuture<LlmChatResponse> chat(String baseUrl, String apiToken, LlmChatRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WebClient client = getOrCreateClient(baseUrl);
                
                // Convert LlmMessage list to Map format for GLM API
                List<Map<String, String>> messages = new java.util.ArrayList<>();
                if (request.messages() != null) {
                    for (LlmChatRequest.LlmMessage msg : request.messages()) {
                        messages.add(Map.of("role", msg.role(), "content", msg.content()));
                    }
                }
                
                Map<String, Object> requestBody = new java.util.HashMap<>();
                requestBody.put("model", request.model() != null && !request.model().isBlank() ? request.model() : "glm-5");
                requestBody.put("messages", messages);
                requestBody.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
                requestBody.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : 4096);
                
                log.debug("GLM API 호출: baseUrl={}, model={}", baseUrl, requestBody.get("model"));
                
                Map<String, Object> response = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiToken)
                    .body(Mono.just(requestBody), Map.class)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(300))
                    .block();
                
                return parseResponse(response);
                
            } catch (Exception e) {
                log.error("GLM API 호출 실패: {}", e.getMessage());
                return new LlmChatResponse(null, null, null, null, new Error("GLM API error: " + e.getMessage(), "api_error"));
            }
        });
    }
    
    public boolean testConnection(String baseUrl, String apiToken) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4";
        }
        
        try {
            WebClient testClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
            
            Map<String, Object> requestBody = Map.of(
                "model", "glm-5",
                "messages", List.of(Map.of("role", "user", "content", "test")),
                "max_tokens", 10
            );
            
            testClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiToken)
                .body(Mono.just(requestBody), Map.class)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .block();
            
            return true;
        } catch (Exception e) {
            log.warn("GLM 연결 테스트 실패: {}", e.getMessage());
            return false;
        }
    }
    
    private WebClient getOrCreateClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4";
        }
        
        if (webClient == null || !baseUrl.equals(currentBaseUrl)) {
            currentBaseUrl = baseUrl;
            webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        }
        return webClient;
    }
    
    @SuppressWarnings("unchecked")
    private LlmChatResponse parseResponse(Map<String, Object> response) {
        if (response == null) {
            return new LlmChatResponse(null, null, null, null, new Error("Empty response from GLM API", "empty_response"));
        }
        
        if (response.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) response.get("error");
            String message = error != null ? String.valueOf(error.get("message")) : "Unknown error";
            String code = error != null ? String.valueOf(error.get("type")) : "unknown";
            return new LlmChatResponse(null, null, null, null, new Error(message, code));
        }
        
        if (response.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    String finishReason = (String) choice.get("finish_reason");
                    Choice c = new Choice(0, new ChoiceMessage("assistant", content != null ? content : ""), finishReason);
                    
                    Usage usage = null;
                    if (response.containsKey("usage")) {
                        Map<String, Object> usageMap = (Map<String, Object>) response.get("usage");
                        usage = new Usage(
                            ((Number) usageMap.getOrDefault("prompt_tokens", 0)).intValue(),
                            ((Number) usageMap.getOrDefault("completion_tokens", 0)).intValue(),
                            ((Number) usageMap.getOrDefault("total_tokens", 0)).intValue()
                        );
                    }
                    
                    String id = (String) response.get("id");
                    String model = (String) response.get("model");
                    return new LlmChatResponse(id, model, List.of(c), usage, null);
                }
            }
        }
        
        return new LlmChatResponse(null, null, null, null, new Error("Invalid response format from GLM API", "invalid_format"));
    }
}
