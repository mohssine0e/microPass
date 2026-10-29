package com.micropass.service;

import com.micropass.common.CommandExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

        List<String> cmd = List.of("git", "-c", "http.version=HTTP/1.1", "ls-remote", "--heads", gitUrl.trim());
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
        return syncRepository(gitUrl, branch, targetDir, logConsumer, cancellationFlag, processRef);
    }

    public GitResult syncRepository(String gitUrl, String branch, Path targetDir, Consumer<String> logConsumer,
                                    AtomicBoolean cancellationFlag, AtomicReference<Process> processRef) {
        logConsumer.accept(">>> STEP 1/5: Synchronizing Git repository...");
        logConsumer.accept(">>> Target Repository: " + gitUrl);
        logConsumer.accept(">>> Selected Branch: " + branch);
        logConsumer.accept(">>> Local Workspace: " + targetDir.toAbsolutePath());

        Path gitDir = targetDir.resolve(".git");
        boolean hasExistingGit = Files.exists(gitDir);

        if (hasExistingGit && isGitRepoHealthy(targetDir)) {
            String remoteUrl = getRemoteUrl(targetDir);
            if (remoteUrl != null && isSameRepoUrl(remoteUrl, gitUrl)) {
                logConsumer.accept(">>> Found existing repository cache. Performing fast incremental fetch for branch: [" + branch + "]...");
                GitResult fetchResult = incrementalFetch(targetDir, branch, logConsumer, cancellationFlag, processRef);
                if (fetchResult.success()) {
                    return fetchResult;
                }
                logConsumer.accept(">>> [WARN] Incremental sync encountered an issue. Falling back to clean fresh clone...");
            } else {
                logConsumer.accept(">>> Repository URL changed (old: " + remoteUrl + ", new: " + gitUrl + "). Resetting cache...");
            }
        } else if (hasExistingGit) {
            logConsumer.accept(">>> [WARN] Existing repository cache is incomplete or corrupted. Resetting cache...");
        }

        // Fallback or Initial: Clean and Fresh Shallow Clone
        cleanTargetDir(targetDir);
        return freshClone(gitUrl, branch, targetDir, logConsumer, cancellationFlag, processRef);
    }

    private GitResult incrementalFetch(Path targetDir, String branch, Consumer<String> logConsumer,
                                       AtomicBoolean cancellationFlag, AtomicReference<Process> processRef) {
        if (cancellationFlag != null && cancellationFlag.get()) {
            return new GitResult(false, null, "[CANCELLED] Sync cancelled by user.");
        }

        List<String> fetchCmd = List.of(
                "git",
                "-c", "http.version=HTTP/1.1",
                "-c", "http.postBuffer=1048576000",
                "-c", "http.lowSpeedLimit=1000",
                "-c", "http.lowSpeedTime=60",
                "fetch",
                "--depth", "1",
                "origin",
                branch
        );

        CommandExecutor.ExecutionResult fetchResult = CommandExecutor.execute(
                fetchCmd, targetDir.toFile(), logConsumer, 10, TimeUnit.MINUTES, cancellationFlag, processRef);

        if (fetchResult.exitCode() != 0) {
            return new GitResult(false, null, fetchResult.output());
        }

        if (cancellationFlag != null && cancellationFlag.get()) {
            return new GitResult(false, null, "[CANCELLED] Sync cancelled by user.");
        }

        // Checkout the target branch
        List<String> checkoutCmd = List.of("git", "checkout", "-B", branch, "origin/" + branch);
        CommandExecutor.ExecutionResult checkoutResult = CommandExecutor.execute(
                checkoutCmd, targetDir.toFile(), null, 2, TimeUnit.MINUTES, cancellationFlag, processRef);

        if (checkoutResult.exitCode() != 0) {
            return new GitResult(false, null, checkoutResult.output());
        }

        // Reset hard and clean untracked artifacts to guarantee pristine build context
        List<String> resetCmd = List.of("git", "reset", "--hard", "origin/" + branch);
        CommandExecutor.execute(resetCmd, targetDir.toFile(), null);

        List<String> cleanCmd = List.of("git", "clean", "-fd");
        CommandExecutor.execute(cleanCmd, targetDir.toFile(), null);

        String commitHash = getCommitHash(targetDir);
        logConsumer.accept(">>> Fast incremental sync complete in < 1s! Checked out commit: [" + commitHash + "]");
        return new GitResult(true, commitHash, null);
    }

    private GitResult freshClone(String gitUrl, String branch, Path targetDir, Consumer<String> logConsumer,
                                 AtomicBoolean cancellationFlag, AtomicReference<Process> processRef) {
        logConsumer.accept(">>> Starting full shallow clone from origin...");
        
        List<String> cloneCmd = List.of(
                "git",
                "-c", "http.version=HTTP/1.1",
                "-c", "http.postBuffer=1048576000",
                "-c", "http.lowSpeedLimit=1000",
                "-c", "http.lowSpeedTime=60",
                "clone",
                "--depth", "1",
                "--progress",
                "--branch", branch,
                gitUrl,
                targetDir.toAbsolutePath().toString()
        );

        int maxAttempts = 2;
        CommandExecutor.ExecutionResult cloneResult = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancellationFlag != null && cancellationFlag.get()) {
                return new GitResult(false, null, "[CANCELLED] Process cancelled by user.");
            }

            if (attempt > 1) {
                logConsumer.accept(">>> [RETRY] Network drop detected. Cleaning workspace and retrying clone (attempt " + attempt + "/" + maxAttempts + ")...");
                cleanTargetDir(targetDir);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new GitResult(false, null, "Interrupted during retry backoff");
                }
            }

            cloneResult = CommandExecutor.execute(
                    cloneCmd, null, logConsumer, 10, TimeUnit.MINUTES, cancellationFlag, processRef);

            if (cloneResult.exitCode() == 0) {
                break;
            }

            if (cancellationFlag != null && cancellationFlag.get()) {
                break;
            }
        }

        if (cloneResult == null || cloneResult.exitCode() != 0) {
            return new GitResult(false, null, cloneResult != null ? cloneResult.output() : "Unknown clone error");
        }

        String commitHash = getCommitHash(targetDir);
        logConsumer.accept(">>> Full clone complete! Checked out commit: [" + commitHash + "]");
        return new GitResult(true, commitHash, null);
    }

    private boolean isGitRepoHealthy(Path targetDir) {
        try {
            List<String> cmd = List.of("git", "rev-parse", "--is-inside-work-tree");
            CommandExecutor.ExecutionResult result = CommandExecutor.execute(cmd, targetDir.toFile(), null);
            return result.exitCode() == 0 && result.output() != null && result.output().trim().contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    private String getRemoteUrl(Path targetDir) {
        try {
            List<String> cmd = List.of("git", "remote", "get-url", "origin");
            CommandExecutor.ExecutionResult result = CommandExecutor.execute(cmd, targetDir.toFile(), null);
            return result.exitCode() == 0 && result.output() != null ? result.output().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSameRepoUrl(String url1, String url2) {
        if (url1 == null || url2 == null) return false;
        String u1 = url1.trim().replaceAll("\\.git$", "");
        String u2 = url2.trim().replaceAll("\\.git$", "");
        return u1.equalsIgnoreCase(u2);
    }

    private String getCommitHash(Path targetDir) {
        List<String> revParseCmd = List.of("git", "rev-parse", "--short", "HEAD");
        CommandExecutor.ExecutionResult revResult = CommandExecutor.execute(revParseCmd, targetDir.toFile(), null);
        return revResult.exitCode() == 0 && revResult.output() != null ? revResult.output().trim() : "unknown";
    }

    private void cleanTargetDir(Path targetDir) {
        if (Files.exists(targetDir)) {
            try (var stream = Files.walk(targetDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }
}
