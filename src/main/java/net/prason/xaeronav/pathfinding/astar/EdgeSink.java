package net.prason.xaeronav.pathfinding.astar;

/**
 * Receives every move (edge) {@link AStarPathfinder} generates, whether accepted or not.
 *
 * <p>Used by the nav graph's cluster construction and by the perfect cost-to-go closure that measures its correctness.
 * <b>Getting edges from the same move generation as pathfinding</b> is what matters: re-laying edges with a different cost model would,
 * like layers 1 and 2, give "a different guess" rather than "a coarsened version of the true cost".
 *
 * <p>Reporting happens at the entry of {@link AStarPathfinder#relax}. Edges that don't improve are also reported before being discarded, so
 * running the closure to completion yields every edge leaving the reached nodes. However, edges discarded by path-dependent caps on move generation
 * (consecutive bridge length, total placements, submersion time) only appear as seen from the state that reached there most cheaply.
 *
 * <p>{@code cost} is the value before the underwater surcharge ({@code ActionCosts#SUBMERGED_TRAVEL_PENALTY}).
 * The surcharge depends on the air accounting of the path taken, so it isn't uniquely determined as an edge price.
 */
@FunctionalInterface
interface EdgeSink {

    void edge(int fromX, int fromY, int fromZ, boolean fromBoating, int toX, int toY, int toZ, boolean toBoating,
              double cost, MoveKind kind);
}
