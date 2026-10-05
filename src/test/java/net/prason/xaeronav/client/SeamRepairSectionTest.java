package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * When only the stretch spanning a seam is swapped, the path and <b>leg boundaries</b> are correctly re-established.
 *
 * <p>Boundaries are looked at by the HUD's "which relay point are we heading to" and the map's dotted line (drawn only for the part not yet passed).
 * A swap moves the indices before and after it, so if this drifts, only the guidance numbers jump.
 */
class SeamRepairSectionTest {

    private static final int Y = 64;

    private static PathStep step(int x, double cost) {
        return new PathStep(new BlockPos(x, Y, 0), MovementType.TRAVERSE, cost,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** A path stepping one block at a time to x=1..12, with cost 4. */
    private static List<PathStep> straight() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 12; x++) {
            steps.add(step(x, 4.0));
        }
        return steps;
    }

    private static PathfindingState.DisplayedPath shown(List<PathStep> steps,
                                                        List<PathfindingState.PathSegment> segments) {
        PathResult result = new PathResult(steps, PathResult.Termination.REACHED_GOAL, 0, 0);
        return new PathfindingState.DisplayedPath(result, PathfindingState.PathMode.WAYPOINT, 2,
                segments);
    }

    /** Nothing outside the swapped stretch moves by even one step. */
    @Test
    void keepsEverythingOutsideTheSection() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps,
                List.of(new PathfindingState.PathSegment(11, 2)));
        // Swap x=4..8 (indices 3..7) for 2 cheaper steps
        List<PathStep> section = List.of(step(20, 1.0), step(8, 1.0));

        List<PathStep> after = SeamRepair.withSection(before, section, 3, 7).result().steps();

        assertEquals(steps.subList(0, 3), after.subList(0, 3));
        assertEquals(steps.subList(8, 12), after.subList(after.size() - 4, after.size()));
        assertEquals(3 + section.size() + 4, after.size());
    }

    /** Boundaries inside the swapped part disappear, and their intermediate target numbers are taken over by the following leg. */
    @Test
    void dropsSegmentBoundariesInsideTheSection() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps, List.of(
                new PathfindingState.PathSegment(2, 0),
                new PathfindingState.PathSegment(6, 1),
                new PathfindingState.PathSegment(11, 2)));
        List<PathStep> section = List.of(step(20, 1.0), step(8, 1.0));

        PathfindingState.DisplayedPath after = SeamRepair.withSection(before, section, 3, 7);

        assertEquals(List.of(new PathfindingState.PathSegment(2, 0),
                        new PathfindingState.PathSegment(after.result().steps().size() - 1, 2)),
                after.segments());
    }

    /** Even if the swap reaches the end of the path, the last leg always reaches the end. */
    @Test
    void alwaysCoversTheEnd() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps, List.of(
                new PathfindingState.PathSegment(5, 1),
                new PathfindingState.PathSegment(11, 2)));
        List<PathStep> section = List.of(step(20, 1.0));

        PathfindingState.DisplayedPath after = SeamRepair.withSection(before, section, 8, 11);

        List<PathfindingState.PathSegment> segments = after.segments();
        assertEquals(after.result().steps().size() - 1, segments.get(segments.size() - 1).endStep());
        assertEquals(2, after.waypointIndexAtStep(after.result().steps().size() - 1));
    }

    /** If the swapped stretch steps on the same positions as before or after, it's folded (lines don't overlap at the seam). */
    @Test
    void foldsOverlapAtTheNewSeam() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps,
                List.of(new PathfindingState.PathSegment(11, 2)));
        // The swapped stretch goes back to x=2, passed earlier, before advancing
        List<PathStep> section = List.of(step(2, 1.0), step(8, 1.0));

        List<PathStep> after = SeamRepair.withSection(before, section, 3, 7).result().steps();

        long visits = after.stream().filter(s -> s.pos().getX() == 2).count();
        assertEquals(1, visits);
        assertTrue(after.size() < 3 + section.size() + 4);
    }
}
