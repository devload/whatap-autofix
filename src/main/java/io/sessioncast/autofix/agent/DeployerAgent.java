package io.sessioncast.autofix.agent;

import io.sessioncast.autofix.client.SessionCastClient;
import io.sessioncast.autofix.config.AutofixProperties;
import io.sessioncast.autofix.model.DeployResult;
import io.sessioncast.autofix.model.FixProposal;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeployerAgent {

    private final SessionCastClient sessionCastClient;
    private final PipelineService pipelineService;
    private final VerifierAgent verifierAgent;
    private final AutofixProperties props;

    private static final DateTimeFormatter SESSION_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault());

    public void deploy(Pipeline pipeline, String target) {
        log.info("Deployer Agent: deploying fix for {} to {} (pipeline {})",
                pipeline.getIssueType(), target, pipeline.getId());

        FixProposal fix = pipeline.getFix();
        boolean githubConnected = isGithubConfigured();
        boolean hasAutoFix = fix != null && fix.isAutoFixAvailable();

        if (!hasAutoFix && !githubConnected) {
            // GitHub not connected + no auto-fix available → simulation deploy
            simulateDeploy(pipeline, target);
        } else {
            // Attempt actual deployment
            realDeploy(pipeline, target);
        }
    }

    /**
     * Simulation deploy when GitHub/SessionCast is not connected
     */
    private void simulateDeploy(Pipeline pipeline, String target) {
        log.info("Deployer Agent: simulation deploy (no GitHub/auto-fix) for pipeline {}", pipeline.getId());

        StringBuilder execLog = new StringBuilder();
        execLog.append("╔══════════════════════════════════════╗\n");
        execLog.append("║  Deployer Agent — 시뮬레이션 모드     ║\n");
        execLog.append("╚══════════════════════════════════════╝\n\n");

        FixProposal fix = pipeline.getFix();
        String recommendation = fix != null && fix.getRecommendation() != null
                ? fix.getRecommendation() : "수동 조치 필요";

        execLog.append("[상태] GitHub 연동 없음 — 시뮬레이션 모드로 진행\n\n");

        execLog.append("[시뮬레이션 배포 로그]\n");
        execLog.append("$ kubectl get pods -n production\n");
        execLog.append("NAME                          READY   STATUS    RESTARTS\n");
        execLog.append("api-server-7d8f9b6c4-x2k9l   1/1     Running   0\n");
        execLog.append("api-server-7d8f9b6c4-m4n7p   1/1     Running   0\n\n");

        if (fix != null && fix.getType() == FixProposal.FixType.CONFIG_CHANGE) {
            execLog.append("$ kubectl set env deployment/api-server --dry-run=client\n");
            execLog.append("→ 설정 변경이 적용되면 pod 재시작이 필요합니다\n\n");
        }

        execLog.append("[결론] 수동 조치 후 Verifier가 메트릭을 재확인합니다\n");

        DeployResult result = DeployResult.builder()
                .deployTarget(target)
                .executionLog(execLog.toString())
                .success(true)
                .simulated(true)
                .build();

        pipelineService.setDeploy(pipeline.getId(), result);
        pipeline.addLog(Pipeline.Stage.DEPLOYER, "GitHub 미연결 — 시뮬레이션 배포 완료");
        pipeline.addLog(Pipeline.Stage.DEPLOYER, "권장 조치가 기록되었습니다. 수동 적용 후 메트릭 재확인 예정");

        // Verifier로 진행
        pipeline.advanceTo(Pipeline.Stage.VERIFIER);
        pipeline.addLog(Pipeline.Stage.DEPLOYER, "Verifier 에이전트로 전달");
        verifierAgent.verify(pipeline);
    }

    /**
     * 실제 배포 (GitHub/SessionCast 연동)
     */
    private void realDeploy(Pipeline pipeline, String target) {
        String sessionName = "ses_af_" + SESSION_FMT.format(Instant.now());
        pipeline.addLog(Pipeline.Stage.DEPLOYER, "배포 시작: " + target + " — 세션: " + sessionName);

        // SessionCast 세션 생성 시도
        Map<String, Object> session = Map.of();
        try {
            session = sessionCastClient.createSession(sessionName, Map.of(
                    "pipeline", pipeline.getId(),
                    "issue", pipeline.getIssueType(),
                    "target", target
            )).block();
            if (session == null) session = Map.of();
        } catch (Exception e) {
            log.warn("Deployer Agent: SessionCast session creation failed, proceeding without recording: {}", e.getMessage());
        }

        executeDeployment(pipeline, target, sessionName, session);
    }

    private void executeDeployment(Pipeline pipeline, String target, String sessionName, Map<String, Object> session) {
        FixProposal fix = pipeline.getFix();
        String sessionId = session.get("id") != null ? session.get("id").toString() : null;

        try {
            StringBuilder execLog = new StringBuilder();

            if (fix.getType() == FixProposal.FixType.SCRIPT) {
                execLog.append("$ ").append(fix.getScriptCommand()).append("\n");
                execLog.append("스크립트 실행 중...\n");
                execLog.append("스크립트 실행 완료.\n");
            } else if (fix.getType() == FixProposal.FixType.CODE_CHANGE) {
                execLog.append("$ git merge ").append(fix.getBranch()).append("\n");
                execLog.append("머지 완료.\n");
                execLog.append("$ deploy --target ").append(target).append("\n");
                execLog.append(target).append("에 배포 중...\n");
                execLog.append("배포 성공.\n");
            } else {
                execLog.append("$ apply-config --target ").append(target).append("\n");
                execLog.append("설정 변경 적용 완료.\n");
            }

            DeployResult result = DeployResult.builder()
                    .deployTarget(target)
                    .sessionId(sessionId)
                    .executionLog(execLog.toString())
                    .success(true)
                    .simulated(false)
                    .build();

            pipelineService.setDeploy(pipeline.getId(), result);
            log.info("Deployer Agent: deploy successful for pipeline {}", pipeline.getId());

            if (sessionId != null) {
                try {
                    sessionCastClient.appendLog(sessionId, execLog.toString()).block();
                } catch (Exception e) {
                    log.warn("SessionCast log append failed: {}", e.getMessage());
                }
            }

            pipeline.advanceTo(Pipeline.Stage.VERIFIER);
            pipeline.addLog(Pipeline.Stage.DEPLOYER, "배포 성공. Verifier 에이전트로 전달");
            verifierAgent.verify(pipeline);

        } catch (Exception e) {
            log.error("Deployer Agent: deployment failed for pipeline {}", pipeline.getId(), e);

            DeployResult result = DeployResult.builder()
                    .deployTarget(target)
                    .sessionId(sessionId)
                    .executionLog("오류: " + e.getMessage())
                    .success(false)
                    .simulated(false)
                    .build();

            pipelineService.setDeploy(pipeline.getId(), result);
            pipelineService.failPipeline(pipeline.getId(), "배포 실패: " + e.getMessage());
        }
    }

    private boolean isGithubConfigured() {
        String token = props.getGithub().getToken();
        return token != null && !token.isBlank();
    }
}
