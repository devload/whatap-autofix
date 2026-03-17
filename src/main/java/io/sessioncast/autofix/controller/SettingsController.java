package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.agent.ScoutAgent;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.MetricProfile;
import io.sessioncast.autofix.service.ClaudeLocalService;
import io.sessioncast.autofix.service.FeedbackService;
import io.sessioncast.autofix.service.GlmService;
import io.sessioncast.autofix.service.PipelineService;
import io.sessioncast.autofix.service.WebhookService;
import io.sessioncast.core.SessionCastClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final AutofixProperties props;
    private final PipelineService pipelineService;
    private final ScoutAgent scoutAgent;
    private final GlmService glmService;
    private final ClaudeLocalService claudeLocalService;
    private final FeedbackService feedbackService;
    private final WebhookService webhookService;

    @Autowired(required = false)
    private SessionCastClient sessionCastClient;

    // AI 모델 설정 (인메모리)
    private static final Map<String, String> aiConfig = new ConcurrentHashMap<>(Map.of(
            "provider", "glm",
            "model", "",
            "glmApiToken", "",
            "glmBaseUrl", "https://open.bigmodel.cn/api/coding/paas/v4"
    ));

    /**
     * 로컬 .env에서 읽은 기본 로그인 정보 (개발 편의용, 커밋 금지)
     */
    @GetMapping("/defaults")
    public Map<String, String> getDefaults() {
        String email = System.getenv("WHATAP_DEFAULT_EMAIL");
        String password = System.getenv("WHATAP_DEFAULT_PASSWORD");
        String glmToken = System.getenv("GLM_API_TOKEN");
        return Map.of(
                "email", email != null ? email : "",
                "password", password != null ? password : "",
                "glmToken", glmToken != null ? glmToken : ""
        );
    }

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
                ),
                "claudeLocal", Map.of(
                        "available", claudeLocalService.isAvailable(),
                        "sessioncast", claudeLocalService.isSessionCastAvailable()
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
        if (body.containsKey("glmApiToken")) aiConfig.put("glmApiToken", body.get("glmApiToken"));
        if (body.containsKey("glmBaseUrl")) aiConfig.put("glmBaseUrl", body.get("glmBaseUrl"));
        log.info("AI 설정 변경 — provider: {}, model: {}", aiConfig.get("provider"), aiConfig.get("model"));
        return getAiSettingsSafe();
    }

    @PostMapping("/ai/glm/test")
    public ResponseEntity<?> testGlmConnection(@RequestBody Map<String, String> body) {
        String token = body.get("glmApiToken");
        String baseUrl = body.get("glmBaseUrl");
        
        if (token == null || token.isBlank()) {
            token = aiConfig.get("glmApiToken");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = aiConfig.get("glmBaseUrl");
        }
        
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "GLM API 토큰이 필요합니다"));
        }
        
        boolean success = glmService.testConnection(baseUrl, token);
        
        if (success) {
            aiConfig.put("glmApiToken", token);
            if (baseUrl != null && !baseUrl.isBlank()) {
                aiConfig.put("glmBaseUrl", baseUrl);
            }
            log.info("GLM 연결 테스트 성공");
            return ResponseEntity.ok(Map.of("success", true));
        } else {
            return ResponseEntity.ok(Map.of("success", false, "error", "GLM API 연결 실패"));
        }
    }

    public static String getAiProvider() {
        return aiConfig.getOrDefault("provider", "glm");
    }

    public static String getAiModel() {
        return aiConfig.getOrDefault("model", "");
    }
    
    public static String getGlmApiToken() {
        return aiConfig.getOrDefault("glmApiToken", "");
    }
    
    public static String getGlmBaseUrl() {
        return aiConfig.getOrDefault("glmBaseUrl", "https://open.bigmodel.cn/api/coding/paas/v4");
    }
    
    private Map<String, String> getAiSettingsSafe() {
        return Map.of(
            "provider", aiConfig.get("provider"),
            "model", aiConfig.get("model"),
            "glmBaseUrl", aiConfig.get("glmBaseUrl"),
            "hasGlmToken", aiConfig.get("glmApiToken") != null && !aiConfig.get("glmApiToken").isBlank() ? "true" : "false"
        );
    }

    // ─── Profile Thresholds ───

    @GetMapping("/profile-thresholds")
    public ResponseEntity<?> getProfileThresholds() {
        MetricProfile profile = scoutAgent.getCurrentProfile();
        if (profile == null) {
            return ResponseEntity.ok(Map.of("status", "no_profile", "targets", List.of()));
        }
        return ResponseEntity.ok(Map.of("status", "active", "targets", profile.getTargets()));
    }

    @PutMapping("/profile-thresholds")
    public ResponseEntity<?> updateProfileThresholds(@RequestBody List<Map<String, Object>> updates) {
        MetricProfile profile = scoutAgent.getCurrentProfile();
        if (profile == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "프로파일이 아직 생성되지 않았습니다"));
        }
        int changed = 0;
        for (Map<String, Object> upd : updates) {
            String key = (String) upd.get("key");
            if (key == null) continue;
            for (MetricProfile.MonitorTarget t : profile.getTargets()) {
                if (key.equals(t.getKey())) {
                    if (upd.containsKey("warnThreshold")) {
                        t.setWarnThreshold(((Number) upd.get("warnThreshold")).doubleValue());
                    }
                    if (upd.containsKey("critThreshold")) {
                        t.setCritThreshold(((Number) upd.get("critThreshold")).doubleValue());
                    }
                    changed++;
                    break;
                }
            }
        }
        log.info("프로파일 임계값 {} 건 수정됨", changed);
        return ResponseEntity.ok(Map.of("status", "ok", "changed", changed, "targets", profile.getTargets()));
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

    // ─── Webhook Settings ───

    @GetMapping("/webhook")
    public Map<String, Object> getWebhookSettings() {
        return Map.of(
                "url", WebhookService.getWebhookUrl(),
                "enabled", WebhookService.isEnabled()
        );
    }

    @PutMapping("/webhook")
    public Map<String, Object> updateWebhookSettings(@RequestBody Map<String, String> body) {
        if (body.containsKey("url")) WebhookService.setWebhookUrl(body.get("url"));
        if (body.containsKey("enabled")) WebhookService.setEnabled("true".equals(body.get("enabled")));
        log.info("Webhook 설정 변경 — url: {}, enabled: {}", WebhookService.getWebhookUrl(), WebhookService.isEnabled());
        return Map.of("url", WebhookService.getWebhookUrl(), "enabled", WebhookService.isEnabled());
    }

    @GetMapping("/webhook/history")
    public List<Map<String, Object>> getAlertHistory() {
        return webhookService.getAlertHistory();
    }

    // ─── Feedback ───

    @PostMapping("/feedback")
    public ResponseEntity<?> submitFeedback(@RequestBody FeedbackService.Feedback feedback) {
        feedbackService.addFeedback(feedback);
        return ResponseEntity.ok(Map.of("success", true, "message", "피드백이 반영되었습니다."));
    }

    @GetMapping("/feedback")
    public List<FeedbackService.Feedback> getFeedbacks() {
        return feedbackService.getAllFeedbacks();
    }
}
