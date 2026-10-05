package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Splits {@code [0, count)} into chunks and runs them side by side on the calling thread and the pool.
 *
 * <p>The caller does work too, so this does not stall even when the pool is busy with other jobs.
 * <b>Do not call this from a pool thread</b>: it blocks that thread while waiting, reducing parallelism by one.
 */
record Parallel(@Nullable Executor pool, int workers) {

    static final Parallel INLINE = new Parallel(null, 1);

    /** Processes {@code [from, to)}. Returns {@code false} to stop. */
    @FunctionalInterface
    interface Range {
        boolean run(int from, int to);
    }

    /** @return {@code false} if any chunk stopped early */
    boolean forEach(int count, int grain, BooleanSupplier cancelled, Range body) {
        if (pool == null || workers <= 1 || count <= grain) {
            for (int from = 0; from < count; from += grain) {
                if (cancelled.getAsBoolean() || !body.run(from, Math.min(count, from + grain))) {
                    return false;
                }
            }
            return true;
        }
        AtomicInteger cursor = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable worker = () -> {
            while (!stopped.get()) {
                int from = cursor.getAndAdd(grain);
                if (from >= count) {
                    return;
                }
                if (cancelled.getAsBoolean() || !body.run(from, Math.min(count, from + grain))) {
                    stopped.set(true);
                }
            }
        };
        int helpers = Math.min(workers - 1, (count + grain - 1) / grain - 1);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[helpers];
        for (int i = 0; i < helpers; i++) {
            futures[i] = CompletableFuture.runAsync(worker, pool);
        }
        worker.run();
        CompletableFuture.allOf(futures).join();
        return !stopped.get();
    }
}
