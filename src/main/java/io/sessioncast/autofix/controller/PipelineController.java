package io.sessioncast.autofix.controller;

import io.sessioncast.autofix.agent.DeployerAgent;
import io.sessioncast.autofix.agent.FixerAgent;
import io.sessioncast.autofix.model.Pipeline;
import io.sessioncast.autofix.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final FixerAgent fixerAgent;
    private final DeployerAgent deployerAgent;

    @GetMapping
    public List<Pipeline> listPipelines(
            @RequestParam(required = false) Pipeline.PipelineStatus status) {
        if (status != null) {
            return pipelineService.getPipelinesByStatus(status);
        }
        return pipelineService.getAllPipelines();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Pipeline> getPipeline(@PathVariable String id) {
        Pipeline pipeline = pipelineService.getPipeline(id);
        if (pipeline == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(pipeline);
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return pipelineService.getStats();
    }

    // Manual action: deploy a fix
    @PostMapping("/{id}/deploy")
    public ResponseEntity<Pipeline> deploy(@PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        Pipeline pipeline = pipelineService.getPipeline(id);
        if (pipeline == null) return ResponseEntity.notFound().build();
        if (pipeline.getCurrentStage() != Pipeline.Stage.FIXER) {
            return ResponseEntity.badRequest().build();
        }

        String target = body.getOrDefault("target", "ote");
        fixerAgent.advanceToDeployer(pipeline);
        deployerAgent.deploy(pipeline, target);
        return ResponseEntity.ok(pipeline);
    }

    // Manual action: skip to next stage or skip pipeline
    @PostMapping("/{id}/skip")
    public ResponseEntity<Pipeline> skip(@PathVariable String id) {
        Pipeline pipeline = pipelineService.getPipeline(id);
        if (pipeline == null) return ResponseEntity.notFound().build();
        pipeline.addLog(pipeline.getCurrentStage(), "사용자가 건너뜀");
        pipeline.complete();
        return ResponseEntity.ok(pipeline);
    }

    // Manual action: retry failed pipeline
    @PostMapping("/{id}/retry")
    public ResponseEntity<Pipeline> retry(@PathVariable String id) {
        Pipeline pipeline = pipelineService.getPipeline(id);
        if (pipeline == null) return ResponseEntity.notFound().build();
        if (pipeline.getStatus() != Pipeline.PipelineStatus.FAILED) {
            return ResponseEntity.badRequest().build();
        }

        Pipeline.Stage failedStage = pipeline.getCurrentStage();
        pipeline.setStatus(Pipeline.PipelineStatus.IN_PROGRESS);
        pipeline.addLog(failedStage, failedStage + " 단계에서 재시도");

        // Re-trigger the failed stage
        switch (failedStage) {
            case DEPLOYER -> deployerAgent.deploy(pipeline,
                    pipeline.getDeploy() != null ? pipeline.getDeploy().getDeployTarget() : "ote");
            default -> pipeline.addLog(failedStage, "수동 재시도 — 해당 단계는 수동 처리가 필요합니다");
        }
        return ResponseEntity.ok(pipeline);
    }
}
