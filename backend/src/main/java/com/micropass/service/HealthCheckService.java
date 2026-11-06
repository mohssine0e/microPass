package com.micropass.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public boolean waitForHealthy(
            int port,
            int maxAttempts,
            Duration delayBetweenAttempts,
            Consumer<String> logConsumer) {
        return waitForHealthy(port, maxAttempts, delayBetweenAttempts, logConsumer, () -> false);
    }

    public boolean waitForHealthy(
            int port,
            int maxAttempts,
            Duration delayBetweenAttempts,
            Consumer<String> logConsumer,
            BooleanSupplier cancelled) {
        String url = "http://localhost:" + port + "/";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        for (int i = 1; i <= maxAttempts; i++) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            try {
                if (logConsumer != null) {
                    logConsumer.accept(">>> Readiness probe (" + i + "/" + maxAttempts + "): Pinging " + url + " ...");
                }
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 400) {
                    log.info("Health check attempt {} succeeded with HTTP status {}", i, status);
                    if (logConsumer != null) {
                        logConsumer.accept(">>> Readiness probe PASSED! HTTP " + status + " response from container.");
                    }
                    return true;
                }
                log.debug("Health check attempt {} received non-ready HTTP status {}", i, status);
                if (logConsumer != null) {
                    logConsumer.accept(">>> Readiness probe (" + i + "/" + maxAttempts
                            + "): Container answered HTTP " + status + " but is not ready yet.");
                }
            } catch (Exception e) {
                log.debug("Health check attempt {} failed: {}", i, e.getMessage());
                if (logConsumer != null) {
                    logConsumer.accept(">>> Readiness probe (" + i + "/" + maxAttempts + "): Container still warming up...");
                }
            }

            if (cancelled.getAsBoolean()) {
                return false;
            }
            try {
                Thread.sleep(delayBetweenAttempts.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean waitForHealthy(int port, int maxAttempts, Duration delayBetweenAttempts) {
        return waitForHealthy(port, maxAttempts, delayBetweenAttempts, null);
    }
}
