package ai.voicecraft.jobs;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Zdarzenia projektu dla przeglądarki (Server-Sent Events). Pamięć lokalna instancji —
 * przy wielu instancjach backendu zastąp np. Redis Pub/Sub.
 */
@Component
public class EventHub {
    private record Sub(String userId, SseEmitter emitter) {}

    private final Map<String, List<Sub>> subs = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId, String projectId) {
        var emitter = new SseEmitter(30 * 60 * 1000L);
        var sub = new Sub(userId, emitter);
        subs.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(sub);
        Runnable remove = () -> subs.getOrDefault(projectId, List.of()).remove(sub);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connected").reconnectTime(3000));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    public void publish(String userId, String projectId, String event, Object data) {
        for (Sub s : subs.getOrDefault(projectId, List.of())) {
            if (!s.userId().equals(userId)) continue;
            try {
                s.emitter().send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                subs.getOrDefault(projectId, List.of()).remove(s);
            }
        }
    }

    /** Podtrzymanie połączenia przez proxy (komentarz SSE co 20 s). */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        subs.forEach((project, list) -> list.forEach(s -> {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                list.remove(s);
            }
        }));
    }
}
