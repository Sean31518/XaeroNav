package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

/**
 * @param steps         path to the goal excluding the start (or, if cut off, to the point that got closest to the goal)
 * @param termination   why the search ended. {@link #complete()} alone can't distinguish "ran out of resources" from
 *                      "no path within range", so retries that only make sense for the former (range expansion,
 *                      coarse waypoint chains) would be launched for the latter too
 * @param distinctNodes number of distinct cells the search touched. When {@code expandedNodes} greatly exceeds this,
 *                      the same cells are being re-expanded many times (with a weighted heuristic, closed nodes
 *                      return to open). Without both side by side, this spinning can't be told apart from a genuinely wide search
 * @param limitsHeld    whether a configured cap (bridge length, diving, fall damage, risky jumps, item count) discarded moves
 *                      and the search ended without relaxing it because of {@code strictLimits}. Used to phrase the failure as "no path"
 *                      vs. "no path within the caps"
 */
public record PathResult(List<PathStep> steps, Termination termination, int expandedNodes, int distinctNodes,
                         boolean limitsHeld) {

    public PathResult {
        steps = List.copyOf(steps);
    }

    public PathResult(List<PathStep> steps, Termination termination, int expandedNodes, int distinctNodes) {
        this(steps, termination, expandedNodes, distinctNodes, false);
    }

    public PathResult withLimitsHeld() {
        return new PathResult(steps, termination, expandedNodes, distinctNodes, true);
    }

    /** Why the search was cut off. */
    public enum Termination {
        /** Reached the goal. */
        REACHED_GOAL,
        /** Hit the expanded-node limit. */
        NODE_BUDGET,
        /** Hit the time limit. */
        TIME_LIMIT,
        /** Evicted by a newer search. */
        CANCELLED,
        /**
         * The open set ran out = there's no way to reach the goal within the search range. Increasing the budget or widening the range
         * gives the same result, so this is genuinely "stuck" and not a candidate for retrying.
         */
        EXHAUSTED
    }

    public boolean complete() {
        return termination == Termination.REACHED_GOAL;
    }

    /**
     * Whether it was cut off after using up search resources (node count, time). Retries such as widening the range or
     * splitting into segments only make sense in this case.
     */
    public boolean budgetExhausted() {
        return termination == Termination.NODE_BUDGET || termination == Termination.TIME_LIMIT;
    }
}
