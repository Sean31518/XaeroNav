package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.WindowedCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * Measures long Nether distances including the loaded window, carry-over, extension and seam re-solving.
 * Compares running with the 3D coarse layer ({@code VoxelCostToGo}) against running without it.
 *
 * <p>The floors given to the 3D coarse layer are built by {@link XaeroMapModel}: <b>only the same "per-column,
 * per-layer floor Y" that Xaero has</b>, with no ceilings or rock shapes. This is different from the ideal
 * guide built from the full 3D terrain ({@code measuresTheIdealFloorOnlyGuide}), which is the upper bound of headroom.
 *
 * <p>An offline check that knows the saved terrain completely even inside the window; it does not reproduce real-game ticks or rendering.
 * Regular leg searches use weight 1.5, unlike the real game's initial parallel search (regular weight 1.2).
 * The initial-search API is checked directly in a separate method. Uses a comparison setting with fall tolerance 6,
 * unlike the user's fall tolerance 0 as saved on 2026-09-08.
 * ProgressiveWalk's extension budget still differs from the main code.
 */
@Tag("slow")
class NetherLiveWalkTest {

    static final int WINDOW_RADIUS = 160;

    /** The Nether's actual height. The fixture's export range extends above the ceiling, so it can't be used as-is. */
    static final int NETHER_MIN_Y = 0;
    static final int NETHER_MAX_Y = 127;

    static List<BlockPos[]> routes() {
        return List.of(
                new BlockPos[] {new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379)},
                new BlockPos[] {new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496)},
                new BlockPos[] {new BlockPos(-317, 44, 567), new BlockPos(-523, 66, 465)},
                new BlockPos[] {new BlockPos(-474, 69, 629), new BlockPos(-271, 73, 482)});
    }

    static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static String ratio(ProgressiveWalk.Trace trace, double best) {
        if (trace.steps().isEmpty()) {
            return "not reached: " + trace.stopped();
        }
        double cost = ProgressiveWalk.cost(trace.steps());
        return String.format(Locale.ROOT, "%6.0f(%.3fx) seams %d re-solves %d/%d redraws %d(underfoot %d)",
                cost, cost / best, trace.joints().size(), trace.repairsTaken(),
                trace.repairAttempts(), trace.redraws(), trace.nearRedraws());
    }

    @Test
    void walksTheNetherTheWayTheGameDoes() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        for (BlockPos[] route : routes()) {
            long began = System.currentTimeMillis();
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            long mapBegan = System.currentTimeMillis();
            CostToGo guide = XaeroMapModel.guide(cells, route[0], route[1],
                    NETHER_MIN_Y, NETHER_MAX_Y, 1.0, 0L);
            System.out.printf(Locale.ROOT, "  building the 3D coarse layer took %dms%n",
                    System.currentTimeMillis() - mapBegan);
            ProgressiveWalk.Trace voxel = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            ProgressiveWalk.Trace plain = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL);
            report.add(String.format(Locale.ROOT,
                    "%s->%s baseline %6.0f%n  3D coarse layer %s%n  no guide %s%n  (%.0fs)",
                    route[0].toShortString(), route[1].toShortString(), best,
                    ratio(voxel, best), ratio(plain, best),
                    (System.currentTimeMillis() - began) / 1000.0));
            System.out.println(report.get(report.size() - 1));
            assertTrue(Double.isFinite(best), "baseline search did not finish");
            assertTrue(!voxel.steps().isEmpty(), "no longer reachable with the 3D coarse layer: " + report.get(report.size() - 1));
        }
        assertTrue(!report.isEmpty(), "not a single route measured");
    }

    /** Runs the real-game initial-search API directly. Weights and calls differ from the trace that measures extensions. */
    @Test
    void usesFullInitialBudgetOnRecordedNetherTerrain() throws Exception {
        FakeCells cells = terrain();
        SearchLimits normal = new SearchLimits(100_000, 30_000, 1.2);
        SearchLimits deep = new SearchLimits(800_000, 30_000, 1.5);
        for (BlockPos[] route : routes()) {
            var box = ProgressiveWalk.searchBox(cells, route[0], route[1], WINDOW_RADIUS);
            WindowedCells view = new WindowedCells(cells, route[0], WINDOW_RADIUS, box);
            assertTrue(!box.contains(route[1].getX(), route[1].getY(), route[1].getZ()));
            // Before the fix, the returned normal pass was limited to 40%. The
            // subsequent retries could not finish and their partial paths were discarded.
            PathResult oldPass = new AStarPathfinder(view, new SearchLimits(40_000, 30_000, 1.2))
                    .search(route[0], route[1], () -> false);
            PathResult fullPass = new AStarPathfinder(view, normal)
                    .search(route[0], route[1], () -> false);
            long began = System.currentTimeMillis();
            PathResult actual = new PathfindingExecutor().submitWithDeepFallback(view,
                    new WindowedCells(cells, route[0], WINDOW_RADIUS, box),
                    route[0], route[1], normal, deep, true, 0).get(40, TimeUnit.SECONDS);
            assertEquals(fullPass.termination(), actual.termination());
            assertEquals(fullPass.steps().stream().map(PathStep::pos).toList(),
                    actual.steps().stream().map(PathStep::pos).toList());
            System.out.printf(Locale.ROOT,
                    "initial %s->%s: old 40%%=%d steps / %d nodes, fixed=%d steps / %d nodes (%dms)%n",
                    route[0].toShortString(), route[1].toShortString(),
                    oldPass.steps().size(), oldPass.expandedNodes(),
                    actual.steps().size(), actual.expandedNodes(), System.currentTimeMillis() - began);
        }
    }

    /**
     * The <b>upper bound of headroom</b> for the 3D coarse layer: an ideal guide that knows the terrain fully and is given only floor positions.
     * Xaero's real data is sparser than this, so don't read this value as a production guarantee.
     */
    @Test
    void measuresTheIdealFloorOnlyGuide() throws Exception {
        FakeCells cells = terrain();
        for (BlockPos[] route : routes()) {
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            assertTrue(Double.isFinite(best), "The reference route must finish");
            CostToGo guide = WideVoxelGuide.build(cells, cells.bounds(), route[1], true);
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            System.out.printf(Locale.ROOT, "floor positions only %s->%s: %s%n",
                    route[0].toShortString(), route[1].toShortString(), ratio(trace, best));
        }
    }
}
