package io.sessioncast.core.api;

public class LlmChatRequest {
    private final String system;
    private final String user;
    private final String model;

    private LlmChatRequest(Builder builder) {
        this.system = builder.system;
        this.user = builder.user;
        this.model = builder.model;
    }

    public String getSystem() { return system; }
    public String getUser() { return user; }
    public String getModel() { return model; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String system;
        private String user;
        private String model;

        public Builder system(String system) { this.system = system; return this; }
        public Builder user(String user) { this.user = user; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public LlmChatRequest build() { return new LlmChatRequest(this); }
    }
}
