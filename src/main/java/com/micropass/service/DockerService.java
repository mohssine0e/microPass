package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class DockerService {

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);

    public record DockerBuildResult(boolean success, String imageTag, String logs) {}
    public record DockerRunResult(boolean success, String containerId, String logs) {}

    public DockerBuildResult buildImage(Path projectDir, String imageName, String tag, Consumer<String> logConsumer) {
        String fullTag = imageName.toLowerCase() + ":" + tag;
        logConsumer.accept("Building Docker image: " + fullTag);

        Path dockerfile = projectDir.resolve("Dockerfile");
        if (!Files.exists(dockerfile)) {
            String err = "No Dockerfile found in repository root: " + projectDir;
            logConsumer.accept(err);
            return new DockerBuildResult(false, null, err);
        }

        List<String> buildCmd = List.of(
                "docker", "build",
                "-t", fullTag,
                projectDir.toAbsolutePath().toString()
        );

        CommandExecutor.ExecutionResult result = CommandExecutor.execute(buildCmd, projectDir.toFile(), logConsumer);
        if (result.exitCode() != 0) {
            logConsumer.accept("Docker build failed with exit code " + result.exitCode());
            return new DockerBuildResult(false, null, result.output());
        }

        logConsumer.accept("Docker image built successfully: " + fullTag);
        return new DockerBuildResult(true, fullTag, result.output());
    }

    public DockerRunResult runContainer(String fullImageTag, String containerName, int hostPort, int containerPort, Consumer<String> logConsumer) {
        logConsumer.accept(String.format("Starting container '%s' on port %d (mapping to container port %d)...",
                containerName, hostPort, containerPort));

        // Stop and remove any prior container with the same name if exists
        stopAndRemoveContainer(containerName, logConsumer);

        List<String> runCmd = List.of(
                "docker", "run", "-d",
                "--name", containerName,
                "-p", hostPort + ":" + containerPort,
                "--restart", "unless-stopped",
                fullImageTag
        );

        CommandExecutor.ExecutionResult result = CommandExecutor.execute(runCmd, null, logConsumer);
        if (result.exitCode() != 0) {
            logConsumer.accept("Docker run failed: " + result.output());
            return new DockerRunResult(false, null, result.output());
        }

        String containerId = result.output().trim();
        logConsumer.accept("Container started successfully! ID: " + containerId.substring(0, Math.min(12, containerId.length())));
        return new DockerRunResult(true, containerId, result.output());
    }

    public void stopAndRemoveContainer(String containerNameOrId, Consumer<String> logConsumer) {
        if (containerNameOrId == null || containerNameOrId.isBlank()) return;

        List<String> stopCmd = List.of("docker", "rm", "-f", containerNameOrId);
        CommandExecutor.ExecutionResult result = CommandExecutor.execute(stopCmd, null, null);
        if (result.exitCode() == 0 && logConsumer != null) {
            logConsumer.accept("Cleaned up container: " + containerNameOrId);
        }
    }

    public boolean isContainerRunning(String containerNameOrId) {
        if (containerNameOrId == null || containerNameOrId.isBlank()) return false;
        List<String> inspectCmd = List.of("docker", "inspect", "-f", "{{.State.Running}}", containerNameOrId);
        CommandExecutor.ExecutionResult result = CommandExecutor.execute(inspectCmd, null, null);
        return result.exitCode() == 0 && result.output().trim().equalsIgnoreCase("true");
    }
}

