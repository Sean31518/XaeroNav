package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Puts "silently discard the result if the generation has been overtaken" in one place. The five async
 * completion handlers of live navigation ({@code PathfindingState}) each wrote the same generation check
 * by hand, so this unifies them (TEST-01). Same idea as {@link DiagnosticJobRunner}, but that one has its
 * own executor and generation counter, while this one piggybacks on the {@link AtomicLong} and executor
 * the caller already has.
 *
 * <p>How results get back to the main thread is taken at construction as {@code onMainThread} (callers
 * are expected to pass {@code Minecraft.getInstance()::execute}). The class itself stays independent of
 * Minecraft, so unit tests of generation handling and cancellation propagation need no real client.
 */
public final class GenerationGate {

    private final AtomicLong generation;
    private final Consumer<Runnable> onMainThread;

    public GenerationGate(AtomicLong generation, Consumer<Runnable> onMainThread) {
        this.generation = generation;
        this.onMainThread = onMainThread;
    }

    /**
     * If {@code myGeneration} is still the latest generation when {@code future} completes, calls
     * {@code action} via {@code onMainThread}. If the generation has been overtaken, {@code action} is
     * never called (the result is silently discarded).
     */
    public <T> void whenStillCurrent(CompletableFuture<T> future, long myGeneration,
                                      BiConsumer<T, Throwable> action) {
        future.whenComplete((result, error) -> onMainThread.accept(() -> {
            if (generation.get() != myGeneration) {
                return;
            }
            action.accept(result, error);
        }));
    }
}
