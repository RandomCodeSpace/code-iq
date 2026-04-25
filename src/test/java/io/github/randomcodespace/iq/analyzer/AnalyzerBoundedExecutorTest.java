package io.github.randomcodespace.iq.analyzer;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link Analyzer.BoundedExecutor}: the default
 * {@code ExecutorService.close()} can block up to 24 hours waiting for stuck
 * ANTLR parser threads. The wrapper enforces a graceful 10s shutdown followed
 * by a 5s {@code shutdownNow} window.
 */
class AnalyzerBoundedExecutorTest {

    /** Hard upper bound on close() = graceful 10s + force 5s + scheduling slack. */
    private static final long MAX_CLOSE_SECONDS = 18;

    @Test
    void close_completes_within_bounded_window_for_long_running_task() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        Analyzer.BoundedExecutor executor = new Analyzer.BoundedExecutor(delegate);

        AtomicBoolean wasInterrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Future<Void> task = executor.submit(() -> {
            started.countDown();
            try {
                // Far longer than the bounded close() window.
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertNotNull(task);
        assertTrue(started.await(5, TimeUnit.SECONDS), "submitted task should start");

        Instant t0 = Instant.now();
        executor.close();
        Duration elapsed = Duration.between(t0, Instant.now());

        assertTrue(delegate.isShutdown(),
                "delegate must be shutdown after close()");
        assertTrue(elapsed.toSeconds() < MAX_CLOSE_SECONDS,
                "close() must respect bounded shutdown window (max "
                        + MAX_CLOSE_SECONDS + "s), got " + elapsed);
        // shutdownNow should have interrupted the sleeping task.
        assertTrue(wasInterrupted.get(),
                "blocked task must be interrupted by shutdownNow");
    }

    @Test
    void close_is_immediate_when_executor_is_idle() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        Analyzer.BoundedExecutor executor = new Analyzer.BoundedExecutor(delegate);

        Instant t0 = Instant.now();
        executor.close();
        Duration elapsed = Duration.between(t0, Instant.now());

        assertTrue(delegate.isShutdown());
        assertTrue(delegate.isTerminated());
        // Idle close should return well under the graceful window.
        assertTrue(elapsed.toMillis() < 2_000,
                "idle close() should return promptly, got " + elapsed);
    }

    @Test
    void close_propagates_interrupt_to_caller_thread() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        Analyzer.BoundedExecutor executor = new Analyzer.BoundedExecutor(delegate);

        CountDownLatch started = new CountDownLatch(1);
        executor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } catch (InterruptedException ignored) {
                // swallow — we're testing the wrapper, not the task
            }
            return null;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        AtomicBoolean closerInterrupted = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            executor.close();
            closerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        closer.start();
        // Let the closer enter awaitTermination, then interrupt it.
        Thread.sleep(200);
        closer.interrupt();
        closer.join(TimeUnit.SECONDS.toMillis(MAX_CLOSE_SECONDS));

        assertFalse(closer.isAlive(), "closer thread should finish after interrupt");
        assertTrue(delegate.isShutdown(), "interrupt path must still shutdown the delegate");
        assertTrue(closerInterrupted.get(),
                "wrapper must restore the caller's interrupt flag");
    }
}
