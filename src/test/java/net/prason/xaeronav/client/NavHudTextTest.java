package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Choosing HUD wording that doesn't mistake the end of the path for the destination. */
class NavHudTextTest {

    @Test
    void onlyAPathEndingAtTheDestinationUsesTheUnqualifiedRemainingLabel() {
        assertEquals("hud.xaeronav.remaining", NavHud.remainingKey(true, false));
        assertEquals("hud.xaeronav.path_remaining", NavHud.remainingKey(false, false));
        assertEquals("hud.xaeronav.path_remaining_eta", NavHud.remainingKey(false, true));
    }

    @Test
    void arrivalTextDistinguishesDestinationSurfaceAndIntermediateEnds() {
        assertEquals("hud.xaeronav.arriving",
                NavHud.endpointKey(false, true, false));
        assertEquals("hud.xaeronav.surface_ahead",
                NavHud.endpointKey(true, false, false));
        assertEquals("hud.xaeronav.route_continues",
                NavHud.endpointKey(false, false, false));
    }

    @Test
    void aStuckRouteDoesNotPromiseThatGuidanceContinues() {
        assertNull(NavHud.endpointKey(false, false, true));
    }
}
