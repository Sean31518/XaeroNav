package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Async execution infrastructure dedicated to the {@code /xaeronav debug} diagnostic commands.
 *
 * <p>Runs on a single background thread. Each call to {@link #begin()} advances the generation and discards waiting
 * jobs from the previous generation, and running jobs receive the generation mismatch as a cooperative-cancel signal;
 * diagnostics never run several at once, and only the latest one ever produces a result. When a single command invocation
 * runs several search stages in sequence, call {@link #begin()} only once at the start and use the same generation number
 * for every later stage (calling {@link #begin()} again midway overtakes yourself and gets treated as cancelled).
 *
 * <p>Use a completely separate instance and thread from live navigation's {@link PathfindingExecutor}.
 * Sharing it would cancel the in-progress live search just by typing a diagnostic command.
 *
 * <p>How results are handed back to the main thread is received at construction as {@code onMainThread}
 * (callers are expected to pass {@code Minecraft.getInstance()::execute}). The class itself is kept
 * independent of Minecraft, so unit tests of generation management and cancellation don't need a real client.
 */
public final class DiagnosticJobRunner {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "xaeronav-diagnostic");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong generation = new AtomicLong();
    private final Consumer<Runnable> onMainThread;

    public DiagnosticJobRunner(Consumer<Runnable> onMainThread) {
        this.onMainThread = onMainThread;
    }

    /**
     * Starts a new diagnostic chain. Advances the generation and discards jobs of the previous generation that haven't started yet from the wait queue.
     *
     * @return the generation number to pass to every subsequent {@link #submit}
     */
    public long begin() {
        long mine = generation.incrementAndGet();
        executor.getQueue().clear();
        return mine;
    }

    /**
     * Runs {@code work} in the background. The {@link BooleanSupplier} passed to {@code work} returns {@code true}
     * if the generation has been overtaken since the call, so it can be passed directly as the cooperative-cancel
     * argument of {@code AStarPathfinder#search} and the like. On completion, if still the latest generation, calls
     * {@code onComplete} via {@code onMainThread} ({@code error} is {@code null} on a normal result, {@code result} is
     * {@code null} on an exception). If the generation has been overtaken, {@code onComplete} is never called (the result is silently discarded).
     *
     * <p>An {@link Exception} thrown by {@code work} is caught here and passed as the second argument of {@code onComplete}.
     * An {@link Error} is an unrecoverable fatal state, so it isn't caught and propagates as-is to the thread's
     * uncaught exception handler ({@link ThreadPoolExecutor} automatically replaces dead workers).
     */
    public <T> void submit(long generationToken, Function<BooleanSupplier, T> work,
                            BiConsumer<T, Throwable> onComplete) {
        BooleanSupplier cancelled = () -> generation.get() != generationToken;
        executor.execute(() -> {
            T result;
            try {
                result = work.apply(cancelled);
            } catch (Exception exception) {
                complete(generationToken, null, exception, onComplete);
                return;
            }
            complete(generationToken, result, null, onComplete);
        });
    }

    private <T> void complete(long generationToken, T result, Throwable error, BiConsumer<T, Throwable> onComplete) {
        if (generation.get() != generationToken) {
            return;
        }
        onMainThread.accept(() -> {
            if (generation.get() != generationToken) {
                return;
            }
            onComplete.accept(result, error);
        });
    }
}
