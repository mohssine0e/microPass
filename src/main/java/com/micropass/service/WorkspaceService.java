package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

@Service
public class WorkspaceService { // This service is responsible for managing the workspace directories for each
                                // deployment.

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final Path baseWorkspaceDir;

    public WorkspaceService(@Value("${micropass.workspace.base-dir}") String baseDir) {
        this.baseWorkspaceDir = Paths.get(baseDir);
        try {
            Files.createDirectories(this.baseWorkspaceDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize base workspace directory: " + baseDir, e);
        }
    }

    public Path prepareWorkspace(Long appId, Long deploymentId) {
        Path deploymentDir = baseWorkspaceDir.resolve("app-" + appId).resolve("deployment-" + deploymentId);
        try {
            if (Files.exists(deploymentDir)) {
                cleanDirectory(deploymentDir);
            }
            Files.createDirectories(deploymentDir);
            return deploymentDir;
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare workspace directory: " + deploymentDir, e);
        }
    }

    public void cleanDirectory(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("Could not completely clean directory {}: {}", path, e.getMessage());
        }
    }
}
