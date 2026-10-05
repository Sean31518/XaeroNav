package net.prason.xaeronav.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;

/**
 * For two partial paths, the remaining distance from their ends to the goal. Compares which one reaches closer to the goal.
 *
 * <p>The yardstick is the nav graph guide only when both ends are inside the window. Values outside the window are estimates on mismatched scales
 * ({@link WindowField#measuredInWindow}), and subtracting them turns the estimation error straight into the conclusion. In that case
 * compare the horizontal distance to the goal. Either way, the two values compared are of the same kind.
 *
 * @param oldAhead whether the displayed path reaches closer to the goal. If equal, the new result is taken
 */
record PartialProgress(double oldLeft, double newLeft, String yardstick, boolean oldAhead) {

    static PartialProgress compare(PathResult shown, PathResult replacement, BlockPos start, BlockPos goal,
            @Nullable WindowField guide) {
        BlockPos oldEnd = PathfindingState.endOf(shown, start);
        BlockPos newEnd = PathfindingState.endOf(replacement, start);
        if (guide != null && guide.measuredInWindow(oldEnd.getX(), oldEnd.getZ())
                && guide.measuredInWindow(newEnd.getX(), newEnd.getZ())) {
            double oldLeft = guide.estimate(oldEnd.getX(), oldEnd.getY(), oldEnd.getZ());
            double newLeft = guide.estimate(newEnd.getX(), newEnd.getY(), newEnd.getZ());
            return new PartialProgress(oldLeft, newLeft, "guide", oldLeft < newLeft);
        }
        double oldLeft = PathfindingState.horizontalDistance(oldEnd, goal);
        double newLeft = PathfindingState.horizontalDistance(newEnd, goal);
        return new PartialProgress(oldLeft, newLeft, "distance", oldLeft < newLeft);
    }
}
