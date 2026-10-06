package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.RouteProfile;

/**
 * The movement options routes are planned with while auto-walk is on: only what it can follow by itself.
 */
class MovementOptionsAutoWalkTest {

    private static MovementOptions everythingAllowed() {
        return new MovementOptions(true, true, true, true, 96, 30, 96, 250, true, false, true, 4, false,
                RouteProfile.FASTEST, true, true, true);
    }

    @Test
    void avoidsWaterAirAndJumps() {
        MovementOptions auto = everythingAllowed().forAutoWalk();

        assertFalse(auto.swimmingEnabled(), "No swimming");
        assertFalse(auto.bridgingEnabled(), "No blocks placed into the air");
        assertFalse(auto.lavaBridgingEnabled());
        assertFalse(auto.jumpGapEnabled(), "No gap jumps");
        assertFalse(auto.fallDamageToleranceEnabled(), "No falls that hurt");
        assertTrue(auto.avoidRiskyJumps());
    }

    @Test
    void keepsWhatAutoWalkHandsBackToThePlayer() {
        MovementOptions auto = everythingAllowed().forAutoWalk();

        assertTrue(auto.diggingEnabled(), "Digging stays; auto-walk stops before it");
        assertTrue(auto.boatsEnabled(), "Boats stay; auto-walk steers them");
        assertEquals(RouteProfile.FASTEST, auto.routeProfile());
        assertEquals(4, auto.blockBudgetReserve());
    }
}
