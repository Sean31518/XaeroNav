package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;

class FlightProgressTest {

    private static final double THRESHOLD = 24.0;

    /** A route of two straight, level segments with vertices 100 blocks apart. */
    private static FlightRoute straight() {
        return new FlightRoute(List.of(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(100.0, 64.0, 0.0),
                new Vec3(200.0, 64.0, 0.0)), PathResult.Termination.REACHED_GOAL, 1, 4);
    }

    private static FlightProgress at(FlightRoute route, Vec3 position) {
        FlightProgress.INSTANCE.update(route, position);
        return FlightProgress.INSTANCE;
    }

    @Test
    void onTheLineBetweenTwoDistantPointsCountsAsZeroOffset() {
        // Measured at vertices, this would be off by 50 blocks. It's 0 precisely because it's measured against the segment
        FlightProgress progress = at(straight(), new Vec3(50.0, 64.0, 0.0));

        assertEquals(0.0, progress.horizontalOffset(), 1.0e-6);
        assertEquals(0.0, progress.verticalOffset(), 1.0e-6);
        assertFalse(progress.deviated(THRESHOLD));
    }

    @Test
    void driftingSidewaysWithinToleranceDoesNotCountAsDeviation() {
        assertFalse(at(straight(), new Vec3(50.0, 64.0, 20.0)).deviated(THRESHOLD));
    }

    @Test
    void driftingWellOutsideToleranceCountsAsDeviation() {
        assertTrue(at(straight(), new Vec3(50.0, 64.0, 40.0)).deviated(THRESHOLD));
    }

    @Test
    void verticalDriftIsAllowedFurtherThanHorizontal() {
        // Altitude wobbles more than horizontal position. Allow vertically what would be off-route horizontally
        double justOverHorizontal = THRESHOLD * 1.2;

        assertTrue(at(straight(), new Vec3(50.0, 64.0, justOverHorizontal)).deviated(THRESHOLD));
        assertFalse(at(straight(), new Vec3(50.0, 64.0 + justOverHorizontal, 0.0)).deviated(THRESHOLD));
    }

    @Test
    void combinedHorizontalAndVerticalDriftAddUp() {
        // Why an ellipsoid: within tolerance on either axis alone, but off-route when combined
        FlightRoute route = straight();

        assertFalse(at(route, new Vec3(50.0, 64.0, 20.0)).deviated(THRESHOLD));
        assertFalse(at(route, new Vec3(50.0, 94.0, 0.0)).deviated(THRESHOLD));
        assertTrue(at(route, new Vec3(50.0, 94.0, 20.0)).deviated(THRESHOLD));
    }

    @Test
    void tracksWhichSegmentThePlayerIsOn() {
        FlightRoute route = straight();

        assertEquals(0, at(route, new Vec3(10.0, 64.0, 0.0)).segmentFor(route));
        assertEquals(1, at(route, new Vec3(150.0, 64.0, 0.0)).segmentFor(route));
    }

    @Test
    void theSegmentIndexFollowsTheRouteNotTheStartOfTheList() {
        // The dotted line's trimming uses this index. Passing the thick line's end rather than the player is the caller's
        // responsibility, but if this were "always 0" trimming would never work and the dotted line would run back from the end, looking like two lines
        FlightRoute route = straight();

        assertEquals(0, at(route, new Vec3(0.0, 64.0, 0.0)).segmentFor(route));
        assertEquals(1, at(route, new Vec3(199.0, 64.0, 0.0)).segmentFor(route));
    }

    @Test
    void carryingOverKeepsTheSegmentWhenTheRouteIsExtended() {
        // Extending doesn't change the indices of earlier points, so the mapping stays valid.
        // Without carrying it over, the index would reset to 0 and already-passed segments would be redrawn just at the moment of extension
        FlightRoute route = straight();
        at(route, new Vec3(150.0, 64.0, 0.0));
        assertEquals(1, FlightProgress.INSTANCE.segmentFor(route));

        FlightRoute extended = route.append(new FlightRoute(
                List.of(new Vec3(200.0, 64.0, 0.0), new Vec3(300.0, 64.0, 0.0)),
                PathResult.Termination.REACHED_GOAL, 1, 4));
        FlightProgress.INSTANCE.carryOver(extended);

        assertEquals(4, extended.points().size(), "Extension duplicated points");
        assertEquals(1, FlightProgress.INSTANCE.segmentFor(extended),
                "Extension reset the mapping to the start");
    }

    @Test
    void anEmptyRouteReportsNoDeviation() {
        assertFalse(at(FlightRoute.NONE, new Vec3(0.0, 64.0, 0.0)).deviated(THRESHOLD));
    }
}
