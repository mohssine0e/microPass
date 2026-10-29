package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitServiceTest {

    @TempDir
    Path tempDir;

    private Path remoteRepoDir;
    private Path workspaceDir;
    private GitService gitService;

    @BeforeEach
    void setUp() throws IOException {
        gitService = new GitService();
        remoteRepoDir = tempDir.resolve("remote-repo");
        workspaceDir = tempDir.resolve("workspace");

        Files.createDirectories(remoteRepoDir);
        Files.createDirectories(workspaceDir);

        // Initialize a dummy local git origin repository
        runCommand(List.of("git", "init", "-b", "main"), remoteRepoDir);
        runCommand(List.of("git", "config", "user.name", "Tester"), remoteRepoDir);
        runCommand(List.of("git", "config", "user.email", "test@test.com"), remoteRepoDir);

        Files.writeString(remoteRepoDir.resolve("README.md"), "# Initial Commit");
        runCommand(List.of("git", "add", "."), remoteRepoDir);
        runCommand(List.of("git", "commit", "-m", "Initial commit"), remoteRepoDir);
    }

    @Test
    void syncRepository_firstRunClonesAndSecondRunIncrementallyFetches() throws IOException {
        List<String> logs = new ArrayList<>();
        String repoUrl = remoteRepoDir.toAbsolutePath().toString();

        // 1. First run: Fresh clone
        GitService.GitResult result1 = gitService.syncRepository(
                repoUrl, "main", workspaceDir, logs::add, null, null);

        assertThat(result1.success()).isTrue();
        assertThat(Files.exists(workspaceDir.resolve("README.md"))).isTrue();
        assertThat(logs).anyMatch(l -> l.contains("Starting full shallow clone"));

        // 2. Add a new commit to the origin repository
        Files.writeString(remoteRepoDir.resolve("feature.txt"), "New Feature");
        runCommand(List.of("git", "add", "."), remoteRepoDir);
        runCommand(List.of("git", "commit", "-m", "Add feature"), remoteRepoDir);

        // 3. Second run: Fast incremental sync
        logs.clear();
        GitService.GitResult result2 = gitService.syncRepository(
                repoUrl, "main", workspaceDir, logs::add, null, null);

        assertThat(result2.success()).isTrue();
        assertThat(result2.commitHash()).isNotEqualTo(result1.commitHash());
        assertThat(Files.exists(workspaceDir.resolve("feature.txt"))).isTrue();
        assertThat(logs).anyMatch(l -> l.contains("Performing fast incremental fetch"));
    }

    private void runCommand(List<String> command, Path dir) {
        CommandExecutor.ExecutionResult res = CommandExecutor.execute(command, dir.toFile(), null);
        if (res.exitCode() != 0) {
            throw new RuntimeException("Command failed: " + command + " with output: " + res.output());
        }
    }
}
