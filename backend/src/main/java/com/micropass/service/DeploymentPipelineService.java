package com.micropass.service;

import com.micropass.common.CommandExecutor;
import com.micropass.config.RabbitConfig;
import com.micropass.domain.*;
import com.micropass.service.event.DeploymentTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DeploymentPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentPipelineService.class);
    private static final List<DeploymentStatus> ACTIVE_STATUSES = List.of(
            DeploymentStatus.QUEUED,
            DeploymentStatus.CLONING,
            DeploymentStatus.BUILDING,
            DeploymentStatus.DEPLOYING);

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final WorkspaceService workspaceService;
    private final GitService gitService;
    private final DockerService dockerService;
    private final PortAllocationService portAllocationService;
    private final LogStreamService logStreamService;
    private final HealthCheckService healthCheckService;
    private final RabbitTemplate rabbitTemplate;
    private final ProxyRoutingService proxyRoutingService;

    // Active cancellation flags and process handles for instant killing
    private final Map<Long, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicReference<Process>> activeProcesses = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> appDeploymentLocks = new ConcurrentHashMap<>();

    public DeploymentPipelineService(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            WorkspaceService workspaceService,
            GitService gitService,
            DockerService dockerService,
            PortAllocationService portAllocationService,
            LogStreamService logStreamService,
            HealthCheckService healthCheckService,
            RabbitTemplate rabbitTemplate,
            ProxyRoutingService proxyRoutingService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.workspaceService = workspaceService;
        this.gitService = gitService;
        this.dockerService = dockerService;
        this.portAllocationService = portAllocationService;
        this.logStreamService = logStreamService;
        this.healthCheckService = healthCheckService;
        this.rabbitTemplate = rabbitTemplate;
        this.proxyRoutingService = proxyRoutingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleDeployments() {
        List<Deployment> stale = deploymentRepository.findByStatusIn(ACTIVE_STATUSES);
        for (Deployment d : stale) {
            log.warn("Found stale deployment #{} in state {} on application startup. Marking as CANCELLED.", d.getId(),
                    d.getStatus());
            d.setStatus(DeploymentStatus.CANCELLED);
            d.setFinishedAt(LocalDateTime.now());
            String existingLogs = d.getBuildLogs() != null ? d.getBuildLogs() : "";
            d.setBuildLogs(existingLogs
                    + "\n⚠️ [RECOVERY] System was restarted while this deployment was in progress. Marked as CANCELLED.\n");
            deploymentRepository.save(d);

            if (d.getContainerId() != null && !d.getContainerId().isBlank()) {
                dockerService.stopAndRemoveContainer(d.getContainerId(), null);
            }

            if (d.getApp() != null) {
                workspaceService.cleanWorkspace(d.getApp().getId(), d.getId());
            }
        }
    }

    public synchronized Deployment triggerDeployment(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        if (deploymentRepository.existsByAppIdAndStatusIn(appId, ACTIVE_STATUSES)) {
            throw new ActiveDeploymentException("Application already has an active deployment");
        }

        Deployment deployment = Deployment.builder()
                .app(app)
                .status(DeploymentStatus.QUEUED)
                .build();
        deployment = deploymentRepository.save(deployment);

        activeCancellations.put(deployment.getId(), new AtomicBoolean(false));
        activeProcesses.put(deployment.getId(), new AtomicReference<>());

        // Publish to RabbitMQ. There is intentionally no invisible local fallback:
        // queue availability is part of the platform's production contract.
        try {
            DeploymentTriggerEvent event = new DeploymentTriggerEvent(deployment.getId(), app.getId(), app.getName());
            rabbitTemplate.convertAndSend(RabbitConfig.DEPLOYMENT_EXCHANGE, RabbitConfig.DEPLOYMENT_ROUTING_KEY, event);
            log.info("Published DeploymentTriggerEvent for deployment #{} to RabbitMQ", deployment.getId());
        } catch (Exception e) {
            String message = "RabbitMQ is unavailable; deployment was not queued";
            log.error("{}: {}", message, e.getMessage());
            deployment.setStatus(DeploymentStatus.FAILED);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setBuildLogs("[ERROR] " + message + "\n");
            deploymentRepository.save(deployment);
            activeCancellations.remove(deployment.getId());
            activeProcesses.remove(deployment.getId());
            logStreamService.complete(deployment.getId(), DeploymentStatus.FAILED.name());
            throw new DeploymentQueueUnavailableException(message, e);
        }

        return deployment;
    }

    public boolean cancelDeployment(Long deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId).orElse(null);
        if (deployment == null || isTerminal(deployment.getStatus())) {
            return false;
        }

        int cancelled = deploymentRepository.cancelActive(
                deploymentId,
                ACTIVE_STATUSES,
                DeploymentStatus.CANCELLED,
                LocalDateTime.now());
        if (cancelled == 0) {
            return false;
        }

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

        // Append a durable explanation after the atomic state transition. The
        // running pipeline also records cancellation if it was already active.
        deploymentRepository.findById(deploymentId).ifPresent(d -> {
            if (d.getStatus() == DeploymentStatus.CANCELLED) {
                String existingLogs = d.getBuildLogs() != null ? d.getBuildLogs() : "";
                d.setBuildLogs(existingLogs + "\n🛑 [CANCELLED] Deployment stopped and cancelled by user.\n");
                deploymentRepository.save(d);

                if (d.getApp() != null) {
                    workspaceService.cleanWorkspace(d.getApp().getId(), d.getId());
                }
            }
        });

        // A queued deployment can be cancelled before a RabbitMQ consumer claims it.
        // Complete the stream here so EventSource does not reconnect forever while
        // waiting for a worker that will intentionally skip the cancelled record.
        logStreamService.complete(deploymentId, DeploymentStatus.CANCELLED.name());

        return true;
    }

    public void stopApp(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        if (deploymentRepository.existsByAppIdAndStatusIn(appId, ACTIVE_STATUSES)) {
            throw new ActiveDeploymentException("Cancel the active deployment before stopping this application");
        }

        String activeContainerId = app.getActiveContainerId();
        proxyRoutingService.deactivate(app.getId());
        String containerNameOrId = activeContainerId != null && !activeContainerId.isBlank()
                ? activeContainerId
                : "micropass-" + app.getName();
        dockerService.stopAndRemoveContainer(containerNameOrId, null);
        app.setActiveContainerId(null);
        app.setAllocatedPort(null);
        appRepository.save(app);

        // The latest attempt may be FAILED while an older healthy container is still
        // serving traffic. Mark the deployment that actually owned the stopped
        // container, rather than blindly changing the latest deployment.
        java.util.Optional<Deployment> runningDeployment = activeContainerId != null && !activeContainerId.isBlank()
                ? deploymentRepository.findTopByAppIdAndContainerIdOrderByIdDesc(appId, activeContainerId)
                : deploymentRepository.findTopByAppIdAndStatusOrderByIdDesc(appId, DeploymentStatus.HEALTHY);
        runningDeployment.ifPresent(deployment -> {
            deployment.setStatus(DeploymentStatus.STOPPED);
            deploymentRepository.save(deployment);
        });
    }

    public void deleteApp(Long appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("App not found: " + appId));

        if (deploymentRepository.existsByAppIdAndStatusIn(appId, ACTIVE_STATUSES)) {
            throw new ActiveDeploymentException("Cancel the active deployment before deleting this application");
        }

        stopApp(appId);
        workspaceService.cleanAppWorkspace(appId);
        deploymentRepository.findByAppIdOrderByIdDesc(appId)
                .forEach(deploymentRepository::delete);
        appRepository.delete(app);
    }

    public void runPipeline(Long appId, Long deploymentId) {
        ReentrantLock appLock = appDeploymentLocks.computeIfAbsent(appId, ignored -> new ReentrantLock());
        appLock.lock();

        App app = null;
        Deployment deployment = null;
        AtomicBoolean cancellationFlag = null;
        AtomicReference<Process> processRef = null;
        StringBuilder logBuffer = new StringBuilder();
        String candidateContainerName = null;
        String candidateContainerId = null;
        boolean ownsDeployment = false;
        boolean candidatePromoted = false;
        boolean routeChanged = false;
        Integer previousPort = null;
        String previousActiveContainerId = null;

        try {
            int claimed = deploymentRepository.transitionStatus(
                    deploymentId, DeploymentStatus.QUEUED, DeploymentStatus.CLONING);
            if (claimed == 0) {
                log.info("Skipping deployment #{} because it is no longer QUEUED (duplicate, cancelled, or recovered)",
                        deploymentId);
                return;
            }

            ownsDeployment = true;
            app = appRepository.findById(appId).orElseThrow();
            deployment = deploymentRepository.findById(deploymentId).orElseThrow();
            cancellationFlag = activeCancellations.computeIfAbsent(deploymentId, ignored -> new AtomicBoolean(false));
            processRef = activeProcesses.computeIfAbsent(deploymentId, ignored -> new AtomicReference<>());
            final Deployment pipelineDeployment = deployment;

            autoLog(deployment, logBuffer, "==========================================================");
            autoLog(deployment, logBuffer, "  microPass Pipeline Execution: " + app.getName());
            autoLog(deployment, logBuffer, "  Deployment ID: #" + deploymentId);
            autoLog(deployment, logBuffer, "==========================================================");

            checkCancelled(cancellationFlag);

            // Step 1: Prepare Workspace
            Path workspacePath = workspaceService.prepareWorkspace(app.getId(), deployment.getId());
            autoLog(deployment, logBuffer,
                    ">>> Initialized isolated sandbox directory: " + workspacePath.toAbsolutePath());

            // Step 2: Git Clone
            checkCancelled(cancellationFlag);
            GitService.GitResult gitResult = gitService.cloneRepository(
                    app.getGitUrl(),
                    app.getDefaultBranch(),
                    workspacePath,
                    line -> autoLog(pipelineDeployment, logBuffer, "[GIT] " + line),
                    cancellationFlag,
                    processRef);

            if (!gitResult.success()) {
                checkCancelled(cancellationFlag);
                throw new RuntimeException("Git clone failed: " + gitResult.errorOutput());
            }

            deployment.setCommitHash(gitResult.commitHash());
            deploymentRepository.save(deployment);

            // Step 3: Docker Build
            checkCancelled(cancellationFlag);
            transitionStatus(deployment, DeploymentStatus.CLONING, DeploymentStatus.BUILDING);

            String imageTag = "micropass-" + app.getName();
            DockerService.DockerBuildResult buildResult = dockerService.buildImage(
                    workspacePath,
                    app.getDockerfilePath(),
                    imageTag,
                    gitResult.commitHash(),
                    line -> autoLog(pipelineDeployment, logBuffer, "[DOCKER-BUILD] " + line),
                    cancellationFlag,
                    processRef);

            if (!buildResult.success()) {
                checkCancelled(cancellationFlag);
                throw new RuntimeException("Docker build failed: " + buildResult.logs());
            }

            deployment.setImageTag(buildResult.imageTag());
            deploymentRepository.save(deployment);

            // Step 4: Allocate Port & Deploy
            checkCancelled(cancellationFlag);
            transitionStatus(deployment, DeploymentStatus.BUILDING, DeploymentStatus.DEPLOYING);

            autoLog(deployment, logBuffer, ">>> STEP 3/5: Checking and allocating host port...");
            int hostPort = portAllocationService.allocatePort(app);
            int containerPort = app.getTargetPort() != null ? app.getTargetPort() : 80;
            autoLog(deployment, logBuffer,
                    ">>> Reserved Host Port: " + hostPort + " (Internal Container Port: " + containerPort + ")");

            autoLog(deployment, logBuffer, ">>> STEP 4/5: Launching Docker container...");
            candidateContainerName = "micropass-" + app.getName() + "-d" + deploymentId;
            DockerService.DockerRunResult runResult = dockerService.runContainer(
                    buildResult.imageTag(),
                    candidateContainerName,
                    hostPort,
                    containerPort,
                    line -> autoLog(pipelineDeployment, logBuffer, "[DOCKER-RUN] " + line));

            if (!runResult.success()) {
                checkCancelled(cancellationFlag);
                throw new RuntimeException("Container launch failed: " + runResult.logs());
            }

            candidateContainerId = runResult.containerId();
            deployment.setContainerId(candidateContainerId);
            deployment.setPort(hostPort);
            deploymentRepository.save(deployment);

            // Step 5: Health Check Probe
            checkCancelled(cancellationFlag);
            autoLog(deployment, logBuffer,
                    ">>> STEP 5/5: Probing HTTP readiness probe at http://localhost:" + hostPort + "/ ...");
            boolean isHealthy = healthCheckService.waitForHealthy(hostPort, 12, Duration.ofMillis(1000),
                    line -> autoLog(pipelineDeployment, logBuffer, line), cancellationFlag::get);

            if (!isHealthy) {
                checkCancelled(cancellationFlag);
                dockerService.stopAndRemoveContainer(candidateContainerId, null);
                candidateContainerId = null;
                throw new RuntimeException("Container failed health check probe within 12 seconds.");
            }

            checkCancelled(cancellationFlag);
            autoLog(deployment, logBuffer, ">>> Container readiness probe PASSED.");

            // This transition is the linearization point between a successful
            // promotion and cancellation. If cancellation already won, do not
            // promote the candidate even though its readiness probe passed.
            transitionStatus(deployment, DeploymentStatus.DEPLOYING, DeploymentStatus.HEALTHY);

            String previousContainerId = app.getActiveContainerId();
            previousActiveContainerId = previousContainerId;
            previousPort = app.getAllocatedPort();
            proxyRoutingService.activate(app.getId(), hostPort);
            routeChanged = proxyRoutingService.isEnabled();
            app.setActiveContainerId(candidateContainerId);
            app.setAllocatedPort(hostPort);
            appRepository.save(app);
            candidatePromoted = true;

            if (previousContainerId != null && !previousContainerId.isBlank()
                    && !previousContainerId.equals(candidateContainerId)) {
                dockerService.stopAndRemoveContainer(previousContainerId, null);
            }

            deployment.setFinishedAt(LocalDateTime.now());

            autoLog(deployment, logBuffer, "==========================================================");
            String publicUrl = proxyRoutingService.urlFor(app);
            autoLog(deployment, logBuffer, "🎉 Deployment SUCCESS! Application live at "
                    + (publicUrl != null ? publicUrl : "http://localhost:" + hostPort));
            autoLog(deployment, logBuffer, "==========================================================");
            deployment.setBuildLogs(logBuffer.toString());
            deploymentRepository.save(deployment);

        } catch (Exception e) {
            if (routeChanged && !candidatePromoted && app != null) {
                app.setActiveContainerId(previousActiveContainerId);
                app.setAllocatedPort(previousPort);
                try {
                    if (previousPort == null) proxyRoutingService.deactivate(app.getId());
                    else proxyRoutingService.activate(app.getId(), previousPort);
                } catch (Exception rollbackError) {
                    log.error("Could not restore previous proxy route for app #{}", appId, rollbackError);
                }
            }
            if (!candidatePromoted && candidateContainerId != null) {
                dockerService.stopAndRemoveContainer(candidateContainerId, null);
            } else if (!candidatePromoted && candidateContainerName != null) {
                dockerService.stopAndRemoveContainer(candidateContainerName, null);
            }

            if (!ownsDeployment || deployment == null) {
                log.error("Could not start deployment #{} for app #{}", deploymentId, appId, e);
                return;
            }

            boolean isUserCancel = (cancellationFlag != null && cancellationFlag.get())
                    || (e instanceof DeploymentCancelledException);

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
            if (ownsDeployment && deployment != null) {
                logStreamService.complete(deployment.getId(), deployment.getStatus().name());
            }
            appLock.unlock();
        }
    }

    private boolean isTerminal(DeploymentStatus status) {
        return status == DeploymentStatus.HEALTHY
                || status == DeploymentStatus.FAILED
                || status == DeploymentStatus.CANCELLED
                || status == DeploymentStatus.STOPPED;
    }

    public static class ActiveDeploymentException extends RuntimeException {
        public ActiveDeploymentException(String message) {
            super(message);
        }
    }

    public static class DeploymentQueueUnavailableException extends RuntimeException {
        public DeploymentQueueUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class DeploymentCancelledException extends RuntimeException {
        public DeploymentCancelledException() {
            super("Deployment was cancelled by user.");
        }
    }

    private void checkCancelled(AtomicBoolean cancellationFlag) {
        if (cancellationFlag != null && cancellationFlag.get()) {
            throw new DeploymentCancelledException();
        }
    }

    private void transitionStatus(
            Deployment deployment,
            DeploymentStatus expectedStatus,
            DeploymentStatus nextStatus) {
        int updated = deploymentRepository.transitionStatus(deployment.getId(), expectedStatus, nextStatus);
        if (updated == 0) {
            DeploymentStatus current = deploymentRepository.findById(deployment.getId())
                    .map(Deployment::getStatus)
                    .orElse(null);
            if (current == DeploymentStatus.CANCELLED) {
                throw new DeploymentCancelledException();
            }
            throw new IllegalStateException(
                    "Deployment state changed unexpectedly; expected " + expectedStatus + " but was " + current);
        }
        deployment.setStatus(nextStatus);
    }

    private void autoLog(Deployment deployment, StringBuilder logBuffer, String message) {
        log.info(message);
        logBuffer.append(message).append("\n");
        logStreamService.broadcast(deployment.getId(), message);
    }
}
