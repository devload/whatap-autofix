package io.sessioncast.core;

import io.sessioncast.core.api.Capability;
import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class SessionCastClient {
    private final String relay;
    private final String token;
    private volatile boolean connected = false;

    private SessionCastClient(Builder builder) {
        this.relay = builder.relay;
        this.token = builder.token;
    }

    public boolean isConnected() { return connected; }

    public CompletableFuture<LlmChatResponse> llmChat(LlmChatRequest request) {
        return CompletableFuture.completedFuture(
                new LlmChatResponse(null, new LlmChatResponse.LlmError("SessionCast stub - not connected")));
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String relay;
        private String token;

        public Builder relay(String relay) { this.relay = relay; return this; }
        public Builder token(String token) { this.token = token; return this; }
        public Builder machineId(String machineId) { return this; }
        public Builder label(String label) { return this; }
        public Builder reconnect(boolean enabled) { return this; }
        public Builder reconnectDelay(Duration initial, Duration max) { return this; }
        public Builder maxReconnectAttempts(int max) { return this; }
        public Builder apiTimeout(Duration timeout) { return this; }
        public Builder llmTimeout(Duration timeout) { return this; }
        public Builder requiredCapabilities(Capability... caps) { return this; }
        public SessionCastClient build() { return new SessionCastClient(this); }
    }
}
