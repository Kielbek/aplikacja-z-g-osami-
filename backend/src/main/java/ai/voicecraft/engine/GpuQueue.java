package ai.voicecraft.engine;

import ai.voicecraft.common.ApiException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Kolejka dostępu do modelu na GPU. Jedna karta obsługuje {@code ENGINE_CONCURRENCY} wywołań naraz (domyślnie 1);
 * pozostałe czekają w kolejce według priorytetu, a w ramach priorytetu — w kolejności zgłoszeń (FIFO).
 *
 * <ul>
 *   <li>{@link Priority#INTERACTIVE} — użytkownik czeka w edytorze (pojedynczy fragment, próbka głosu),</li>
 *   <li>{@link Priority#API} — synchroniczne żądanie publicznego API,</li>
 *   <li>{@link Priority#BATCH} — zadania w tle (generowanie całego projektu, zadania API).</li>
 * </ul>
 *
 * Żądanie interaktywne i API, które czeka dłużej niż {@code ENGINE_QUEUE_TIMEOUT}, kończy się kodem 503
 * (ENGINE_BUSY) — klient może ponowić. Zadania w tle czekają tak długo, jak trzeba.
 */
@Component
public class GpuQueue {
    public enum Priority { INTERACTIVE, API, BATCH }

    private static final ThreadLocal<Priority> CURRENT = new ThreadLocal<>();

    private record Ticket(Priority priority, long seq) {}

    private final int permits;
    private final Duration timeout;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Ticket> waiting = new PriorityQueue<>(
            (a, b) -> a.priority != b.priority ? a.priority.compareTo(b.priority) : Long.compare(a.seq, b.seq));
    private int running;
    private long seq;

    public GpuQueue(@Value("${voicecraft.engine.concurrency:1}") int permits,
                    @Value("${voicecraft.engine.queue-timeout:120s}") Duration timeout) {
        this.permits = Math.max(1, permits);
        this.timeout = timeout;
    }

    /** Wykonuje kod z podanym priorytetem kolejki (dotyczy wywołań silnika w tym wątku). */
    public static <T> T withPriority(Priority priority, Supplier<T> action) {
        Priority previous = CURRENT.get();
        CURRENT.set(priority);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** Wywołanie modelu: czeka na wolne miejsce na GPU, wykonuje i zwalnia miejsce. */
    public <T> T run(Supplier<T> work) {
        Priority priority = CURRENT.get() == null ? Priority.INTERACTIVE : CURRENT.get();
        acquire(priority);
        try {
            return work.get();
        } finally {
            release();
        }
    }

    public record Stats(int capacity, int running, Map<Priority, Integer> waiting) {
        public int waitingTotal() {
            return waiting.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            var counts = new EnumMap<Priority, Integer>(Priority.class);
            for (Priority p : Priority.values()) counts.put(p, 0);
            waiting.forEach(t -> counts.merge(t.priority, 1, Integer::sum));
            return new Stats(permits, running, counts);
        } finally {
            lock.unlock();
        }
    }

    private void acquire(Priority priority) {
        lock.lock();
        try {
            var ticket = new Ticket(priority, seq++);
            waiting.add(ticket);
            long deadline = priority == Priority.BATCH ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
            try {
                while (running >= permits || waiting.peek() != ticket) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        waiting.remove(ticket);
                        changed.signalAll();
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Silnik jest teraz zajęty innymi nagraniami. Spróbuj ponownie za chwilę.", "ENGINE_BUSY");
                    }
                    if (deadline == Long.MAX_VALUE) changed.await();
                    else changed.awaitNanos(left);
                }
            } catch (InterruptedException e) {
                waiting.remove(ticket);
                changed.signalAll();
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Generowanie przerwane.", "ENGINE_BUSY");
            }
            waiting.remove(ticket);
            running++;
            changed.signalAll(); // kolejny w kolejce może sprawdzić, czy jest wolne miejsce (gdy permits > 1)
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        lock.lock();
        try {
            running--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Do testów: czeka, aż w kolejce pojawi się podana liczba oczekujących. */
    boolean awaitWaiting(int n, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (stats().waitingTotal() >= n) return true;
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return false;
    }
}
