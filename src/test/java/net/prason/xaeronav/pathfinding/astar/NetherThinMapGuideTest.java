package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * Guard ensuring a path can be walked end to end on <b>a map as thin as the one real in-game Xaero actually has</b>.
 *
 * <p>The other Nether regressions ({@code NetherVoxelReachTest}, {@code NetherLiveWalkTest}) have
 * {@link XaeroMapModel#fill(VoxelTerrain, net.prason.xaeronav.pathfinding.world.CellSource,
 * double, long)} feed <b>up to 8 floors per column</b>. Real Xaero saves are much thinner: cave layers are
 * <b>only written for the height bands where the player actually was</b>, so walking the Nether at a constant height fills
 * only one. The per-layer breakdown in an in-game log (2026-09-09) was {@code L4=3408} with all others 0.
 *
 * <p>On a thin map the grid's "non-floor" share rises, and the estimate swells to 5.84x the straight-line distance.
 * <b>That in itself is normal</b> (this path's real cost is about 5.7x the straight-line distance). Shrinking the table to suppress the swelling
 * <b>makes it unwalkable instead</b>, so this guards <b>being able to walk it end to end</b>, not "the amount of swelling".
 */
@Tag("slow")
class NetherThinMapGuideTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);

    /** The only cave layer the in-game save had. */
    private static final int[] LAYERS = {4};

    /** The in-game log's "known cells=4410/6486" = 68% visited. */
    private static final double VISITED = 0.68;

    /** Equivalent to {@code PathfindingState}'s default render distance. */
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private void walk(String name, VoxelTerrain grid, FakeCells cells) {
        assertNotNull(grid, name + ": could not build the grid");
        VoxelCostToGo guide = VoxelCostToGo.build(grid, GOAL, () -> false);
        assertNotNull(guide, name + ": could not build the guide");
        ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, START, GOAL, WINDOW,
                ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
        System.out.printf(Locale.ROOT, "%-22s cells=%d edges=%d %s -> %s moves=%d%n", name,
                grid.cellCount(), grid.cellBlocks(), grid.breakdown(),
                trace.stopped().isEmpty() ? "reached" : "not reached: " + trace.stopped(),
                trace.steps().size());
        assertTrue(!trace.steps().isEmpty(), name + ": can no longer be walked end to end: " + trace.stopped());
    }

    @Test
    void walksTheStallRouteOnAMapAsThinAsTheRealSave() throws Exception {
        FakeCells cells = terrain();
        VoxelTerrain grid = VoxelTerrain.of(XaeroMapModel.guideBox(START, GOAL,
                NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y), true);
        XaeroMapModel.fill(grid, cells, LAYERS, VISITED, 1L);
        walk("1 cave layer, 68% visited", grid, cells);
    }

    /**
     * <b>Walkable even when the dimension's height is larger than the walkable height.</b> The only common divisor of the in-game log's cell counts
     * (276318 and 229405) is 43, and together with edge=6 the box's Y extent is <b>256</b>,
     * twice the Nether's walkable height. When the space above the bedrock ceiling takes up half the grid,
     * the guide draws a road "running on bridges above the ceiling", and the search is dragged toward it.
     *
     * <p>Measured (back when the box's Y spanned the dimension's full height): with a 0..255 box it couldn't be walked end to end, and the search
     * stopped at y=95, 100 blocks west of the path; the same shape as the in-game log's "big seam detour (x=-416)".
     * Fixed now that {@code VoxelTerrain#boxFor} narrows the box to the range that has floors.
     *
     * <p>Measured at the same thinness as the in-game save (1 cave layer). The dense model that picks up 8 per column
     * doubles the grid, so the {@code ProgressiveWalk.trace} walk-through times out on slow
     * machines, and in any case the {@code boxFor} clamp doesn't depend on the layer count.
     */
    @Test
    void walksWhenTheDimensionIsTallerThanTheGroundItHas() throws Exception {
        FakeCells cells = terrain();
        walk("full height 256, 1 layer",
                XaeroMapModel.grid(cells, START, GOAL, XaeroMapModel.height(0, 255),
                        LAYERS, VISITED, 1L),
                cells);
    }
}
