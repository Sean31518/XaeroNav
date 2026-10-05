package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Loads <b>the exact spot where the user reported "only island-hopping in the End fails"</b> from save data and
 * runs the same search as in-game ({@code run/saves/test/DIM1/region/r.1.2.mca}, x700-1000 z1050-1350).
 *
 * <p><b>Symptom</b>: the long-range route was found (in-game log "No path avoiding void/lava found, so ...")
 * but the layer 3 path came out as only <b>1-4 steps with 0 bridges</b>, repeating "arrived at end" every 0.05 seconds.
 *
 * <p><b>Cause</b>: the player was at the tip of a huge 24339-column island, and the nearest other islands were
 * 99 blocks northeast and 130 east. <b>On the island the heuristic is nearly constant</b>: wherever you are,
 * the goal is across the void and the remaining estimate is "distance to the edge + bridge price", so it barely varies.
 * A weight-1.5 search turns almost breadth-first there and exhausts the budget scouring the island before reaching for a bridge.
 * One bridge block costs as much as 10 blocks of walking, so reaching a 100-block void requires first expanding
 * 1000 blocks' worth of land.
 *
 * <p><b>Measurements</b> (to the island 99 blocks northeast at 839,57,1081, budget 600k nodes):
 *
 * <pre>
 * weight 1.5 with guide -> not reached    weight 2.5 with guide -> reached (198k)
 * weight 2.0 with guide -> not reached    weight 3.0 with guide -> reached (107k)
 * weight 1.5-3.0 <b>without</b> guide -> none reached
 * </pre>
 *
 * <p><b>Without the cost-to-go guide no weight solves it.</b> The guide is what leads to the island's edge;
 * the weight only raises how much it is trusted. The fix was
 * {@code PathfindingExecutor#retryGreedier} (when the budget burns out without reaching, retry with a higher weight).
 *
 * <p>If this fails, the symptom has come back.
 */
@Tag("slow")
class PlayerAreaEndReproTest {

    /** The current position from the in-game log. The northern tip of the 24339-column island (x700-877 z1118-1350). */
    private static final BlockPos PLAYER = new BlockPos(769, 51, 1151);

    /** The in-game deep search ({@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR} = 6x regular). */
    private static final SearchLimits DEEP = new SearchLimits(600_000, 15_000, 1.5);

    private static FakeCells terrain() throws IOException {
        // Matches the in-game config (run/config/xaeronav-client.toml).
        // The player is in creative, so placing is allowed and the inventory budget is unlimited
        return TerrainFixture.load("/end_player_area.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0)
                .avoidRiskyJumps(true));
    }

    private static String describe(PathResult r) {
        long bridges = r.steps().stream().filter(PathStep::bridging).count();
        return String.format("%s steps=%d bridges=%d nodes=%d",
                r.complete() ? "reached" : r.termination(), r.steps().size(), bridges, r.expandedNodes());
    }

    /** The island the user reported. All three tests below target the path to it. */
    private static final BlockPos NEAREST_ISLAND = new BlockPos(839, 0, 1081);

    private static BlockPos onGround(FakeCells terrain, BlockPos p) {
        return TerrainFixture.onGround(terrain, terrain.bounds(), p);
    }

    /**
     * Crossing to nearby islands under the same conditions as the in-game deep search.
     *
     * <p>{@link #NEAREST_ISLAND} isn't included here: the reduced-budget test below runs <b>the same search
     * with a tighter budget</b>, so including it here would just pay for the same path twice.
     */
    @Test
    void crossesToTheNeighbouringIslands() throws Exception {
        FakeCells terrain = terrain();
        System.out.printf("%n=== island-hopping (start=%s, same conditions as in-game deep search) ===%n", PLAYER);
        // 130 east / 163 northeast. Both have a void longer than the bridge cap of 96 in between
        for (int[] t : new int[][] {{899, 1151}, {912, 1072}}) {
            BlockPos goal = onGround(terrain, new BlockPos(t[0], 0, t[1]));
            double dist = Math.hypot(goal.getX() - PLAYER.getX(), goal.getZ() - PLAYER.getZ());
            PathResult r = new PathfindingExecutor().submit(terrain(), PLAYER, goal, DEEP, true, 0).get();
            System.out.printf("  %-12s dist %-5.0f %s%n", goal.getX() + "," + goal.getZ(), dist, describe(r));
            assertTrue(r.complete(), "can't reach " + goal + ": " + describe(r));
            assertTrue(r.steps().stream().anyMatch(PathStep::bridging),
                    goal + " crossed without bridging, so the terrain isn't a valid control");
        }
    }

    /**
     * <b>Reachable even with a tighter budget than the deep search.</b> With two thirds of {@link #DEEP}'s node budget
     * it can bridge across to {@link #NEAREST_ISLAND}: a guard that checks it's solved with margin.
     * If this fails, it's the first thing to get stuck in-game when budget or time gets cut.
     *
     * <p>What's reduced is the <b>node count</b>, not wall-clock time. Each {@code retryGreedier} retry is bounded by
     * the remaining time, so reducing by time makes the result depend on the CI runner's speed (CI actually failed because of that).
     */
    @Test
    void crossesUnderABudgetTighterThanTheDeepSearch() throws Exception {
        FakeCells terrain = terrain();
        BlockPos goal = onGround(terrain, NEAREST_ISLAND);
        SearchLimits tight = new SearchLimits(400_000, 15_000, 1.5);

        long began = System.currentTimeMillis();
        PathResult r = new PathfindingExecutor().submit(terrain, PLAYER, goal, tight, true, 0).get();
        System.out.printf("%n=== reduced budget (400k nodes) ===%n  %s (%dms)%n",
                describe(r), System.currentTimeMillis() - began);
        assertTrue(r.complete(), "can't reach with the reduced budget: " + describe(r));
        assertTrue(r.steps().stream().anyMatch(PathStep::bridging),
                "crossed without bridging, so the terrain isn't a valid control: " + describe(r));
    }

    /**
     * <b>Control.</b> Without raising the weight, the same budget doesn't reach. If this breaks,
     * we'd be verifying terrain solvable even without {@code retryGreedier}, and the test would be a no-op.
     */
    @Test
    void theSameSearchFailsWithoutRaisingTheWeight() throws Exception {
        FakeCells terrain = terrain();
        BlockPos goal = onGround(terrain, NEAREST_ISLAND);

        PathResult bare = new PathfindingExecutor().submitRaw(terrain, PLAYER, goal, DEEP).get();

        System.out.printf("%n=== control (weight stays 1.5, no retry) ===%n  %s%n", describe(bare));
        assertTrue(!bare.complete(),
                "reaches even at weight 1.5, so the effect of retryGreedier can't be checked on this terrain: "
                        + describe(bare));
    }
}
