package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule that "bridges an earlier step is about to place count as footing for the steps after it."
 *
 * <p>The extension search uses earlier placements as footing, so a route can pass back over its own bridge.
 * If this breaks, bridges not yet placed are judged to have "no footing", and the whole route so far
 * gets re-planned (on a real Nether run a 621-step route shrank to 75 steps).
 */
class PathValidatorPlannedBridgeTest {

    private static final BlockPos BRIDGE = new BlockPos(53, 49, 695);

    private static PathStep walk(BlockPos pos) {
        return new PathStep(pos, MovementType.TRAVERSE, 1.0, List.of(pos, pos.above()), List.of(), PathRisk.NONE, null);
    }

    private static PathStep bridge(BlockPos pos) {
        return new PathStep(pos, MovementType.TRAVERSE, 1.0, List.of(pos, pos.above()), List.of(), PathRisk.NONE,
                pos.below());
    }

    private static final List<PathStep> ROUTE = List.of(
            walk(new BlockPos(51, 50, 695)),
            bridge(BRIDGE.above()),
            walk(new BlockPos(54, 50, 695)),
            walk(BRIDGE.above()));

    @Test
    void bridgeAheadCountsAsFooting() {
        assertTrue(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 3, BRIDGE));
    }

    /** A bridge already passed should have been placed. If it is missing, that is a real change and must not be overlooked. */
    @Test
    void bridgeAlreadyPassedIsStillChecked() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 2, 3, BRIDGE));
    }

    /** Bridges placed by the step itself or by later steps do not count as footing for this step. */
    @Test
    void bridgePlacedLaterIsNotFooting() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 1, BRIDGE));
    }

    @Test
    void unrelatedCellIsNotFooting() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 3, BRIDGE.east()));
    }
}
