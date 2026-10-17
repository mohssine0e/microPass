package com.micropass.service;

import com.micropass.common.CommandExecutor;
import com.micropass.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DeploymentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentPipelineService.class);

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final DockerService dockerService;
    private final PortAllocationService portAllocationService;
    private final LogStreamService logStreamService;
    private final HealthCheckService healthCheckService;

    // Active cancellation flags and process handles for instant killing
    private final Map<Long, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicReference<Process>> activeProcesses = new ConcurrentHashMap<>();

    public DeploymentPipelineService(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            WorkspaceService workspaceService,
            GitService gitService,
            DockerService dockerService,
            PortAllocationService portAllocationService,
            LogStreamService logStreamService,
            HealthCheckService healthCheckService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.workspaceService = workspaceService;
        this.gitService = gitService;
        this.dockerService = dockerService;
        this.portAllocationService = portAllocationService;
        this.logStreamService = logStreamService;
        this.healthCheckService = healthCheckService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleDeployments() {
        List<DeploymentStatus> activeStatuses = List.of(
                DeploymentStatus.QUEUED,
                DeploymentStatus.CLONING,
                DeploymentStatus.BUILDING,
                DeploymentStatus.DEPLOYING);
        List<Deployment> stale = deploymentRepository.findByStatusIn(activeStatuses);
        for (Deployment d : stale) {
            log.warn("Found stale deployment #{} in state {} on application startup. Marking as CANCELLED.", d.getId(),
                    d.getStatus());
            d.setStatus(DeploymentStatus.CANCELLED);
            d.setFinishedAt(LocalDateTime.now());
            String existingLogs = d.getBuildLogs() != null ? d.getBuildLogs() : "";
            d.setBuildLogs(existingLogs
                    + "\n⚠️ [RECOVERY] System was restarted while this deployment was in progress. Marked as CANCELLED.\n");
            deploymentRepository.save(d);

            if (d.getApp() != null) {
                workspaceService.cleanWorkspace(d.getApp().getId(), d.getId());
            }
        }
    }

    public Deployment triggerDeployment(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        Deployment deployment = Deployment.builder()
                .app(app)
                .status(DeploymentStatus.QUEUED)
                .build();
        deployment = deploymentRepository.save(deployment);

        activeCancellations.put(deployment.getId(), new AtomicBoolean(false));
        activeProcesses.put(deployment.getId(), new AtomicReference<>());

        // Execute asynchronously using Java 21 native Virtual Thread (bypasses Spring
        // proxy limitations)
        final Long finalAppId = app.getId();
        final Long finalDeploymentId = deployment.getId();
        Thread.ofVirtual().name("pipeline-" + finalDeploymentId).start(() -> {
            runPipeline(finalAppId, finalDeploymentId);
        });

        return deployment;
    }

    public boolean cancelDeployment(Long deploymentId) {
        AtomicBoolean flag = activeCancellations.get(deploymentId);
        if (flag != null) {
            flag.set(true);
        }

        logStreamService.broadcast(deploymentId,
                "🛑 [USER-CANCEL] Immediate cancellation received! Terminating active process...");

        // Instantly destroy the running process tree without waiting for any loop
        AtomicReference<Process> procRef = activeProcesses.get(deploymentId);
        if (procRef != null && procRef.get() != null) {
            CommandExecutor.killProcessTree(procRef.get());
        }

        // Always ensure deployment status is updated to CANCELLED in database
        deploymentRepository.findById(deploymentId).ifPresent(d -> {
            if (d.getStatus() != DeploymentStatus.HEALTHY &&
                    d.getStatus() != DeploymentStatus.FAILED &&
                    d.getStatus() != DeploymentStatus.CANCELLED &&
                    d.getStatus() != DeploymentStatus.STOPPED) {
                d.setStatus(DeploymentStatus.CANCELLED);
                d.setFinishedAt(LocalDateTime.now());
                String existingLogs = d.getBuildLogs() != null ? d.getBuildLogs() : "";
                d.setBuildLogs(existingLogs + "\n🛑 [CANCELLED] Deployment stopped and cancelled by user.\n");
                deploymentRepository.save(d);

                if (d.getApp() != null) {
                    workspaceService.cleanWorkspace(d.getApp().getId(), d.getId());
                }
            }
        });

        return true;
    }

    public void stopApp(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        String containerName = "micropass-" + app.getName();
        dockerService.stopAndRemoveContainer(containerName, null);
        app.setActiveContainerId(null);
        appRepository.save(app);
    }

    public void runPipeline(Long appId, Long deploymentId) {
        App app = appRepository.findById(appId).orElseThrow();
        Deployment deployment = deploymentRepository.findById(deploymentId).orElseThrow();
        AtomicBoolean cancellationFlag = activeCancellations.get(deploymentId);
        AtomicReference<Process> processRef = activeProcesses.get(deploymentId);
        StringBuilder logBuffer = new StringBuilder();

        autoLog(deployment, logBuffer, "==========================================================");
        autoLog(deployment, logBuffer, "  microPass Pipeline Execution: " + app.getName());
        autoLog(deployment, logBuffer, "  Deployment ID: #" + deploymentId);
        autoLog(deployment, logBuffer, "==========================================================");

        try {
            checkCancelled(cancellationFlag);

            // Step 1: Prepare Workspace
            deployment.setStatus(DeploymentStatus.CLONING);
            deploymentRepository.save(deployment);
            Path workspacePath = workspaceService.prepareWorkspace(app.getId(), deployment.getId());
            autoLog(deployment, logBuffer,
                    ">>> Initialized isolated sandbox directory: " + workspacePath.toAbsolutePath());

            // Step 2: Git Clone
            checkCancelled(cancellationFlag);
            GitService.GitResult gitResult = gitService.cloneRepository(
                    app.getGitUrl(),
                    app.getDefaultBranch(),
                    workspacePath,
                    line -> autoLog(deployment, logBuffer, "[GIT] " + line),
                    cancellationFlag,
                    processRef);

            if (!gitResult.success()) {
                throw new RuntimeException("Git clone failed: " + gitResult.errorOutput());
            }

            deployment.setCommitHash(gitResult.commitHash());
            deploymentRepository.save(deployment);

            // Step 3: Docker Build
            checkCancelled(cancellationFlag);
            deployment.setStatus(DeploymentStatus.BUILDING);
            deploymentRepository.save(deployment);

            String imageTag = "micropass-" + app.getName();
            DockerService.DockerBuildResult buildResult = dockerService.buildImage(
                    workspacePath,
                    app.getDockerfilePath(),
                    imageTag,
                    gitResult.commitHash(),
                    line -> autoLog(deployment, logBuffer, "[DOCKER-BUILD] " + line),
                    cancellationFlag,
                    processRef);

            if (!buildResult.success()) {
                throw new RuntimeException("Docker build failed: " + buildResult.logs());
            }

            deployment.setImageTag(buildResult.imageTag());
            deploymentRepository.save(deployment);

            // Step 4: Allocate Port & Deploy
            checkCancelled(cancellationFlag);
            deployment.setStatus(DeploymentStatus.DEPLOYING);
            deploymentRepository.save(deployment);

            autoLog(deployment, logBuffer, ">>> STEP 3/5: Checking and allocating host port...");
            int hostPort = portAllocationService.allocatePort(app);
            int containerPort = app.getTargetPort() != null ? app.getTargetPort() : 80;
            autoLog(deployment, logBuffer,
                    ">>> Reserved Host Port: " + hostPort + " (Internal Container Port: " + containerPort + ")");

            autoLog(deployment, logBuffer, ">>> STEP 4/5: Launching Docker container...");
            String containerName = "micropass-" + app.getName();
            DockerService.DockerRunResult runResult = dockerService.runContainer(
                    buildResult.imageTag(),
                    containerName,
                    hostPort,
                    containerPort,
                    line -> autoLog(deployment, logBuffer, "[DOCKER-RUN] " + line));

            if (!runResult.success()) {
                throw new RuntimeException("Container launch failed: " + runResult.logs());
            }

            deployment.setContainerId(runResult.containerId());
            deployment.setPort(hostPort);

            // Step 5: Health Check Probe
            checkCancelled(cancellationFlag);
            autoLog(deployment, logBuffer,
                    ">>> STEP 5/5: Probing HTTP readiness probe at http://localhost:" + hostPort + "/ ...");
            boolean isHealthy = healthCheckService.waitForHealthy(hostPort, 12, Duration.ofMillis(1000),
                    line -> autoLog(deployment, logBuffer, line));

            if (!isHealthy) {
                throw new RuntimeException("Container failed health check probe within 12 seconds.");
            }

            autoLog(deployment, logBuffer, ">>> Container readiness probe PASSED! HTTP 200 OK received.");
            deployment.setStatus(DeploymentStatus.HEALTHY);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setBuildLogs(logBuffer.toString());
            deploymentRepository.save(deployment);

            app.setActiveContainerId(runResult.containerId());
            app.setAllocatedPort(hostPort);
            appRepository.save(app);

            autoLog(deployment, logBuffer, "==========================================================");
            autoLog(deployment, logBuffer, "🎉 Deployment SUCCESS! Application live at http://localhost:" + hostPort);
            autoLog(deployment, logBuffer, "==========================================================");

        } catch (Exception e) {
            boolean isUserCancel = (cancellationFlag != null && cancellationFlag.get())
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("cancel"));

            if (isUserCancel) {
                log.info("Deployment #{} was cancelled by user.", deploymentId);
                autoLog(deployment, logBuffer, "🛑 [CANCELLED] Deployment stopped and cancelled by user.");
                deployment.setStatus(DeploymentStatus.CANCELLED);
            } else {
                log.error("Deployment failed for app {}", app.getName(), e);
                autoLog(deployment, logBuffer, "[ERROR] " + e.getMessage());
                deployment.setStatus(DeploymentStatus.FAILED);
            }
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setBuildLogs(logBuffer.toString());
            deploymentRepository.save(deployment);

            // Clean up incomplete/failed workspace to prevent disk clutter
            workspaceService.cleanWorkspace(app.getId(), deployment.getId());
        } finally {
            activeCancellations.remove(deploymentId);
            activeProcesses.remove(deploymentId);
            logStreamService.complete(deployment.getId());
        }
    }

    private void checkCancelled(AtomicBoolean cancellationFlag) {
        if (cancellationFlag != null && cancellationFlag.get()) {
            throw new RuntimeException("Deployment cancelled by user.");
        }
    }

    private void autoLog(Deployment deployment, StringBuilder logBuffer, String message) {
        log.info(message);
        logBuffer.append(message).append("\n");
        logStreamService.broadcast(deployment.getId(), message);
    }
}
