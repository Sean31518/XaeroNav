package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the ladder that loosens caps step by step (bridge run length, diving, fall damage) runs <b>even for searches
 * that ended by running out of budget</b>.
 *
 * <p>Previously it only loosened on {@code EXHAUSTED} (all reachable cells in bounds were scoured).
 * That way loosening only kicked in on "terrain small enough to scour", and on large terrain the search kept failing
 * without loosening the caps even though the caps were why there was no way.
 */
@Tag("slow")
class PathfindingExecutorLooseningTest {

    /** Cap on bridge run length (the in-game default). */
    private static final int BRIDGE_RUN_CAP = 30;

    /** A void too wide to ever cross at the cap. */
    private static final int VOID_GAP = 45;

    /**
     * An island large enough that its reachable cells alone use up the expanded-node cap. End islands are on this side,
     * and this difference alone decided whether loosening ran or not.
     */
    private static final int LARGE_ISLAND_RADIUS = 80;

    /**
     * The time limit is looser than in-game (2 seconds). What we want to measure here is "whether the loosening steps run",
     * not execution speed, and we don't want a test whose result depends on CI speed differences.
     *
     * <p><b>Don't reduce the expanded-node cap.</b> We tried lowering it for speed, but
     * {@link #crossesTheSameVoidThroughTheCoarseGuidedChain} splits the budget per leg, so below
     * 190,000 it can't cross (fails at 160,000). Sitting at a value only 1.2x from the cliff means
     * this fails every time the cost model is touched. The slowness is absorbed by {@code @Tag("slow")}.
     */
    private static final SearchLimits LIMITS =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * <b>The shape hit in-game</b> (the End, 2026-08-27). From the cliff edge of a large island, aim across a void wider
     * than the cap. The budget runs out on reachable cells alone, so the search ends with {@code NODE_BUDGET}, and
     * when the ladder was limited to {@code EXHAUSTED} it ended with <b>0 steps, i.e. not a single line shown</b>.
     */
    @Test
    void crossesAVoidWiderThanTheBridgeCapFromTheEdgeOfALargeIsland() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        // Control. That this island ends by running out of budget is the very premise of this test; if it tips to EXHAUSTED,
        // it becomes a no-op that only verifies "scourable terrain" (when the island size or budget changes)
        PathResult bare = new PathfindingExecutor().submitRaw(cells, start, goal, LIMITS).get();
        assertEquals(PathResult.Termination.NODE_BUDGET, bare.termination(),
                "the large island's search did not end by running out of budget, so this terrain isn't a valid control");

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "should cross even from the cliff edge: " + result.termination());
        assertTrue(longestBridgeRun(result) > BRIDGE_RUN_CAP,
                "a bridge beyond the cap was built, so the loosening steps ran: " + longestBridgeRun(result));
    }

    /**
     * <b>This is what actually runs in-game.</b> Leg searches of the coarse waypoint chain only get
     * {@code COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS} (800ms), so if the loosening deadline were taken from the
     * leg's time limit, the first search would use it all up and <b>the loosening steps would never run</b>.
     * Loosening is bounded by the deadline of the whole chain.
     */
    @Test
    void crossesTheSameVoidThroughTheCoarseGuidedChain() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        PathResult result = new PathfindingExecutor()
                .submitCoarseGuided(cells, cells.bounds(), start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "should cross even from a leg search: " + result.termination());
        assertTrue(longestBridgeRun(result) > BRIDGE_RUN_CAP,
                "a bridge beyond the cap was built, so the loosening steps ran: " + longestBridgeRun(result));
    }

    /** While the island is small (the side that reaches {@code EXHAUSTED}), it still crosses as before. */
    @Test
    void stillCrossesFromASmallIslandWhereTheSearchExhaustsInstead() throws Exception {
        FakeCells cells = twoIslands(20);
        BlockPos start = new BlockPos(20, 61, 20);
        BlockPos goal = new BlockPos(20 + VOID_GAP + 5, 61, 20);

        // Control. This is where it splits from the large island above: if both became NODE_BUDGET, the difference is gone
        PathResult bare = new PathfindingExecutor().submitRaw(cells, start, goal, LIMITS).get();
        assertEquals(PathResult.Termination.EXHAUSTED, bare.termination(),
                "the small island's search did not end by scouring, so the large/small control is broken");

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "should have crossed from the small island already: " + result.termination());
    }

    /** With caps strictly enforced, it doesn't cross a void that loosening would cross, and records that the cap was the reason. */
    @Test
    void strictLimitsNeverLoosenAndSayWhy() throws Exception {
        FakeCells cells = twoIslands(20).strictLimits(true);
        BlockPos start = new BlockPos(20, 61, 20);
        BlockPos goal = new BlockPos(20 + VOID_GAP + 5, 61, 20);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertFalse(result.complete(), "crossed by building a bridge beyond the cap");
        assertTrue(longestBridgeRun(result) <= BRIDGE_RUN_CAP, "bridge beyond the cap: " + longestBridgeRun(result));
        assertEquals(PathResult.Termination.EXHAUSTED, result.termination());
        assertTrue(result.limitsHeld(), "the result doesn't record that the cap discarded moves");
    }

    /** If reachable within the cap, it reaches normally even with strict caps and doesn't blame the cap. */
    @Test
    void strictLimitsStillReachWhatFitsWithinThem() throws Exception {
        int narrowGap = BRIDGE_RUN_CAP - 10;
        SearchBounds bounds = new SearchBounds(-8, 20, -8, 40 + narrowGap + 24, 93, 48);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(BRIDGE_RUN_CAP)
                .strictLimits(true);
        for (int z = 0; z <= 40; z++) {
            for (int x = 0; x <= 20; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
            for (int x = 21 + narrowGap; x <= 40 + narrowGap; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        BlockPos start = new BlockPos(20, 61, 20);
        BlockPos goal = new BlockPos(20 + narrowGap + 5, 61, 20);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "should cross within the cap: " + result.termination());
        assertFalse(result.limitsHeld());
    }

    /** Start island -> {@link #VOID_GAP} blocks of void -> island at the same height. The start is at the cliff edge of the start island. */
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
