package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Checks two properties of {@link PathfindingExecutor#submitCoarseGuided} (layer 3's
 * countermeasure against local obstacles).
 *
 * <ol>
 * <li>On terrain where coarse waypoints help, it reaches the goal even where a single detailed search runs out of budget
 * <li>On terrain where a coarse waypoint points at an unreachable spot, the chain doesn't break down and still reaches the goal
 * </ol>
 *
 * <p>Layer 3 isn't always an advantage. On flat ground the expanded node counts are about the same (measured: direct 201 / chain 203),
 * so splitting itself gains nothing; it reaches slightly farther only because each segment gets a fresh budget.
 */
class PathfindingExecutorCoarseGuidedTest {

    /**
     * A long lake running sideways. The heuristic estimates the remainder at land sprint speed, so it strongly favors going straight = swimming,
     * but swimming actually costs about 1.56x, and by that margin the detailed search expands needlessly wide over the water. The coarse map
     * recognizes the lake as {@code WATER} and places waypoints detouring north, so each segment's search can trace a short path over land only.
     *
     * <p>The cost-to-go guide is explicitly disabled. What this test wants to verify is "the gain from waypoint splitting
     * itself"; if the guide added in stage 4 kicks in, the direct search also reaches the goal on this budget
     * (because the guide itself returns estimates that avoid the lake), and the premise of the comparison breaks. The guide's effect
     * is checked in a separate test.
     */
    @Test
    void reachesTheGoalOnABudgetThatDefeatsASingleSearch() throws Exception {
        SearchBounds bounds = new SearchBounds(-16, 0, -112, 216, 100, 112);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = -16; x <= 216; x++) {
            for (int z = -112; z <= 112; z++) {
                cells.set(x, 62, z, FakeCells.STONE);
            }
        }
        for (int x = 60; x <= 160; x++) {
            for (int z = -24; z <= 24; z++) {
                cells.set(x, 63, z, FakeCells.WATER);
            }
        }
        BlockPos start = new BlockPos(0, 63, 0);
        BlockPos goal = new BlockPos(200, 63, 0);
        // A budget too small for the direct search (measured: needs 635) but enough for the chain (reaches even at 200)
        SearchLimits limits = new SearchLimits(400, 30_000, 1.5);

        PathfindingExecutor executor = new PathfindingExecutor();
        // The control uses the <b>raw search</b> (submitRaw). submit goes through the dead-end-avoidance retries
        // (relaxing limits, retryGreedier raising the weight), so it effectively gets more out of the same budget;
        // the control would reach the goal and we could no longer say "it reached because of the chain"
        PathResult direct = executor.submitRaw(cells, start, goal, limits).get(60, TimeUnit.SECONDS);
        assertFalse(direct.complete(), "The direct search reaches the goal on this budget, so the chain's gain can't be verified");

        PathResult chain = executor.submitCoarseGuided(cells, bounds, start, goal, limits, false)
                .get(60, TimeUnit.SECONDS);

