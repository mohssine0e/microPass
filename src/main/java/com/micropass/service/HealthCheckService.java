package com.micropass.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public boolean waitForHealthy(int port, int maxAttempts, Duration delayBetweenAttempts) {
        String url = "http://localhost:" + port + "/";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        for (int i = 1; i <= maxAttempts; i++) {
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                // Any HTTP response (2xx, 3xx, 4xx, 5xx) proves the container socket is alive
                // and responding
                log.info("Health check attempt {} succeeded with HTTP status {}", i, response.statusCode());
                return true;
            } catch (Exception e) {
                log.debug("Health check attempt {} failed: {}", i, e.getMessage());
                try {
                    Thread.sleep(delayBetweenAttempts.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
