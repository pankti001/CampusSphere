package com.campussphere.backend.service;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live chat updates over Server-Sent Events (part of Spring MVC; no extra library). An event only says which
 * conversation changed ("message" or "read"); the app then loads the content through the normal, access-checked
 * API, so nothing private travels on the stream. Connections live in this server's memory, close after a few
 * minutes and are reopened by the app.
 */
@Component
public class ChatStream {
    static final long TIMEOUT_MS = 5 * 60 * 1000L;
    static final int MAX_PER_STUDENT = 4;

    private final Map<Long, Set<SseEmitter>> open = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pings = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-stream-ping");
        thread.setDaemon(true);
        return thread;
    });

    public ChatStream() {
        // A comment line every 20 s keeps idle connections (and the phone's read timeout) alive.
        pings.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
    }

    public SseEmitter connect(long student) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> mine = open.computeIfAbsent(student, k -> ConcurrentHashMap.newKeySet());
        if (mine.size() >= MAX_PER_STUDENT) mine.stream().findFirst().ifPresent(old -> { mine.remove(old); old.complete(); });
        mine.add(emitter);
        Runnable drop = () -> remove(student, emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        send(student, emitter, SseEmitter.event().name("ready").data("{}", MediaType.APPLICATION_JSON));
        return emitter;
    }

    /** Tells each student that a conversation changed. */
    public void publish(Collection<Long> students, String type, long conversation) {
        String data = "{\"conversationId\":" + conversation + "}";
        for (Long student : students) {
            for (SseEmitter emitter : open.getOrDefault(student, Set.of())) {
                send(student, emitter, SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
            }
        }
    }

    int connections(long student) {
        return open.getOrDefault(student, Set.of()).size();
    }

    private void ping() {
        open.forEach((student, emitters) -> emitters.forEach(e -> send(student, e, SseEmitter.event().comment("ping"))));
    }

    private void send(long student, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException gone) {
            remove(student, emitter);
        }
    }

    private void remove(long student, SseEmitter emitter) {
        open.computeIfPresent(student, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    @PreDestroy
    void stop() {
        pings.shutdownNow();
        open.values().forEach(set -> set.forEach(SseEmitter::complete));
        open.clear();
    }
}
