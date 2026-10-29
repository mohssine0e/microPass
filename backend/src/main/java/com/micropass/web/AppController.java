package com.micropass.web;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.Deployment;
import com.micropass.domain.DeploymentRepository;
import com.micropass.service.DeploymentPipelineService;
import com.micropass.service.DockerService;
import com.micropass.service.LogStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AppController {

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentPipelineService pipelineService;
    private final LogStreamService logStreamService;
    private final DockerService dockerService;
    private final com.micropass.service.GitService gitService;

    public AppController(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            DeploymentPipelineService pipelineService,
            LogStreamService logStreamService,
            DockerService dockerService,
            com.micropass.service.GitService gitService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.pipelineService = pipelineService;
        this.logStreamService = logStreamService;
        this.dockerService = dockerService;
        this.gitService = gitService;
    }

    public record CreateAppRequest(
            @NotBlank String name,
            @NotBlank String gitUrl,
            String defaultBranch,
            String dockerfilePath,
            Integer targetPort
    ) {}

    @PostMapping("/apps")
    public ResponseEntity<App> createApp(@RequestBody @Valid CreateAppRequest req) {
        App app = App.builder()
                .name(req.name().trim().toLowerCase())
                .gitUrl(req.gitUrl().trim())
                .defaultBranch(req.defaultBranch() == null || req.defaultBranch().isBlank() ? "main" : req.defaultBranch().trim())
                .dockerfilePath(req.dockerfilePath() != null && !req.dockerfilePath().isBlank() ? req.dockerfilePath().trim() : "Dockerfile")
                .targetPort(req.targetPort() != null ? req.targetPort() : 80)
                .build();
        return ResponseEntity.ok(appRepository.save(app));
    }

    @GetMapping("/git/branches")
    public ResponseEntity<List<String>> getBranches(@RequestParam String gitUrl) {
        return ResponseEntity.ok(gitService.listRemoteBranches(gitUrl));
    }

    @GetMapping("/apps/{id}/latest-deployment")
    public ResponseEntity<DeploymentResponse> getLatestDeployment(@PathVariable Long id) {
        return deploymentRepository.findTopByAppIdOrderByIdDesc(id)
                .map(d -> ResponseEntity.ok(toResponse(d)))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/apps")
    public ResponseEntity<List<App>> listApps() {
        return ResponseEntity.ok(appRepository.findAll());
    }

    @PostMapping("/apps/{id}/deploy")
    public ResponseEntity<DeploymentResponse> triggerDeploy(@PathVariable Long id) {
        Deployment deployment = pipelineService.triggerDeployment(id);
        return ResponseEntity.accepted().body(toResponse(deployment));
    }

    @PostMapping("/apps/{id}/stop")
    public ResponseEntity<Map<String, String>> stopApp(@PathVariable Long id) {
        pipelineService.stopApp(id);
        return ResponseEntity.ok(Map.of("message", "Application stopped successfully"));
    }

    @DeleteMapping("/apps/{id}")
    public ResponseEntity<Map<String, String>> deleteApp(@PathVariable Long id) {
        pipelineService.deleteApp(id);
        return ResponseEntity.ok(Map.of("message", "Application and workspace deleted successfully"));
    }

    @PostMapping("/deployments/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelDeployment(@PathVariable Long id) {
        boolean cancelled = pipelineService.cancelDeployment(id);
        return ResponseEntity.ok(Map.of(
                "deploymentId", id,
                "cancelled", cancelled,
                "message", cancelled ? "Cancellation signal sent" : "Deployment is not currently active"
        ));
    }

    @GetMapping("/apps/{id}/status")
    public ResponseEntity<Map<String, Object>> getAppStatus(@PathVariable Long id) {
        return appRepository.findById(id).map(app -> {
            boolean isRunning = dockerService.isContainerRunning("micropass-" + app.getName());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "appId", app.getId(),
                    "name", app.getName(),
                    "running", isRunning,
                    "allocatedPort", app.getAllocatedPort() != null ? app.getAllocatedPort() : 0,
                    "activeContainerId", app.getActiveContainerId() != null ? app.getActiveContainerId() : ""
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/deployments/{id}")
    public ResponseEntity<DeploymentResponse> getDeployment(@PathVariable Long id) {
        return deploymentRepository.findById(id)
                .map(d -> ResponseEntity.ok(toResponse(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    private DeploymentResponse toResponse(Deployment d) {
        return new DeploymentResponse(
                d.getId(),
                d.getApp() != null ? d.getApp().getId() : null,
                d.getApp() != null ? d.getApp().getName() : null,
                d.getStatus(),
                d.getCommitHash(),
                d.getImageTag(),
                d.getContainerId(),
                d.getPort(),
                d.getStartedAt(),
                d.getFinishedAt()
        );
    }

    @GetMapping(value = "/deployments/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable Long id) {
        Deployment deployment = deploymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Deployment not found: " + id));

        boolean isTerminal = deployment.getFinishedAt() != null ||
                deployment.getStatus() == com.micropass.domain.DeploymentStatus.HEALTHY ||
                deployment.getStatus() == com.micropass.domain.DeploymentStatus.FAILED ||
                deployment.getStatus() == com.micropass.domain.DeploymentStatus.CANCELLED ||
                deployment.getStatus() == com.micropass.domain.DeploymentStatus.STOPPED;

        return logStreamService.subscribe(id, deployment.getBuildLogs(), isTerminal);
    }
}
