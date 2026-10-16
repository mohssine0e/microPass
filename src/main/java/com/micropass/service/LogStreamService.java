package com.micropass.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class LogStreamService {

    private static final Logger log = LoggerFactory.getLogger(LogStreamService.class);
    private static final Long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes 

    // deploymentId -> List of active client emitters
    private final Map<Long, List<SseEmitter>> emittersMap = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long deploymentId, String historicalLogs) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT); // long-lived HTTP connection which allows the server to push logs to the client in real-time

        emittersMap.computeIfAbsent(deploymentId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(deploymentId, emitter));
        emitter.onTimeout(() -> removeEmitter(deploymentId, emitter));
        emitter.onError(e -> removeEmitter(deploymentId, emitter));

        // Replay any historical logs recorded so far before connection
        if (historicalLogs != null && !historicalLogs.isBlank()) {
            for (String line : historicalLogs.split("\n")) {
                try {
                    emitter.send(SseEmitter.event().data(line));
                } catch (IOException e) {
                    removeEmitter(deploymentId, emitter);
                    break;
                }
            }
        }

        return emitter;
    }

    public void broadcast(Long deploymentId, String logLine) {
        List<SseEmitter> emitters = emittersMap.get(deploymentId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(logLine));
            } catch (Exception e) {
                removeEmitter(deploymentId, emitter);
            }
        }
    }

    public void complete(Long deploymentId) {
        List<SseEmitter> emitters = emittersMap.remove(deploymentId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void removeEmitter(Long deploymentId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersMap.get(deploymentId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersMap.remove(deploymentId);
            }
        }
    }
}
