package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class FlightLineRouterTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-200, 0, -200, 200, 300, 200);

    private static final Vec3 START = new Vec3(-100.0, 100.0, 0.0);
    private static final Vec3 GOAL = new Vec3(100.0, 100.0, 0.0);

    /** Places a wall near X=0 with height {@code top}, X thickness {@code halfWidth*2}, and Z width {@code halfDepth*2}. */
    private static FakeCells wall(int halfWidth, int top, int halfDepth) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfDepth; z <= halfDepth; z++) {
                for (int y = BOUNDS.minY(); y <= top; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    /** A water body with the same shape as {@link #wall}. */
    private static FakeCells water(int halfWidth, int top, int halfDepth) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfDepth; z <= halfDepth; z++) {
                for (int y = BOUNDS.minY(); y <= top; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    private static List<Vec3> route(FakeCells cells) {
        return new FlightLineRouter(cells).findGuideLine(START, GOAL);
    }

    /** Which way, and how far, the bend point shifted from the midpoint. */
    private static Vec3 bendOffset(List<Vec3> line) {
        return line.get(1).subtract(START.add(GOAL).scale(0.5));
    }

    @Test
    void goesStraightWhenNothingIsInTheWay() {
        assertEquals(List.of(START, GOAL), route(FakeCells.empty(BOUNDS)));
    }

    @Test
    void bendsAroundAThinTallSpire() {
        // A thin, tall spire. Going over it needs dozens of blocks of climbing, but sideways it can be passed in a few blocks
        List<Vec3> line = route(wall(2, 260, 2));

        assertEquals(3, line.size(), "No bend point was added; the line still pierces the spire");
        Vec3 offset = bendOffset(line);
        assertTrue(Math.abs(offset.z) > Math.abs(offset.y),
                "A narrow spire should be avoided sideways, but it's trying to go over: " + offset);
    }

    @Test
    void climbsOverALowButVeryWideRidge() {
        // A low but wide ridge. Going around sideways isn't enough even at the full search radius; climbing a few blocks is cheaper
        List<Vec3> line = route(wall(4, 110, 180));

        assertEquals(3, line.size(), "No bend point was added; the line still pierces the ridge");
        Vec3 offset = bendOffset(line);
        assertTrue(offset.y > Math.abs(offset.z),
                "A wide ridge should be crossed over the top, but it's trying to avoid it sideways: " + offset);
    }

    @Test
    void keepsTheBentLineClearOfTheTerrain() {
        FakeCells cells = wall(2, 260, 2);
        List<Vec3> line = route(cells);

        FlightLineRouter router = new FlightLineRouter(cells);
        for (int i = 0; i + 1 < line.size(); i++) {
            assertTrue(router.findGuideLine(line.get(i), line.get(i + 1)).size() == 2,
                    "Segment " + i + " after bending still pierces the terrain");
        }
    }

    @Test
    void fallsBackToTheStraightLineWhenNothingClears() {
        // A wall extending beyond the search range up, down, left, and right. There's no way to bend around it
        List<Vec3> line = route(wall(4, BOUNDS.maxY(), 200));

        assertEquals(List.of(START, GOAL), line,
                "When it can't be avoided, fall back to the plain straight line (don't drop the line altogether)");
    }

    @Test
    void bendsAroundWater() {
        // Water is an obstacle too. This behavior keeps the gliding dotted line from piercing the water surface
        assertEquals(3, route(water(2, 260, 2)).size(), "The line still pierces the water body without bending");
    }

    @Test
    void ignoresUnknownCellsInsteadOfTreatingThemAsWalls() {
        // Space filled as unloaded (ABSENT). Treating places that merely have no data as walls would make
        // a goal far beyond the render distance be judged as "piercing" every time
        FakeCells cells = FakeCells.empty(BOUNDS).fillWith(FakeCells.ABSENT);

        assertEquals(List.of(START, GOAL), route(cells));
    }
}
