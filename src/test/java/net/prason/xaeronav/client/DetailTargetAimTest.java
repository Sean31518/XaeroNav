package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Two rules for "where to aim" in the detail search. Breaking either keeps handing the search targets
 * it fundamentally can't satisfy.
 *
 * <h4>Destinations too far away are cut short</h4>
 *
 * <p>The search box is cut by render distance, so destinations outside it can't be reached. In a real-game
 * probe, aiming at a destination 509 blocks away with no cap burned 465,536 nodes and 2 seconds without
 * reaching it (box 140×305). And that repeats every few seconds.
 *
 * <h4>Points too close aren't aimed at</h4>
 *
 * <p>The goal is a region, so if the start is inside it the search returns "reached" with 0 steps. The path
 * is empty, and since it's not a failure no escalation runs either (the symptom of issue #32).
 */
class DetailTargetAimTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final int REACH = 96;

    /** A destination within the distance reachable at once is aimed at as-is (cutting it short would never arrive). */
    @Test
    void aimsAtTheGoalWhenItIsWithinReach() {
        BlockPos goal = new BlockPos(REACH, 64, 0);

        assertEquals(goal, PathfindingState.aimTowardGoal(START, goal, REACH));
    }

    /** A destination too far away is replaced by the point exactly {@code reach} along its direction. */
    @Test
    void clipsAGoalBeyondReachToAPointAlongTheWay() {
        BlockPos goal = new BlockPos(509, 64, 0);

        BlockPos aim = PathfindingState.aimTowardGoal(START, goal, REACH);

        assertNotEquals(goal, aim, "aiming directly at a destination outside the box");
        assertEquals(REACH, aim.getX(), "not the point reach along the destination's direction");
        assertEquals(0, aim.getZ());
    }

    /** Cut by distance even diagonally (cutting per axis saturates the near axis first and skews the direction). */
    @Test
    void clipsDiagonallyByDistanceNotPerAxis() {
        BlockPos goal = new BlockPos(400, 64, 300);

        BlockPos aim = PathfindingState.aimTowardGoal(START, goal, REACH);

        double distance = Math.sqrt(aim.getX() * aim.getX() + (double) aim.getZ() * aim.getZ());
        assertEquals(REACH, distance, 1.0, "distance to the cut point doesn't match reach");
        assertEquals(400.0 / 300.0, (double) aim.getX() / aim.getZ(), 0.05, "direction is skewed");
    }

    /** If the target is too close the search ends in 0 steps; treat that distance as the boundary. */
    @Test
    void refusesAnAimTooCloseToProduceAPath() {
        assertTrue(PathfindingState.tooCloseToAim(START, START), "not refusing to aim at our own position");
        assertTrue(PathfindingState.tooCloseToAim(START, new BlockPos(10, 64, 0)));
    }

    /** Aim normally when far enough. Refusing this far would discard even sensible intermediate targets. */
    @Test
    void acceptsAnAimFarEnoughAway() {
        assertFalse(PathfindingState.tooCloseToAim(START, new BlockPos(40, 64, 0)));
    }
}
