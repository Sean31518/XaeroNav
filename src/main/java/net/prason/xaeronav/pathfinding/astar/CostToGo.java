package net.prason.xaeronav.pathfinding.astar;

/**
 * Estimate of the remaining cost to the goal. The hook for swapping the heuristic injected into
 * {@link AStarPathfinder}: the default is {@link Heuristic} (a geometric lower bound, admissible), but
 * using it together with the {@code costToGo} table built from layer 1's coarse map ({@code CoarseRouter#costToGo})
 * brings it closer to a "following the actual terrain" estimate that routes around walls and lava seas.
 *
 * <p>The goal coordinates aren't an argument so they stay confined to the implementation: {@link AStarPathfinder}'s
 * goal is decided by {@link AStarPathfinder#search}, not the constructor, so passing coordinates to the constructor
 * couldn't prevent a mismatch between "the table's goal" and "the search's goal".
 */
@FunctionalInterface
public interface CostToGo {

    double estimate(int x, int y, int z);

    /**
     * The value attached to a search node. May return {@link Double#NaN} for cells with nothing to base an estimate on.
     * {@link AStarPathfinder} then uses {@link #estimate} there, and also raises it so it doesn't drop below the parent's value minus one move's cost.
     */
    default double searchEstimate(int x, int y, int z) {
        return estimate(x, y, z);
    }
}
