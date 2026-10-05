package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;

/**
 * The caps handed to the normal-budget side of the parallel fallback.
 *
 * <p>Only the weight is lowered; the budget and time are left alone. The normal-budget side is a search that is
 * <b>allowed to fail</b>, and whatever it misses is picked up by the deep budget running at the same time.
 * Cutting the budget too would shrink the set of paths that can be picked up.
 */
class QualityPassTest {

    @Test
    void lowersOnlyTheWeight() {
        SearchLimits configured = new SearchLimits(100_000, 2_000,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        SearchLimits quality = PathfindingState.qualityLimits(configured);

        assertEquals(configured.maxExpandedNodes(), quality.maxExpandedNodes());
        assertEquals(configured.timeLimitMillis(), quality.timeLimitMillis());
        assertEquals(1.2, quality.heuristicWeight(), 1e-9);
    }

    /** Don't override the value of someone who has configured a lighter weight than the default. */
    @Test
    void neverRaisesAWeightTheUserAlreadyLowered() {
        SearchLimits configured = new SearchLimits(100_000, 2_000, 1.05);

        assertEquals(1.05, PathfindingState.qualityLimits(configured).heuristicWeight(), 1e-9);
    }
}
