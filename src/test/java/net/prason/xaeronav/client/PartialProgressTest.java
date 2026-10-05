package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.PathResult.Termination;
import org.junit.jupiter.api.Test;

/**
 * Between partial paths, keep the one drawn closer to the destination. In the real Nether, a path drawn to within 16 blocks
 * of the destination was being replaced by a recompute that ran out of budget and stopped 145 blocks short.
 */
class PartialProgressTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos GOAL = new BlockPos(200, 64, 0);

    private static PathResult partialTo(int x) {
        BlockPos end = new BlockPos(x, 64, 0);
        PathStep step = new PathStep(end, MovementType.TRAVERSE, 1.0, List.of(end, end.above()), List.of(),
                PathRisk.NONE, null);
        return new PathResult(List.of(step), Termination.NODE_BUDGET, 1, 1);
    }

    @Test
    void keepsTheRouteThatGetsCloser() {
        assertTrue(PartialProgress.compare(partialTo(184), partialTo(55), START, GOAL, null).oldAhead());
    }

    @Test
    void takesTheNewResultWhenItGetsCloser() {
        assertFalse(PartialProgress.compare(partialTo(55), partialTo(184), START, GOAL, null).oldAhead());
    }

    /** If both reach the same point, take the newer one. The old path was drawn from an old reading of the terrain. */
    @Test
    void takesTheNewResultOnATie() {
        assertFalse(PartialProgress.compare(partialTo(100), partialTo(100), START, GOAL, null).oldAhead());
    }
}
