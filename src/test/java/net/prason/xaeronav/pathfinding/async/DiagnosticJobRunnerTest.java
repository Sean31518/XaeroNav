package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link DiagnosticJobRunner}: single worker, generation-based cancellation, handing back to the main thread on completion.
 *
 * <p>Tests pass {@code Runnable::run} as {@code onMainThread} (runs immediately on the calling background
 * thread). Real callers are expected to pass {@code Minecraft.getInstance()::execute}, but
 * what we want to verify here is the generation check itself, so the actual switch to the main thread is out of scope.
 */
class DiagnosticJobRunnerTest {

    private static final long AWAIT_SECONDS = 5;

    @Test
    void newerGenerationDiscardsTheOlderOnesResultAndSignalsItsCancelFlag() throws InterruptedException {
        AtomicBoolean firstOnCompleteCalled = new AtomicBoolean();
        AtomicBoolean firstSawCancelled = new AtomicBoolean();
        AtomicReference<String> secondResult = new AtomicReference<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        DiagnosticJobRunner runner = new DiagnosticJobRunner(Runnable::run);

        long firstGeneration = runner.begin();
        runner.submit(firstGeneration, cancelled -> {
            firstStarted.countDown();
            awaitOrFail(releaseFirst);
            // By the time it's released, the next generation should already have started; check that the cooperative cancel signal arrived
            firstSawCancelled.set(cancelled.getAsBoolean());
            return "first";
        }, (result, error) -> firstOnCompleteCalled.set(true));

        assertTrue(firstStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "The first job doesn't start");

        long secondGeneration = runner.begin();
        runner.submit(secondGeneration, cancelled -> "second",
                (result, error) -> secondResult.set(result));

        releaseFirst.countDown();

        awaitCondition(() -> secondResult.get() != null);

        assertTrue(firstSawCancelled.get(), "Overtaken by a newer generation, but the cooperative cancel signal didn't arrive");
        assertFalse(firstOnCompleteCalled.get(), "The overtaken first job's result reached the caller");
        assertEquals("second", secondResult.get());
    }

    @Test
    void exceptionFromWorkIsDeliveredAsTheErrorArgument() {
        RuntimeException thrown = new IllegalStateException("boom");
        AtomicReference<String> result = new AtomicReference<>("untouched");
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        DiagnosticJobRunner runner = new DiagnosticJobRunner(Runnable::run);
        long generation = runner.begin();
        runner.<String>submit(generation, cancelled -> {
            throw thrown;
        }, (r, e) -> {
            result.set(r);
            error.set(e);
            done.countDown();
        });

        assertTrue(awaitOrFail(done), "Completion isn't called");
        assertNull(result.get(), "On exception, result should be passed as null");
        assertNotNull(error.get());
        assertEquals(thrown, error.get());
    }

    private static boolean awaitOrFail(CountDownLatch latch) {
        try {
            return latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            Thread.onSpinWait();
        }
    }
}
