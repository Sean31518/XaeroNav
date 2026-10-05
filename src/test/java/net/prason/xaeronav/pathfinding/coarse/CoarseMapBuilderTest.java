package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Behavior of floor sorting, overwriting and capping in {@link CoarseMapBuilder#putFloor}.
 * This is the foundation for whether {@link CoarseMap} can correctly hold the Nether's multi-layer structure (several independent
 * passages stacked vertically at the same XZ), so the boundary conditions are pinned down in isolation.
 */
class CoarseMapBuilderTest {

    private static CoarseMapBuilder oneCell() {
        return new CoarseMapBuilder(0, 0, 1, 1);
    }

    @Test
    void aSingleFloorLandsAtIndexZero() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        assertEquals(1, map.floorCount(0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(64, map.heightAtFloor(0, 0, 0));
        assertEquals(64, map.minHeightAtFloor(0, 0, 0));
        assertEquals(64, map.maxHeightAtFloor(0, 0, 0));
        assertEquals(1, map.knownCells());
    }

    @Test
    void floorsAreKeptInAscendingHeightOrderRegardlessOfInsertionOrder() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 80);
        builder.putFloor(0, 0, CoarseMap.LAVA, 32);
        builder.putFloor(0, 0, CoarseMap.LAND, 50);
        CoarseMap map = builder.build();

        assertEquals(3, map.floorCount(0, 0));
        assertEquals(32, map.heightAtFloor(0, 0, 0));
        assertEquals(50, map.heightAtFloor(0, 0, 1));
        assertEquals(80, map.heightAtFloor(0, 0, 2));
        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void writingTheSameHeightTwiceOverwritesInsteadOfAddingAFloor() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(0, 0, CoarseMap.LAVA_MIXED, 64);
        CoarseMap map = builder.build();

        assertEquals(1, map.floorCount(0, 0), "The same height overwrites rather than adding a new floor");
        assertEquals(CoarseMap.LAVA_MIXED, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void aFloorBeyondTheLimitIsDropped() {
        CoarseMapBuilder builder = oneCell();
        // Assumes floors are passed in order of closeness to the reference Y (matching the order of XaeroMapReader#layersFor).
        // Fill exactly up to the cap, then add one more floor, the highest
        for (int i = 0; i < CoarseMap.MAX_FLOORS; i++) {
            builder.putFloor(0, 0, CoarseMap.LAND, 30 + i * 10);
        }
        builder.putFloor(0, 0, CoarseMap.LAND, 200);
        CoarseMap map = builder.build();

        assertEquals(CoarseMap.MAX_FLOORS, map.floorCount(0, 0));
        for (int i = 0; i < CoarseMap.MAX_FLOORS; i++) {
            assertEquals(30 + i * 10, map.heightAtFloor(0, 0, i));
        }
    }

    @Test
    void anEmptyCellHasNoFloors() {
        CoarseMap map = oneCell().build();

        assertEquals(0, map.floorCount(0, 0));
        assertEquals(0, map.knownCells());
    }

    @Test
    void outOfRangeWritesAreSilentlyDropped() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(5, 5, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        assertEquals(0, map.floorCount(0, 0));
        assertEquals(0, map.knownCells());
    }

    @Test
    void nearestFloorPicksTheClosestHeight() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 30);
        builder.putFloor(0, 0, CoarseMap.LAND, 60);
        builder.putFloor(0, 0, CoarseMap.LAND, 100);
        CoarseMap map = builder.build();

        assertEquals(1, map.nearestFloor(0, 0, 65));
        assertEquals(0, map.nearestFloor(0, 0, 40));
        assertEquals(2, map.nearestFloor(0, 0, 95));
    }

    @Test
    void nearestFloorReturnsMinusOneWhenTheCellIsEmpty() {
        CoarseMap map = oneCell().build();

        assertEquals(-1, map.nearestFloor(0, 0, 64));
    }
}
