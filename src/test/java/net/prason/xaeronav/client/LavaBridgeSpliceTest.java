package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>On terrain that can't be crossed without bridging, a forward-moving splice must not be mistaken for "backtracking".</b>
 *
 * <p>In an in-game log (2026-09-18, a Nether lava sea), 5 of 8 splices were treated as backtracking, and 2 of those
 * led directly to discarding a complete route on the spot. One bridge is about 35.6 ticks = 10 blocks of sprinting, so as long as it's measured with the geometric lower bound,
 * "the progress made" is only 1/10 of the real cost, and even {@code Splice#SPLICE_DETOUR_ALLOWANCE_TICKS} doesn't cover it.
 *
 * <p>The terrain is southern land (z≤6) and a northern island (18≤z≤24) separated by 11 blocks of lava. The destination is the east end of the southern land.
 */
class LavaBridgeSpliceTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int FLOOR_Y = 63;
    private static final int STAND_Y = FLOOR_Y + 1;

    /** The in-game default ({@code XaeroNavConfig}). The 11-block-wide lava fits within it. */
    private static final int LAVA_BRIDGE_BLOCKS = 30;

    private static final BlockPos GOAL = new BlockPos(60, STAND_Y, 3);
    /** On the island. Going to the destination or returning to the path both mean crossing the same lava. */
    private static final BlockPos ON_THE_ISLAND = new BlockPos(30, STAND_Y, 21);
    /** On the southern land. The destination is to the east on the same land, so crossing to the island from here is a pure detour. */
    private static final BlockPos ON_THE_MAINLAND = new BlockPos(30, STAND_Y, 3);

    private static final int WINDOW_CENTER_X = 32;
    private static final int WINDOW_CENTER_Z = 16;
    private static final int WINDOW_RADIUS = 64;

    private static FakeCells terrain() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 56, -8, 88, 96, 48))
                .canPlaceBlocks(true)
                .maxLavaBridgeRunBlocks(LAVA_BRIDGE_BLOCKS);
        for (int x = 0; x <= 80; x++) {
            for (int z = 0; z <= 40; z++) {
                char symbol = z <= 6 || (z >= 18 && z <= 24) ? FakeCells.BEDROCK : FakeCells.LAVA;
                for (int y = 56; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, symbol);
                }
            }
        }
        return cells;
    }

    private static WindowField guide(FakeCells cells) {
        NavGraph graph = new NavGraph(GOAL, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(WINDOW_CENTER_X, WINDOW_CENTER_Z, 1 << 20);
        long[] keys = graph.missingSections(WINDOW_CENTER_X, WINDOW_CENTER_Z, WINDOW_RADIUS, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, NEVER));
        WindowField field = graph.field(WINDOW_CENTER_X, WINDOW_CENTER_Z, WINDOW_RADIUS, FarField.UNKNOWN, NEVER);
        assertNotNull(field);
        return field;
    }

    private static double spliceCost(FakeCells cells, BlockPos from, BlockPos to) {
        PathResult result = new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT))
                .search(from, to, NEVER);
        assertTrue(result.complete(), "The stretch that should connect by bridging didn't come out");
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void takesABridgingSpliceThatMovesTowardTheGoal() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_ISLAND, ON_THE_MAINLAND);

        assertTrue(Splice.spliceWorthTaking(cost, ON_THE_ISLAND, ON_THE_MAINLAND, GOAL, guide(cells)),
                "A forward-moving splice that merely needs a bridge is treated as backtracking: splice stretch=" + Math.round(cost) + "tick");
    }

    /**
     * Without a guide, it can only be measured by the geometric lower bound, and the same splice is rejected. <b>Why this terrain needs a guide</b>
     * shows up here: what was fixed is the yardstick, not the allowance ({@code SPLICE_DETOUR_ALLOWANCE_TICKS}).
     */
    @Test
    void theGeometricYardstickAloneRefusesTheSameSplice() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_ISLAND, ON_THE_MAINLAND);

        assertFalse(Splice.spliceWorthTaking(cost, ON_THE_ISLAND, ON_THE_MAINLAND, GOAL, null),
                "If it passes even with the geometric lower bound, this terrain can't measure whether a guide is needed");
    }

    /** Even once bridges look cheap, a splice moving away from the destination is refused (the cliff check isn't killed). */
    @Test
    void stillRefusesASpliceThatLeadsAwayFromTheGoal() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_MAINLAND, ON_THE_ISLAND);

        assertFalse(Splice.spliceWorthTaking(cost, ON_THE_MAINLAND, ON_THE_ISLAND, GOAL, guide(cells)),
                "A detour crossing from the land to the island was adopted: splice stretch=" + Math.round(cost) + "tick");
    }
}
