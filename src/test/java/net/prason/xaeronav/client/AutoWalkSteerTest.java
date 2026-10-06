package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Auto-walk steering: where it looks, which keys it holds, and where it hands control back to the player.
 *
 * <p>The stops matter most. Auto-walk walking into a step it can't do (a gap jump, a bridge) or into danger is
 * worse than not having it, so those have to stop it a few steps ahead, not on the step itself.
 */
class AutoWalkSteerTest {

    private static final int Y = 64;

    private static PathStep walk(int x, int y, int z) {
        return step(x, y, z, MovementType.TRAVERSE, PathRisk.NONE);
    }

    private static PathStep step(int x, int y, int z, MovementType movement, PathRisk risk) {
        return new PathStep(new BlockPos(x, y, z), movement, 4.0, List.of(), List.of(), risk, null);
    }

    /** A straight route along +X from x=0. */
    private static List<PathStep> straightX(int length) {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 0; x < length; x++) {
            steps.add(walk(x, Y, 0));
        }
        return steps;
    }

    private static AutoWalkSteer.Player standingAt(double x, double z, float yaw) {
        return new AutoWalkSteer.Player(x, Y, z, yaw, true, false, false);
    }

    @Test
    void walksAndSprintsAlongAStraightRoute() {
        // Facing +X is yaw -90 in Minecraft's convention
        AutoWalkSteer.Command command = AutoWalkSteer.steer(straightX(20), 2, standingAt(2.5, 0.5, -90.0F), true);

        assertEquals(AutoWalkSteer.Stop.NONE, command.stop());
        assertTrue(command.forward());
        assertTrue(command.sprint(), "Long straight stretch ahead");
        assertFalse(command.jump());
        assertEquals(-90.0F, command.yaw(), 1.0e-3F);
    }

    @Test
    void doesNotSprintWhenSprintingIsOff() {
        AutoWalkSteer.Command command = AutoWalkSteer.steer(straightX(20), 2, standingAt(2.5, 0.5, -90.0F), false);

        assertTrue(command.forward());
        assertFalse(command.sprint());
    }

    @Test
    void turnsOnTheSpotBeforeWalkingOff() {
        // Facing -X (yaw 90), route goes +X: needs a half turn, limited per tick, without walking off the route meanwhile
        AutoWalkSteer.Command command = AutoWalkSteer.steer(straightX(20), 2, standingAt(2.5, 0.5, 90.0F), true);

        assertEquals(AutoWalkSteer.MAX_TURN_PER_TICK, Math.abs(AutoWalkSteer.wrapDegrees(command.yaw() - 90.0F)), 1.0e-3F);
        assertFalse(command.forward());
        assertFalse(command.sprint());
    }

    @Test
    void doesNotSprintIntoACorner() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            steps.add(walk(x, Y, 0));
        }
        for (int z = 1; z <= 10; z++) {
            steps.add(walk(4, Y, z));
        }

        AutoWalkSteer.Command command = AutoWalkSteer.steer(steps, 2, standingAt(2.5, 0.5, -90.0F), true);

        assertTrue(command.forward());
        assertFalse(command.sprint(), "The route turns two blocks ahead");
    }

    @Test
    void jumpsUpAStep() {
        List<PathStep> steps = new ArrayList<>(straightX(3));
        steps.add(step(3, Y + 1, 0, MovementType.ASCEND, PathRisk.NONE));
        steps.add(walk(4, Y + 1, 0));

        AutoWalkSteer.Command command = AutoWalkSteer.steer(steps, 2, standingAt(2.6, 0.5, -90.0F), true);

        assertTrue(command.forward());
        assertTrue(command.jump());
    }

    @Test
    void jumpsWhenWalkingIntoAWall() {
        AutoWalkSteer.Player bumped = new AutoWalkSteer.Player(2.5, Y, 0.5, -90.0F, true, false, true);

        assertTrue(AutoWalkSteer.steer(straightX(20), 2, bumped, true).jump());
    }

    @Test
    void swimsUpUnlessTheRouteGoesDown() {
        List<PathStep> level = straightX(10);
        AutoWalkSteer.Player swimming = new AutoWalkSteer.Player(2.5, Y - 0.1, 0.5, -90.0F, false, true, false);
        assertTrue(AutoWalkSteer.steer(level, 2, swimming, true).jump());
        assertFalse(AutoWalkSteer.steer(level, 2, swimming, true).sprint(), "No sprint-swimming");

        List<PathStep> down = new ArrayList<>(straightX(3));
        down.add(step(3, Y - 2, 0, MovementType.SWIM, PathRisk.NONE));
        down.add(step(4, Y - 2, 0, MovementType.SWIM, PathRisk.NONE));
        AutoWalkSteer.Player surface = new AutoWalkSteer.Player(2.6, Y, 0.5, -90.0F, false, true, false);
        assertFalse(AutoWalkSteer.steer(down, 2, surface, true).jump());
    }

    @Test
    void stopsAheadOfStepsThatNeedThePlayer() {
        List<PathStep> steps = new ArrayList<>(straightX(4));
        steps.add(step(4, Y, 0, MovementType.JUMP, PathRisk.NONE));
        steps.add(walk(7, Y, 0));

        assertEquals(AutoWalkSteer.Stop.NONE, AutoWalkSteer.steer(steps, 0, standingAt(0.5, 0.5, -90.0F), true).stop(),
                "Still far enough away");
        assertEquals(AutoWalkSteer.Stop.MANUAL_STEP, AutoWalkSteer.steer(steps, 1, standingAt(1.5, 0.5, -90.0F), true).stop());

        List<PathStep> bridge = new ArrayList<>(straightX(3));
        bridge.add(new PathStep(new BlockPos(3, Y, 0), MovementType.TRAVERSE, 20.0, List.of(), List.of(), PathRisk.NONE,
                new BlockPos(3, Y - 1, 0)));
        assertEquals(AutoWalkSteer.Stop.MANUAL_STEP, AutoWalkSteer.steer(bridge, 1, standingAt(1.5, 0.5, -90.0F), true).stop());

        List<PathStep> dig = new ArrayList<>(straightX(3));
        dig.add(new PathStep(new BlockPos(3, Y, 0), MovementType.TRAVERSE, 20.0, List.of(),
                List.of(new BlockPos(3, Y, 0)), PathRisk.NONE, null));
        assertEquals(AutoWalkSteer.Stop.MANUAL_STEP, AutoWalkSteer.steer(dig, 1, standingAt(1.5, 0.5, -90.0F), true).stop());
    }

    @Test
    void stopsAheadOfDanger() {
        for (PathRisk risk : new PathRisk[] {PathRisk.LAVA_ADJACENT, PathRisk.VOID_BELOW, PathRisk.FALL_DAMAGE,
                PathRisk.MLG_REQUIRED, PathRisk.DROWNING, PathRisk.SNEAK_OVER_MAGMA}) {
            List<PathStep> steps = new ArrayList<>(straightX(3));
            steps.add(step(3, Y, 0, MovementType.TRAVERSE, risk));
            assertEquals(AutoWalkSteer.Stop.DANGER,
                    AutoWalkSteer.steer(steps, 1, standingAt(1.5, 0.5, -90.0F), true).stop(), risk.name());
        }
        List<PathStep> fall = new ArrayList<>(straightX(3));
        fall.add(step(3, Y - 6, 0, MovementType.FALL_DAMAGE, PathRisk.NONE));
        assertEquals(AutoWalkSteer.Stop.DANGER, AutoWalkSteer.steer(fall, 1, standingAt(1.5, 0.5, -90.0F), true).stop());
    }

    @Test
    void waterInflowIsNotDangerEnoughToStop() {
        List<PathStep> steps = new ArrayList<>(straightX(3));
        steps.add(step(3, Y, 0, MovementType.TRAVERSE, PathRisk.WATER_INFLOW));
        steps.add(walk(4, Y, 0));

        assertEquals(AutoWalkSteer.Stop.NONE, AutoWalkSteer.steer(steps, 1, standingAt(1.5, 0.5, -90.0F), true).stop());
    }

    @Test
    void stopsOnTheLastStep() {
        List<PathStep> steps = straightX(5);

        assertEquals(AutoWalkSteer.Stop.NONE, AutoWalkSteer.steer(steps, 3, standingAt(3.5, 0.5, -90.0F), true).stop());
        assertEquals(AutoWalkSteer.Stop.END, AutoWalkSteer.steer(steps, 4, standingAt(4.5, 0.5, -90.0F), true).stop());
    }

    @Test
    void headsForTheMappedStepUntilItIsReached() {
        // The route turns at (4, 0). Mapped to the corner while still short of it, cutting toward (4, 1) would clip the corner
        List<PathStep> steps = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            steps.add(walk(x, Y, 0));
        }
        for (int z = 1; z <= 5; z++) {
            steps.add(walk(4, Y, z));
        }
        AutoWalkSteer.Player shortOfCorner = standingAt(4.0, 0.5, -90.0F);

        assertEquals(4, AutoWalkSteer.targetIndex(steps, 4, shortOfCorner));
        assertEquals(5, AutoWalkSteer.targetIndex(steps, 4, standingAt(4.5, 0.6, 0.0F)));
    }

    @Test
    void aimsAlongStraightRunsButNotAroundCorners() {
        List<PathStep> straight = straightX(20);
        assertEquals(3 + AutoWalkSteer.AIM_LOOKAHEAD, AutoWalkSteer.aimIndex(straight, 3));

        List<PathStep> corner = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            corner.add(walk(x, Y, 0));
        }
        corner.add(walk(4, Y, 1));
        assertEquals(4, AutoWalkSteer.aimIndex(corner, 3), "Stops at the corner");
    }

    @Test
    void wrapsYawTheShortWayRound() {
        assertEquals(10.0F, AutoWalkSteer.turnTowards(170.0F, -180.0F) - 170.0F, 1.0e-3F);
        assertEquals(-20.0F, AutoWalkSteer.wrapDegrees(340.0F), 1.0e-3F);
        assertEquals(-180.0F, AutoWalkSteer.wrapDegrees(180.0F), 1.0e-3F);
    }
}
