package net.prason.xaeronav.pathfinding.flight;

import net.prason.xaeronav.pathfinding.astar.SearchLimits;

/**
 * The tuning values needed to run one aerial route search.
 *
 * <p>They're bundled together because these three <b>pull against each other</b>. A coarser grid means fewer cells for the same
 * volume, reaching further within the budget, but narrow passages become impassable. Raising the weight also reaches further, but
 * detours creep in. Raising the budget reaches further but makes each computation longer. Scattered as separate arguments, touching one
 * would remove the incentive to revisit the others.
 *
 * @param cellBlocks            grid cell edge length (blocks). <b>The most effective lever for reaching far</b>:
 *                              the cell count is inversely proportional to the cube of the edge. It's also the margin around the line itself
 * @param clearancePenaltyTicks surcharge (ticks) for entering a cell whose 26-neighborhood is fully blocked. 0 disables it
 * @param limits                expansion-count and time limits and the heuristic weight
 */
public record FlightTuning(int cellBlocks, double clearancePenaltyTicks, SearchLimits limits) {
}
