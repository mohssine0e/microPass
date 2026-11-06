package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class DockerService {

    private static final Logger log = LoggerFactory.getLogger(DockerService.class);
    private final String memoryLimit;
    private final String cpuLimit;
    private final int pidsLimit;

    public DockerService(
            @Value("${micropass.container.memory:512m}") String memoryLimit,
            @Value("${micropass.container.cpus:1.0}") String cpuLimit,
            @Value("${micropass.container.pids-limit:256}") int pidsLimit) {
        this.memoryLimit = memoryLimit;
        this.cpuLimit = cpuLimit;
        this.pidsLimit = pidsLimit;
    }

    public record DockerBuildResult(boolean success, String imageTag, String logs) {}
    public record DockerRunResult(boolean success, String containerId, String logs) {}

    public DockerBuildResult buildImage(Path projectDir, String dockerfileRelPath, String imageName, String tag, 
                                        Consumer<String> logConsumer, java.util.concurrent.atomic.AtomicBoolean cancellationFlag,
                                        java.util.concurrent.atomic.AtomicReference<Process> processRef) {
        String fullTag = imageName.toLowerCase() + ":" + tag;
        String relPath = (dockerfileRelPath == null || dockerfileRelPath.isBlank()) ? "Dockerfile" : dockerfileRelPath.trim();
        Path normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        Path dockerfile = normalizedProjectDir.resolve(relPath).normalize();

        if (!dockerfile.startsWith(normalizedProjectDir)) {
            String err = "[ERROR] Dockerfile path must stay inside the application workspace: " + relPath;
            logConsumer.accept(err);
            return new DockerBuildResult(false, null, err);
        }

        logConsumer.accept(">>> STEP 2/5: Packaging application into Docker image...");
        logConsumer.accept(">>> Target Image Tag: " + fullTag);
        logConsumer.accept(">>> Using Dockerfile: " + dockerfile.toAbsolutePath());

        if (!Files.exists(dockerfile)) {
            // Smart Auto-Detection: Generate standard Dockerfile template if missing
            if (Files.exists(projectDir.resolve("composer.json"))) {
                logConsumer.accept(">>> [AUTO-DETECT] PHP / Laravel project detected (composer.json found).");
                logConsumer.accept(">>> Auto-generating standalone PHP web container Dockerfile...");
                String content = """
                        FROM php:8.2-cli-alpine
                        WORKDIR /app
                        COPY . /app
                        EXPOSE 80
                        CMD ["sh", "-c", "if [ -d 'public' ]; then php -S 0.0.0.0:80 -t public; else php -S 0.0.0.0:80; fi"]
                        """;
                try {
                    Files.writeString(dockerfile, content);
                    logConsumer.accept(">>> Generated Dockerfile template at: " + dockerfile.toAbsolutePath());
                } catch (Exception e) {
                    log.warn("Failed to write auto-generated Dockerfile: {}", e.getMessage());
                }
            } else if (Files.exists(projectDir.resolve("package.json"))) {
                logConsumer.accept(">>> [AUTO-DETECT] Node.js project detected (package.json found).");
                logConsumer.accept(">>> Auto-generating Node.js web container Dockerfile...");
                String content = """
                        FROM node:20-alpine
                        WORKDIR /app
                        COPY package*.json ./
                        RUN npm install --omit=dev || npm install
                        COPY . .
                        EXPOSE 3000
                        CMD ["npm", "start"]
                        """;
                try {
                    Files.writeString(dockerfile, content);
                    logConsumer.accept(">>> Generated Dockerfile template at: " + dockerfile.toAbsolutePath());
                } catch (Exception e) {
                    log.warn("Failed to write auto-generated Dockerfile: {}", e.getMessage());
                }
            } else {
                String err = "[ERROR] Dockerfile not found at: " + relPath + " (Checked full path: " + dockerfile.toAbsolutePath() + ")";
                logConsumer.accept(err);
                return new DockerBuildResult(false, null, err);
            }
        }

        List<String> buildCmd = List.of(
                "docker", "build",
                "-f", dockerfile.toAbsolutePath().toString(),
                "-t", fullTag,
                projectDir.toAbsolutePath().toString()
        );

        CommandExecutor.ExecutionResult result = CommandExecutor.execute(
                buildCmd, projectDir.toFile(), logConsumer, 15, java.util.concurrent.TimeUnit.MINUTES, cancellationFlag, processRef);
        
        if (result.exitCode() != 0) {
            logConsumer.accept("[ERROR] Docker build failed with exit code: " + result.exitCode());
            return new DockerBuildResult(false, null, result.output());
        }

        logConsumer.accept(">>> Docker image built and tagged successfully: [" + fullTag + "]");
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
                "--memory", memoryLimit,
                "--cpus", cpuLimit,
                "--pids-limit", String.valueOf(pidsLimit),
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
