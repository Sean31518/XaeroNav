package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link GenerationGate}: results overtaken by a newer generation are silently dropped, and the latest result
 * is delivered. Verifies, without a real client, the same shape as the generation counter the live navigation
 * ({@code PathfindingState}) shares in five places (TEST-01).
 *
 * <p>Tests pass {@code Runnable::run} as {@code onMainThread}
 * (same reason as {@link DiagnosticJobRunnerTest}: what we want to verify is the generation check itself).
 */
class GenerationGateTest {

    private static final long AWAIT_SECONDS = 5;

    @Test
    void aStaleGenerationsResultIsDiscardedEvenAfterItCompletes() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        GenerationGate gate = new GenerationGate(generation, Runnable::run);
        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch actionCalled = new CountDownLatch(1);

        CompletableFuture<String> staleFuture = new CompletableFuture<>();
        long staleGeneration = generation.get();
        gate.whenStillCurrent(staleFuture, staleGeneration, (result, error) -> {
            delivered.set(result);
            actionCalled.countDown();
        });

        // The generation advances before completion (replaced by a new request)
        generation.incrementAndGet();
        staleFuture.complete("stale");

        // action should not be called. Wait with a timeout to confirm it is "not called"
        assertEquals(false, actionCalled.await(300, TimeUnit.MILLISECONDS),
                "action for a result overtaken by a newer generation was called");
        assertNull(delivered.get());
    }

    @Test
    void theCurrentGenerationsResultIsDeliveredToTheAction() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        GenerationGate gate = new GenerationGate(generation, Runnable::run);
        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch actionCalled = new CountDownLatch(1);

        CompletableFuture<String> future = new CompletableFuture<>();
        gate.whenStillCurrent(future, generation.get(), (result, error) -> {
            delivered.set(result);
            actionCalled.countDown();
        });

        future.complete("fresh");

        assertEquals(true, actionCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "result of the latest generation was not delivered");
        assertEquals("fresh", delivered.get());
    }

    @Test
    void theActionRunsThroughTheInjectedOnMainThreadConsumer() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        AtomicReference<Thread> ranOnThread = new AtomicReference<>();
        CountDownLatch mainThreadInvoked = new CountDownLatch(1);
        // Check that onMainThread is actually used: even when called from another thread,
        // it must always go through the Consumer registered here
        GenerationGate gate = new GenerationGate(generation, runnable -> {
            mainThreadInvoked.countDown();
            runnable.run();
        });
        CompletableFuture<String> future = new CompletableFuture<>();
        gate.whenStillCurrent(future, generation.get(), (result, error) -> ranOnThread.set(Thread.currentThread()));

        future.complete("ok");

        assertEquals(true, mainThreadInvoked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onMainThread was not called");
        assertEquals(Thread.currentThread(), ranOnThread.get());
    }
}
