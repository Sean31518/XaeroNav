package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class FlightPathfinderTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-160, 0, -160, 160, 128, 160);
    private static final int CELL = 4;
    private static final double GOAL_RADIUS = 6.0;

    private static FlightRoute route(FakeCells cells, Vec3 start, Vec3 goal, boolean rockets) {
        return route(cells, start, goal, rockets, 0.0);
    }

    private static FlightRoute route(FakeCells cells, Vec3 start, Vec3 goal, boolean rockets,
                                      double clearancePenaltyTicks) {
        return new FlightPathfinder(new AirGrid(cells, CELL), rockets, SearchLimits.DEFAULT,
                clearancePenaltyTicks).search(start, goal, GOAL_RADIUS);
    }

    /** A Nether-like space with a ceiling and floor. Fills everything at or below {@code floor} and at or above {@code ceiling} with rock. */
    private static FakeCells nether(int floor, int ceiling) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -160; x <= 160; x++) {
            for (int z = -160; z <= 160; z++) {
                for (int y = 0; y <= floor; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
                for (int y = ceiling; y <= 128; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /** Whether every segment of the polyline passes only through flyable cells. */
    private static boolean staysInOpenAir(FlightRoute route, FakeCells cells) {
        AirGrid grid = new AirGrid(cells, CELL);
        for (int i = 0; i + 1 < route.points().size(); i++) {
            if (!grid.clearLine(route.points().get(i), route.points().get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    private static double maxY(FlightRoute route) {
        return route.points().stream().mapToDouble(Vec3::y).max().orElse(Double.NaN);
    }

    @Test
    void openSkyCollapsesToASingleStraightSegment() {
        FlightRoute route = route(FakeCells.empty(BOUNDS), new Vec3(-100.0, 64.0, 0.0),
                new Vec3(100.0, 64.0, 0.0), false);

        assertTrue(route.complete(), "Didn't reach the destination in empty sky: " + route.termination());
        assertEquals(2, route.points().size(), "Bends remain even after smoothing: " + route.points());
    }

    @Test
    void threadsThroughTheOnlyGapInAWall() {
        FakeCells cells = nether(32, 120);
        // A wall at X=0 reaching up to the ceiling. Only around Z=40 is left open
        for (int y = 33; y < 120; y++) {
            for (int z = -160; z <= 160; z++) {
                if (z >= 36 && z <= 48) {
                    continue;
                }
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        FlightRoute route = route(cells, new Vec3(-100.0, 80.0, 0.0), new Vec3(100.0, 80.0, 0.0), false);

        assertTrue(route.complete(), "Couldn't get through the only gap: " + route.termination());
        assertTrue(staysInOpenAir(route, cells), "The route goes through rock: " + route.points());
        double gapZ = route.points().stream()
                .filter(point -> Math.abs(point.x) < 8.0)
                .mapToDouble(Vec3::z)
                .findFirst()
                .orElse(Double.NaN);
        assertTrue(gapZ >= 32.0 && gapZ <= 52.0, "The position where the route passes the wall is off from the gap: z=" + gapZ);
    }

    @Test
    void goesAroundRatherThanOverWhenTheCeilingIsInTheWay() {
        // The essence of the Nether. The wall reaches the ceiling, so "going over it" is fundamentally not an option
        FakeCells cells = nether(32, 96);
        for (int y = 33; y < 96; y++) {
            for (int z = -160; z <= 60; z++) {
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        FlightRoute route = route(cells, new Vec3(-100.0, 64.0, 0.0), new Vec3(100.0, 64.0, 0.0), false);

        assertTrue(route.complete(), "Couldn't go around the wall: " + route.termination());
        assertTrue(staysInOpenAir(route, cells), "The route goes through rock: " + route.points());
        assertTrue(maxY(route) < 96.0, "A route passing above the bedrock ceiling came out: " + maxY(route));
        assertTrue(route.points().stream().anyMatch(point -> point.z > 55.0),
                "Didn't go around the end of the wall (z>60): " + route.points());
    }

    @Test
    void doesNotRouteThroughUnloadedSpace() {
        // Never draw a route into what can't be read. Beyond the range is the dotted line's job
        FakeCells cells = FakeCells.empty(new SearchBounds(-160, 0, -160, 160, 128, 8));

        FlightRoute route = route(cells, new Vec3(0.0, 64.0, 0.0), new Vec3(0.0, 64.0, 120.0), false);

        assertFalse(route.complete(), "Counted as reaching a destination outside the range");
        assertTrue(route.points().stream().allMatch(point -> point.z < 8.0),
                "The route extends outside the range: " + route.points());
    }

    @Test
    void climbsWhenItHasToRegardlessOfRockets() {
        // Terrain where climbing is the only way. Rockets change the cost, but a route must come out either way
        FakeCells cells = nether(32, 120);
        for (int y = 33; y <= 80; y++) {
            for (int z = -160; z <= 160; z++) {
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        for (boolean rockets : new boolean[] {false, true}) {
            FlightRoute route = route(cells, new Vec3(-100.0, 40.0, 0.0), new Vec3(100.0, 40.0, 0.0), rockets);

            assertTrue(route.complete(), "Couldn't get over the wall with rockets=" + rockets + ": " + route.termination());
            assertTrue(staysInOpenAir(route, cells), "The route goes through rock: " + route.points());
            assertTrue(maxY(route) > 80.0, "Didn't get over the wall: " + maxY(route));
        }
    }

    @Test
    void prefersDescendingOverStayingLevelWhenBothAreOpen() {
        // "Level flight is already climbing". If both reach the same place, the one that can descend by gliding is cheaper
        FlightRoute route = route(FakeCells.empty(BOUNDS), new Vec3(-100.0, 100.0, 0.0),
                new Vec3(100.0, 40.0, 0.0), false);

        assertTrue(route.complete());
        List<Vec3> points = route.points();
        for (int i = 0; i + 1 < points.size(); i++) {
            assertTrue(points.get(i + 1).y <= points.get(i).y + 1.0e-6,
                    "Climbs back up partway through the descent: " + points);
        }
    }

    @Test
    void reusingOneInstanceForASecondGoalDoesNotKeepTheOldEstimates() {
        // Estimates are computed from the goal when nodes are created. Carrying the table over pulls the second search toward the previous goal
        FlightPathfinder pathfinder = new FlightPathfinder(
                new AirGrid(FakeCells.empty(BOUNDS), CELL), false, SearchLimits.DEFAULT, 0.0);
        Vec3 start = new Vec3(0.0, 64.0, 0.0);

        pathfinder.search(start, new Vec3(120.0, 64.0, 0.0), GOAL_RADIUS);
        FlightRoute second = pathfinder.search(start, new Vec3(-120.0, 64.0, 0.0), GOAL_RADIUS);

        assertTrue(second.complete(), "The second search didn't reach: " + second.termination());
        assertTrue(second.tail().x < -100.0, "The second route points toward the first goal: " + second.points());
    }

    /**
     * Opens two passages through a thick wall spanning X=-30 to 30: at z=2 a long tunnel whose cross-section is exactly one grid cell,
     * and at z=60-92 a passage with a wide cross-section. In straight-line distance the tunnel is shorter.
     */
    private static FakeCells wallWithATightTunnelAndAWideDetour(boolean withDetour) {
        FakeCells cells = nether(32, 120);
        for (int x = -30; x <= 30; x++) {
            for (int y = 33; y < 120; y++) {
                for (int z = -160; z <= 160; z++) {
                    boolean tightTunnel = z >= 0 && z <= 3 && y >= 60 && y <= 63;
                    boolean wideGap = withDetour && z >= 60 && z <= 92 && y >= 40 && y <= 104;
                    if (tightTunnel || wideGap) {
                        continue;
                    }
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    @Test
    void takesTheTightTunnelWhenNothingDiscouragesIt() {
        FlightRoute route = route(wallWithATightTunnelAndAWideDetour(true),
                new Vec3(-100.0, 62.0, 2.0), new Vec3(100.0, 62.0, 2.0), false, 0.0);

        assertTrue(route.complete(), "Couldn't get through the narrow tunnel: " + route.termination());
        assertTrue(route.points().stream().allMatch(point -> point.z < 40.0),
                "Detours even though there's no surcharge: " + route.points());
    }

    @Test
    void avoidsTheTightTunnelWhenClearanceIsWorthADetour() {
        // "Don't guide me through tight spots even if they're shortest". Choose the wide passage even though it loses on distance
        FlightRoute route = route(wallWithATightTunnelAndAWideDetour(true),
                new Vec3(-100.0, 62.0, 2.0), new Vec3(100.0, 62.0, 2.0), false, 12.0);

        assertTrue(route.complete(), "Couldn't get through via the wide passage either: " + route.termination());
        assertTrue(route.points().stream().anyMatch(point -> point.z > 50.0),
                "Still goes through the narrow tunnel even with the surcharge: " + route.points());
    }

    @Test
    void stillUsesATightPassageWhenItIsTheOnlyWay() {
        // A surcharge isn't a ban. If it's the only way, go through it (losing the route entirely is the worst outcome)
        FlightRoute route = route(wallWithATightTunnelAndAWideDetour(false),
                new Vec3(-100.0, 62.0, 2.0), new Vec3(100.0, 62.0, 2.0), false, 12.0);

        assertTrue(route.complete(), "Gives up on the only narrow tunnel because of the surcharge: " + route.termination());
    }

    @Test
    void reachesAGoalWhoseExactPointIsBuriedInTerrain() {
        // Intermediate waypoints are estimates (chunk center + band Y), so at block resolution they're often inside rock.
        // Requiring a spherical goal makes them unreachable in principle, and every search burns the node cap to discover
        // that; the more complex the terrain the more likely this is, and the route stops extending
        FakeCells cells = nether(32, 120);
        for (int x = 92; x <= 108; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 33; y <= 70; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }

        // The destination is inside rock. The 20 blocks directly above are open
        FlightRoute route = route(cells, new Vec3(-100.0, 80.0, 0.0), new Vec3(100.0, 60.0, 0.0), false);

        assertTrue(route.complete(), "Couldn't get close to a target buried in rock: " + route.termination());
        assertTrue(staysInOpenAir(route, cells), "The route goes through rock: " + route.points());
        assertTrue(route.expandedNodes() < 20_000,
                "Reached, but burned too much search (the region goal isn't working): " + route.expandedNodes());
    }

    @Test
    void reportsNoRouteFromInsideSolidRock() {
        FakeCells cells = FakeCells.empty(BOUNDS).fillWith(FakeCells.STONE);

        assertTrue(route(cells, new Vec3(0.0, 64.0, 0.0), new Vec3(100.0, 64.0, 0.0), false).isEmpty());
    }
}