        assertReachesGoal(chain, goal);
    }

    /**
     * Enabling the cost-to-go guide ({@code XaeroNavConfig#costToGoGuideEnabled}) makes even the <b>direct search</b>
     * (no waypoint splitting) more likely to reach the goal on terrain where a wall forces a large detour.
     * The geometric straight-line heuristic doesn't know the wall exists, so it pays for expanding toward the wall first
     * and then backtracking. Layer 1's coarse map roughly captures the wall as per-chunk relief,
     * so combining its estimate reduces the backtracking.
     *
     * <p>Not tested on the same lake terrain as {@code reachesTheGoalOnABudgetThatDefeatsASingleSearch}.
     * For the lake, the cost difference of detouring is small (crossing the water isn't fatally expensive), and the coarse map's
     * chunk granularity instead becomes noise; measurements showed that on that terrain combining the guide actually increases
     * the expansion count (1997->2375). Note that this is a conditional improvement that helps on terrain like a wall, where you
     * "can't reach it without detouring, or it costs far more even if you do".
     */
    @Test
    void costToGoGuideLetsADirectSearchSucceedOnTheSameBudgetThatDefeatedItWithoutTheGuide() throws Exception {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);
        // The raw direct search needs 7932 nodes (the geometric straight-line distance doesn't know the wall exists, so it
        // expands into the z<64 side and then backtracks). Layer 1 roughly captures the wall as relief rather than NO_DATA
        // and points to the detour side early, so with the guide it reaches the goal in 6921 nodes.
        //
        // The budget has to sit between the two, so re-measure after any change that alters the search's expansion order.
        // {@code AStarPathfinder#LINE_TIE_BREAK_TICKS} (quantizes f into steps and only breaks ties) barely
        // moves this (7840/6927 when disabled); an implementation adding directly to f inflated it to 9013/8474,
        // and both failed on this budget
        SearchLimits limits = new SearchLimits(7_200, 30_000, 1.5);

        // The control uses the raw search (same reason as reachesTheGoalOnABudgetThatDefeatsASingleSearch above)
        PathResult unguided = new PathfindingExecutor().submitRaw(wallCells(), start, goal, limits)
                .get(60, TimeUnit.SECONDS);
        assertFalse(unguided.complete(), "Reaches the goal on this budget even without the guide, so there's nothing to compare");

        PathResult guided = new PathfindingExecutor().submit(wallCells(), start, goal, limits, true)
                .get(60, TimeUnit.SECONDS);

        assertTrue(guided.complete(), "The direct search with the guide didn't reach the goal on this budget");
    }

    /** An undiggable wall open only at {@code z&gt;=64}. The detour is 64+ blocks of sideways movement. */
    private static final SearchBounds WALL_BOUNDS = new SearchBounds(-16, 0, -112, 216, 100, 112);

    private static FakeCells wallCells() {
        FakeCells cells = FakeCells.empty(WALL_BOUNDS);
        for (int x = -16; x <= 216; x++) {
            for (int z = -112; z <= 112; z++) {
                cells.set(x, 63, z, FakeCells.STONE);
            }
        }
        for (int x = 96; x <= 111; x++) {
            for (int z = -112; z < 64; z++) {
                for (int y = 64; y <= 80; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /**
     * A vertical wall filling whole chunks. The coarse map can't represent "impassable" except for lava, and this wall
     * looks like a flat plateau with {@code min=max}, i.e. zero relief, so a waypoint lands on top of the wall, an unreachable spot.
     * Unreachable waypoints are skipped in favor of the next one, so the last segment (the actual goal) ends up with the same
     * result as the direct search. Back when a single unreachable waypoint discarded the whole chain, it failed even with 10x the budget.
     */
    @Test
    void skipsUnreachableWaypointsInsteadOfAbandoningTheChain() throws Exception {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);

        PathResult chain = new PathfindingExecutor()
                .submitCoarseGuided(wallCells(), WALL_BOUNDS, start, goal, new SearchLimits(10_000, 30_000, 1.5))
                .get(60, TimeUnit.SECONDS);

        assertReachesGoal(chain, goal);
    }

    private static void assertReachesGoal(PathResult result, BlockPos goal) {
        assertTrue(result.complete(), "The waypoint chain didn't reach the goal");
        List<PathStep> steps = result.steps();
        PathStep last = steps.get(steps.size() - 1);
        assertEquals(goal.getX(), last.pos().getX(), "The end of the path doesn't reach the goal");
        assertEquals(goal.getZ(), last.pos().getZ(), "The end of the path doesn't reach the goal");
        // The path must not jump at segment seams (a wrong join makes it jump here)
        for (int i = 1; i < steps.size(); i++) {
            BlockPos previous = steps.get(i - 1).pos();
            BlockPos current = steps.get(i).pos();
            assertTrue(previous.distSqr(current) <= 4.0,
                    "The path jumps at a segment seam: " + previous + " -> " + current);
        }
    }
}
