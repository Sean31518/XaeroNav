package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Guards a real route a user was stuck on for a week. <b>Not being able to draw this line was the main Nether
 * symptom</b> ("the line breaks off and stops at the same place"), so if this fails, the 3D coarse layer is broken.
 *
 * <p>Settings follow the user's save from 2026-09-08 (fall tolerance 0, lava bridge 30).
 * What diagnostics confirmed about the current implementation:
 *
 * <pre>
 * Layer 1's 2.5D guide           0 steps (can't move a single step from the start)
 * No guide, whole world visible  not reached after 3 million nodes
 * 3D coarse layer                reached
 * </pre>
 *
 * <p>It must also be reached on a sparse map (visited chunks cut down to 25-42%). This is the only check that
 * the destination-anchor hardening in {@code VoxelCostToGo} is working. Before the hardening, the starting points
 * were limited to floors, so reaching it was a coin flip depending on the seed.
 */
@Tag("slow")
class NetherVoxelReachTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);

    /** Equivalent to {@code PathfindingState}'s default render distance. The same window as the real-game logs. */
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private static VoxelCostToGo guide(FakeCells cells, double keep, long seed) {
        return XaeroMapModel.guide(cells, START, GOAL,
                NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, keep, seed);
    }

    @Test
    void theStallRouteIsUnreachableWithoutTheVoxelLayer() throws Exception {
        FakeCells cells = terrain();
        SearchBounds box = ProgressiveWalk.searchBox(cells, START, GOAL, WINDOW);
        WindowedCells windowed = new WindowedCells(cells, START, WINDOW, box);
        CoarseMap coarse = LiveCoarseSampler.sample(windowed, windowed.bounds(), START.getY(), () -> false);
        CostToGo flat = CoarseRouter.costToGo(coarse, GOAL, false, CoarseRouter.BridgePolicy.BRIDGE);

        PathResult withFlatGuide = new AStarPathfinder(windowed,
                new SearchLimits(800_000, 60_000, 1.5), flat).search(START, GOAL, () -> false);
        assertTrue(withFlatGuide.steps().isEmpty(),
                "if layer 1's 2.5D guide now works, re-measure this guard's premise: "
                        + withFlatGuide.termination() + " " + withFlatGuide.steps().size() + " steps");
    }

    @Test
    void walksTheStallRouteWithTheVoxelLayer() throws Exception {
        FakeCells cells = terrain();
        record Variant(String name, double keep, long seed) { }
        // 42% and 25% are a pessimistic approximation of Xaero's missing regions (in real data the walked corridor is filled contiguously)
        Variant[] variants = {
            new Variant("all chunks visited", 1.0, 0L),
            new Variant("42% seed1", 0.42, 1L),
            new Variant("42% seed2", 0.42, 2L),
            new Variant("25% seed1", 0.25, 1L),
        };
        for (Variant variant : variants) {
            VoxelCostToGo guide = guide(cells, variant.keep(), variant.seed());
            assertNotNull(guide, "couldn't build the 3D coarse layer: " + variant.name());
            long began = System.currentTimeMillis();
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, START, GOAL, WINDOW,
                    ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            System.out.printf(Locale.ROOT, "%-18s -> %s cost=%.0f steps=%d (%ds)%n", variant.name(),
                    trace.stopped().isEmpty() ? "reached" : "not reached: " + trace.stopped(),
                    ProgressiveWalk.cost(trace.steps()), trace.steps().size(),
                    (System.currentTimeMillis() - began) / 1000);
            assertTrue(!trace.steps().isEmpty(),
                    variant.name() + ": can no longer walk all the way on this map: " + trace.stopped());
        }
    }
}
