package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;

/**
 * Long-range route in the air. Terrain can be built directly with the Xaero-independent {@link CoarseMapBuilder}.
 *
 * <p>Written for the Nether, flying between the floor (y=32) and the bedrock ceiling (y=120).
 */
class CoarseFlightRouterTest {

    private static final int MIN_CHUNK = -20;
    private static final int CHUNKS = 41;
    private static final int MIN_Y = 34;
    /** Below the bedrock ceiling. The ceiling itself is opaque, so it isn't recorded as a floor. */
    private static final int MAX_Y = 118;

    private static final int FLOOR = 32;

    /** An open Nether with a single floor in every cell. */
    private static CoarseMapBuilder openNether() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, FLOOR);
            }
        }
        return builder;
    }

    private static CoarseAirMap air(CoarseMapBuilder builder) {
        return CoarseAirMap.from(builder.build(), MIN_Y, MAX_Y);
    }

    private static CoarseRouter.Route route(CoarseMapBuilder builder, BlockPos start, BlockPos goal) {
        return CoarseFlightRouter.findRoute(air(builder), start, goal, true);
    }

    @Test
    void derivesOneWideBandOverAFlatFloor() {
        CoarseAirMap map = air(openNether());

        assertEquals(1, map.bandCount(0, 0));
        assertTrue(map.bandBottom(0, 0, 0) > FLOOR, "the band includes the space right above the floor");
        assertEquals(MAX_Y, map.bandTop(0, 0, 0), "the topmost band doesn't extend to the ceiling");
    }

    @Test
    void dropsBandsThatAreTooThinToFlyThrough() {
        // A ceiling (i.e. the next floor) at y=44, just above floor 32. The gap is too thin to fly through
        CoarseMapBuilder builder = openNether();
        builder.putFloor(0, 0, CoarseMap.LAND, 44);
        CoarseAirMap map = air(builder);

        // The band above 32 is 36..40, thin, so it's discarded and only the band above 44 remains
        assertEquals(1, map.bandCount(0, 0));
        assertTrue(map.bandBottom(0, 0, 0) > 44, "the thin band remains");
    }

    @Test
    void routesStraightAcrossOpenNether() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        assertTrue(route.reachedGoal(), "doesn't reach on open terrain");
        assertFalse(route.isEmpty());
        assertTrue(route.waypoints().stream().allMatch(point -> Math.abs(point.getZ()) < 64),
                "swerves sideways where it could go straight: " + route.waypoints());
    }

    /**
     * The only shape in which the coarse layer can represent a "wall": a cell whose floors are packed up near the ceiling
     * so that no band thick enough to fly through remains. Conversely, since there are at most 4 floors, a column fully
     * blocked from low down to the ceiling can't be fully represented in this layer (that's layer 3's job).
     */
    private static void sealColumn(CoarseMapBuilder builder, int chunkX, int chunkZ) {
        // A floor just below the ceiling removes the topmost band, and the band below is also thinned out
        builder.putFloor(chunkX, chunkZ, CoarseMap.LAND, MAX_Y - 2);
        builder.putFloor(chunkX, chunkZ, CoarseMap.LAND, MAX_Y - 14);
    }

    @Test
    void goesAroundAWallOfSealedColumns() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                if (x == 0 && z <= 4) {
                    sealColumn(builder, x, z);
                } else {
                    builder.putFloor(x, z, CoarseMap.LAND, MAX_Y - 20);
                }
            }
        }
        CoarseAirMap map = air(builder);
        assertTrue(map.blocked(0, 0), "the blocked column didn't become a wall");
        assertFalse(map.blocked(0, 8), "columns that aren't walls are blocked too");

        CoarseRouter.Route route = CoarseFlightRouter.findRoute(map,
                new BlockPos(-300, 110, 0), new BlockPos(300, 110, 0), true);

        assertTrue(route.reachedGoal(), "didn't go around the wall");
        assertTrue(route.waypoints().stream().anyMatch(point -> point.getZ() > 70),
                "didn't go around the end of the wall (chunk z>4): " + route.waypoints());
    }

    @Test
    void tellsAWallApartFromUnvisitedGround() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        sealColumn(builder, 0, 0);
        CoarseAirMap map = air(builder);

        assertTrue(map.blocked(0, 0), "a cell packed with floors isn't judged a wall");
        assertFalse(map.unknown(0, 0), "treated as unvisited despite having data");
        assertTrue(map.unknown(5, 5), "a cell with nothing written isn't treated as unvisited");
        assertFalse(map.blocked(5, 5), "an unvisited cell became a wall");
    }

    @Test
    void staysInTheLowerBandWhenTheUpperOneIsSealedOff() {
        // Two layers everywhere. Bands form above the lower layer (32) and above the upper layer (80).
        // The upper layer blocks up to the ceiling at column x=0, so there's no way through the upper band
        CoarseMapBuilder builder = openNether();
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 80);
            }
        }
        CoarseAirMap map = air(builder);
        assertEquals(2, map.bandCount(0, 0), "two floor layers didn't produce two bands");

        CoarseRouter.Route route = CoarseFlightRouter.findRoute(map,
                new BlockPos(-300, 40, 0), new BlockPos(300, 40, 0), true);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().allMatch(point -> point.getY() < 80),
                "started from the lower band but jumped to the upper band (floor = going through rock): "
                        + route.waypoints());
    }

    @Test
    void treatsUnmappedGroundAsPassable() {
        // A map with nothing written = unvisited. For flight this doesn't mean "known to be impassable"
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);

        CoarseRouter.Route route = route(builder, new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        assertTrue(route.reachedGoal(), "the unvisited area became a wall");
    }

    @Test
    void thinsWaypointsToTheSameSpacingAsTheWalkingLayer() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        List<BlockPos> waypoints = route.waypoints();
        for (int i = 1; i < waypoints.size() - 1; i++) {
            double spacing = Math.hypot(waypoints.get(i).getX() - waypoints.get(i - 1).getX(),
                    waypoints.get(i).getZ() - waypoints.get(i - 1).getZ());
            assertTrue(spacing >= 32.0 && spacing <= 128.0,
                    "waypoint spacing is off from the expected (around 64 blocks): " + spacing);
        }
    }

    @Test
    void reportsNotReachedWhenTheGoalIsOutsideTheMap() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(0, 70, 0), new BlockPos(9000, 70, 0));

        assertFalse(route.reachedGoal());
        assertTrue(route.isEmpty());
    }
}
