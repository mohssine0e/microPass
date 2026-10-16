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

    public AppController(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            DeploymentPipelineService pipelineService,
            LogStreamService logStreamService,
            DockerService dockerService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.pipelineService = pipelineService;
        this.logStreamService = logStreamService;
        this.dockerService = dockerService;
    }

    public record CreateAppRequest(
            @NotBlank String name,
            @NotBlank String gitUrl,
            String defaultBranch
    ) {}

    @PostMapping("/apps")
    public ResponseEntity<App> createApp(@RequestBody @Valid CreateAppRequest req) {
        App app = App.builder()
                .name(req.name().trim().toLowerCase())
                .gitUrl(req.gitUrl().trim())
                .defaultBranch(req.defaultBranch() == null || req.defaultBranch().isBlank() ? "main" : req.defaultBranch().trim())
                .build();
        return ResponseEntity.ok(appRepository.save(app));
    }

    @GetMapping("/apps")
    public ResponseEntity<List<App>> listApps() {
        return ResponseEntity.ok(appRepository.findAll());
    }

    @PostMapping("/apps/{id}/deploy")
    public ResponseEntity<Deployment> triggerDeploy(@PathVariable Long id) {
        Deployment deployment = pipelineService.triggerDeployment(id);
        return ResponseEntity.accepted().body(deployment);
    }

    @PostMapping("/apps/{id}/stop")
    public ResponseEntity<Map<String, String>> stopApp(@PathVariable Long id) {
        pipelineService.stopApp(id);
        return ResponseEntity.ok(Map.of("message", "Application stopped successfully"));
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
    public ResponseEntity<Deployment> getDeployment(@PathVariable Long id) {
        return deploymentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/deployments/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable Long id) {
        Deployment deployment = deploymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Deployment not found: " + id));

        return logStreamService.subscribe(id, deployment.getBuildLogs());
    }
}
