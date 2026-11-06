package com.micropass.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorTest {

    @Test
    void execute_enforcesTimeoutWhileProcessIsStillRunning() {
        Instant started = Instant.now();

        CommandExecutor.ExecutionResult result = CommandExecutor.execute(
                List.of("sh", "-c", "sleep 5"),
                null,
                null,
                150,
                TimeUnit.MILLISECONDS,
                null,
                null);

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output()).contains("timed out");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void execute_observesCancellationWithoutWaitingForTimeout() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Process> processReference = new AtomicReference<>();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(150);
                cancelled.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CommandExecutor.ExecutionResult result = CommandExecutor.execute(
                List.of("sh", "-c", "sleep 5"),
                null,
                null,
                5,
                TimeUnit.SECONDS,
                cancelled,
                processReference);
        canceller.join();

        assertThat(result.exitCode()).isEqualTo(-99);
        assertThat(result.output()).contains("CANCELLED");
        assertThat(processReference.get()).isNull();
    }
}
