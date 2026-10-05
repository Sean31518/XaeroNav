package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * The map's dot sequence collapses consecutive steps with the same XZ (stairs, digging down) into one dot.
 * So skipping the already-passed section requires mapping step indices back to dot indices.
 */
class MapDotsTest {

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    @Test
    void mapsStepIndicesOntoDotsThatCollapsedVerticalRuns() {
        // Steps 1-3 share the same XZ (stairs climbing straight up), so they collapse into one dot
        MapDots dots = MapDots.forPath(path(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 66, 0))));

        assertEquals(3, dots.count);
        assertEquals(0, dots.firstDotFrom(0));
        assertEquals(1, dots.firstDotFrom(1));
        // When pointed into the middle of a collapsed section, start drawing from the dot that section produced
        assertEquals(2, dots.firstDotFrom(2));
        assertEquals(2, dots.firstDotFrom(4));
    }

    @Test
    void returnsTheEndWhenEverythingIsBehind() {
        MapDots dots = MapDots.forPath(path(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0))));

        assertEquals(dots.count, dots.firstDotFrom(99));
    }
}
