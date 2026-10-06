package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Boats need open water at least two blocks wide ({@link AStarPathfinder#boatFits}). A boat is wider than one block,
 * so the 1-wide channels of a swamp jam it against the banks; a route must not send it in there.
 */
class BoatClearanceTest {

    private static final int LENGTH = 40;

    /**
     * A channel along +X from the shore at x=0 to the far shore, {@code width} blocks wide (z=0..width-1), water surface
     * at y=62, banks of bedrock reaching above the water on both sides, nothing around.
     */
    private static FakeCells channel(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -6, LENGTH + 12, 76, width + 6));
        for (int x = -1; x <= LENGTH + 2; x++) {
            for (int z = -2; z <= width + 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean water = x >= 1 && x <= LENGTH && z >= 0 && z < width;
                boolean bank = x >= 1 && x <= LENGTH && !water;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                if (bank) {
                    // Banks too high to walk along: the only way is through the water
                    for (int y = 63; y <= 66; y++) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
        }
        return cells.boatAvailable(true);
    }

    private static PathResult search(FakeCells cells) {
        return new AStarPathfinder(cells).search(new BlockPos(0, 63, 0), new BlockPos(LENGTH + 1, 63, 0), () -> false);
    }

    @Test
    void paddlesThroughAChannelTwoBlocksWide() {
        PathResult result = search(channel(2));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(PathStep::boating), "two blocks is enough for a boat");
    }

    @Test
    void neverTakesTheBoatIntoAOneBlockChannel() {
        PathResult result = search(channel(1));

        assertTrue(result.steps().stream().noneMatch(PathStep::boating), "a boat jams in a 1-wide channel");
        assertTrue(result.complete(), "swims through instead");
    }

    @Test
    void doesNotCrossAOneBlockChannelWithOnlyABoat() {
        PathResult result = search(channel(1).swimmingEnabled(false));

        assertFalse(result.complete(), "no swimming and no room for the boat: no way through");
        assertTrue(result.steps().stream().noneMatch(PathStep::boating));
    }
}
