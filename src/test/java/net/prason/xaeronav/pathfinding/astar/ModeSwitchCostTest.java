package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Changing how you travel costs time and attention ({@code ActionCosts#BOAT_DOCK_TICKS},
 * {@code ActionCosts#MODE_SWITCH_PENALTY_TICKS}): a boat on open water must not be sent ashore over a small island just
 * because the line across it is a little shorter.
 */
class ModeSwitchCostTest {

    private static final int WATER_END = 120;
    private static final int ISLAND_FROM = 55;
    private static final int ISLAND_TO = 58;
    private static final int ISLAND_HALF = 60;
    private static final int SEA_HALF = 70;

    /**
     * Open sea from x=1 to {@value #WATER_END} (surface y=62), shores on both ends. In the middle a long, narrow island
     * (x {@value #ISLAND_FROM}..{@value #ISLAND_TO}, z ±{@value #ISLAND_HALF}) lies across the way; going round it adds
     * about 48 blocks of paddling.
     */
    private static FakeCells sea() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -SEA_HALF - 2, WATER_END + 12, 76, SEA_HALF + 2));
        for (int x = -1; x <= WATER_END + 3; x++) {
            for (int z = -SEA_HALF; z <= SEA_HALF; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean island = x >= ISLAND_FROM && x <= ISLAND_TO && Math.abs(z) <= ISLAND_HALF;
                boolean water = x >= 1 && x <= WATER_END && !island;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
            }
        }
        return cells.boatAvailable(true).ridingBoat(true);
    }

    @Test
    void paddlesRoundASmallIslandRatherThanCrossingIt() {
        PathResult result = new AStarPathfinder(sea())
                .search(new BlockPos(1, 62, 0), new BlockPos(WATER_END + 1, 63, 0), () -> false);

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        // Everything before the far shore is paddled: no getting out on the island and back in
        for (PathStep step : steps.subList(0, steps.size() - 1)) {
            assertTrue(step.boating(), "leaves the boat at " + step.pos() + " (" + step.movement() + ")");
        }
    }
}
