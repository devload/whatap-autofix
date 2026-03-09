package io.sessioncast.core.api;

public class LlmChatResponse {
    private final String content;
    private final LlmError error;

    public LlmChatResponse(String content, LlmError error) {
        this.content = content;
        this.error = error;
    }

    public String content() { return content; }
    public boolean hasError() { return error != null; }
    public LlmError error() { return error; }

    public record LlmError(String message) {}
}
