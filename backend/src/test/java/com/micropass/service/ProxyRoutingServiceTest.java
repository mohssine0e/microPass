package com.micropass.service;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyRoutingServiceTest {
    private HttpServer admin;
    private AppRepository appRepository;
    private AtomicReference<String> loadedConfig;
    private AtomicInteger responseStatus;
    private ProxyRoutingService service;

    @BeforeEach
    void setUp() throws Exception {
        loadedConfig = new AtomicReference<>();
        responseStatus = new AtomicInteger(200);
        admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        admin.createContext("/load", exchange -> {
            loadedConfig.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        admin.start();
        appRepository = mock(AppRepository.class);
        service = new ProxyRoutingService(appRepository, true, 9080,
                "http://127.0.0.1:" + admin.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        admin.stop(0);
    }

    @Test
    void activationReplacesOnlyTheReadyAppsRoute() {
        App current = app(1L, 9101);
        when(appRepository.findAll()).thenReturn(List.of(current, app(2L, 9200)));
        service.reconcileOnStartup();

        service.activate(1L, 9102);

        assertThat(loadedConfig.get())
                .contains("http://app-1.localhost:9080", "reverse_proxy host.docker.internal:9102")
                .contains("http://app-2.localhost:9080", "reverse_proxy host.docker.internal:9200")
                .doesNotContain("host.docker.internal:9101");
        assertThat(service.urlFor(current)).isEqualTo("http://app-1.localhost:9080/");
    }

    @Test
    void rejectedConfigFailsActivationBeforeDatabasePromotion() {
        when(appRepository.findAll()).thenReturn(List.of(app(1L, 9101)));
        service.reconcileOnStartup();
        responseStatus.set(500);

        assertThatThrownBy(() -> service.activate(1L, 9102))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Caddy rejected");
    }

    private App app(Long id, int port) {
        return App.builder().id(id).name("app-" + id).allocatedPort(port)
                .activeContainerId("container-" + id).build();
    }
}
