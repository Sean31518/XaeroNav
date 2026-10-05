package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class VoxelCostToGoTest {

    private static SearchBounds box() {
        return new SearchBounds(0, 0, 0, 127, 63, 127);
    }

    /** Place floor along a single corridor only, and check that the estimate rises the further you stray from it. */
    private static VoxelTerrain corridor() {
        VoxelTerrain terrain = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        for (int x = 0; x <= 127; x++) {
            terrain.markFloor(x, 64, 15, false);
        }
        return terrain;
    }

    @Test
    void followsTheFloorInsteadOfTheStraightLine() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        assertNotNull(guide);
        // On the corridor it's just running. For the same distance, places without floor cost as much as bridging
        double onFloor = guide.estimate(8, 16, 64);
        double offFloor = guide.estimate(8, 16, 8);
        assertTrue(offFloor > onFloor * 2.0,
                "Estimate off the floor is too cheap: on floor=" + onFloor + " off=" + offFloor);
    }

    @Test
    void neverReportsMoreThanZeroAtTheGoal() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        assertEquals(0.0, guide.estimate(120, 16, 64));
    }

    /**
     * Outside the box: the edge value + the straight line to it. Returning 0 would make the edge a cliff, and A* would
     * read "outside the box is cheaper" and expand away from the route.
     */
    @Test
    void doesNotDropToZeroOutsideTheBox() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        double atEdge = guide.estimate(0, 16, 64);
        double outside = guide.estimate(-40, 16, 64);
        assertTrue(outside >= atEdge, "Outside the box is cheaper than the edge: edge=" + atEdge + " outside=" + outside);
    }

    /** The table can be built even with no floor at all. Giving up here falls back to no guide and the symptom returns. */
    @Test
    void anchorsEvenWhenNoFloorIsKnown() {
        VoxelTerrain empty = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        VoxelCostToGo guide = VoxelCostToGo.build(empty, new BlockPos(120, 16, 64), () -> false);
        assertNotNull(guide, "Must not give up on the guide just because there's no floor");
        assertTrue(guide.reachableCells() > empty.cellCount() / 2);
        assertTrue(guide.estimate(0, 16, 64) > 0.0);
    }

    /** Even if the destination cell is lava, the surrounding floor can serve as the origin. */
    @Test
    void anchorsNextToTheGoalWhenItsOwnCellIsBlocked() {
        VoxelTerrain terrain = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        terrain.markFloor(64, 64, 15, true);
        terrain.markFloor(76, 64, 15, false);
        VoxelCostToGo guide = VoxelCostToGo.build(terrain, new BlockPos(64, 16, 64), () -> false);
        assertNotNull(guide);
        assertTrue(guide.estimate(76, 16, 64) < guide.estimate(4, 16, 4),
                "Can't use the nearby floor as the origin");
    }

    @Test
    void refusesAGoalOutsideTheBox() {
        assertNull(VoxelCostToGo.build(corridor(), new BlockPos(400, 16, 64), () -> false));
    }

    @Test
    void stopsWhenCancelled() {
        assertNull(VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> true));
    }
}
