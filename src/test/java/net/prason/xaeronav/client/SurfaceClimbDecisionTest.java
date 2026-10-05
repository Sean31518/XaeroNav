package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The height check for whether to enter surface-first navigation ({@code PathfindingState#climbWorthwhile}).
 *
 * <p>Pins that the reference Y is <b>the local surface, not a config constant</b> (#45).
 */
class SurfaceClimbDecisionTest {

    @Test
    void climbsOutOfADeepCaveUnderAMountain() {
        // A cave under a mountain (y=70). The surface is at 130, so it is 60 blocks underground, but with the config default (60)
        // as the reference it was judged "already at surface height" and never entered the relay leg
        assertTrue(PathfindingState.climbWorthwhile(70, 135, 130));
        assertFalse(PathfindingState.climbWorthwhile(70, 135, 60), "missed when using the default constant as reference");
    }

    @Test
    void staysUndergroundWhenTheGoalIsAlsoUnderground() {
        // Cave to cave. No reason to surface and then dive back down
        assertFalse(PathfindingState.climbWorthwhile(70, 90, 130));
        assertFalse(PathfindingState.climbWorthwhile(30, 40, 64));
    }

    @Test
    void ignoresShallowDepthsWhereTheExitIsAlreadyNearby() {
        assertFalse(PathfindingState.climbWorthwhile(61, 70, 64), "just below the surface is not worth relaying");
        assertTrue(PathfindingState.climbWorthwhile(59, 70, 64));
    }

    @Test
    void worksBelowSeaLevelWhereTheLocalSurfaceIsLow() {
        // On the seabed or a valley floor the surface itself is below the default of 60
        assertTrue(PathfindingState.climbWorthwhile(20, 45, 45));
        assertFalse(PathfindingState.climbWorthwhile(43, 45, 45));
    }
}
