package com.micropass.web;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.DeploymentRepository;
import com.micropass.service.DeploymentPipelineService;
import com.micropass.service.DockerService;
import com.micropass.service.GitService;
import com.micropass.service.LogStreamService;
import com.micropass.service.ProxyRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppControllerTest {

    private AppRepository appRepository;
    private AppController controller;

    @BeforeEach
    void setUp() {
        appRepository = mock(AppRepository.class);
        when(appRepository.save(any(App.class))).thenAnswer(invocation -> invocation.getArgument(0));
        controller = new AppController(
                appRepository,
                mock(DeploymentRepository.class),
                mock(DeploymentPipelineService.class),
                mock(LogStreamService.class),
                mock(DockerService.class),
                mock(GitService.class),
                mock(ProxyRoutingService.class));
    }

    @Test
    void createApp_rejectsDockerfileTraversal() {
        AppController.CreateAppRequest request = new AppController.CreateAppRequest(
                "demo", "https://example.test/demo.git", "main", "../Dockerfile", 8080);

        assertThatThrownBy(() -> controller.createApp(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("inside the repository root");
    }

    @Test
    void createApp_normalizesSafeRelativeDockerfilePath() {
        AppController.CreateAppRequest request = new AppController.CreateAppRequest(
                "Demo", "https://example.test/demo.git", "main", "docker/../Dockerfile", 8080);

        controller.createApp(request);

        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        org.mockito.Mockito.verify(appRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("demo");
        assertThat(captor.getValue().getDockerfilePath()).isEqualTo("Dockerfile");
    }
}
