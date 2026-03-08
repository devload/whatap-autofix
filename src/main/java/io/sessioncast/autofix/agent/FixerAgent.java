package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.GithubApiClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.controller.SettingsController;
import io.sessioncast.autofix.model.FixProposal;
import io.sessioncast.autofix.model.FixProposal.FileDiff;
import io.sessioncast.autofix.model.FixProposal.FixType;
import io.sessioncast.autofix.model.Metric;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import io.sessioncast.core.SessionCastClient;
import io.sessioncast.core.api.LlmChatRequest;
import io.sessioncast.core.api.LlmChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FixerAgent {

    private final GithubApiClient githubClient;
    private final RuleEngine ruleEngine;
    private final PipelineService pipelineService;
    private final AutofixProperties props;
    private final DeployerAgent deployerAgent;
    private final SessionCastClient sessionCastClient;

    public FixerAgent(GithubApiClient githubClient,
                      RuleEngine ruleEngine,
                      PipelineService pipelineService,
                      AutofixProperties props,
                      DeployerAgent deployerAgent,
                      @Autowired(required = false) SessionCastClient sessionCastClient) {
        this.githubClient = githubClient;
        this.ruleEngine = ruleEngine;
        this.pipelineService = pipelineService;
        this.props = props;
        this.deployerAgent = deployerAgent;
        this.sessionCastClient = sessionCastClient;
    }

    public void fix(Pipeline pipeline) {
        log.info("Fixer Agent: proposing fix for {} (pipeline {})", pipeline.getIssueType(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "수정안 생성 시작");

        Rule rule = ruleEngine.findRule(pipeline.getIssueType());

        if (rule != null && rule.getFixScript() != null) {
            FixProposal fix = FixProposal.builder()
                    .type(FixType.SCRIPT)
                    .description("자동 매칭 규칙: " + rule.getName() + " → " + rule.getFixScript())
                    .scriptCommand(rule.getFixScript())
                    .build();
            pipelineService.setFix(pipeline.getId(), fix);
            pipeline.addLog(Pipeline.Stage.FIXER, "스크립트 수정 매칭: " + rule.getFixScript());
            advanceToDeployer(pipeline);
        } else if (isGithubConfigured()) {
            proposeCodeFix(pipeline);
        } else {
            proposeRecommendation(pipeline);
        }
    }

    private boolean isGithubConfigured() {
        String token = props.getGithub().getToken();
        String owner = props.getGithub().getOwner();
        String repo = props.getGithub().getRepo();
        return token != null && !token.isBlank()
                && owner != null && !owner.isBlank()
                && repo != null && !repo.isBlank();
    }

    /**
     * GitHub 미연결 시: LLM으로 권장 조치 생성, 실패 시 규칙 기반 폴백
     */
    private void proposeRecommendation(Pipeline pipeline) {
        try {
            if (sessionCastClient != null) {
                performLlmRecommendation(pipeline);
            } else {
                fallbackRecommendation(pipeline);
            }
        } catch (Exception e) {
            log.error("Fixer: LLM fix generation failed, using fallback for pipeline {}", pipeline.getId(), e);
            pipeline.addLog(Pipeline.Stage.FIXER, "LLM 수정안 생성 실패 — 규칙 기반 제안으로 전환");
            fallbackRecommendation(pipeline);
        }
    }

    /**
     * SessionCast Relay → LLM으로 마크다운 형식의 동적 수정안 생성
     */
    private void performLlmRecommendation(Pipeline pipeline) throws Exception {
        String issueType = pipeline.getIssueType();
        double value = pipeline.getIssue().getValue();
        double threshold = pipeline.getIssue().getThreshold();
        String rootCause = pipeline.getAnalysis() != null ? pipeline.getAnalysis().getRootCause() : "분석 결과 없음";
        double confidence = pipeline.getAnalysis() != null ? pipeline.getAnalysis().getConfidence() : 0.0;
        var correlatedMetrics = pipeline.getAnalysis() != null ? pipeline.getAnalysis().getCorrelatedMetrics() : List.of();

        Metric latest = pipelineService.getLatestMetric();
        String metricsSnapshot = formatMetrics(latest);

        String prompt = String.format("""
                ## 장애 분석 결과
                - 이슈 타입: %s
                - 근본 원인: %s
                - 신뢰도: %.0f%%
                - 상관 메트릭: %s

                ## 현재 서버 메트릭
                %s

                ## 감지 기준
                - 메트릭: %s, 현재 값: %.1f, 임계값: %.1f

                ## 요청사항
                위 장애 분석 결과를 바탕으로 구체적인 수정 권장안을 작성해주세요.

                반드시 아래 JSON 형식으로 응답해주세요:
                {"description": "1줄 요약 (50자 이내)", "recommendation": "마크다운 형식의 상세 권장 조치"}

                recommendation 마크다운에 반드시 포함할 항목:
                1. **긴급도 판단** — 현재 상황의 심각성 평가
                2. **즉시 조치 사항** — 번호 목록으로 구체적 조치
                3. **설정 변경 예시** — application.yml, JVM 옵션 등을 코드 블록으로
                4. **근본 원인 해결** — 중장기 개선 방안
                5. **모니터링 포인트** — 테이블 형식으로 (메트릭명 | 정상범위 | 현재값 | 상태)

                다양한 마크다운 요소(테이블, 코드 블록, 볼드, 리스트 등)를 활용하고,
                현재 메트릭 값을 기반으로 상황에 맞는 구체적인 수치와 조치를 제시해주세요.
                반드시 한국어로 응답하세요.
                """,
                issueType, rootCause, confidence * 100, correlatedMetrics,
                metricsSnapshot, pipeline.getIssue().getMetric(), value, threshold);

        String aiProvider = SettingsController.getAiProvider();
        String aiModel = SettingsController.getAiModel();
        pipeline.addLog(Pipeline.Stage.FIXER, "LLM 수정안 요청 중... (provider: " + aiProvider + ")");

        var requestBuilder = LlmChatRequest.builder()
                .system("당신은 서버 운영 전문가이자 SRE(Site Reliability Engineer)입니다. "
                      + "장애 분석 결과를 바탕으로 구체적인 수정 권장안을 마크다운으로 작성합니다. "
                      + "반드시 한국어로, JSON 형식으로 응답하세요.")
                .user(prompt);
        if (aiModel != null && !aiModel.isBlank()) {
            requestBuilder.model(aiModel);
        }
        LlmChatRequest request = requestBuilder.build();

        LlmChatResponse response = sessionCastClient.llmChat(request)
                .get(5, java.util.concurrent.TimeUnit.MINUTES);

        if (response.hasError()) {
            log.warn("Fixer: LLM response error: {}", response.error().message());
            throw new RuntimeException("LLM error: " + response.error().message());
        }

        String content = response.content();
        log.info("Fixer: LLM response received ({} chars)", content != null ? content.length() : 0);
        pipeline.addLog(Pipeline.Stage.FIXER, "LLM 수정안 생성 완료");

        // Parse LLM response
        FixProposal fix = parseLlmFixResponse(content, pipeline);
        pipelineService.setFix(pipeline.getId(), fix);

        log.info("Fixer: LLM fix description='{}' for pipeline {}", fix.getDescription(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "AI 수정안: " + fix.getDescription());

        advanceToDeployer(pipeline);
    }

    private FixProposal parseLlmFixResponse(String content, Pipeline pipeline) {
        try {
            String json = content;
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                json = content.substring(jsonStart, jsonEnd + 1);
            }

            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);

            String description = node.has("description") ? node.get("description").asText() : "AI 생성 수정안";
            String recommendation = node.has("recommendation") ? node.get("recommendation").asText() : content;

            return FixProposal.builder()
                    .type(FixType.CONFIG_CHANGE)
                    .description(description)
                    .recommendation(recommendation)
                    .autoFixAvailable(false)
                    .build();

        } catch (Exception e) {
            log.warn("Fixer: LLM response parsing failed, using raw content");
            return FixProposal.builder()
                    .type(FixType.CONFIG_CHANGE)
                    .description(pipeline.getIssueType() + " — AI 분석 기반 수정안")
                    .recommendation(content != null ? content : "파싱 실패")
                    .autoFixAvailable(false)
                    .build();
        }
    }

    private String formatMetrics(Metric m) {
        if (m == null) return "메트릭 데이터 없음";
        return String.format(
                "CPU: %.1f%%, Memory: %.1f%%, TPS: %d, ErrorRate: %.1f%%, ActiveTx: %d, ResponseTime: %dms, DBPool: %.1f%%",
                m.getCpu(), m.getMemory(), m.getTps(), m.getErrorRate(),
                m.getActiveTransaction(), m.getResponseTime(), m.getDbPoolUsage());
    }

    /**
     * 규칙 기반 폴백 (SessionCast 미연결 시)
     */
    private void fallbackRecommendation(Pipeline pipeline) {
        String issueType = pipeline.getIssueType();
        String rootCause = pipeline.getAnalysis() != null ? pipeline.getAnalysis().getRootCause() : "";

        RecommendedFix rec = getRecommendation(issueType, rootCause);

        FixProposal fix = FixProposal.builder()
                .type(FixType.CONFIG_CHANGE)
                .description(rec.description)
                .recommendation(rec.detail)
                .autoFixAvailable(false)
                .build();

        pipelineService.setFix(pipeline.getId(), fix);
        log.info("Fixer Agent: fallback recommendation for pipeline {}", pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "규칙 기반 제안: " + rec.description);

        advanceToDeployer(pipeline);
    }

    private record RecommendedFix(String description, String detail) {}

    private RecommendedFix getRecommendation(String issueType, String rootCause) {
        return switch (issueType) {
            case "CPU_HIGH" -> new RecommendedFix(
                    "CPU 사용량 과다 — 연결 풀 및 스레드 풀 설정 조정 권장",
                    "1) HikariCP maximum-pool-size: 20→50 증가\n"
                    + "2) server.tomcat.threads.max: 200→400\n"
                    + "3) 활성 트랜잭션이 높으면 slow query 점검 필요"
            );
            case "MEMORY_HIGH" -> new RecommendedFix(
                    "메모리 사용량 과다 — 힙 설정 및 리소스 누수 점검 권장",
                    "1) JVM -Xmx 값 확인 (현재 대비 20% 여유 권장)\n"
                    + "2) HttpClient/DB Connection close 누수 점검\n"
                    + "3) 캐시 TTL 및 최대 크기 제한 확인"
            );
            case "ERROR_SPIKE" -> new RecommendedFix(
                    "에러율 급증 — Circuit Breaker 및 의존성 점검 권장",
                    "1) 외부 API 의존성 타임아웃 설정 확인\n"
                    + "2) @CircuitBreaker 패턴 적용 검토\n"
                    + "3) 최근 배포 이력 확인 및 롤백 고려"
            );
            case "RESPONSE_SLOW" -> new RecommendedFix(
                    "응답 시간 지연 — DB 쿼리 및 연결 풀 최적화 권장",
                    "1) Slow query 로그 확인 (2초 이상)\n"
                    + "2) DB 커넥션 풀 사용량 확인\n"
                    + "3) 캐시 적용 가능한 API 점검\n"
                    + "4) N+1 쿼리 패턴 확인"
            );
            case "DB_POOL_EXHAUSTED" -> new RecommendedFix(
                    "DB 커넥션 풀 고갈 — 커넥션 누수 및 풀 설정 점검 권장",
                    "1) 트랜잭션 미종료 커넥션 확인\n"
                    + "2) HikariCP leak-detection-threshold 설정\n"
                    + "3) maximum-pool-size 증가 검토\n"
                    + "4) idle 커넥션 타임아웃 조정"
            );
            case "DISK_FULL" -> new RecommendedFix(
                    "디스크 용량 부족 — 로그 정리 및 로테이션 설정 권장",
                    "1) /var/log 하위 오래된 로그 정리\n"
                    + "2) logback maxHistory/maxFileSize 설정 확인\n"
                    + "3) /tmp 임시 파일 정리"
            );
            default -> new RecommendedFix(
                    issueType + " 이슈 — 설정 점검 권장",
                    "수동 확인이 필요합니다. WhaTap 대시보드에서 상세 메트릭을 확인해주세요."
            );
        };
    }

    private void proposeCodeFix(Pipeline pipeline) {
        String issueType = pipeline.getIssueType();
        String rootCause = pipeline.getAnalysis() != null ? pipeline.getAnalysis().getRootCause() : "";

        FixProposal fix = generateFixForIssue(issueType, rootCause);
        pipelineService.setFix(pipeline.getId(), fix);

        log.info("Fixer Agent: {} fix proposed for pipeline {}", fix.getType(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "수정안 제안: " + fix.getDescription());

        if (fix.getType() == FixType.CODE_CHANGE && fix.getDiffs() != null && !fix.getDiffs().isEmpty()) {
            createPullRequest(pipeline, fix);
        }
    }

    private FixProposal generateFixForIssue(String issueType, String rootCause) {
        return switch (issueType) {
            case "CPU_HIGH" -> FixProposal.builder()
                    .type(FixType.CODE_CHANGE)
                    .description("Active Transaction 급증 대응을 위한 커넥션 풀 크기 증가")
                    .repository(props.getGithub().getOwner() + "/" + props.getGithub().getRepo())
                    .branch("fix/cpu-high-pool-config")
                    .diffs(List.of(FileDiff.builder()
                            .filePath("src/main/resources/application.yml")
                            .diff("pool.maximum-pool-size: 20 → 50, connection-timeout: 30000 → 10000")
                            .build()))
                    .autoFixAvailable(true)
                    .build();

            case "ERROR_SPIKE" -> FixProposal.builder()
                    .type(FixType.CODE_CHANGE)
                    .description("장애 엔드포인트에 Circuit Breaker 및 재시도 로직 추가")
                    .repository(props.getGithub().getOwner() + "/" + props.getGithub().getRepo())
                    .branch("fix/error-spike-circuit-breaker")
                    .diffs(List.of(FileDiff.builder()
                            .filePath("src/main/java/service/ApiService.java")
                            .diff("@CircuitBreaker 어노테이션 및 폴백 메서드 추가")
                            .build()))
                    .autoFixAvailable(true)
                    .build();

            case "DB_POOL_EXHAUSTED" -> FixProposal.builder()
                    .type(FixType.SCRIPT)
                    .description("DB 커넥션 정리 스크립트 실행")
                    .scriptCommand("scripts/db-cleanup.sh")
                    .autoFixAvailable(true)
                    .build();

            case "MEMORY_HIGH" -> FixProposal.builder()
                    .type(FixType.CODE_CHANGE)
                    .description("HTTP 클라이언트 리소스 누수 수정")
                    .repository(props.getGithub().getOwner() + "/" + props.getGithub().getRepo())
                    .branch("fix/memory-leak-httpclient")
                    .diffs(List.of(FileDiff.builder()
                            .filePath("src/main/java/service/UserService.java")
                            .diff("HttpClient를 try-with-resources로 래핑")
                            .build()))
                    .autoFixAvailable(true)
                    .build();

            default -> FixProposal.builder()
                    .type(FixType.CONFIG_CHANGE)
                    .description(issueType + " 대응 설정 조정")
                    .autoFixAvailable(false)
                    .build();
        };
    }

    private void createPullRequest(Pipeline pipeline, FixProposal fix) {
        String branchName = fix.getBranch();

        try {
            githubClient.getMainBranchRef()
                    .flatMap(ref -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> object = (Map<String, Object>) ref.get("object");
                        String sha = (String) object.get("sha");
                        return githubClient.createBranch(branchName, sha);
                    })
                    .flatMap(branch -> githubClient.createPullRequest(
                            "fix: " + pipeline.getIssueType().toLowerCase().replace("_", " "),
                            "## AutoFix Agent\n\n"
                                    + "**Issue:** " + pipeline.getIssueType() + "\n"
                                    + "**Root Cause:** " + (pipeline.getAnalysis() != null ? pipeline.getAnalysis().getRootCause() : "N/A") + "\n"
                                    + "**Fix:** " + fix.getDescription() + "\n\n"
                                    + "Generated by AutoFix Agent Pipeline " + pipeline.getId(),
                            branchName,
                            props.getGithub().getDefaultBranch()
                    ))
                    .subscribe(
                            pr -> {
                                log.info("Fixer Agent: PR #{} created for pipeline {}", pr.get("number"), pipeline.getId());
                                pipeline.addLog(Pipeline.Stage.FIXER, "PR #" + pr.get("number") + " 생성 완료");
                            },
                            error -> {
                                log.error("Fixer Agent: failed to create PR for pipeline {}", pipeline.getId(), error);
                                pipeline.addLog(Pipeline.Stage.FIXER, "PR 생성 실패: " + error.getMessage());
                            }
                    );
        } catch (Exception e) {
            log.error("Fixer Agent: PR creation error for pipeline {}", pipeline.getId(), e);
            pipeline.addLog(Pipeline.Stage.FIXER, "PR 생성 오류: " + e.getMessage());
        }
    }

    public void advanceToDeployer(Pipeline pipeline) {
        pipeline.advanceTo(Pipeline.Stage.DEPLOYER);
        pipeline.addLog(Pipeline.Stage.FIXER, "Deployer 에이전트로 전달");
        String target = pipeline.getDeploy() != null ? pipeline.getDeploy().getDeployTarget() : "ote";
        deployerAgent.deploy(pipeline, target);
    }
}
