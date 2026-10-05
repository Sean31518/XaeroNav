package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/** The classification for non-color identification (A11Y-01) matches the existing color priority (danger → work → movement). */
class PathColorsTest {

    private static PathStep step(MovementType movement, PathRisk risk, List<BlockPos> digCells,
                                  BlockPos placedBlockPos) {
        return new PathStep(BlockPos.ZERO, movement, 1.0, List.of(), digCells, risk, placedBlockPos);
    }

    @Test
    void anyNonNoneRiskIsDangerRegardlessOfMovementOrWork() {
        for (PathRisk risk : PathRisk.values()) {
            if (risk == PathRisk.NONE) {
                continue;
            }
            assertEquals(PathColors.Kind.DANGER,
                    PathColors.kindFor(step(MovementType.TRAVERSE, risk, List.of(), null)),
                    risk + " should be DANGER");
        }
    }

    @Test
    void diggingWithoutRiskIsWork() {
        PathStep digging = step(MovementType.TRAVERSE, PathRisk.NONE, List.of(BlockPos.ZERO), null);
        assertEquals(PathColors.Kind.WORK, PathColors.kindFor(digging));
    }

    @Test
    void bridgingWithoutRiskIsWork() {
        PathStep bridging = step(MovementType.TRAVERSE, PathRisk.NONE, List.of(), BlockPos.ZERO);
        assertEquals(PathColors.Kind.WORK, PathColors.kindFor(bridging));
    }

    @Test
    void plainMovementWithoutRiskOrWorkIsMovement() {
        PathStep walk = step(MovementType.TRAVERSE, PathRisk.NONE, List.of(), null);
        assertEquals(PathColors.Kind.MOVEMENT, PathColors.kindFor(walk));

        PathStep swim = step(MovementType.SWIM, PathRisk.NONE, List.of(), null);
        assertEquals(PathColors.Kind.MOVEMENT, PathColors.kindFor(swim));
    }

    @Test
    void riskOutranksWorkJustLikeForStepDoes() {
        // Both digging() and bridging() are set, but risk should take priority (the same priority as forStep)
        PathStep dangerousDig = step(MovementType.TRAVERSE, PathRisk.LAVA_ADJACENT,
                List.of(BlockPos.ZERO), BlockPos.ZERO);
        assertEquals(PathColors.Kind.DANGER, PathColors.kindFor(dangerousDig));
    }
}
