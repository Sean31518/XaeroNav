package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>Reproduces the "bridge across the valley and cut straight through" route with saved data from the real End at
 * (2481,57,-488).</b>
 *
 * <p>The terrain from the user report "from a big island to a small island and back to the same big island" is here:
 * a 6-block-wide void due east of the player, beyond it stepping stones only 3 columns wide (x2496..2500), and beyond
 * that the eastern landmass. <b>The east and west landmasses connect at z≈-462</b>, so crossing the stepping stones
 * means "crossing back to the same island".
 *
 * <p><b>Layer 1 is innocent.</b> Counted with 8-neighbors, the landmass in this range is a single 228-cell island, and
 * the valley itself is not visible ({@code LiveCoarseSampler} marks a chunk LAND if even one column has a floor).
 * The small-island surcharge ({@code SMALL_ISLAND_PENALTY} in {@link CoarseRouter}) cannot fire either.
 *
 * <p><b>The real cause was the greed of weighted A*.</b> {@code f = g + w·h} systematically dislikes routes that first
 * move away from the goal. The detour goes 28 blocks south before heading east, so h increases for a while.
 * The cost model said the detour was cheaper all along (bridge route 596.3 ticks / detour 548.2 ticks).
 */
@Tag("slow")
class EndDetourReproTest {

    /** Where the user reported the symptom. */
    private static final BlockPos PLAYER = new BlockPos(2481, 57, -488);

    /** Due east across the valley. The detour passes through z≈-462 to the south. */
    private static final BlockPos ACROSS_THE_CHASM = new BlockPos(2520, 0, -488);

    private static final SearchLimits LIMITS =
            new SearchLimits(600_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static FakeCells terrain() throws IOException {
        // Match the in-game defaults (maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96, fall tolerance 6)
        return TerrainFixture.load("/end_terrain_columns_2481.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static BlockPos onGround(FakeCells terrain, BlockPos p) {
        return TerrainFixture.onGround(terrain, terrain.bounds(), p);
    }

    private static PathResult solve(FakeCells terrain, BlockPos start, BlockPos goal) throws Exception {
        return new PathfindingExecutor().submit(terrain, start, goal, LIMITS, true, 0).get();
    }

    private static double totalCost(PathResult r) {
        double total = 0;
        for (PathStep step : r.steps()) {
            total += step.cost();
        }
        return total;
    }

    /** Number of steps over the void. With {@code jump}, counts only jumps; otherwise only bridges. */
    private static long overTheVoid(PathResult r, boolean jump) {
        return r.steps().stream()
                .filter(s -> s.risk() == PathRisk.VOID_BELOW)
                .filter(s -> jump ? s.movement() == MovementType.JUMP : s.bridging())
                .count();
    }

    /**
     * <b>The core of symptom 2.</b> If there is a detour, do not bridge straight across the void.
     *
     * <p>Before the fix, it built a 15-block bridge (7 of them over the void) and crossed in 40 steps.
     *
     * <p><b>Also checks that it is not "passing by chance".</b> Comparing with a setting that cannot place blocks pins
     * down that the detour really exists and is <b>also cheaper in the cost model</b>. This is the key point: the route
     * that bridges straight across is <b>more expensive</b> than the detour. The pricing was right; the search was just
     * greedy. If this inequality flips, the diagnosis of the cause has changed with it.
     *
     * <p><b>The tolerance is relative.</b> Allowing placement adds branches for {@code addBridge} and changes the
     * expansion order, so weighted A* may settle on a route a few ticks more expensive. What we want to guard here is
     * "it did not fall back to the bridge route"; tightening with an absolute value would pick up search-order jitter
     * every time the level of route costs changes.
     */
    @Test
    void walksAroundTheChasmInsteadOfBridgingIt() throws Exception {
        FakeCells withBlocks = terrain();
        BlockPos start = onGround(withBlocks, PLAYER);
        BlockPos goal = onGround(withBlocks, ACROSS_THE_CHASM);

        PathResult chosen = solve(withBlocks, start, goal);
        assertTrue(chosen.complete(), "should arrive by going around: " + chosen.termination());
        assertEquals(0, overTheVoid(chosen, false),
                "bridged over the void even though a detour exists: steps=" + chosen.steps().size()
                        + " total=" + totalCost(chosen));

        FakeCells withoutBlocks = terrain().canPlaceBlocks(false);
        PathResult walking = solve(withoutBlocks, start, goal);
        assertTrue(walking.complete(), "should be able to go around even without blocks: " + walking.termination());
        assertTrue(totalCost(chosen) <= totalCost(walking) * 1.01,
                "the search allowed to place blocks picked a route more than 1% more expensive than the walk-only route: "
                        + totalCost(chosen) + " vs " + totalCost(walking));
    }

    /**
     * <b>Symptom 1.</b> Routes heading southwest over the same terrain. Before the fix, several of these jumped over the
     * void, because the relaxation ladder unconditionally enabled {@code allowRiskyJumps} and the enabled jumps had no
     * hazard charge.
     */
    @Test
    void neverJumpsOverTheVoidWhereThereIsAWayAround() throws Exception {
        FakeCells terrain = terrain();
        BlockPos start = onGround(terrain, PLAYER);
        for (BlockPos raw : List.of(
                new BlockPos(2460, 0, -300),
                new BlockPos(2480, 0, -320),
                new BlockPos(2500, 0, -340),
                new BlockPos(2500, 0, -300),
                new BlockPos(2440, 0, -580))) {
            BlockPos goal = onGround(terrain, raw);
            PathResult result = solve(terrain, start, goal);
            assertEquals(0, overTheVoid(result, true),
                    goal.toShortString() + " route jumped over the void");
        }
    }
}
