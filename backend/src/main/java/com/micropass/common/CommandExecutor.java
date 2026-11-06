package com.micropass.common;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
        Thread outputReader = null;
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

            InputStream processOutput = process.getInputStream();
            outputReader = Thread.ofVirtual()
                    .name("command-output-reader-" + process.pid())
                    .start(() -> consumeOutput(processOutput, fullOutput, logConsumer));

            long timeoutNanos = unit.toNanos(timeout);
            long deadline = System.nanoTime() + timeoutNanos;
            boolean cancelled = false;
            boolean timedOut = false;

            while (process.isAlive()) {
                if (cancellationFlag != null && cancellationFlag.get()) {
                    cancelled = true;
                    killProcessTree(process);
                    break;
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    timedOut = true;
                    killProcessTree(process);
                    break;
                }

                process.waitFor(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            }

            joinReader(outputReader);

            if (cancelled) {
                String message = "[CANCELLED] Process killed immediately by user cancellation.";
                emitAndAppend(fullOutput, logConsumer, message);
                return new ExecutionResult(-99, fullOutput.toString());
            }

            if (timedOut) {
                String message = "Process timed out after " + timeout + " " + unit.name().toLowerCase();
                emitAndAppend(fullOutput, logConsumer, message);
                return new ExecutionResult(-1, fullOutput.toString());
            }

            return new ExecutionResult(process.exitValue(), fullOutput.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                killProcessTree(process);
            }
            joinReader(outputReader);
            String errorMsg = "Execution interrupted";
            emitAndAppend(fullOutput, logConsumer, errorMsg);
            return new ExecutionResult(-1, fullOutput.toString());
        } catch (Exception e) {
            if (process != null) {
                killProcessTree(process);
            }
            joinReader(outputReader);
            String errorMsg = "Execution failed: " + e.getMessage();
            emitAndAppend(fullOutput, logConsumer, errorMsg);
            return new ExecutionResult(-1, fullOutput.toString());
        } finally {
            if (processRef != null) {
                processRef.set(null);
            }
        }
    }

    private static void consumeOutput(InputStream input, StringBuilder fullOutput, Consumer<String> logConsumer) {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder lineBuffer = new StringBuilder();
            long lastCarriageReturnEmit = 0;
            int value;
            while ((value = reader.read()) != -1) {
                char c = (char) value;
                if (c == '\n') {
                    if (!lineBuffer.isEmpty()) {
                        emitAndAppend(fullOutput, logConsumer, lineBuffer.toString());
                        lineBuffer.setLength(0);
                    }
                } else if (c == '\r') {
                    long now = System.currentTimeMillis();
                    if (!lineBuffer.isEmpty()) {
                        String line = lineBuffer.toString();
                        if (now - lastCarriageReturnEmit > 150 || line.contains("100%") || line.contains("done")) {
                            emitAndAppend(fullOutput, logConsumer, line);
                            lastCarriageReturnEmit = now;
                        }
                        lineBuffer.setLength(0);
                    }
                } else {
                    lineBuffer.append(c);
                }
            }

            if (!lineBuffer.isEmpty()) {
                emitAndAppend(fullOutput, logConsumer, lineBuffer.toString());
            }
        } catch (Exception ignored) {
            // The stream normally closes when a timed-out or cancelled process is killed.
        }
    }

    private static void emitAndAppend(StringBuilder output, Consumer<String> logConsumer, String message) {
        synchronized (output) {
            output.append(message).append("\n");
        }
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }

    private static void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
