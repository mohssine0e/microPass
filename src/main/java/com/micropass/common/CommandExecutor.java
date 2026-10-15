package com.micropass.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

public class CommandExecutor {

    public record ExecutionResult(int exitCode, String output) {} // this gives us a simple immutable data structure to hold the result of the command execution

    public static ExecutionResult execute(List<String> command, File workingDir, Consumer<String> logConsumer) {
        StringBuilder fullOutput = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDir != null) {
                processBuilder.directory(workingDir);
            }
            processBuilder.redirectErrorStream(true); // Combine stdout and stderr

            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    fullOutput.append(line).append("\n");
                    if (logConsumer != null) {
                        logConsumer.accept(line);
                    }
                }
            }

            int exitCode = process.waitFor();
            return new ExecutionResult(exitCode, fullOutput.toString());
        } catch (Exception e) {
            String errorMsg = "Execution failed: " + e.getMessage();
            if (logConsumer != null) {
                logConsumer.accept(errorMsg);
            }
            return new ExecutionResult(-1, errorMsg);
        }
    }
}

