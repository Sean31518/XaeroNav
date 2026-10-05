package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * Resnapping the search's start and end points.
 *
 * <p>Every move A* produces ends at "a cell with a foothold, water, or a ladder", so passing any other coordinate
 * means not a single path grows. Both the start (a falling player) and the end (a map click coordinate) are values
 * that come from outside the search, and it's not unusual for them to be invalid as-is.
 */
class StanceFinderTest {

    @Test
    void startFallingThroughTheAirDropsToTheLandingSpot() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ...
                ###""");

        // A start floating in mid-air. We want the path from where it will land onward
        BlockPos resolved = StanceFinder.resolveStart(cells, new BlockPos(1, 63, 0));

        assertEquals(new BlockPos(1, 61, 0), resolved, "lowered onto the floor");
    }

    @Test
    void startAlreadyStandingIsLeftAlone() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveStart(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void startEmbeddedInTheFloorIsLiftedOneBlock() {
        // Inside a half block / sunk into the ground. Look one block up rather than lowering straight down
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ###
                ###""");

        assertEquals(new BlockPos(1, 62, 0), StanceFinder.resolveStart(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void goalBuriedInDiggableGroundIsKeptAsIs() {
        // An underground destination. It can be reached by digging, so don't move it; producing the tunnel there is correct
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ###
                ###
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)),
                "coordinates reachable by digging aren't moved");
    }

    @Test
    void goalFloatingInTheAirIsPulledDownToTheGround() {
        // Map clicks and waypoints can point into the air. Treating them as unreachable would give
        // "no path" even when you've come right up to it
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ...
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 63, 0)),
                "moved to a height with a foothold");
    }

    @Test
    void goalInsideUndiggableRockIsPulledToTheNearestReachableCell() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                BBB
                BBB""");

        // (1,61) is inside bedrock and can't be reached even by digging. Move it to the space above
        assertEquals(new BlockPos(1, 62, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void waterCountsAsAStanceEvenWithoutAFloor() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                .~.
                .~.
                ###""");

        // Underwater counts as standable even without a foothold (swimming). Without this, every destination over the ocean would get moved
        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)));
    }
}
