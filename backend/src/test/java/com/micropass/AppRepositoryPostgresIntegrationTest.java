package com.micropass;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.Deployment;
import com.micropass.domain.DeploymentRepository;
import com.micropass.domain.DeploymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class AppRepositoryPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Test
    @DisplayName("Verify real PostgreSQL container runs and persists microPass entities")
    void testAppAndDeploymentPersistenceInRealPostgres() {
        // 1. Create and persist App
        App app = new App();
        app.setName("postgres-demo-app");
        app.setGitUrl("https://github.com/example/demo.git");
        app.setDefaultBranch("main");
        app.setDockerfilePath("Dockerfile");
        app.setTargetPort(8080);
        app.setAllocatedPort(9105);
        app.setCreatedAt(LocalDateTime.now());

        App savedApp = appRepository.save(app);
        assertThat(savedApp.getId()).isNotNull();

        // 2. Query App from PostgreSQL
        Optional<App> fetchedApp = appRepository.findByName("postgres-demo-app");
        assertThat(fetchedApp).isPresent();
        assertThat(fetchedApp.get().getAllocatedPort()).isEqualTo(9105);

        // 3. Create and persist Deployment linked to App
        Deployment deployment = new Deployment();
        deployment.setApp(savedApp);
        deployment.setCommitHash("abc1234");
        deployment.setImageTag("micropass-demo:abc1234");
        deployment.setStatus(DeploymentStatus.HEALTHY);
        deployment.setPort(9105);
        deployment.setStartedAt(LocalDateTime.now());

        Deployment savedDeployment = deploymentRepository.save(deployment);
        assertThat(savedDeployment.getId()).isNotNull();

        // 4. Verify deployment relations
        List<Deployment> deployments = deploymentRepository.findByAppIdOrderByIdDesc(savedApp.getId());
        assertThat(deployments).hasSize(1);
        assertThat(deployments.get(0).getStatus()).isEqualTo(DeploymentStatus.HEALTHY);
    }

    @Test
    @DisplayName("Atomically cancel only deployments that are still active")
    void cancelActiveUsesConditionalPostgresUpdate() {
        App app = App.builder()
                .name("postgres-cancel-app")
                .gitUrl("https://github.com/example/cancel.git")
                .defaultBranch("main")
                .dockerfilePath("Dockerfile")
                .targetPort(8080)
                .build();
        app = appRepository.save(app);

        Deployment deployment = Deployment.builder()
                .app(app)
                .status(DeploymentStatus.BUILDING)
                .build();
        deployment = deploymentRepository.save(deployment);

        List<DeploymentStatus> active = List.of(
                DeploymentStatus.QUEUED,
                DeploymentStatus.CLONING,
                DeploymentStatus.BUILDING,
                DeploymentStatus.DEPLOYING);
        int first = deploymentRepository.cancelActive(
                deployment.getId(), active, DeploymentStatus.CANCELLED, LocalDateTime.now());
        int second = deploymentRepository.cancelActive(
                deployment.getId(), active, DeploymentStatus.CANCELLED, LocalDateTime.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Deployment cancelled = deploymentRepository.findById(deployment.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(cancelled.getFinishedAt()).isNotNull();
    }
}
