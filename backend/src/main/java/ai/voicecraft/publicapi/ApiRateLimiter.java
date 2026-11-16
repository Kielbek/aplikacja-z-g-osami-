package ai.voicecraft.publicapi;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Limity publicznego API na konto (wszystkie klucze użytkownika razem):
 * liczba żądań w bieżącej minucie i liczba syntez wykonywanych równolegle.
 * Stan w pamięci — przy wielu instancjach backendu należy przenieść go do Redis.
 */
@Component
public class ApiRateLimiter {
    public record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {}

    private static final class Window {
        long minute;
        int count;
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /** Rejestruje żądanie w oknie minutowym. */
    public Decision hit(String userId, int perMinute) {
        long nowMs = System.currentTimeMillis();
        long minute = nowMs / 60_000;
        long reset = Math.max(1, 60 - (nowMs / 1000) % 60);
        Window w = windows.computeIfAbsent(userId, k -> new Window());
        synchronized (w) {
            if (w.minute != minute) {
                w.minute = minute;
                w.count = 0;
            }
            if (w.count >= perMinute) return new Decision(false, perMinute, 0, reset);
            w.count++;
            return new Decision(true, perMinute, perMinute - w.count, reset);
        }
    }

    /** Zajmuje miejsce na równoległą syntezę; false, gdy limit jest wyczerpany. */
    public boolean tryEnter(String userId, int limit) {
        AtomicInteger n = inFlight.computeIfAbsent(userId, k -> new AtomicInteger());
        while (true) {
            int cur = n.get();
            if (cur >= limit) return false;
            if (n.compareAndSet(cur, cur + 1)) return true;
        }
    }

    public void exit(String userId) {
        AtomicInteger n = inFlight.get(userId);
        if (n != null) n.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** Do testów. */
    void reset() {
        windows.clear();
        inFlight.clear();
    }
}
