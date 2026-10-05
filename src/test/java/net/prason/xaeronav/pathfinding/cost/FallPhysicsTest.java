package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies that the vanilla fall formula ({@code velocity = (velocity - 0.08) * 0.98}) implemented by {@link FallPhysics}
 * is integrated correctly. If this goes wrong, the jump, fall and heuristic costs all shift via {@link ActionCosts},
 * so it's worth paying for more than any other class.
 */
class FallPhysicsTest {

    @Test
    void zeroDistanceTakesNoTicks() {
        assertEquals(0.0, FallPhysics.ticksToFall(0.0));
        assertEquals(0.0, FallPhysics.ticksToFall(-1.0));
    }

    @Test
    void longerFallsTakeMoreTicks() {
        double previous = 0.0;
        for (double distance = 1.0; distance <= 200.0; distance += 1.0) {
            double ticks = FallPhysics.ticksToFall(distance);
            assertTrue(ticks > previous, "distance=" + distance + " must not be shorter than the previous one");
            previous = ticks;
        }
    }

    /**
     * After a long enough fall, the ticks per block converge to the reciprocal of the terminal velocity, 3.92 blocks/tick.
     * If this is off, fall costs down deep shafts are estimated more leniently/strictly than actual vanilla behavior.
     */
    @Test
    void approachesTerminalVelocityOverLongFalls() {
        double perBlockNear = (FallPhysics.ticksToFall(1000.0) - FallPhysics.ticksToFall(999.0));
        assertEquals(1.0 / 3.92, perBlockNear, 0.01);
    }
}
