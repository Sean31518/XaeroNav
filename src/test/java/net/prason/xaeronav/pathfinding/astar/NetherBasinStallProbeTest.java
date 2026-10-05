package net.prason.xaeronav.pathfinding.astar;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Measurement probe (no assertions). Fires a single A\* from the stop points of real-game run #2 (2026-09-09)
 * and prints how many moves it makes and in which direction.
 *
 * <p>Findings ("third session" in [[xaeronav-nether-3d-realmap-failure]]):
 * <ul>
 *   <li>{@code (-341,24,601)} is inside a lava pillar with zero successors = EXHAUSTED, expanded=1. A\* is correct.</li>
 *   <li>The real game's "steps=0" at {@code (-323,34,520)} <b>doesn't reproduce offline</b>:
 *       here even w=1.5 makes 30 moves, 30 blocks toward the destination.</li>
 *   <li>This terrain is a lava ocean. A fix surcharging floors at the lava's edge got worse with the greedy weight.</li>
 * </ul>
 */
@Tag("slow")
class NetherBasinStallProbeTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);
    private static final int[] LAYERS = {4};
    private static final double VISITED = 0.68;
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private void probe(String label, FakeCells cells, VoxelCostToGo guide, BlockPos from) {
        SearchBounds box = ProgressiveWalk.searchBox(cells, from, GOAL, WINDOW);
        WindowedCells view = new WindowedCells(cells, from, WINDOW, box);
        for (double weight : new double[] {AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 2.5, 3.0}) {
            PathResult r = new AStarPathfinder(view, new SearchLimits(100_000, 30_000, weight), guide)
                    .search(from, GOAL, () -> false, 0);
            BlockPos end = r.steps().isEmpty() ? from : r.steps().get(r.steps().size() - 1).pos();
            System.out.printf(Locale.ROOT,
                    "%-26s w=%.1f -> %s expanded=%d steps=%d end=%s guide.est=%.0f%n",
                    label, weight, r.termination(), r.expandedNodes(), r.steps().size(),
                    end.toShortString(),
                    guide.estimate(from.getX(), from.getY(), from.getZ()));
        }
    }

    private static VoxelCostToGo thinGuide(FakeCells cells, BlockPos start, BlockPos goal) {
        VoxelTerrain grid = VoxelTerrain.of(
                XaeroMapModel.guideBox(start, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                        NetherLiveWalkTest.NETHER_MAX_Y), true);
        XaeroMapModel.fill(grid, cells, LAYERS, VISITED, 1L);
        return VoxelCostToGo.build(grid, goal, () -> false);
    }

    @Test
    void printsWhatEachStopSpotDoesOffline() throws Exception {
        FakeCells cells = terrain();
        VoxelCostToGo original = thinGuide(cells, START, GOAL);

        probe("y64 start (sanity check)", cells, original, START);
        probe("y34 (-323,520) original guide", cells, original, new BlockPos(-323, 34, 520));
        probe("y24 (-341,601) original guide", cells, original, new BlockPos(-341, 24, 601));

        // When stuck, the real game rebuilds centered on the player
        VoxelCostToGo rebuilt = thinGuide(cells, new BlockPos(-323, 34, 520), GOAL);
        probe("y34 (-323,520) rebuilt", cells, rebuilt, new BlockPos(-323, 34, 520));
    }
}
