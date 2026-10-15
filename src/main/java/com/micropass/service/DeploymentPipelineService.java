package com.micropass.service;

import com.micropass.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;

@Service
public class DeploymentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentPipelineService.class);

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final DockerService dockerService;
    private final PortAllocationService portAllocationService;

    public DeploymentPipelineService(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            WorkspaceService workspaceService,
            GitService gitService,
            DockerService dockerService,
            PortAllocationService portAllocationService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.workspaceService = workspaceService;
        this.gitService = gitService;
        this.dockerService = dockerService;
        this.portAllocationService = portAllocationService;
    }

    public Deployment triggerDeployment(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        Deployment deployment = Deployment.builder()
                .app(app)
                .status(DeploymentStatus.QUEUED)
                .build();
        deployment = deploymentRepository.save(deployment);

        // Execute asynchronously using Spring's virtual thread pool
        runPipelineAsync(app.getId(), deployment.getId());

        return deployment;
    }

    @Async
    public void runPipelineAsync(Long appId, Long deploymentId) {
        App app = appRepository.findById(appId).orElseThrow();
        Deployment deployment = deploymentRepository.findById(deploymentId).orElseThrow();
        StringBuilder logBuffer = new StringBuilder();

        autoLog(deployment, logBuffer, "=== Starting Deployment for App: " + app.getName() + " ===");

        try {
            // Step 1: Prepare Workspace
            deployment.setStatus(DeploymentStatus.CLONING);
            deploymentRepository.save(deployment);
            Path workspacePath = workspaceService.prepareWorkspace(app.getId(), deployment.getId());
            autoLog(deployment, logBuffer, "Workspace initialized at: " + workspacePath.toAbsolutePath());

            // Step 2: Git Clone
            GitService.GitResult gitResult = gitService.cloneRepository(
                    app.getGitUrl(),
                    app.getDefaultBranch(),
                    workspacePath,
                    line -> autoLog(deployment, logBuffer, "[GIT] " + line)
            );

            if (!gitResult.success()) {
                throw new RuntimeException("Git clone failed: " + gitResult.errorOutput());
            }

            deployment.setCommitHash(gitResult.commitHash());
            deploymentRepository.save(deployment);

            // Step 3: Docker Build
            deployment.setStatus(DeploymentStatus.BUILDING);
            deploymentRepository.save(deployment);

            String imageTag = "micropass-" + app.getName();
            DockerService.DockerBuildResult buildResult = dockerService.buildImage(
                    workspacePath,
                    imageTag,
                    gitResult.commitHash(),
                    line -> autoLog(deployment, logBuffer, "[DOCKER-BUILD] " + line)
            );

            if (!buildResult.success()) {
                throw new RuntimeException("Docker build failed.");
            }

            deployment.setImageTag(buildResult.imageTag());
            deploymentRepository.save(deployment);

            // Step 4: Allocate Port & Deploy
            deployment.setStatus(DeploymentStatus.DEPLOYING);
            deploymentRepository.save(deployment);

            int hostPort = portAllocationService.allocatePort(app);
            int containerPort = 80; // Standard container HTTP port for sample apps

            String containerName = "micropass-" + app.getName();
            DockerService.DockerRunResult runResult = dockerService.runContainer(
                    buildResult.imageTag(),
                    containerName,
                    hostPort,
                    containerPort,
                    line -> autoLog(deployment, logBuffer, "[DOCKER-RUN] " + line)
            );

            if (!runResult.success()) {
                throw new RuntimeException("Container launch failed: " + runResult.logs());
            }

            // Step 5: Mark Healthy
            deployment.setContainerId(runResult.containerId());
            deployment.setPort(hostPort);
            deployment.setStatus(DeploymentStatus.HEALTHY);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setBuildLogs(logBuffer.toString());
            deploymentRepository.save(deployment);

            app.setActiveContainerId(runResult.containerId());
            app.setAllocatedPort(hostPort);
            appRepository.save(app);

            autoLog(deployment, logBuffer, "Deployment SUCCESS! Application reachable at http://localhost:" + hostPort);

        } catch (Exception e) {
            log.error("Deployment failed for app {}", app.getName(), e);
            autoLog(deployment, logBuffer, "[ERROR] " + e.getMessage());
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setBuildLogs(logBuffer.toString());
            deploymentRepository.save(deployment);
        }
    }

    private void autoLog(Deployment deployment, StringBuilder logBuffer, String message) {
        log.info(message);
        logBuffer.append(message).append("\n");
    }
}

