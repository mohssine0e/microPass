package com.micropass.service;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

@Service
public class PortAllocationService {

    private final int portStart;
    private final int portEnd;
    private final AppRepository appRepository;

    public PortAllocationService(
            @Value("${micropass.ports.start:9100}") int portStart,
            @Value("${micropass.ports.end:9200}") int portEnd,
            AppRepository appRepository) {
        this.portStart = portStart;
        this.portEnd = portEnd;
        this.appRepository = appRepository;
    }

    public synchronized int allocatePort(App app) {
        if (app.getAllocatedPort() != null && isPortAvailable(app.getAllocatedPort())) {
            return app.getAllocatedPort();
        }

        Set<Integer> usedPorts = new HashSet<>();
        appRepository.findAll().forEach(a -> {
            if (a.getAllocatedPort() != null && !a.getId().equals(app.getId())) {
                usedPorts.add(a.getAllocatedPort());
            }
        });

        for (int p = portStart; p <= portEnd; p++) {
            if (!usedPorts.contains(p) && isPortAvailable(p)) {
                app.setAllocatedPort(p);
                appRepository.save(app);
                return p;
            }
        }

        throw new IllegalStateException("No available ports in range " + portStart + " to " + portEnd);
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

