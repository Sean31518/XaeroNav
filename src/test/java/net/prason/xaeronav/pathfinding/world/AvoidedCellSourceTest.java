package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;

class AvoidedCellSourceTest {

    private static final SearchLimits LIMITS = new SearchLimits(10_000, 5_000, 1.0);

    @Test
    void routesAroundTheAvoidedFootingInsteadOfThroughIt() throws Exception {
        // The middle column of a 3-wide flat floor. Walking straight is shortest, but avoiding one footing block along the way
        // leaves no choice but to veer into the next column and go around (the edge columns have the void beside them, so the shortest path goes through the middle)
        FakeCells cells = FakeCells.of(0, 60, 0, """
                .......
                #######""").extrudeZ(0, 2);
        BlockPos start = new BlockPos(0, 61, 1);
        BlockPos goal = new BlockPos(6, 61, 1);
        BlockPos footing = new BlockPos(3, 60, 1);

        PathResult direct = search(cells, start, goal);
        assertTrue(direct.complete());
        assertTrue(steppedOn(direct, footing), "Premise: without avoidance, the path passes over this footing");

        PathResult avoided = search(AvoidedCellSource.wrap(cells, List.of(footing)), start, goal);
        assertTrue(avoided.complete(), "If a detour remains, a path can be drawn");
        assertFalse(steppedOn(avoided, footing), "Must not pick the avoided cell again");
        assertTrue(CellData.standable(cells.cell(3, 60, 1)), "The world isn't modified");
    }

    @Test
    void wrappingNothingReturnsTheSourceItself() {
        FakeCells cells = FakeCells.of(0, 60, 0, """
                ...
                ###""");
        assertSame(cells, AvoidedCellSource.wrap(cells, List.of()));
    }

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) throws Exception {
        return new PathfindingExecutor().submit(cells, start, goal, LIMITS, false)
                .get(10, TimeUnit.SECONDS);
    }

    private static boolean steppedOn(PathResult result, BlockPos footing) {
        return result.steps().stream().anyMatch(step -> step.pos().equals(footing.above()));
    }
}
