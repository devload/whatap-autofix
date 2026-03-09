package io.sessioncast.autofix.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.File;
import java.util.Map;

/**
 * 로컬 SessionCast CLI config에서 agentToken을 자동으로 읽어와
 * sessioncast.relay.token 기본값으로 설정한다.
 *
 * 토큰 탐색 순서:
 * 1. 환경변수 SESSIONCAST_TOKEN (이미 설정돼 있으면 스킵)
 * 2. macOS: ~/Library/Preferences/sessioncast-nodejs/config.json
 * 3. Linux: ~/.config/sessioncast-nodejs/config.json
 * 4. Windows: %APPDATA%/sessioncast-nodejs/Config/config.json
 */
@Slf4j
public class SessionCastTokenResolver implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // 이미 환경변수로 설정돼 있으면 스킵
        String existing = env.getProperty("SESSIONCAST_TOKEN");
        if (existing != null && !existing.isBlank()) {
            return;
        }

        String token = readLocalToken();
        if (token != null && !token.isBlank()) {
            env.getPropertySources().addFirst(
                    new MapPropertySource("sessioncast-local-token", Map.of(
                            "sessioncast.relay.token", token,
                            "sessioncast.agent.auto-connect", "true"
                    ))
            );
            log.info("SessionCast: 로컬 CLI config에서 agentToken 로드 완료");
        }
    }

    private String readLocalToken() {
        for (File configFile : getCandidatePaths()) {
            if (configFile.exists() && configFile.isFile()) {
                try {
                    JsonNode root = new ObjectMapper().readTree(configFile);
                    JsonNode tokenNode = root.get("agentToken");
                    if (tokenNode != null && !tokenNode.asText().isBlank()) {
                        log.info("SessionCast: 토큰 발견 — {}", configFile.getAbsolutePath());
                        return tokenNode.asText();
                    }
                } catch (Exception e) {
                    log.debug("SessionCast: config 파싱 실패 — {}: {}", configFile, e.getMessage());
                }
            }
        }
        return null;
    }

    private File[] getCandidatePaths() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac")) {
            return new File[]{
                    new File(home, "Library/Preferences/sessioncast-nodejs/config.json")
            };
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = home + "/AppData/Roaming";
            return new File[]{
                    new File(appData, "sessioncast-nodejs/Config/config.json")
            };
        } else {
            // Linux
            return new File[]{
                    new File(home, ".config/sessioncast-nodejs/config.json")
            };
        }
    }
}
