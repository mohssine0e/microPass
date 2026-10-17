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

    // deploymentId -> List of accumulated active logs in memory (prevents losing logs before SSE connects)
    private final Map<Long, List<String>> activeLogsMap = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long deploymentId, String historicalLogs, boolean isTerminal) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT); // long-lived HTTP connection which allows the server to push logs to the client in real-time

        emittersMap.computeIfAbsent(deploymentId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(deploymentId, emitter));
        emitter.onTimeout(() -> removeEmitter(deploymentId, emitter));
        emitter.onError(e -> removeEmitter(deploymentId, emitter));

        // Flush HTTP headers and establish connection immediately
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().data(">>> Connected to deployment live stream"));
            }
        } catch (Exception ignored) {
        }

        // 1. Replay active logs accumulated in memory first (for in-progress deployments)
        List<String> activeLogs = activeLogsMap.get(deploymentId);
        if (activeLogs != null && !activeLogs.isEmpty()) {
            for (String line : activeLogs) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().data(line));
                    }
                } catch (IOException e) {
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignored) {
                    }
                    removeEmitter(deploymentId, emitter);
                    return emitter;
                }
            }
        } else if (historicalLogs != null && !historicalLogs.isBlank()) {
            // 2. Replay historical logs from database (for past completed deployments)
            for (String line : historicalLogs.split("\n")) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().data(line));
                    }
                } catch (IOException e) {
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignored) {
                    }
                    removeEmitter(deploymentId, emitter);
                    return emitter;
                }
            }
        }

        if (isTerminal) {
            try {
                synchronized (emitter) {
                    emitter.complete();
                }
            } catch (Exception ignored) {
            }
            removeEmitter(deploymentId, emitter);
        }

        return emitter;
    }

    public SseEmitter subscribe(Long deploymentId, String historicalLogs) {
        return subscribe(deploymentId, historicalLogs, false);
    }

    public void broadcast(Long deploymentId, String logLine) {
        if (logLine == null) return;

        // Store into memory buffer so any late-connecting SSE subscriber gets full history
        activeLogsMap.computeIfAbsent(deploymentId, k -> new CopyOnWriteArrayList<>()).add(logLine);

        List<SseEmitter> emitters = emittersMap.get(deploymentId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().data(logLine));
                }
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
                removeEmitter(deploymentId, emitter);
            }
        }
    }

    public void complete(Long deploymentId) {
        List<SseEmitter> emitters = emittersMap.remove(deploymentId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    synchronized (emitter) {
                        emitter.complete();
                    }
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
