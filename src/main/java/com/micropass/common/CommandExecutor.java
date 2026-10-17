package com.micropass.common;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class CommandExecutor {

    public record ExecutionResult(int exitCode, String output) {}

    public static ExecutionResult execute(List<String> command, File workingDir, Consumer<String> logConsumer) {
        return execute(command, workingDir, logConsumer, 10, TimeUnit.MINUTES, null, null);
    }

    public static ExecutionResult execute(List<String> command, File workingDir, Consumer<String> logConsumer, 
                                          long timeout, TimeUnit unit, AtomicBoolean cancellationFlag,
                                          AtomicReference<Process> processRef) {
        StringBuilder fullOutput = new StringBuilder();
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDir != null) {
                processBuilder.directory(workingDir);
            }
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();
            if (processRef != null) {
                processRef.set(process);
            }

            try (InputStream in = process.getInputStream()) {
                StringBuilder lineBuffer = new StringBuilder();
                long lastCarriageReturnEmit = 0;
                int b;
                while ((b = in.read()) != -1) {
                    if (cancellationFlag != null && cancellationFlag.get()) {
                        killProcessTree(process);
                        String cancelMsg = "[CANCELLED] Process killed immediately by user cancellation.";
                        if (logConsumer != null) logConsumer.accept(cancelMsg);
                        return new ExecutionResult(-99, fullOutput + "\n" + cancelMsg);
                    }

                    char c = (char) b;
                    if (c == '\n') {
                        if (!lineBuffer.isEmpty()) {
                            String line = lineBuffer.toString();
                            fullOutput.append(line).append("\n");
                            if (logConsumer != null) {
                                logConsumer.accept(line);
                            }
                            lineBuffer.setLength(0);
                        }
                    } else if (c == '\r') {
                        long now = System.currentTimeMillis();
                        if (!lineBuffer.isEmpty()) {
                            String line = lineBuffer.toString();
                            // Debounce progress ticks to at most once per 150ms or on completion (100% / done)
                            if (now - lastCarriageReturnEmit > 150 || line.contains("100%") || line.contains("done")) {
                                fullOutput.append(line).append("\n");
                                if (logConsumer != null) {
                                    logConsumer.accept(line);
                                }
                                lastCarriageReturnEmit = now;
                            }
                            lineBuffer.setLength(0);
                        }
                    } else {
                        lineBuffer.append(c);
                    }
                }
                if (!lineBuffer.isEmpty()) {
                    String line = lineBuffer.toString();
                    fullOutput.append(line).append("\n");
                    if (logConsumer != null) {
                        logConsumer.accept(line);
                    }
                }
            }

            boolean finished = process.waitFor(timeout, unit);
            if (!finished) {
                killProcessTree(process);
                String err = "Process timed out after " + timeout + " " + unit.name().toLowerCase();
                if (logConsumer != null) logConsumer.accept(err);
                return new ExecutionResult(-1, fullOutput + "\n" + err);
            }

            return new ExecutionResult(process.exitValue(), fullOutput.toString());
        } catch (Exception e) {
            if (process != null) {
                killProcessTree(process);
            }
            String errorMsg = "Execution failed: " + e.getMessage();
            if (logConsumer != null) {
                logConsumer.accept(errorMsg);
            }
            return new ExecutionResult(-1, errorMsg);
        } finally {
            if (processRef != null) {
                processRef.set(null);
            }
        }
    }

    public static void killProcessTree(Process process) {
        if (process == null) return;
        try {
            // Destroy all descendants first (e.g. git-remote-https, pack-objects)
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }
}
