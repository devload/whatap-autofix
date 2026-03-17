package io.sessioncast.autofix.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 분석 피드백 서비스 — 사용자가 분석 결과를 평가하면 다음 분석 프롬프트에 반영.
 * "맞다/틀리다" + 코멘트를 저장하고, Analyzer가 프롬프트에 과거 피드백을 포함.
 */
@Slf4j
@Service
public class FeedbackService {

    private final List<Feedback> feedbacks = new CopyOnWriteArrayList<>();

    @Data
    public static class Feedback {
        private String pipelineId;
        private String issueType;
        private String rootCause;
        private String rating;      // "correct", "partially", "wrong"
        private String comment;     // 사용자 코멘트
        private Instant createdAt = Instant.now();
    }

    public void addFeedback(Feedback feedback) {
        feedbacks.add(0, feedback);
        if (feedbacks.size() > 100) {
            feedbacks.subList(100, feedbacks.size()).clear();
        }
        log.info("Feedback recorded: pipeline={} rating={} comment={}",
                feedback.getPipelineId(), feedback.getRating(), feedback.getComment());
    }

    public List<Feedback> getAllFeedbacks() {
        return List.copyOf(feedbacks);
    }

    /**
     * 최근 피드백을 기반으로 Analyzer 프롬프트에 추가할 컨텍스트 생성.
     * 같은 issueType의 과거 피드백을 참조하여 AI가 학습.
     */
    public String buildFeedbackContext(String issueType) {
        List<Feedback> relevant = feedbacks.stream()
                .filter(f -> f.getIssueType().equals(issueType))
                .limit(5)
                .toList();

        if (relevant.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n## 과거 분석 피드백 (같은 이슈 타입)\n");
        sb.append("사용자가 이전 분석 결과에 대해 다음과 같이 평가했습니다. 이를 참고하세요:\n");

        for (Feedback f : relevant) {
            String ratingLabel = switch (f.getRating()) {
                case "correct" -> "✓ 정확함";
                case "partially" -> "△ 부분적으로 맞음";
                case "wrong" -> "✗ 틀림";
                default -> f.getRating();
            };

            sb.append(String.format("- [%s] 근본 원인: \"%s\"", ratingLabel, f.getRootCause()));
            if (f.getComment() != null && !f.getComment().isBlank()) {
                sb.append(String.format(" → 사용자 코멘트: \"%s\"", f.getComment()));
            }
            sb.append("\n");
        }
        sb.append("위 피드백을 반영하여 분석 정확도를 높이세요.\n");

        return sb.toString();
    }
}
