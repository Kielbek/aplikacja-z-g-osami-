package ai.voicecraft.security;

import ai.voicecraft.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Ochrona przed zgadywaniem haseł: max 5 nieudanych prób na konto+IP i 30 na IP w oknie 15 minut.
 * Pamięć lokalna instancji — przy wielu instancjach zastąp np. Redisem.
 */
@Component
public class LoginRateLimiter {
    static final int PER_ACCOUNT = 5;
    static final int PER_IP = 30;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public void check(String email, String ip) {
        check("acc:" + email.toLowerCase() + "|" + ip, PER_ACCOUNT);
        check("ip:" + ip, PER_IP);
    }

    public void recordFailure(String email, String ip) {
        add("acc:" + email.toLowerCase() + "|" + ip);
        add("ip:" + ip);
    }

    public void recordSuccess(String email, String ip) {
        failures.remove("acc:" + email.toLowerCase() + "|" + ip);
    }

    private void check(String key, int limit) {
        Deque<Instant> q = failures.get(key);
        if (q == null) return;
        synchronized (q) {
            prune(q);
            if (q.size() >= limit) {
                long minutes = Math.max(1, Duration.between(Instant.now(), q.peekFirst().plus(WINDOW)).toMinutes() + 1);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "Zbyt wiele nieudanych prób logowania. Spróbuj ponownie za " + minutes + " min.");
            }
        }
    }

    private void add(String key) {
        Deque<Instant> q = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(Instant.now());
        }
    }

    private static void prune(Deque<Instant> q) {
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }
}
