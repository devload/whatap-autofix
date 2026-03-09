package io.sessioncast.autofix.config;

import io.sessioncast.core.SessionCastClient;
import io.sessioncast.core.api.Capability;
import io.sessioncast.spring.SessionCastProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Configuration
public class WebClientConfig {

    private volatile WebClient whatapClient;

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean("whatapWebClient")
    public WebClient whatapWebClient(WebClient.Builder builder, AutofixProperties props) {
        this.whatapClient = buildWhatapClient(props);
        return this.whatapClient;
    }

    /**
     * 로그인 후 토큰/pcode가 변경되면 WebClient를 재구성한다.
     */
    public void refreshWhatapClient(AutofixProperties props) {
        this.whatapClient = buildWhatapClient(props);
        log.info("WhaTap WebClient 재구성 완료 (pcode: {})", props.getWhatap().getPcode());
    }

    public WebClient getWhatapClient() {
        return this.whatapClient;
    }

    private WebClient buildWhatapClient(AutofixProperties props) {
        String token = props.getWhatap().getApiToken() != null ? props.getWhatap().getApiToken() : "";
        String pcode = props.getWhatap().getPcode() != null ? props.getWhatap().getPcode() : "";
        return WebClient.builder()
                .baseUrl(props.getWhatap().getApiUrl())
                .defaultHeader("x-whatap-token", token)
                .defaultHeader("x-whatap-pcode", pcode)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean("githubWebClient")
    public WebClient githubWebClient(WebClient.Builder builder, AutofixProperties props) {
        return builder
                .baseUrl(props.getGithub().getApiUrl())
                .defaultHeader("Authorization", "Bearer " + props.getGithub().getToken())
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    @Bean("sessioncastWebClient")
    public WebClient sessioncastWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://api.sessioncast.io")
                .build();
    }

    /**
     * Starter의 기본 빈을 오버라이드하여 LLM_CHAT capability를 요구하도록 설정.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sessioncast.relay", name = "token", matchIfMissing = false)
    public SessionCastClient sessionCastClient(SessionCastProperties props) {
        var relay = props.getRelay();
        if (relay.getToken() == null || relay.getToken().isBlank()) {
            log.info("SessionCast token is empty, skipping client creation");
            return null;
        }
        var agent = props.getAgent();
        var reconnect = props.getReconnect();
        var api = props.getApi();

        return SessionCastClient.builder()
                .relay(relay.getUrl())
                .token(relay.getToken())
                .machineId(agent.getMachineId())
                .label(agent.getLabel())
                .reconnect(reconnect.isEnabled())
                .reconnectDelay(reconnect.getInitialDelay(), reconnect.getMaxDelay())
                .maxReconnectAttempts(reconnect.getMaxAttempts())
                .apiTimeout(api.getTimeout())
                .llmTimeout(api.getLlmTimeout())
                .requiredCapabilities(Capability.LLM_CHAT)
                .build();
    }

}
