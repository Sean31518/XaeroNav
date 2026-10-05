package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Which step to rejoin when you've strayed from the path.
 *
 * <p><b>Everything before the join point is discarded</b>, so the further ahead you can rejoin, the shorter the
 * remaining distance. Choosing "the single closest point" by distance alone picks a step behind you where the path
 * bends, making you walk the stretch you just came along again; this is the shape of the user report "when it
 * recomputes, the connection to the previously decided route isn't optimal".
 */
class SpliceJoinTest {

    private static final int Y = 64;

    private static PathStep step(int x, int z) {
        return new PathStep(new BlockPos(x, Y, z), MovementType.TRAVERSE, 4.0,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** A right-angled path going 10 east, then turning 10 south. */
    private static List<PathStep> corner() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            steps.add(step(x, 0));
        }
        for (int z = 1; z <= 10; z++) {
            steps.add(step(10, z));
        }
        return steps;
    }

    private static int join(List<PathStep> steps, Vec3 position) {
        return Splice.joinableStepIndex(steps, position, 0, i -> true);
    }

    /**
     * Standing inside the right angle, <b>it rejoins past the turn</b>.
     *
     * <p>From this position the closest is the arm before the turn (3 blocks), but rejoining there leaves all 14
     * blocks around the corner. Past the turn is only slightly farther, and the remainder is far shorter.
     */
    @Test
    void joinsPastTheCornerInsteadOfBacktracking() {
        List<PathStep> steps = corner();
        int index = join(steps, new Vec3(3.5, Y + 0.5, 3.5));

        BlockPos joined = steps.get(index).pos();
        assertEquals(10, joined.getX(), "Went back to the arm before the turn: " + joined.toShortString());
        assertTrue(joined.getZ() >= 5,
                "Should rejoin the furthest among the similarly close, not just past the corner: " + joined.toShortString());
    }

    /** If you're just beside the path, rejoin the step at your own position. */
    @Test
    void joinsBesideItselfOnAStraightPath() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        int index = join(steps, new Vec3(20.5, Y + 0.5, 3.5));

        // Right beside it, so up to the slack (8) ahead is similarly close. The point is not going back
        assertTrue(steps.get(index).pos().getX() >= 20,
                "Rejoined behind yourself: " + steps.get(index).pos().toShortString());
        assertTrue(steps.get(index).pos().getX() <= 30,
                "Jumped too far, beyond the slack: " + steps.get(index).pos().toShortString());
    }

    /** Can't rejoin onto a stretch crossed by placing footing (can't stand on blocks that don't exist yet). */
    @Test
    void neverJoinsOntoABridge() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            steps.add(step(x, 0));
        }
        for (int x = 11; x <= 20; x++) {
            steps.add(new PathStep(new BlockPos(x, Y, 0), MovementType.TRAVERSE, 4.0,
                    List.of(), List.of(), PathRisk.NONE, new BlockPos(x, Y - 1, 0)));
        }
        int index = join(steps, new Vec3(14.5, Y + 0.5, 0.5));

        assertTrue(index <= 9, "Rejoined onto the bridge: " + steps.get(index).pos().toShortString());
    }

    /** Steps that became impassable are skipped, rejoining before them. */
    @Test
    void skipsStepsThatAreNoLongerPassable() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        // x >= 22 is blocked
        int index = Splice.joinableStepIndex(steps, new Vec3(20.5, Y + 0.5, 0.5), 0,
                i -> steps.get(i).pos().getX() < 22);

        assertEquals(21, steps.get(index).pos().getX(),
                "Should rejoin the furthest unblocked step: " + steps.get(index).pos().toShortString());
    }

    /**
     * <b>Don't give up even if the whole nearby range is blocked.</b> The range is taken from "the closest step"
     * measured without checks, so if that area is blocked the whole range misses. Detouring around a blocked spot is
     * exactly that case, and returning -1 here would recompute everything even though rejoining is possible.
     */
    @Test
    void looksBeyondTheSlackWhenEverythingNearIsBlocked() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        // Everything around the player (the 8-block slack) is blocked
        int index = Splice.joinableStepIndex(steps, new Vec3(20.5, Y + 0.5, 0.5), 0,
                i -> steps.get(i).pos().getX() < 8 || steps.get(i).pos().getX() > 32);

        assertTrue(steps.get(index).pos().getX() > 32,
                "Gave up before the blocked area: " + steps.get(index).pos().toShortString());
    }

    /** Steps before {@code minIndex} aren't candidates (for detouring around a blocked spot). */
    @Test
    void respectsTheMinimumIndex() {
        List<PathStep> steps = corner();
        int index = Splice.joinableStepIndex(steps, new Vec3(1.5, Y + 0.5, 0.5), 15,
                i -> true);

        assertTrue(index >= 15, "Rejoined before minIndex: " + index);
    }
}
