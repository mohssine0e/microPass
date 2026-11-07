package com.micropass.service;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ProxyRoutingService {
    private final AppRepository appRepository;
    private final boolean enabled;
    private final int publicPort;
    private final URI loadUri;
    private final HttpClient client;
    private Map<Long, Integer> activeRoutes = new TreeMap<>();

    public ProxyRoutingService(
            AppRepository appRepository,
            @Value("${micropass.proxy.enabled:false}") boolean enabled,
            @Value("${micropass.proxy.public-port:9080}") int publicPort,
            @Value("${micropass.proxy.admin-url:http://127.0.0.1:2019}") String adminUrl) {
        this.appRepository = appRepository;
        this.enabled = enabled;
        this.publicPort = publicPort;
        this.loadUri = URI.create(adminUrl.replaceAll("/+$", "") + "/load");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String urlFor(App app) {
        if (!enabled || app.getAllocatedPort() == null || app.getActiveContainerId() == null) return null;
        return "http://app-" + app.getId() + ".localhost:" + publicPort + "/";
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reconcileOnStartup() {
        if (!enabled) return;
        Map<Long, Integer> routes = routesFromDatabase();
        publish(routes);
        activeRoutes = routes;
    }

    public synchronized void activate(Long appId, int hostPort) {
        if (!enabled) return;
        Map<Long, Integer> routes = new TreeMap<>(activeRoutes);
        routes.put(appId, hostPort);
        publish(routes);
        activeRoutes = routes;
    }

    public synchronized void deactivate(Long appId) {
        if (!enabled) return;
        Map<Long, Integer> routes = new TreeMap<>(activeRoutes);
        routes.remove(appId);
        publish(routes);
        activeRoutes = routes;
    }

    private Map<Long, Integer> routesFromDatabase() {
        Map<Long, Integer> routes = new TreeMap<>();
        for (App app : appRepository.findAll()) {
            if (app.getActiveContainerId() != null && app.getAllocatedPort() != null) {
                routes.put(app.getId(), app.getAllocatedPort());
            }
        }
        return routes;
    }

    private void publish(Map<Long, Integer> routes) {
        StringBuilder config = new StringBuilder("{\n  admin 0.0.0.0:2019\n}\n\n");
        config.append("http://:").append(publicPort).append(" {\n  respond 404\n}\n\n");
        routes.forEach((id, port) -> config.append("http://app-")
                .append(id).append(".localhost:").append(publicPort)
                .append(" {\n  reverse_proxy host.docker.internal:")
                .append(port).append("\n}\n\n"));
        HttpRequest request = HttpRequest.newBuilder(loadUri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "text/caddyfile")
                .POST(HttpRequest.BodyPublishers.ofString(config.toString()))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Caddy rejected the route update: HTTP " + response.statusCode()
                        + " " + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Caddy route update failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Caddy route update was interrupted", e);
        }
    }
}
