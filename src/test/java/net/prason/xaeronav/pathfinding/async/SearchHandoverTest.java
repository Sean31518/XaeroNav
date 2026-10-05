package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * Verifies live navigation's asynchronous handover by laying it out in time order with the same parts as
 * {@code PathfindingState} (one {@link PathfindingExecutor}, a shared generation, {@link GenerationGate}) and the same steps.
 *
 * <p>{@code PathfindingState} itself doesn't run without a Minecraft client, so only the steps of the state
 * transitions that "advance the generation, discard the search, resubmit" are reproduced here. The steps match
 * {@code PathfindingState#setGoal} ({@code clear}), {@code clear}, and the takeoff and landing branches.
 *
 * <p>The old search is {@link #slow}; left alone it runs for tens of seconds. What's checked is not whether it arrives, but <b>whether it stops</b>.
 */
class SearchHandoverTest {

    private static final long AWAIT_SECONDS = 10;

    /** It takes much longer than this for the slow search to finish on its own. */
    private static final SearchLimits LONG = new SearchLimits(10_000_000, 120_000,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final SearchLimits SHORT = new SearchLimits(100_000, 5_000,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final BlockPos START = new BlockPos(5, 61, 5);

    /** On top of a platform cut off from the floor by a 6-block gap. Can't jump it and can't bridge, so unreachable. */
    private static final BlockPos UNREACHABLE = new BlockPos(215, 61, 100);

    private static final BlockPos NEAR = new BlockPos(20, 61, 5);

    private final AtomicLong generation = new AtomicLong();
    private final GenerationGate gate = new GenerationGate(generation, Runnable::run);
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    /** Control. Without clearing, it keeps reading; if this breaks, the tests below verify nothing. */
    @Test
    void theSlowSearchKeepsRunningUnlessCleared() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow running = slow();
        request(executor, running.view(), UNREACHABLE, "control");
        running.awaitReads();

        long before = running.reads().get();
        Thread.sleep(2_000);
        assertTrue(running.reads().get() > before, "The slow search finishes right away = can't tell whether it stopped");
        executor.cancelAll();
    }

    /** Changed the destination mid-search. The old search's result isn't delivered, and the old search doesn't make the new one wait. */
    @Test
    void changingTheGoalMidSearchDeliversOnlyTheNewRoute() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow old = slow();
        CompletableFuture<PathResult> stale = request(executor, old.view(), UNREACHABLE, "old goal");

        CompletableFuture<PathResult> fresh = request(executor, field(), NEAR, "new goal");

        PathResult result = fresh.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.complete(), "Doesn't reach the new goal: " + result.termination());
        assertTrue(stale.isDone(), "The old search is still around after the new search");
        assertEquals(List.of("new goal"), delivered);
    }

    /** Cleared the guidance (logout, dimension change, arrival). The running search stops reading the view. */
    @Test
    void clearingStopsTheRunningSearchFromReadingTheWorld() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow running = slow();
        CompletableFuture<PathResult> stale = request(executor, running.view(), UNREACHABLE, "before clear");
        running.awaitReads();

        clear(executor);

        assertTrue(running.settles(), "The search keeps reading chunks after clearing");
        assertTrue(stale.isDone());
        assertTrue(delivered.isEmpty(), "A path came back after clearing: " + delivered);
    }

    /** A search running the deep budget in parallel also stops on both threads when cleared. */
    @Test
    void clearingAlsoStopsTheParallelDeepSearch() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow normal = slow();
        Slow deep = slow();
        long myGeneration = generation.incrementAndGet();
        CompletableFuture<PathResult> stale = executor.submitWithDeepFallback(normal.view(), deep.view(),
                START, UNREACHABLE, LONG, LONG, false, 0);
        gate.whenStillCurrent(stale, myGeneration, (result, error) -> delivered.add("before clear"));
        normal.awaitReads();
        deep.awaitReads();

        clear(executor);

        assertTrue(normal.settles(), "The normal-budget search doesn't stop");
        assertTrue(deep.settles(), "The deep-budget search doesn't stop");
        assertTrue(delivered.isEmpty(), "A path came back after clearing: " + delivered);
    }

    /**
     * Took off during a search while walking, landed, and recomputed. The pre-takeoff search stops at takeoff,
     * and only the post-landing result is delivered.
     */
    @Test
    void takingOffAndLandingDeliversOnlyTheRouteFromAfterLanding() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow beforeTakeoff = slow();
        request(executor, beforeTakeoff.view(), UNREACHABLE, "before takeoff");
        beforeTakeoff.awaitReads();

        generation.incrementAndGet();
        executor.cancelAll();
        assertTrue(beforeTakeoff.settles(), "The walking search keeps running after takeoff");

        CompletableFuture<PathResult> afterLanding = request(executor, field(), NEAR, "after landing");

        assertTrue(afterLanding.get(AWAIT_SECONDS, TimeUnit.SECONDS).complete());
        assertEquals(List.of("after landing"), delivered);
    }

    /** Same steps as {@code PathfindingState#recalculate}: advance the generation, submit, and receive in that generation. */
    private CompletableFuture<PathResult> request(PathfindingExecutor executor, CellSource view, BlockPos goal,
                                                  String label) {
        long myGeneration = generation.incrementAndGet();
        CompletableFuture<PathResult> future = executor.submit(view, START, goal, LONG, false);
        gate.whenStillCurrent(future, myGeneration, (result, error) -> {
            if (error == null) {
                delivered.add(label);
            }
        });
        return future;
    }

    /** Same steps as {@code PathfindingState#clear}. */
    private void clear(PathfindingExecutor executor) {
        generation.incrementAndGet();
        executor.cancelAll();
    }

    /** A 200×200 floor and a platform separated by a 6-block gap. */
    private static FakeCells field() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 230, 90, 204));
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        for (int x = 206; x < 226; x++) {
            for (int z = 90; z < 110; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /** A view that waits a little on every cell read. It takes tens of seconds to exhaust the floor. */
    private static Slow slow() {
        FakeCells cells = field();
        AtomicLong reads = new AtomicLong();
        CellSource view = (CellSource) Proxy.newProxyInstance(CellSource.class.getClassLoader(),
                new Class<?>[] {CellSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cell")) {
                        reads.incrementAndGet();
                        LockSupport.parkNanos(50_000);
                    }
                    try {
                        return method.invoke(cells, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new Slow(view, reads);
    }

    private record Slow(CellSource view, AtomicLong reads) {

        void awaitReads() throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (reads.get() < 1_000) {
                assertTrue(System.nanoTime() < until, "The search doesn't start");
                Thread.sleep(10);
            }
        }

        /** Whether reads stopped. If not, give up after {@link #AWAIT_SECONDS}. */
        boolean settles() throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            long last = reads.get();
            while (System.nanoTime() < until) {
                Thread.sleep(300);
                long now = reads.get();
                if (now == last) {
                    return true;
                }
                last = now;
            }
            return false;
        }
    }
}
