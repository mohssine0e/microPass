package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class GitService {

    public record GitResult(boolean success, String commitHash, String errorOutput) {}

    public List<String> listRemoteBranches(String gitUrl) {
        List<String> branches = new ArrayList<>();
        if (gitUrl == null || gitUrl.isBlank()) {
            return branches;
        }

        List<String> cmd = List.of("git", "ls-remote", "--heads", gitUrl.trim());
        CommandExecutor.ExecutionResult result = CommandExecutor.execute(cmd, null, null);
        if (result.exitCode() == 0 && result.output() != null) {
            for (String line : result.output().split("\n")) {
                line = line.trim();
                int idx = line.indexOf("refs/heads/");
                if (idx != -1) {
                    branches.add(line.substring(idx + "refs/heads/".length()).trim());
                }
            }
        }
        return branches;
    }

    public GitResult cloneRepository(String gitUrl, String branch, Path targetDir, Consumer<String> logConsumer, 
                                     AtomicBoolean cancellationFlag, AtomicReference<Process> processRef) {
        logConsumer.accept(">>> STEP 1/5: Starting Git repository clone...");
        logConsumer.accept(">>> Target Repository: " + gitUrl);
        logConsumer.accept(">>> Selected Branch: " + branch);
        logConsumer.accept(">>> Local Workspace: " + targetDir.toAbsolutePath());
        
        List<String> cloneCmd = List.of(
                "git", "clone",
                "--depth", "1",
                "--progress",
                "--branch", branch,
                gitUrl,
                targetDir.toAbsolutePath().toString()
        );

        CommandExecutor.ExecutionResult cloneResult = CommandExecutor.execute(
                cloneCmd, null, logConsumer, 10, TimeUnit.MINUTES, cancellationFlag, processRef);
        
        if (cloneResult.exitCode() != 0) {
            return new GitResult(false, null, cloneResult.output());
        }

        // Get commit hash
        List<String> revParseCmd = List.of("git", "rev-parse", "--short", "HEAD");
        CommandExecutor.ExecutionResult revResult = CommandExecutor.execute(revParseCmd, targetDir.toFile(), null);
        String commitHash = revResult.exitCode() == 0 ? revResult.output().trim() : "unknown";

        logConsumer.accept(">>> Git clone complete! Checked out commit: [" + commitHash + "]");
        return new GitResult(true, commitHash, null);
    }
}
