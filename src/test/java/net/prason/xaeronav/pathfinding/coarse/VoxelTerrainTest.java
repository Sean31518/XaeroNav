package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class VoxelTerrainTest {

    private static final SearchBounds BOX = new SearchBounds(0, 0, 0, 63, 63, 63);

    @Test
    void unknownCellsStayPassable() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        assertEquals(VoxelTerrain.OPEN, terrain.kindAt(terrain.indexOfBlock(32, 32, 32)),
                "Making places not on the map into walls erases the detours there along with them");
    }

    @Test
    void marksTheCellAboveTheFloor() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        terrain.markFloor(8, 8, 19, false);
        assertEquals(VoxelTerrain.STANDABLE, terrain.kindAt(terrain.indexOfBlock(8, 20, 8)));
        assertEquals(1, terrain.floorMarks());
    }

    /**
     * Lava is recorded regardless of the bridge setting. Even when crossing is allowed, pricing a lava sea the same as
     * "a place that just isn't on the map" erases the direction you should detour.
     */
    @Test
    void lavaIsRecordedWhicheverWayBridgingIsSet() {
        for (boolean bridging : new boolean[] {false, true}) {
            VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, bridging);
            terrain.markFloor(8, 8, 19, true);
            assertEquals(VoxelTerrain.LAVA, terrain.kindAt(terrain.indexOfBlock(8, 20, 8)),
                    "lavaBridge=" + bridging);
            assertEquals(1, terrain.floorMarks(), "lavaBridge=" + bridging);
        }
    }

    /** If floor and lava mix in the same cell, floor wins. Regardless of write order. */
    @Test
    void floorWinsOverLavaInTheSameCell() {
        VoxelTerrain lavaFirst = VoxelTerrain.of(BOX, 4, false);
        lavaFirst.markFloor(8, 8, 19, true);
        lavaFirst.markFloor(10, 10, 19, false);
        VoxelTerrain floorFirst = VoxelTerrain.of(BOX, 4, false);
        floorFirst.markFloor(10, 10, 19, false);
        floorFirst.markFloor(8, 8, 19, true);
        assertEquals(VoxelTerrain.STANDABLE, lavaFirst.kindAt(lavaFirst.indexOfBlock(8, 20, 8)));
        assertEquals(VoxelTerrain.STANDABLE, floorFirst.kindAt(floorFirst.indexOfBlock(8, 20, 8)));
    }

    @Test
    void ignoresFloorsOutsideTheBox() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        terrain.markFloor(8, 8, 200, false);
        terrain.markFloor(-40, 8, 19, false);
        assertEquals(0, terrain.floorMarks());
    }

    /**
     * The box's Y is determined by <b>the range that has floors</b>, not the dimension's full height.
     *
     * <p>If the dimension is taller than its contents (the real Nether was 256 high), the empty space above the
     * bedrock ceiling takes half the grid, and the guide draws a "bridge across the top of the ceiling" path that can't be walked.
     */
    @Test
    void theBoxFollowsTheMappedFloorsNotTheDimensionHeight() {
        LevelHeightAccessor tall = LevelHeightAccessor.create(0, 256);
        SearchBounds box = VoxelTerrain.boxFor(tall, new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 100), 30, 70);
        assertEquals(30 - VoxelTerrain.VERTICAL_MARGIN_BLOCKS, box.minY());
        assertEquals(70 + VoxelTerrain.VERTICAL_MARGIN_BLOCKS, box.maxY());
    }

    /** The dimension's height acts as a cap. The margin around the floors must not spread beyond it. */
    @Test
    void theBoxNeverLeavesTheDimension() {
        LevelHeightAccessor nether = LevelHeightAccessor.create(0, 128);
        SearchBounds box = VoxelTerrain.boxFor(nether, new BlockPos(0, 10, 0),
                new BlockPos(100, 120, 100), 4, 124);
        assertEquals(0, box.minY());
        assertEquals(127, box.maxY());
    }

    /** Start and destination are always inside the box. If the destination is outside, the guide's origin is undefined and the table is entirely empty. */
    @Test
    void theBoxAlwaysHoldsTheStartAndTheGoal() {
        LevelHeightAccessor tall = LevelHeightAccessor.create(0, 256);
        SearchBounds box = VoxelTerrain.boxFor(tall, new BlockPos(0, 200, 0),
                new BlockPos(100, 12, 100), 40, 60);
        assertTrue(box.contains(0, 200, 0), "Start is outside the box: " + box);
        assertTrue(box.contains(100, 12, 100), "Destination is outside the box: " + box);
    }

    /** Allocation doesn't explode for distant destinations. Absorbed by coarsening the cells. */
    @Test
    void coarsensTheGridForFarGoals() {
        SearchBounds wide = new SearchBounds(0, 0, 0, 4000, 127, 4000);
        int cell = VoxelTerrain.cellBlocksFor(wide);
        assertTrue(cell > VoxelTerrain.DEFAULT_CELL_BLOCKS, "Not coarsened: " + cell);
        VoxelTerrain terrain = VoxelTerrain.of(wide, true);
        assertNotNull(terrain);
        assertTrue(terrain.cellCount() <= 500_000, "cells=" + terrain.cellCount());
    }
}
