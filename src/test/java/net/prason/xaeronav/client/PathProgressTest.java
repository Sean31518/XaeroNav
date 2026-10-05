package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Mapping "where on the path am I now".
 *
 * <p>It's the foundation that recompute decisions, guidance display, and render trimming all share, so if it drifts,
 * mismatches like "the guidance shows the next corner but the line is drawn from behind" appear all at once.
 *
 * <p>Especially important is not jumping to a distant segment in terrain where the path passes near itself (cave
 * switchback stairs). A jump suddenly changes the remaining distance and the guidance points somewhere else.
 */
class PathProgressTest {

    private static final int Y = 60;

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    /** Player coordinates when standing at the center of step {@code i}'s cell. */
    private static Vec3 standingOn(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @Test
    void followsThePlayerAlongTheRoute() {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        // Walk in order from the start
        for (int i = 0; i < positions.size(); i++) {
            PathProgress.INSTANCE.update(result, standingOn(positions.get(i)));
            assertEquals(i, PathProgress.INSTANCE.indexFor(result), "Step " + i + " is matched");
            assertEquals(0.0, PathProgress.INSTANCE.distance(), 1.0e-9);
        }
    }

    @Test
    void aPlayerFarAheadIsFoundByTheFullScan() {
        // A sudden jump outside the window (32 steps ahead). Happens with teleports or position corrections after chunk loading
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        PathProgress.INSTANCE.update(result, standingOn(positions.get(80)));

        assertEquals(80, PathProgress.INSTANCE.indexFor(result), "Outside the window, search the whole path again");
        assertEquals(0.0, PathProgress.INSTANCE.distance(), 1.0e-9);
    }

    @Test
    void doesNotJumpBackToAnEarlierLegThatPassesNearby() {
        // A path whose outbound leg (z=0) and return leg (z=2) run parallel two blocks apart. Jumping to the
        // outbound leg while walking the return leg suddenly increases the remaining distance and the guidance points backwards
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        for (int i = 30; i >= 1; i--) {
            positions.add(new BlockPos(i, Y, 2));
        }
        PathResult result = path(positions);

        // Walk the outbound leg fully, then enter the return leg
        for (BlockPos pos : positions.subList(0, 45)) {
            PathProgress.INSTANCE.update(result, standingOn(pos));
        }

        int index = PathProgress.INSTANCE.indexFor(result);
        assertEquals(44, index);
        assertTrue(positions.get(index).getZ() == 2, "Stays on the return-leg side: " + positions.get(index));
    }

    @Test
    void anUnrelatedResultReportsTheStartOfTheRoute() {
        PathResult tracked = path(List.of(new BlockPos(1, Y, 0), new BlockPos(2, Y, 0)));
        PathResult other = path(List.of(new BlockPos(9, Y, 9), new BlockPos(10, Y, 9)));

        PathProgress.INSTANCE.update(tracked, standingOn(new BlockPos(2, Y, 0)));

        assertEquals(0, PathProgress.INSTANCE.indexFor(other),
                "For a path not mapped yet, returns the start (so rendering doesn't begin midway)");
    }

    @Test
    void measuresTheDistanceWithAndWithoutTheVerticalGap() {
        // Swimming on the surface with the path passing 5 blocks below. Counting vertically exceeds the default
        // deviation threshold (4), but underwater you can move freely up and down, so you haven't left the path
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        PathProgress.INSTANCE.update(result, new Vec3(10.5, Y + 5.0, 0.5));

        assertEquals(5.0, PathProgress.INSTANCE.distance(), 1.0e-9);
        assertEquals(0.0, PathProgress.INSTANCE.horizontalDistance(), 1.0e-9);
    }

    @Test
    void anEmptyRouteClearsTheMapping() {
        PathResult result = path(List.of(new BlockPos(1, Y, 0)));
        PathProgress.INSTANCE.update(result, standingOn(new BlockPos(1, Y, 0)));

        PathProgress.INSTANCE.update(null, new Vec3(0, Y, 0));

        assertEquals(Double.MAX_VALUE, PathProgress.INSTANCE.distance(),
                "While there's no path, treat it as \"infinitely far from the path\" = subject to recompute");
        assertEquals(Double.MAX_VALUE, PathProgress.INSTANCE.horizontalDistance(),
                "Same for the horizontal measure (the underwater deviation check reads this)");
    }
}
