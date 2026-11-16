package ai.voicecraft.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.voicecraft.common.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GpuQueueTest {
    @Test
    void neverRunsMoreThanCapacityAndServesHigherPriorityFirst() throws Exception {
        var q = new GpuQueue(1, Duration.ofSeconds(10));
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        List<String> order = new CopyOnWriteArrayList<>();

        Thread holder = new Thread(() -> q.run(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        while (q.stats().running() == 0) Thread.sleep(2);

        Runnable work = () -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            active.decrementAndGet();
        };
        Thread batch = new Thread(() -> GpuQueue.withPriority(GpuQueue.Priority.BATCH, () -> q.run(() -> { work.run(); order.add("batch"); return null; })));
        batch.start();
        assertThat(q.awaitWaiting(1, 2000)).isTrue();
        Thread api = new Thread(() -> GpuQueue.withPriority(GpuQueue.Priority.API, () -> q.run(() -> { work.run(); order.add("api"); return null; })));
        api.start();
        assertThat(q.awaitWaiting(2, 2000)).isTrue();
        Thread interactive = new Thread(() -> q.run(() -> { work.run(); order.add("interactive"); return null; }));
        interactive.start();
        assertThat(q.awaitWaiting(3, 2000)).isTrue();
        assertThat(q.stats().waiting().get(GpuQueue.Priority.BATCH)).isEqualTo(1);

        release.countDown();
        for (Thread t : List.of(holder, batch, api, interactive)) t.join(5000);
        assertThat(order).containsExactly("interactive", "api", "batch");
        assertThat(peak.get()).isEqualTo(1);
        assertThat(q.stats().running()).isZero();
    }

    @Test
    void interactiveRequestGivesUpAfterTimeoutWithEngineBusy() throws Exception {
        var q = new GpuQueue(1, Duration.ofMillis(100));
        var release = new CountDownLatch(1);
        Thread holder = new Thread(() -> q.run(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        while (q.stats().running() == 0) Thread.sleep(2);
        assertThatThrownBy(() -> q.run(() -> "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ENGINE_BUSY"));
        assertThat(q.stats().waitingTotal()).isZero();
        release.countDown();
        holder.join();
        assertThat(q.run(() -> "ok")).isEqualTo("ok");
    }
}
