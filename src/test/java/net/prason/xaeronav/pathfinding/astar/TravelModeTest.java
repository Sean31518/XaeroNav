package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * The travel-mode switches: swimming ({@link CellSource#swimmingEnabled()}) and boats
 * ({@link MovementOptions#boatsEnabled()}). Walking is always on.
 */
class TravelModeTest {

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells).search(start, goal, () -> false);
    }

    private static boolean entersWater(CellSource cells, PathResult result) {
        return result.steps().stream().anyMatch(step -> CellData.water(cells.cell(
                step.pos().getX(), step.pos().getY(), step.pos().getZ())));
    }

    @Test
    void boatsDisabledOfferNoBoatEvenWhenOneIsCarried() {
        BlockPos start = new BlockPos(0, 63, 0);
        BlockPos goal = new BlockPos(91, 63, 0);

        PathResult allowed = search(strait(90).boatAvailable(options(true, true).boatUsable(true)), start, goal);
        assertTrue(allowed.steps().stream().anyMatch(PathStep::boating), "boats on: the strait is paddled across");

        PathResult refused = search(strait(90).boatAvailable(options(true, false).boatUsable(true)), start, goal);
        assertTrue(refused.complete(), "swims across instead");
        assertTrue(refused.steps().stream().noneMatch(PathStep::boating), "boats off: no boat moves at all");
    }

    @Test
    void crossesByBoatWithoutSwimmingWhenOnlyBoatsAreAllowed() {
        CellSource cells = strait(40).boatAvailable(true).swimmingEnabled(false);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(PathStep::boating));
        assertTrue(result.steps().stream().noneMatch(PathStep::swimming), "never drops into the water to swim");
    }

    @Test
    void walksAroundAPondInsteadOfSwimmingWhenSwimmingIsDisabled() {
        BlockPos start = new BlockPos(0, 63, 0);
        BlockPos goal = new BlockPos(7, 63, 0);

        FakeCells swimming = pondWithDetour(6, 8);
        PathResult balanced = search(swimming, start, goal);
        assertTrue(balanced.complete());
        assertTrue(entersWater(swimming, balanced), "with swimming on, the pond is the short way");

        FakeCells dry = pondWithDetour(6, 8).swimmingEnabled(false);
        PathResult detour = search(dry, start, goal);
        assertTrue(detour.complete(), "the dry detour is found");
        assertFalse(entersWater(dry, detour),
                "never enters the water: " + detour.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void stillSwimsOutWhenStartingInWater() {
        FakeCells cells = pondWithDetour(6, 8).swimmingEnabled(false);

        PathResult result = search(cells, new BlockPos(3, 62, 0), new BlockPos(0, 63, 0));

        assertTrue(result.complete(), "a player in the pond must be guided out of it");
        assertEquals(new BlockPos(0, 63, 0), result.steps().get(result.steps().size() - 1).pos());
    }

    @Test
    void stillReachesADestinationInWater() {
        FakeCells cells = pondWithDetour(6, 8).swimmingEnabled(false);
        BlockPos goal = new BlockPos(4, 62, 0);

        PathResult result = search(cells, new BlockPos(0, 63, 0), goal);

        assertTrue(result.complete(), "a destination the user put in the water stays reachable");
        assertEquals(goal, result.steps().get(result.steps().size() - 1).pos());
    }

    @Test
    void doesNotCrossWaterWithNeitherSwimmingNorABoat() {
        CellSource cells = strait(10).swimmingEnabled(false);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(11, 63, 0));

        assertFalse(result.complete(), "walking is the only mode left, and the strait can't be walked");
        assertFalse(entersWater(cells, result));
    }

    /** Options with only the two travel switches varied. */
    private static MovementOptions options(boolean swimmingEnabled, boolean boatsEnabled) {
        return new MovementOptions(true, true, true, true, 96, 30, 96, 250, false, true, true, 0, false,
                RouteProfile.BALANCED, swimmingEnabled, boatsEnabled, true);
    }

    /**
     * From the shore (x=0) across the water surface (x=1..width) to the far shore. Water surface at y=62, shore at
     * y=63 (the same shape as {@code AStarPathfinderTest#strait}). No way around.
     */
    private static FakeCells strait(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, width + 12, 76, 8));
        for (int x = -1; x <= width + 2; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean water = x >= 1 && x <= width;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /**
     * A pond (x=1..width, z=-10..detourZ-1, water surface y=62) between two shores standing at y=63. At z=detourZ
     * dry land runs across, so the pond can be walked around. Past z=-10 there is nothing.
     */
    private static FakeCells pondWithDetour(int width, int detourZ) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-6, 52, -14, width + 6, 76, detourZ + 4));
        for (int x = -2; x <= width + 3; x++) {
            for (int z = -10; z <= detourZ + 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean water = x >= 1 && x <= width && z < detourZ;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
            }
        }
        return cells;
    }
}
