package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.OwnerTrackingCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link PathfindingExecutor#submitWithDeepFallback}: the path that tries the regular and deep budgets in parallel.
 *
 * <p>Serially (confirming the regular budget failed, then submitting the deep budget on the next tick), the two waits
 * add up (for measurements see the javadoc of {@link net.prason.xaeronav.client.PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}).
 * Here we check <b>correctness</b>: terrain reachable within the regular budget returns the regular result, terrain
 * reachable only with the deep budget returns the deep result, terrain reachable with neither returns a failure,
 * and both are cancelled when a new request arrives.
 *
 * <p>The cases checking which result is picked pass one {@link FakeCells} to both searches. {@code FakeCells}
 * doesn't change during the search, so sharing is fine; a real {@code ChunkView} has a cell cache and can't be
 * shared, which is what {@link #givesEachParallelSearchItsOwnView()} checks.
 *
 * <p>It uses the same large island (diameter 80) as {@code PathfindingExecutorLooseningTest}, so each case takes
 * a few seconds. {@code @Tag("slow")} keeps it out of the default {@code test}.
 */
@Tag("slow")
class PathfindingExecutorDeepFallbackTest {

    private static final int BRIDGE_RUN_CAP = 30;
    private static final int VOID_GAP = 45;
    private static final int LARGE_ISLAND_RADIUS = 80;

    /**
     * A smallish cap at which the regular budget ends with {@code NODE_BUDGET}. <b>Measured on this terrain: budget runs out at 20k,
     * reaches at 30k</b> (28,408 nodes), so this takes the low side of the band.
     *
     * <p>When the cost model changes, the required node count changes too. <b>If this falls to the "reaches anyway" side, the test
     * goes green without ever exercising the fallback to the deep budget</b>, so a control
     * {@code assertFalse} is placed at the top of each case.
     */
    private static final SearchLimits NORMAL =
            new SearchLimits(20_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** A large cap that can exhaust the same terrain. */
    private static final SearchLimits DEEP =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** Start island -> {@link #VOID_GAP} blocks of void -> island at the same height. Same terrain as {@code PathfindingExecutorLooseningTest}. */
    private static FakeCells twoIslands(int islandRadius) {
        SearchBounds bounds = new SearchBounds(-8, 20, -8,
                islandRadius * 2 + VOID_GAP + 16, 93, islandRadius * 2 + 8);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(BRIDGE_RUN_CAP);
        for (int x = 0; x <= islandRadius; x++) {
            for (int z = 0; z <= islandRadius * 2; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        for (int x = islandRadius + VOID_GAP + 1; x <= islandRadius * 2 + VOID_GAP + 8; x++) {
            for (int z = 0; z <= islandRadius * 2; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /**
     * Terrain where the regular budget ends with {@code NODE_BUDGET} but the deep budget can cross. The parallel fallback
     * must adopt the deep result and cross all the way.
     */
    @Test
    void fallsBackToTheDeepBudgetWhenTheNormalOneRunsOut() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        // Control. That the regular budget alone really can't reach is the very premise of this test
        PathResult normalOnly = new PathfindingExecutor().submit(cells, start, goal, NORMAL, true, 0).get();
        assertFalse(normalOnly.complete(),
                "reaches with the regular budget alone, so the effect of falling back to the deep budget can't be checked: "
                        + normalOnly.termination());

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertTrue(result.complete(), "should cross when falling back to the deep budget: " + result.termination());
    }

    /** On terrain reachable within the regular budget, return that result directly (without dutifully waiting for the deep one). */
    @Test
    void usesTheNormalResultWhenItAlreadyReachesTheGoal() throws Exception {
        FakeCells cells = twoIslands(10);
        BlockPos start = new BlockPos(10, 61, 10);
        BlockPos goal = new BlockPos(10 + VOID_GAP + 5, 61, 10);

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertTrue(result.complete(), "should have crossed from the small island already: " + result.termination());
    }

    /** On terrain reachable with neither budget, return the regular budget's termination reason as-is. */
    @Test
    void reportsTheNormalTerminationWhenNeitherBudgetReachesTheGoal() throws Exception {
        // Set a cap that can't cross the void itself (not 0=unlimited, but no bridging allowed),
        // making terrain with truly no way even with the deep budget
        SearchBounds bounds = new SearchBounds(-8, 20, -8, 40, 93, 40);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        // The goal island isn't left isolated as-is, i.e. no reachable cell gets to the goal at all
        BlockPos start = new BlockPos(5, 61, 5);
        BlockPos goal = new BlockPos(35, 61, 35);

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertFalse(result.complete(), "reached an isolated goal: " + result.termination());
    }

    /**
     * The two searches <b>each occupy a separate view</b>.
     *
     * <p>Back when the same {@link net.prason.xaeronav.pathfinding.world.CellSource} was passed to both, in-game the
     * {@code ChunkView} cell cache ({@code Long2LongOpenHashMap}) was modified concurrently and threw
     * {@code ArrayIndexOutOfBoundsException}. Whether the exception occurs depends on timing, so
     * here we directly check <b>that each view is touched by only one thread</b>.
     *
     * <p>The key is checking as far as "the two views have different owners": if the deep search weren't actually running,
     * zero violations would hold trivially, and the guard would be a no-op.
     */
    @Test
    void givesEachParallelSearchItsOwnView() throws Exception {
        // Terrain the regular budget can't reach. The deep search runs to the end, so the two really overlap
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        OwnerTrackingCells normalView = new OwnerTrackingCells(cells);
        OwnerTrackingCells deepView = new OwnerTrackingCells(cells);

        new PathfindingExecutor()
                .submitWithDeepFallback(normalView.view(), deepView.view(), start, goal, NORMAL, DEEP, true, 0)
                .get();

        assertNull(normalView.intruder(), "a thread other than the owner touched the regular budget's view");
        assertNull(deepView.intruder(), "a thread other than the owner touched the deep budget's view");
        assertNotNull(normalView.owner(), "the regular budget's view was never used");
        assertNotNull(deepView.owner(), "the deep budget's view was never used, so it didn't run in parallel");
        assertNotSame(normalView.owner(), deepView.owner(),
                "the two searches ran on the same thread, so the parallel fallback isn't working");
    }

    /** When a new request arrives, both the regular and deep searches are cancelled. */
    @Test
    void cancelsBothSearchesWhenSupersededByANewRequest() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        PathfindingExecutor executor = new PathfindingExecutor();
        var superseded = executor.submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0);
        // The next submit to the same executor cancels the previous job (both regular and deep)
        PathResult next = executor.submit(cells, start, goal, NORMAL, true, 0).get();

        assertTrue(superseded.isCancelled() || superseded.isCompletedExceptionally(),
                "the previous request was not cancelled");
        try {
            superseded.get();
        } catch (CancellationException expected) {
            // As expected
        } catch (ExecutionException e) {
            throw new AssertionError("ended with a different exception, not cancellation", e);
        }
        // The new request itself completes normally (only the previous job was cancelled)
        assertEquals(PathResult.Termination.NODE_BUDGET, next.termination());
    }
}
