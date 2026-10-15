package com.micropass.web;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.Deployment;
import com.micropass.domain.DeploymentRepository;
import com.micropass.service.DeploymentPipelineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AppController {

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentPipelineService pipelineService;

    public AppController(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            DeploymentPipelineService pipelineService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.pipelineService = pipelineService;
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

    @GetMapping("/deployments/{id}")
    public ResponseEntity<Deployment> getDeployment(@PathVariable Long id) {
        return deploymentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

