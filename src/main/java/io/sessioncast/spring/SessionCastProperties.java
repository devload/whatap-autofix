package io.sessioncast.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "sessioncast")
public class SessionCastProperties {

    private Relay relay = new Relay();
    private Agent agent = new Agent();
    private Reconnect reconnect = new Reconnect();
    private Api api = new Api();

    public Relay getRelay() { return relay; }
    public void setRelay(Relay relay) { this.relay = relay; }
    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }
    public Reconnect getReconnect() { return reconnect; }
    public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
    public Api getApi() { return api; }
    public void setApi(Api api) { this.api = api; }

    public static class Relay {
        private String url;
        private String token;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class Agent {
        private String machineId;
        private String label;
        private boolean autoConnect;
        public String getMachineId() { return machineId; }
        public void setMachineId(String machineId) { this.machineId = machineId; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public boolean isAutoConnect() { return autoConnect; }
        public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }
    }

    public static class Reconnect {
        private boolean enabled;
        private Duration initialDelay = Duration.ofSeconds(2);
        private Duration maxDelay = Duration.ofSeconds(60);
        private int maxAttempts = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInitialDelay() { return initialDelay; }
        public void setInitialDelay(Duration initialDelay) { this.initialDelay = initialDelay; }
        public Duration getMaxDelay() { return maxDelay; }
        public void setMaxDelay(Duration maxDelay) { this.maxDelay = maxDelay; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class Api {
        private Duration timeout = Duration.ofSeconds(30);
        private Duration llmTimeout = Duration.ofMinutes(5);
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getLlmTimeout() { return llmTimeout; }
        public void setLlmTimeout(Duration llmTimeout) { this.llmTimeout = llmTimeout; }
    }
}
