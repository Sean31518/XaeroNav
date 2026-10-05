package net.prason.xaeronav.pathfinding.astar;

/**
 * Cutoff conditions for a single search, and the heuristic weight.
 *
 * <p>The expansion limit is a "ceiling to give up at when the goal is not reached"; once a path is found the
 * search ends there. Raising it does not change the time for paths that are reachable, and lowering it cuts off
 * paths that should have been reachable. Raising the weight extends the reach for the same number of expansions,
 * but roundabout paths may slip in.
 */
public record SearchLimits(int maxExpandedNodes, long timeLimitMillis, double heuristicWeight) {

    public static final SearchLimits DEFAULT = new SearchLimits(
            AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
            AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
}
