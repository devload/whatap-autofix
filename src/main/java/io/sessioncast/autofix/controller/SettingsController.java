package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.service.PipelineService;
import io.sessioncast.core.SessionCastClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AutofixProperties props;
    private final PipelineService pipelineService;

    @Autowired(required = false)
    private SessionCastClient sessionCastClient;

    // AI 모델 설정 (인메모리)
    private static final Map<String, String> aiConfig = new ConcurrentHashMap<>(Map.of(
            "provider", "claude-code",
            "model", ""
    ));

    @GetMapping("/thresholds")
    public AutofixProperties.ThresholdProps getThresholds() {
        return props.getThresholds();
    }

    @PutMapping("/thresholds")
    public AutofixProperties.ThresholdProps updateThresholds(@RequestBody AutofixProperties.ThresholdProps updated) {
        AutofixProperties.ThresholdProps current = props.getThresholds();
        if (updated.getCpu() > 0) current.setCpu(updated.getCpu());
        if (updated.getMemory() > 0) current.setMemory(updated.getMemory());
        if (updated.getDisk() > 0) current.setDisk(updated.getDisk());
        if (updated.getErrorRate() > 0) current.setErrorRate(updated.getErrorRate());
        if (updated.getResponseTimeMs() > 0) current.setResponseTimeMs(updated.getResponseTimeMs());
        return current;
    }

    @GetMapping("/connections")
    public Map<String, Object> getConnectionStatus() {
        return Map.of(
                "whatap", Map.of(
                        "configured", isConfigured(props.getWhatap().getApiToken()),
                        "apiUrl", props.getWhatap().getApiUrl(),
                        "pcode", maskValue(props.getWhatap().getPcode())
                ),
                "github", Map.of(
                        "configured", isConfigured(props.getGithub().getToken()),
                        "owner", nullSafe(props.getGithub().getOwner()),
                        "repo", nullSafe(props.getGithub().getRepo())
                ),
                "sessioncast", Map.of(
                        "configured", sessionCastClient != null,
                        "connected", sessionCastClient != null && sessionCastClient.isConnected()
                )
        );
    }

    // ─── AI Model Settings ───

    @GetMapping("/ai")
    public Map<String, String> getAiSettings() {
        return Map.copyOf(aiConfig);
    }

    @PutMapping("/ai")
    public Map<String, String> updateAiSettings(@RequestBody Map<String, String> body) {
        if (body.containsKey("provider")) aiConfig.put("provider", body.get("provider"));
        if (body.containsKey("model")) aiConfig.put("model", body.get("model"));
        log.info("AI 설정 변경 — provider: {}, model: {}", aiConfig.get("provider"), aiConfig.get("model"));
        return Map.copyOf(aiConfig);
    }

    public static String getAiProvider() {
        return aiConfig.getOrDefault("provider", "claude-code");
    }

    public static String getAiModel() {
        return aiConfig.getOrDefault("model", "");
    }

    // ─── Data Reset ───

    @PostMapping("/reset")
    public ResponseEntity<?> resetData() {
        pipelineService.clearAll();
        log.info("데이터 초기화 완료");
        return ResponseEntity.ok(Map.of("success", true, "message", "모든 파이프라인 및 메트릭 데이터가 초기화되었습니다."));
    }

    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    private String maskValue(String value) {
        if (value == null || value.length() < 4) return "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
