package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Service
public class GitService {

    public record GitResult(boolean success, String commitHash, String errorOutput) {}

    public GitResult cloneRepository(String gitUrl, String branch, Path targetDir, Consumer<String> logConsumer) {
        logConsumer.accept("Cloning repository: " + gitUrl + " (branch: " + branch + ")...");
        
        List<String> cloneCmd = List.of(
                "git", "clone",
                "--depth", "1",
                "--branch", branch,
                gitUrl,
                targetDir.toAbsolutePath().toString()
        );

        CommandExecutor.ExecutionResult cloneResult = CommandExecutor.execute(cloneCmd, null, logConsumer);
        if (cloneResult.exitCode() != 0) {
            return new GitResult(false, null, cloneResult.output());
        }

        // Get commit hash
        List<String> revParseCmd = List.of("git", "rev-parse", "--short", "HEAD");
        CommandExecutor.ExecutionResult revResult = CommandExecutor.execute(revParseCmd, targetDir.toFile(), null);
        String commitHash = revResult.exitCode() == 0 ? revResult.output().trim() : "unknown";

        logConsumer.accept("Checked out commit: " + commitHash);
        return new GitResult(true, commitHash, null);
    }
}

