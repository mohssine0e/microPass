package com.micropass.service;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.Deployment;
import com.micropass.domain.DeploymentRepository;
import com.micropass.domain.DeploymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.time.LocalDateTime;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeploymentPipelineServiceTest {

    private AppRepository appRepository;
    private DeploymentRepository deploymentRepository;
    private WorkspaceService workspaceService;
    private GitService gitService;
    private DockerService dockerService;
    private PortAllocationService portAllocationService;
    private LogStreamService logStreamService;
    private HealthCheckService healthCheckService;
    private RabbitTemplate rabbitTemplate;
    private ProxyRoutingService proxyRoutingService;
    private DeploymentPipelineService service;

    @BeforeEach
    void setUp() {
        appRepository = mock(AppRepository.class);
        deploymentRepository = mock(DeploymentRepository.class);
        workspaceService = mock(WorkspaceService.class);
        gitService = mock(GitService.class);
        dockerService = mock(DockerService.class);
        portAllocationService = mock(PortAllocationService.class);
        logStreamService = mock(LogStreamService.class);
        healthCheckService = mock(HealthCheckService.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        proxyRoutingService = mock(ProxyRoutingService.class);
        service = new DeploymentPipelineService(
                appRepository,
                deploymentRepository,
                workspaceService,
                gitService,
                dockerService,
                portAllocationService,
                logStreamService,
                healthCheckService,
                rabbitTemplate,
                proxyRoutingService);
    }

    @Test
    void triggerDeployment_rejectsSecondActiveDeploymentForSameApp() {
        App app = app(7L, null);
        when(appRepository.findById(7L)).thenReturn(Optional.of(app));
        when(deploymentRepository.existsByAppIdAndStatusIn(eq(7L), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.triggerDeployment(7L))
                .isInstanceOf(DeploymentPipelineService.ActiveDeploymentException.class)
                .hasMessageContaining("active deployment");

        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void runPipeline_skipsDuplicateDeliveryWhenDeploymentWasAlreadyClaimed() {
        when(deploymentRepository.transitionStatus(
                21L, DeploymentStatus.QUEUED, DeploymentStatus.CLONING)).thenReturn(0);

        service.runPipeline(7L, 21L);

        verify(deploymentRepository).transitionStatus(
                21L, DeploymentStatus.QUEUED, DeploymentStatus.CLONING);
        verify(appRepository, never()).findById(any());
        verifyNoInteractions(gitService, dockerService, portAllocationService, healthCheckService);
    }

    @Test
    void runPipeline_restoresOldRouteIfAppPromotionCannotBeSaved() {
        App app = app(7L, "old-container");
        app.setAllocatedPort(9100);
        Deployment deployment = Deployment.builder().id(21L).app(app).status(DeploymentStatus.QUEUED).build();
        when(deploymentRepository.transitionStatus(eq(21L), any(), any())).thenReturn(1);
        when(appRepository.findById(7L)).thenReturn(Optional.of(app));
        when(deploymentRepository.findById(21L)).thenReturn(Optional.of(deployment));
        when(workspaceService.prepareWorkspace(7L, 21L)).thenReturn(Path.of("/tmp/micropass-test-workspace"));
        when(gitService.cloneRepository(any(), any(), any(), any(), any(), any()))
                .thenReturn(new GitService.GitResult(true, "abc123", null));
        when(dockerService.buildImage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DockerService.DockerBuildResult(true, "micropass-demo:abc123", ""));
        when(portAllocationService.allocatePort(app)).thenReturn(9101);
        when(dockerService.runContainer(any(), any(), eq(9101), eq(80), any()))
                .thenReturn(new DockerService.DockerRunResult(true, "candidate-container", ""));
        when(healthCheckService.waitForHealthy(eq(9101), eq(12), any(), any(), any()))
                .thenReturn(true);
        when(proxyRoutingService.isEnabled()).thenReturn(true);
        when(appRepository.save(app)).thenThrow(new IllegalStateException("database unavailable"));

        service.runPipeline(7L, 21L);

        org.mockito.InOrder routes = org.mockito.Mockito.inOrder(proxyRoutingService);
        routes.verify(proxyRoutingService).activate(7L, 9101);
        routes.verify(proxyRoutingService).activate(7L, 9100);
        assertThat(app.getActiveContainerId()).isEqualTo("old-container");
        assertThat(app.getAllocatedPort()).isEqualTo(9100);
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.FAILED);
        verify(dockerService).stopAndRemoveContainer("candidate-container", null);
    }

    @Test
    void cancelDeployment_persistsCancellationAndCompletesQueuedStream() {
        App app = app(7L, null);
        Deployment deployment = Deployment.builder()
                .id(21L)
                .app(app)
                .status(DeploymentStatus.QUEUED)
                .build();
        when(deploymentRepository.findById(21L)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.cancelActive(
                eq(21L), anyCollection(), eq(DeploymentStatus.CANCELLED), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    deployment.setStatus(DeploymentStatus.CANCELLED);
                    deployment.setFinishedAt(invocation.getArgument(3));
                    return 1;
                });

        boolean cancelled = service.cancelDeployment(21L);

        assertThat(cancelled).isTrue();
        assertThat(deployment.getStatus()).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(deployment.getFinishedAt()).isNotNull();
        assertThat(deployment.getBuildLogs()).contains("cancelled by user");
        verify(deploymentRepository).save(deployment);
        verify(deploymentRepository).cancelActive(
                eq(21L), anyCollection(), eq(DeploymentStatus.CANCELLED), any(LocalDateTime.class));
        verify(workspaceService).cleanWorkspace(7L, 21L);
        verify(logStreamService).complete(21L, DeploymentStatus.CANCELLED.name());
    }

    @Test
    void cancelDeployment_doesNotReportCancelledWhenTerminalTransitionAlreadyWon() {
        Deployment deployment = Deployment.builder()
                .id(21L)
                .app(app(7L, null))
                .status(DeploymentStatus.DEPLOYING)
                .build();
        when(deploymentRepository.findById(21L)).thenReturn(Optional.of(deployment));
        when(deploymentRepository.cancelActive(
                eq(21L), anyCollection(), eq(DeploymentStatus.CANCELLED), any(LocalDateTime.class)))
                .thenReturn(0);

        boolean cancelled = service.cancelDeployment(21L);

        assertThat(cancelled).isFalse();
        verify(logStreamService, never()).broadcast(any(), any());
        verify(logStreamService, never()).complete(any(), any());
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    void stopApp_marksDeploymentThatOwnsRunningContainerAsStopped() {
        App app = app(7L, "container-old");
        app.setAllocatedPort(9102);
        Deployment running = Deployment.builder()
                .id(18L)
                .app(app)
                .containerId("container-old")
                .status(DeploymentStatus.HEALTHY)
                .build();
        when(appRepository.findById(7L)).thenReturn(Optional.of(app));
        when(deploymentRepository.existsByAppIdAndStatusIn(eq(7L), anyCollection())).thenReturn(false);
        when(deploymentRepository.findTopByAppIdAndContainerIdOrderByIdDesc(7L, "container-old"))
                .thenReturn(Optional.of(running));

        service.stopApp(7L);

        assertThat(app.getActiveContainerId()).isNull();
        assertThat(app.getAllocatedPort()).isNull();
        assertThat(running.getStatus()).isEqualTo(DeploymentStatus.STOPPED);
        verify(dockerService).stopAndRemoveContainer("container-old", null);
        verify(appRepository).save(app);
        verify(deploymentRepository).save(running);
    }

    @Test
    void stopApp_rejectsStopWhileDeploymentIsActive() {
        App app = app(7L, "container-old");
        when(appRepository.findById(7L)).thenReturn(Optional.of(app));
        when(deploymentRepository.existsByAppIdAndStatusIn(eq(7L), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.stopApp(7L))
                .isInstanceOf(DeploymentPipelineService.ActiveDeploymentException.class)
                .hasMessageContaining("Cancel the active deployment");

        verifyNoInteractions(dockerService);
        verify(appRepository, never()).save(any());
    }

    private App app(Long id, String activeContainerId) {
        return App.builder()
                .id(id)
                .name("demo")
                .gitUrl("https://example.test/demo.git")
                .activeContainerId(activeContainerId)
                .build();
    }
}
