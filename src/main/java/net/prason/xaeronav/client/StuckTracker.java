package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * Decides "the destination can't be reached". The deadlock check carved out of {@code PathfindingState};
 * it has little entanglement with the other state transitions, so it was made standalone in an easily unit-testable form.
 *
 * <p>A deadlock is defined as {@link #SEARCH_STREAK} consecutive searches that "<b>neither reached their target
 * nor got any closer to the destination</b>".
 *
 * <p>It can't be decided by whether a path was produced. Searches that run out of budget return a partial path toward a dead end every time,
 * so in an in-game log the player stayed at the edge of the same lava sea the whole time step counts went 55→23→5→18→93→0… for 5 minutes.
 * Conversely, looking only at "did it get closer" misjudges stretches that make a large detour around a lava sea (moving away from the destination
 * while correctly progressing) as deadlocks; there the search <b>does reach</b> its intended intermediate target, so
 * only combining the two separates them correctly.
 *
 * <p>The player's own position is included in measuring closeness. Walking forward along a partial path is also a normal
 * way to progress, so however many searches fail meanwhile, it isn't a deadlock.
 *
 * <p><b>Only searches dispatched from roughly the same place count as consecutive</b> ({@link #RETRY_MOVE_BLOCKS}).
 * The basis for a deadlock is that "repeating the same experiment doesn't change the result", so if the start has moved
 * it's a different experiment: the loaded chunks and layer 1's map change too, and the result really can change. In-game
 * (at the End's cliff edge, 06:36), failures while the player went back and forth 26 blocks along the cliff were
 * counted as consecutive and "can't get there" appeared, but 16 seconds later it made it across with 49 bridges.
 */
final class StuckTracker {

    private static final int SEARCH_STREAK = 4;
    private static final double PROGRESS_BLOCKS = 8.0;
    private static final double RETRY_MOVE_BLOCKS = 16.0;

    private volatile double bestApproachBlocks = Double.MAX_VALUE;
    /** Consecutive count of searches that ended without shrinking {@link #bestApproachBlocks}. */
    private volatile int stalledSearches;
    /** Start of the most recent search counted as "made no progress". */
    private volatile BlockPos lastStalledAt;
    private volatile PathfindingState.StuckReason reason;
    private volatile PathfindingState.StuckReason pendingNotice;

    /** Full reset per destination (for {@code PathfindingState#clear()}). */
    void reset() {
        bestApproachBlocks = Double.MAX_VALUE;
        stalledSearches = 0;
        lastStalledAt = null;
        reason = null;
        pendingNotice = null;
    }

    /**
     * Withdraws only the deadlock verdict (for arrival). The consecutive count and closest approach aren't reset,
     * since the same destination may continue until the arrival display ends; that's {@link #reset()}'s job.
     */
    void clearReason() {
        reason = null;
        pendingNotice = null;
    }

    /** The reason if judged a deadlock, otherwise {@code null}. */
    PathfindingState.StuckReason reason() {
        return reason;
    }

    /** Whether it's close to stuck (at least one consecutive failure). Not yet confirmed. */
    boolean stranded() {
        return stalledSearches > 0;
    }

    /** Takes and consumes a deadlock notice not yet announced in chat, if any. {@code null} if none. */
    PathfindingState.StuckReason takePendingNotice() {
        PathfindingState.StuckReason notice = pendingNotice;
        pendingNotice = null;
        return notice;
    }

    /** Whether, after judging a deadlock, it's time to dispatch a search again. */
    boolean retryDue(BlockPos lastStart, BlockPos playerPos, boolean recalcIntervalElapsed) {
        return lastStart == null
                || lastStart.distSqr(playerPos) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS
                || recalcIntervalElapsed;
    }

    /**
     * Applies this search's result to the deadlock check.
     *
     * @param hasCompleteGroundRoute whether a completed ground route is still displayed (the relay stretch {@code TO_SURFACE}
     *         excluded). If true, it's not considered a deadlock and the state is reset; in-game (22:42), "can't reach the destination"
     *         appeared while a 110-step path with 47 bridges was displayed
     * @param routeUnmapped whether layer 1 (Xaero's map, the last rung of the ladder that assumes bridging) fails to reach
     *         the current destination. Used to decide the deadlock reason in order of confidence: this carries the most information,
     *         and if even this doesn't reach, no amount of detailed searching will
     */
    void noteOutcome(BlockPos start, BlockPos planEnd, BlockPos currentGoal, boolean hasCompleteGroundRoute,
                      PathResult result, boolean routeUnmapped, Runnable onStalled) {
        if (hasCompleteGroundRoute) {
            stalledSearches = 0;
            reason = null;
            return;
        }
        double approach = Math.min(horizontalDistance(start, currentGoal), horizontalDistance(planEnd, currentGoal));
        // Once the high-water mark drops below PROGRESS_BLOCKS, a search that gets a further PROGRESS_BLOCKS closer
        // is impossible in principle (distance can't go below 0). For a destination that was reached close to even once,
        // no later search counts as progress, and just SEARCH_STREAK unreached searches in a row produce "can't get there";
        // using a value that can't improve as a ratchet means it never releases
        boolean improvable = bestApproachBlocks >= PROGRESS_BLOCKS;
        boolean progressed = result.complete() || !improvable || approach <= bestApproachBlocks - PROGRESS_BLOCKS;
        bestApproachBlocks = Math.min(bestApproachBlocks, approach);
        if (progressed) {
            stalledSearches = 0;
            reason = null;
            return;
        }
        // Prevents a 3D coarse layer built on a thin map, or a nav graph from before the terrain changed, from staying stuck without being rebuilt.
        // Only searches that "neither reached their target nor got closer to the destination" pass here, so it's a good
        // rebuild trigger (whether to actually rebuild is throttled by the guide side)
        onStalled.run();
        BlockPos previouslyStalledAt = lastStalledAt;
        boolean sameSpot = previouslyStalledAt != null
                && previouslyStalledAt.distSqr(start) < RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        stalledSearches = sameSpot ? stalledSearches + 1 : 1;
        lastStalledAt = start;
        if (stalledSearches < SEARCH_STREAK || reason != null) {
            return;
        }
        reason = classify(result, routeUnmapped);
        pendingNotice = reason;
        XaeroNav.LOGGER.info("XaeroNav: Decided the destination can't be reached (reason={}, closest={} blocks, destination={})",
                reason, Math.round(bestApproachBlocks), currentGoal.toShortString());
    }

    /**
     * Decides the deadlock reason by looking in order of confidence. Next most certain is {@code EXHAUSTED} (proof that there's
     * no means of reaching it within the search range; if the caps were strictly held, a proof limited to within them), and the rest are resource shortages.
     */
    private static PathfindingState.StuckReason classify(PathResult result, boolean routeUnmapped) {
        if (routeUnmapped) {
            return PathfindingState.StuckReason.UNMAPPED;
        }
        if (result.termination() != PathResult.Termination.EXHAUSTED) {
            return PathfindingState.StuckReason.SEARCH_TOO_HARD;
        }
        // Even after exhausting the search, moves discarded by the caps weren't tried. Saying "no path" would make relaxing the caps sound pointless
        return result.limitsHeld()
                ? PathfindingState.StuckReason.LIMITS_HELD
                : PathfindingState.StuckReason.NO_WAY_THROUGH;
    }

    /**
     * Holds the same formula as {@code PathfindingState#horizontalDistance} independently. This confines to the formula itself the semantic
     * decision that the deadlock check ignores y (measuring "did it get closer" by map distance alone).
     */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
