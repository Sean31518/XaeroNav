package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the search on <b>the real world save data itself</b>.
 *
 * <p>End island-hopping stayed "can't find a route at all" through four wrong guesses, so this loads the
 * solid-block columns written out by parsing {@code run/saves/test/DIM1/region/r.2.2.mca}
 * ({@code src/test/resources/end_terrain_columns.txt.gz}). It uses the start and intermediate targets from the
 * in-game log as-is, so <b>the same problem as the search failing in-game</b> can be run locally.
 *
 * <p><b>Only things that can be measured nowhere else go here.</b> Each case takes about 8 seconds, so
 * properties determined by synthetic terrain don't belong: the order of the ladder stages is covered by
 * {@code CapStagesTest}, and the budget and {@code Carryover} arithmetic by {@code BlockBudgetTest}, both
 * in milliseconds. What remains is <b>the hole that only appears at large scale</b>: {@code PathNode.placedTotal}
 * is an approximation not included in node identity, so the further the frontier advances, the more placement
 * branches vanish for no reason. In a synthetic 4-wide rift the frontier doesn't extend, so it structurally
 * can't be reproduced.
 */
@Tag("slow")
class RealEndTerrainTest {

    /** Start of the failed search in the in-game log (08:24). */
    private static final BlockPos START = new BlockPos(1233, 57, 1142);
    /** Target of segment 1 in the same log (direct route). */
    private static final BlockPos DIRECT_GOAL = new BlockPos(1288, 57, 1080);

    /**
     * A value that hits the budget-caused hole. Measured with {@code PathfindingExecutor#capStages},
     * <b>only 16-40 burn 600k nodes and end after 6 steps</b> (42 and above, and 8 and below, reach the goal).
     * The middle of the band rather than its edge is taken so that when the cost model moves, the test doesn't
     * drift out of the band and <b>silently miss</b>.
     */
    private static final int BUDGET_INSIDE_THE_BROKEN_BAND = 32;

    /** Number of bridges needed to cross this terrain. */
    private static final int BRIDGES_NEEDED = 43;

    private static FakeCells terrain(int maxBridgeRun, int placedBlockBudget) throws IOException {
        // Match in-game conditions. Fall damage tolerance is 1/3 of full health = 6 points
        // (0 means "never fall", which erases every route dropping down to lower islands)
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(maxBridgeRun)
                .placedBlockBudget(placedBlockBudget)
                .maxFallDamagePoints(6));
    }

    private static PathResult search(int cap, int nodes, int placedBlockBudget, Carryover carried)
            throws IOException {
        SearchLimits limits = new SearchLimits(nodes, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        try {
            return new net.prason.xaeronav.pathfinding.async.PathfindingExecutor()
                    .submit(terrain(cap, placedBlockBudget), START, DIRECT_GOAL, limits, true, 0, carried)
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * <b>The void in this terrain can be crossed.</b> Measured at 195,486 nodes, it doesn't fit in the default
     * budget (100,000); in-game the deep budget (6x) of {@code PathfindingExecutor#submitWithDeepFallback}
     * picks it up. The deep budget runs <b>in parallel</b> with the normal budget, so there's no wait for
     * confirming the failure first.
     *
     * <p>It used to be crossable on the default budget (69,159 nodes). Dropping the "invented weights" from the
     * layer 1 guide and making it a lower bound of the real cost ({@code CoarseRouter#costToGo}) weakened the
     * guide's ability to narrow the search in exchange. <b>A choice in favor of route quality</b>; how much is
     * measured by {@code PathOptimalityTest}.
     *
     * <p>Solving it with a bridge cap of 96 is also checked here (a separate test would pay for the same search again).
     */
    @Test
    void crossesTheVoidWithTheDeepBudget() throws IOException {
        assertFalse(search(96, 150_000, 0, Carryover.NONE).complete(),
                "Reached at 150,000 = the search is lighter than expected. Re-measure the threshold");

        PathResult deep = search(96, 250_000, 0, Carryover.NONE);
        assertTrue(deep.complete(), "Should be crossable with the deep budget: " + deep.termination());
        assertTrue(longestBridgeRun(deep) > 30,
                "Terrain needing a bridge longer than the cap of 30 (which is why the cap was raised): " + longestBridgeRun(deep));
    }

    /**
     * Pins down that <b>what matters is the budget, not the bridge cap</b>. Even tightening the cap to the old
     * default of 30, the relaxation ladder opens and it solves the same way; trying to fix it by tweaking the
     * cap misses. This session actually took that detour once.
     *
     * <p><b>Durations are not compared.</b> It used to pin down by wall clock that "with a cap of 30 the first
     * search is entirely wasted and it takes more than twice as long", but since
     * {@code PathfindingExecutor#FIRST_PASS_PERCENT} narrowed the first search's share, the difference vanished
     * (measured 4129ms vs 4048ms). Wall-clock ratios also swing with how busy the machine is.
     *
     * <p>The basis for the default of 96 is not duration but <b>the measured width of the void</b> (47-81 blocks in the save data).
     */
    @Test
    void theStrictBridgeCapStillSolvesItThroughTheLooseningLadder() throws IOException {
        assertTrue(search(30, 600_000, 0, Carryover.NONE).complete(),
                "Should solve even with a cap of 30 as the relaxation ladder opens");
    }

    /**
     * <b>Island-hopping is guided even when inventory blocks run short.</b>
     *
     * <p>This terrain needs {@link #BRIDGES_NEEDED} bridges. Narrowing below that, <b>only the middle band burned
     * 600k nodes and ended after 6 steps</b>: the further the frontier advances, the more placement branches
     * vanish for no reason, and the search keeps looking for paths other than bridges. The low side gets
     * through because bridges are cut immediately and the search gives up on them; the nasty part of this hole
     * is that <b>"fewer is safer" does not hold</b>.
     *
     * <p>The fix was in {@code PathfindingExecutor#capStages}: when the budget is the cause, a stage dropping the
     * budget is stacked before the other limits. The cause of the in-game user report "only End island-hopping fails".
     */
    @Test
    void crossesTheIslandsEvenWhenBlocksRunShort() throws IOException {
        PathResult result = search(96, 600_000, BUDGET_INSIDE_THE_BROKEN_BAND, Carryover.NONE);

        assertTrue(result.complete(),
                "Island-hopping no longer comes out with budget " + BUDGET_INSIDE_THE_BROKEN_BAND + ": "
                        + result.termination() + " steps=" + result.steps().size());
    }

    /**
     * <b>Island-hopping is guided even when the budget is narrowed across segments.</b>
     *
     * <p>Now that what earlier segments use is carried over ({@link Carryover}), the hole above can be entered
     * <b>without narrowing the budget itself</b>: even with the full {@link #BRIDGES_NEEDED}, if earlier segments
     * used 20, the remaining 23 lands right in the middle of the band. The stage that drops the budget first must
     * work regardless of carryover.
     */
    @Test
    void crossesTheIslandsWhenEarlierSegmentsAlreadySpentTheBudget() throws IOException {
        PathResult result = search(96, 600_000, BRIDGES_NEEDED, new Carryover(0, 20));

        assertTrue(result.complete(),
                "Island-hopping no longer comes out with 20 already used by earlier segments: " + result.termination()
                        + " steps=" + result.steps().size());
    }

    private static int longestBridgeRun(PathResult result) {
        int longest = 0;
        int run = 0;
        for (PathStep step : result.steps()) {
            run = step.bridging() ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
