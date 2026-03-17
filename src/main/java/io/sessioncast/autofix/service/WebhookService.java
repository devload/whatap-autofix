package io.sessioncast.autofix.service;

import io.sessioncast.autofix.model.Pipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Webhook 알림 서비스 — 파이프라인 이벤트 발생 시 외부 URL로 알림 전송.
 * Verifier FAIL, 파이프라인 완료 등 이벤트에 대해 Slack/Discord/커스텀 webhook 발송.
 */
@Slf4j
@Service
public class WebhookService {

    private final WebClient webClient = WebClient.builder().build();

    // Webhook URL 설정 (인메모리)
    private static final Map<String, String> config = new ConcurrentHashMap<>();

    // 최근 알림 이력 (UI 표시용, 최대 50건)
    private final List<Map<String, Object>> alertHistory = new CopyOnWriteArrayList<>();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static void setWebhookUrl(String url) {
        config.put("webhookUrl", url);
    }

    public static String getWebhookUrl() {
        return config.getOrDefault("webhookUrl", "");
    }

    public static void setEnabled(boolean enabled) {
        config.put("enabled", String.valueOf(enabled));
    }

    public static boolean isEnabled() {
        return "true".equals(config.getOrDefault("enabled", "true"));
    }

    /**
     * 파이프라인 FAIL 시 알림 전송
     */
    public void notifyFailure(Pipeline pipeline) {
        if (!isEnabled()) return;

        String summary = pipeline.getVerification() != null
                ? pipeline.getVerification().getSummary() : "검증 실패";
        String rootCause = pipeline.getAnalysis() != null
                ? pipeline.getAnalysis().getRootCause() : "";

        Map<String, Object> alert = Map.of(
                "event", "PIPELINE_FAILED",
                "pipelineId", pipeline.getId(),
                "issueType", pipeline.getIssueType(),
                "severity", pipeline.getSeverity().name(),
                "summary", summary,
                "rootCause", rootCause,
                "timestamp", Instant.now().toString()
        );

        // 이력 저장
        recordAlert(alert);

        // Webhook URL이 설정되어 있으면 HTTP POST 전송
        String url = getWebhookUrl();
        if (url != null && !url.isBlank()) {
            sendWebhook(url, alert, pipeline);
        }

        log.info("Alert: PIPELINE_FAILED — {} {} (pipeline {})",
                pipeline.getIssueType(), summary, pipeline.getId());
    }

    /**
     * 파이프라인 완료 시 알림
     */
    public void notifyCompleted(Pipeline pipeline) {
        if (!isEnabled()) return;

        Map<String, Object> alert = Map.of(
                "event", "PIPELINE_COMPLETED",
                "pipelineId", pipeline.getId(),
                "issueType", pipeline.getIssueType(),
                "summary", "이슈 해결 완료",
                "timestamp", Instant.now().toString()
        );

        recordAlert(alert);

        String url = getWebhookUrl();
        if (url != null && !url.isBlank()) {
            sendWebhook(url, alert, pipeline);
        }
    }

    private void sendWebhook(String url, Map<String, Object> alert, Pipeline pipeline) {
        try {
            // Slack 형식 감지
            if (url.contains("hooks.slack.com")) {
                sendSlack(url, alert, pipeline);
            } else {
                // 범용 JSON POST
                webClient.post()
                        .uri(url)
                        .bodyValue(alert)
                        .retrieve()
                        .bodyToMono(String.class)
                        .subscribe(
                                resp -> log.info("Webhook 전송 성공: {}", url),
                                err -> log.warn("Webhook 전송 실패: {}", err.getMessage())
                        );
            }
        } catch (Exception e) {
            log.warn("Webhook 전송 오류: {}", e.getMessage());
        }
    }

    private void sendSlack(String url, Map<String, Object> alert, Pipeline pipeline) {
        String emoji = "PIPELINE_FAILED".equals(alert.get("event")) ? "🚨" : "✅";
        String color = "PIPELINE_FAILED".equals(alert.get("event")) ? "#dc2626" : "#16a34a";

        String text = String.format("%s *[%s]* %s\n>%s\n>Pipeline: `%s` | %s",
                emoji,
                alert.get("issueType"),
                alert.get("event"),
                alert.get("summary"),
                alert.get("pipelineId"),
                FMT.format(Instant.now()));

        Map<String, Object> slackPayload = Map.of(
                "text", text,
                "attachments", List.of(Map.of(
                        "color", color,
                        "text", alert.getOrDefault("rootCause", "").toString()
                ))
        );

        webClient.post()
                .uri(url)
                .bodyValue(slackPayload)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        resp -> log.info("Slack 알림 전송 성공"),
                        err -> log.warn("Slack 알림 전송 실패: {}", err.getMessage())
                );
    }

    private void recordAlert(Map<String, Object> alert) {
        alertHistory.add(0, alert);
        if (alertHistory.size() > 50) {
            alertHistory.subList(50, alertHistory.size()).clear();
        }
    }

    public List<Map<String, Object>> getAlertHistory() {
        return List.copyOf(alertHistory);
    }
}
