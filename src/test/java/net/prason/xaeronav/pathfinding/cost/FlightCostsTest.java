package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ElytraPhysics.Velocity;

/**
 * Pins down the glide polar derived from vanilla's recurrence.
 *
 * <p>If this moves, the slope of the cost model (the balance between climbing and level flight) has changed, so the
 * look of routes will change too. The goal is to remember the values themselves, not to forbid changing them.
 */
class FlightCostsTest {

    private static final double TOLERANCE = 1.0e-3;

    @Test
    void bestGlideMatchesTheVanillaRecurrence() {
        Velocity glide = ElytraPhysics.steadyState(0.0, false);

        // 30.2 blocks/s, glide ratio 10.1. Both match values known in the community
        assertEquals(1.5102, glide.horizontal(), TOLERANCE);
        assertEquals(-0.1495, glide.vertical(), TOLERANCE);
        assertEquals(10.10, glide.glideRatio(), 0.01);
    }

    @Test
    void divingIsFasterHorizontallyButBurnsAltitude() {
        Velocity fastest = ElytraPhysics.bestSteadyState(0.0, 80.0, 0.5, false, Velocity::horizontal);

        assertTrue(fastest.horizontal() > 3.3, "Fastest horizontal cruise isn't reached: " + fastest);
        assertTrue(fastest.glideRatio() < ElytraPhysics.steadyState(0.0, false).glideRatio(),
                "Glide ratio of the fastest attitude exceeds the best glide: " + fastest);
    }

    @Test
    void glidingCannotClimbInSteadyState() {
        // Without rockets, the steady-state vertical component is negative at every attitude. The basis for "level flight is already climbing"
        for (double pitch = -90.0; pitch <= 80.0; pitch += 5.0) {
            Velocity steady = ElytraPhysics.steadyState(pitch, false);
            assertTrue(steady.vertical() < 0.0,
                    "Altitude can be held at pitch " + pitch + ": " + steady);
        }
    }

    @Test
    void terminalDiveMatchesVanillaFallPhysics() {
        // Gliding straight down has zero lift, so it settles at terminal velocity 3.92, same as a plain fall
        assertEquals(-3.92, ElytraPhysics.steadyState(90.0, false).vertical(), TOLERANCE);
    }

    @Test
    void rocketsMakeClimbingMuchCheaper() {
        assertTrue(FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK * 3.0 < FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK,
                "Climb cost with and without rockets doesn't differ by 3x: with rockets "
                        + FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK + " / without "
                        + FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK);
    }

    @Test
    void derivedConstantsAreStable() {
        assertEquals(0.6622, FlightCosts.HORIZONTAL_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(10.10, FlightCosts.GLIDE_RATIO, 0.01);
        assertEquals(0.2551, FlightCosts.DESCENT_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(0.6373, FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(2.2365, FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK, 0.01);
    }

    @Test
    void glidingDownTheNaturalSlopeIsFreeButLevelFlightIsNot() {
        double distance = 100.0;
        double naturalDrop = -distance / FlightCosts.GLIDE_RATIO;

        double gliding = FlightCosts.segmentTicks(distance, naturalDrop, false);
        double level = FlightCosts.segmentTicks(distance, 0.0, false);

        assertTrue(level > gliding * 1.2,
                "Level flight costs about the same as natural gliding: level " + level + " / gliding " + gliding);
    }

    @Test
    void divingSteeperThanTheGlideSlopeIsNotRewarded() {
        double distance = 100.0;
        double natural = FlightCosts.segmentTicks(distance, -distance / FlightCosts.GLIDE_RATIO, false);
        double steep = FlightCosts.segmentTicks(distance, -50.0, false);

        assertTrue(steep > natural, "A steep dive that throws away altitude is cheaper than the best glide");
    }

    @Test
    void heuristicNeverExceedsTheSegmentCost() {
        // A* admissibility. Confirms the gliding discount is placed only on the segment cost side
        for (double horizontal = 0.0; horizontal <= 200.0; horizontal += 7.0) {
            for (double vertical = -100.0; vertical <= 100.0; vertical += 7.0) {
                for (boolean rockets : new boolean[] {false, true}) {
                    double estimate = FlightCosts.heuristicTicks(horizontal, vertical, rockets);
                    double actual = FlightCosts.segmentTicks(horizontal, vertical, rockets);
                    assertTrue(estimate <= actual + 1.0e-9,
                            "Estimate exceeded the segment cost: horizontal " + horizontal + " vertical " + vertical
                                    + " rockets " + rockets + " → estimate " + estimate + " / actual " + actual);
                }
            }
        }
    }

    @Test
    void lowerBoundNeverExceedsAnyTwoLegDetour() {
        // A polyline through a waypoint never goes below the lower bound of the single segment from start to end (A* admissibility and consistency)
        for (boolean rockets : new boolean[] {false, true}) {
            for (double viaX = -60.0; viaX <= 160.0; viaX += 20.0) {
                for (double viaY = -80.0; viaY <= 80.0; viaY += 20.0) {
                    for (double endY = -60.0; endY <= 60.0; endY += 30.0) {
                        double first = FlightCosts.segmentTicks(Math.abs(viaX), viaY, rockets);
                        double second = FlightCosts.segmentTicks(Math.abs(100.0 - viaX), endY - viaY, rockets);
                        double bound = FlightCosts.lowerBoundTicks(100.0, endY, endY, rockets);
                        assertTrue(bound <= first + second + 1.0e-9,
                                "Lower bound exceeded the polyline: via " + viaX + "," + viaY + " end Y " + endY);
                    }
                }
            }
        }
    }
}
