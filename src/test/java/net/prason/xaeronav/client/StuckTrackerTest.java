package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathResult.Termination;

/**
 * Unit tests for {@link StuckTracker}.
 *
 * <p>The first call to {@code noteOutcome} always counts as "made progress" (there's no closest distance
 * to compare against yet). When testing a streak of being stuck, first build a baseline once,
 * then repeat no-progress results from the same spot.
 */
class StuckTrackerTest {

    private static final BlockPos GOAL = new BlockPos(1000, 64, 0);
    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos FAR_START = new BlockPos(0, 64, 500);

    private static PathResult incomplete(Termination termination) {
        return new PathResult(List.of(), termination, 0, 0);
    }

    @Test
    void firstOutcomeNeverCountsAsStalled() {
        StuckTracker tracker = new StuckTracker();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false,
                new NetherVoxelGuide()::noteStalled);

        assertFalse(tracker.stranded(), "the first search, with nothing to compare against yet, counts as progress");
        assertNull(tracker.reason());
    }

    @Test
    void repeatedNonProgressFromTheSameSpotEventuallyGetsStuck() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        // The first one builds the baseline (1000 blocks)
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);

        // Three times at the same spot and distance is not yet judged stuck (confirmed on the SEARCH_STREAK=4th)
        for (int i = 0; i < 3; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
            assertNull(tracker.reason(), "not confirmed yet at streak " + i);
            assertTrue(tracker.stranded());
        }

        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.takePendingNotice(),
                "the round that confirms being stuck also raises the chat notice");
        assertNull(tracker.takePendingNotice(), "the notice disappears once taken (not shown twice)");
    }

    @Test
    void routeUnmappedTakesPriorityOverTerminationReason() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), true, voxelGuide::noteStalled);
        for (int i = 0; i < 4; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), true, voxelGuide::noteStalled);
        }
        assertEquals(PathfindingState.StuckReason.UNMAPPED, tracker.reason(),
                "if layer 1 doesn't reach the destination, UNMAPPED takes priority regardless of the cutoff reason");
    }

    @Test
    void resourceExhaustionWithoutUnmappedRouteIsSearchTooHard() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), false, voxelGuide::noteStalled);
        for (int i = 0; i < 4; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), false, voxelGuide::noteStalled);
        }
        assertEquals(PathfindingState.StuckReason.SEARCH_TOO_HARD, tracker.reason());
    }

    @Test
    void exhaustingWithinStrictLimitsSaysTheLimitsAreInTheWay() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        PathResult held = incomplete(Termination.EXHAUSTED).withLimitsHeld();
        for (int i = 0; i < 5; i++) {
            tracker.noteOutcome(START, START, GOAL, false, held, false, voxelGuide::noteStalled);
        }
        assertEquals(PathfindingState.StuckReason.LIMITS_HELD, tracker.reason(),
                "don't say \"no path\" without having tried the moves the caps discarded");
    }

    @Test
    void movingFarBetweenAttemptsResetsTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertTrue(tracker.stranded());

        // Failures from a far-away spot are "a different experiment", so they don't count toward the streak
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertNull(tracker.reason(), "not confirmed unless 4 in a row at the same spot");
    }

    @Test
    void meaningfulProgressResetsTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertTrue(tracker.stranded());

        // A search from a spot 10 blocks closer to the destination (more than PROGRESS_BLOCKS=8) counts as progress
        BlockPos closer = new BlockPos(10, 64, 0);
        tracker.noteOutcome(closer, closer, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertFalse(tracker.stranded(), "meaningful progress resets the streak count");
    }

    @Test
    void completeGroundRouteOverridesStuckState() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());

        // While a completed surface route is shown, it's not stuck no matter how many times stuck searches fail beyond it
        tracker.noteOutcome(START, START, GOAL, true, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertNull(tracker.reason());
        assertFalse(tracker.stranded());
    }

    @Test
    void resetClearsEverythingIncludingReason() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());

        tracker.reset();
        assertNull(tracker.reason());
        assertFalse(tracker.stranded());
        assertNull(tracker.takePendingNotice());

        // After reset the closest distance is reset too, so the baseline is rebuilt even from a far destination
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertFalse(tracker.stranded(), "the first search after reset counts as progress again");
    }

    @Test
    void clearReasonOnlyClearsTheVerdictNotTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide::noteStalled);
        assertTrue(tracker.stranded());

        // On arrival only clearReason() is called (there's no reason check, so no effect at this point, but this
        // confirms continuity for the case of heading to the same coordinates again right after arriving)
        tracker.clearReason();
        assertNull(tracker.reason());
        assertTrue(tracker.stranded(), "clearReason doesn't reset the streak count (see PathfindingState#arrive)");
    }

    @Test
    void retryDueRequiresEitherMovementOrElapsedInterval() {
        StuckTracker tracker = new StuckTracker();
        BlockPos lastStart = new BlockPos(0, 64, 0);

        assertTrue(tracker.retryDue(null, lastStart, false), "with no start point, retrying is always fine");
        assertTrue(tracker.retryDue(lastStart, new BlockPos(20, 64, 0), false), "moving 16+ blocks makes retrying fine");
        assertFalse(tracker.retryDue(lastStart, new BlockPos(5, 64, 0), false),
                "no retry if not moved and the interval hasn't passed");
        assertTrue(tracker.retryDue(lastStart, new BlockPos(5, 64, 0), true), "once the interval passes, retry even without moving");
    }
}
