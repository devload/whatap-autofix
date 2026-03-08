package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.GithubApiClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.FixProposal;
import io.sessioncast.autofix.model.FixProposal.FileDiff;
import io.sessioncast.autofix.model.FixProposal.FixType;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.rule.Rule;
import io.sessioncast.autofix.rule.RuleEngine;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FixerAgent {

    private final GithubApiClient githubClient;
    private final RuleEngine ruleEngine;
    private final PipelineService pipelineService;
    private final AutofixProperties props;
    private final DeployerAgent deployerAgent;

    public void fix(Pipeline pipeline) {
        log.info("Fixer Agent: proposing fix for {} (pipeline {})", pipeline.getIssueType(), pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "수정안 생성 시작");

        Rule rule = ruleEngine.findRule(pipeline.getIssueType());

        if (rule != null && rule.getFixScript() != null) {
            // Script-based fix
            FixProposal fix = FixProposal.builder()
                    .type(FixType.SCRIPT)
                    .description("자동 매칭 규칙: " + rule.getName() + " → " + rule.getFixScript())
                    .scriptCommand(rule.getFixScript())
                    .build();
            pipelineService.setFix(pipeline.getId(), fix);
            pipeline.addLog(Pipeline.Stage.FIXER, "스크립트 수정 매칭: " + rule.getFixScript());
            advanceToDeployer(pipeline);
        } else if (isGithubConfigured()) {
            // GitHub 연동 → 코드 변경 제안 + PR 생성
            proposeCodeFix(pipeline);
        } else {
            // GitHub 미연결 → 권장 조치 제안만 하고 Deployer로 넘김
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
     * GitHub 미연결 시: 권장 조치를 제안하고 Deployer로 넘긴다
     */
    private void proposeRecommendation(Pipeline pipeline) {
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
        log.info("Fixer Agent: recommendation proposed for pipeline {} (GitHub not connected)", pipeline.getId());
        pipeline.addLog(Pipeline.Stage.FIXER, "GitHub 미연결 — 권장 조치 제안: " + rec.description);
        pipeline.addLog(Pipeline.Stage.FIXER, "상세: " + rec.detail);

        // GitHub 없어도 Deployer로 진행
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
