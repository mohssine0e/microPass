package com.micropass.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HealthCheckServiceTest {

    private final HealthCheckService healthCheckService = new HealthCheckService();

    @Test
    void waitForHealthy_acceptsSuccessfulHttpStatus() throws Exception {
        try (OneShotHttpServer server = new OneShotHttpServer(204)) {
            assertThat(healthCheckService.waitForHealthy(
                    server.port(), 1, Duration.ofMillis(1))).isTrue();
        }
    }

    @Test
    void waitForHealthy_rejectsServerErrorStatus() throws Exception {
        try (OneShotHttpServer server = new OneShotHttpServer(500)) {
            assertThat(healthCheckService.waitForHealthy(
                    server.port(), 1, Duration.ofMillis(1))).isFalse();
        }
    }

    @Test
    void waitForHealthy_stopsBeforeProbeWhenDeploymentIsCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        boolean healthy = healthCheckService.waitForHealthy(
                1,
                12,
                Duration.ofSeconds(1),
                ignored -> { },
                cancelled::get);

        assertThat(healthy).isFalse();
    }

    private static final class OneShotHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread serverThread;

        private OneShotHttpServer(int status) throws Exception {
            this.serverSocket = new ServerSocket(0);
            this.serverThread = Thread.ofVirtual().start(() -> respondOnce(status));
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void respondOnce(int status) {
            try (Socket socket = serverSocket.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream output = socket.getOutputStream()) {
                String line;
                while ((line = reader.readLine()) != null && !line.isBlank()) {
                    // Consume the HTTP request headers.
                }
                String reason = status < 400 ? "No Content" : "Internal Server Error";
                String response = "HTTP/1.1 " + status + " " + reason
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            serverThread.join(1_000);
        }
    }
}
