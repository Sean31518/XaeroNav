package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * @param bodyCells      cells the body passes through during this move. Used by {@link PathSafetyChecker} and path
 *                       re-validation to look at every cell, not just the destination
 * @param digCells       cells actually broken by this move (for "show digging routes in a different color").
 *                       Also includes falling-block chains the body doesn't pass through (sand/gravel overhead)
 * @param risk           result of the post-check by {@link PathSafetyChecker}. Fixed at NONE right after the A* search,
 *                       filled in by {@link PathSafetyChecker#annotate}
 * @param placedBlockPos for a stretch crossing a gap by placing blocks, the coordinates to place at. Otherwise null
 */
public record PathStep(BlockPos pos, MovementType movement, double cost,
                        List<BlockPos> bodyCells, List<BlockPos> digCells, PathRisk risk, BlockPos placedBlockPos) {

    public boolean digging() {
        return !digCells.isEmpty();
    }

    public boolean bridging() {
        return placedBlockPos != null;
    }

    public boolean swimming() {
        return movement == MovementType.SWIM;
    }

    public boolean boating() {
        return movement == MovementType.BOAT;
    }

    public boolean climbing() {
        return movement == MovementType.CLIMB;
    }

    /** Whether this step is ridden on an animal. */
    public boolean riding() {
        return movement == MovementType.RIDE;
    }
}
