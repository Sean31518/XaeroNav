package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;

/**
 * Rejoining (splicing) after leaving the path's band. <b>If the path itself is still alive, don't
 * redraw all of it; search only for a segment from the current position that joins back onto it.</b>
 *
 * <p>Redrawing means throwing this path away. The detail search's goal shifts with every
 * recalculation, so there's no guarantee of drawing the same path again. In the real game (island
 * hopping in The End), a completed 110-step route containing 47 bridges was thrown away on every
 * deviation, and the next search burned 300k nodes and ended unreached. The join segment targets a
 * single point within {@link #SPLICE_MAX_JOIN_BLOCKS}, so it is orders of magnitude cheaper, and on
 * success the expensive path (bridges, digging) carries over as-is.
 *
 * <p>The destination/path/computing flag of {@link PathfindingState} are touched only via {@link Host}
 * (same structure as {@link FlightNavState}/{@link SeamRepair}).
 */
final class Splice {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Maximum distance (blocks) at which the path can be rejoined. Farther than this, the path is no
     * longer ours, so redraw all of it.
     *
     * <p><b>Widened from 64 based on measurements.</b> In a real-game log (2026-08-30, 20 joins while
     * keeping a 666-step path), the join segments actually expanded <b>2-116</b> nodes, two to three
     * orders of magnitude below {@link #SPLICE_MAX_EXPANDED_NODES} (30,000). Meanwhile users reported
     * "sometimes it redraws everything", mainly due to the 64-block wall (placing a block further along
     * the line pushes the join point past 64 and it falls back to a redraw).
     *
     * <p>The effective value is also capped by {@code renderRadius} ({@link #joinDistanceLimit}): a join
     * point outside loaded chunks is outside the {@link SearchBounds}, so it can never be reached however much is allowed.
     */
    private static final double SPLICE_MAX_JOIN_BLOCKS = 192.0;

    /**
     * Cap on nodes expanded by a join segment. The join target is a single point within
     * {@link #SPLICE_MAX_JOIN_BLOCKS}, so there's no point giving it the same budget as a full redraw
     * (doing so would make the wait on a failed join as long as a redraw, defeating the purpose of
     * keeping it cheap).
     */
    private static final int SPLICE_MAX_EXPANDED_NODES = 30_000;

    /**
     * Distance slack (blocks) for accepting a join point. Steps within this range <b>of the nearest
     * step</b> count as equally near, and the join goes to the furthest-along step among them.
     *
     * <p><b>Do not pick the single nearest point.</b> Everything before the join point is discarded,
     * so <b>the further along the joined step, the shorter the remaining way</b>. Choosing by distance
     * alone, where the path bends a step behind you gets chosen as "nearest", making you walk the
     * stretch you just came along again. Measured (from positions deviated 4/8/16 blocks from real 3D paths):
     *
     * <pre>
     *              Nearest                      Last among near (+8)
     * Overworld  avg 1.045 worst 1.505 → avg 1.001 worst 1.066
     * Overworld2 avg 1.030 worst 2.013 → avg 1.003 worst 1.089
     * Nether     avg 1.074 worst 1.782 → avg 1.000 worst 1.048
     * End        avg 1.091 worst 1.943 → avg 1.002 worst 1.039
     * </pre>
     *
     * <p>Choosing by "estimate to the join + remaining way" looks more principled, but <b>measured, it
     * got worse in the Overworld</b> (avg 1.088, worst 1.233): {@code Heuristic} is a geometric lower
     * bound, so it estimates points across rivers or cliffs as "near". Capping the slack at 8 blocks
     * takes only "the further one when equally near" without making that bet.
     */
    private static final double JOIN_SLACK_BLOCKS = 8.0;

    /**
     * Cap (ticks) on "movement not getting closer to the destination" that a join may spend. Equivalent to walking 48 blocks.
     *
     * <p>A join segment may detour around obstacles, so some slack is needed. What we want to stop is
     * <b>backtracking, not going around</b>: right after jumping off a cliff, the path directly above
     * remains "nearest", so joining it produces a segment that climbs the cliff again.
     */
    private static final double SPLICE_DETOUR_ALLOWANCE_TICKS = 48.0 * ActionCosts.SPRINT_ONE_BLOCK;

    /** Distance (blocks) to walk from the point where a join failed before trying again. */
    private static final double SPLICE_RETRY_MOVE_BLOCKS = 8.0;

    /** Mutable state held by {@link PathfindingState} that must be read and written on async completion. */
    interface Host {
        /** Current destination. */
        @Nullable BlockPos goal();

        /** Currently displayed path. */
        PathfindingState.DisplayedPath displayed();

        /** Replaces the displayed path. */
        void setDisplayed(PathfindingState.DisplayedPath path);

        /** Sets or clears the computing flag. */
        void setComputing(boolean computing);

        /** Remaining cost to the destination (the yardstick for {@link #spliceWorthTaking}). {@code null} if not built. */
        @Nullable WindowField guide();
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final SeamRepair seamRepair;
    private final RecentFailures recentFailures;

    /** Player position where {@link #trySplice} failed to join. Expires after {@link #SPLICE_RETRY_MOVE_BLOCKS}. */
    private volatile BlockPos blockedFrom;

    /** Reason of the most recently reported join refusal. Deduplication so the same reason isn't logged every tick. */
    private final ChangeGate<String> refusalGate = new ChangeGate<>();

    Splice(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
           Runnable onChanged, Host host, SeamRepair seamRepair, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.seamRepair = seamRepair;
        this.recentFailures = recentFailures;
    }

    /**
     * Whether a new path can be joined is measured afresh. Failure records from the previous path are
     * not carried over (called on both destination changes and full redraws).
     */
    void clearBlock() {
        blockedFrom = null;
    }

    /** Reason of the most recently reported join refusal (for diagnostics). */
    @Nullable String currentRefusal() {
        return refusalGate.current();
    }

    /**
     * Left the path's band. <b>If the path itself is still alive, don't redraw all of it; search only
     * for a segment from the current position that joins back onto it.</b> {@code true} if submitted (the result is applied asynchronously).
     *
     * <p>The join point is <b>the nearest step</b>, and everything before it is discarded. If the player
     * had walked ahead, this also correctly advances (no passed stretch remains). When a join fails, it
     * doesn't retry until {@link #SPLICE_RETRY_MOVE_BLOCKS} have been walked, leaving it to the caller's redraw.
     *
     * @param minJoinIndex Smallest index accepted as a join point. When detouring around a blocked spot,
     *                     joining before it would lead back to the same place, so pass the one after it
     */
    boolean trySplice(Level level, Player player, PathfindingState.DisplayedPath shown, int minJoinIndex) {
        long lap = TickLaps.start();
        try {
            return trySpliceNow(level, player, shown, minJoinIndex);
        } finally {
            TickLaps.add("splice", lap);
        }
    }

    private boolean trySpliceNow(Level level, Player player, PathfindingState.DisplayedPath shown, int minJoinIndex) {
        if (shown.mode() == PathfindingState.PathMode.TO_SURFACE) {
            return false;
        }
        PathResult result = shown.result();
        if (!result.complete() || result.steps().isEmpty()) {
            // An unreached path has no guarantee that "you can get further along it". Joining gains nothing
            return false;
        }
        BlockPos currentGoal = host.goal();
        BlockPos playerAt = player.blockPosition();
        BlockPos blocked = blockedFrom;
        if (currentGoal == null
                || (blocked != null
                        && blocked.distSqr(playerAt) < SPLICE_RETRY_MOVE_BLOCKS * SPLICE_RETRY_MOVE_BLOCKS)) {
            return false;
        }
        int renderRadius = ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        int joinIndex = joinableStepIndex(level, result.steps(), player.position(), minJoinIndex);
        if (joinIndex < 0) {
            // Silently falling back to a redraw leaves no record of why local repair wasn't possible.
            // "Not a single plain step to join" = the whole path is bridges, or everything is blocked
            noteSpliceRefused("no joinable step", result.steps().size(), minJoinIndex, -1);
            return false;
        }
        BlockPos joinPos = result.steps().get(joinIndex).pos();
        double joinDistance = PathfindingState.distanceTo(player.position(), joinPos);
        if (joinDistance > joinDistanceLimit(renderRadius)) {
            noteSpliceRefused("join point too far (" + Math.round(joinDistance) + " blocks)",
                    result.steps().size(), minJoinIndex, joinIndex);
            return false;
        }
        // Only look from the join point onward. Everything before it is discarded, so giving up over
        // changes there would send even paths that connect via a detour to the caller's full redraw.
        // Silently returning false here when found invalid would leave no record of why the join was abandoned
        PathValidator.Failure failure = PathValidator.firstFailureFrom(level, result, joinIndex,
                playerAt, renderRadius);
        if (failure != null) {
            LOGGER.debug("XaeroNav: Gave up joining because cells on the path had changed ({})", failure.reason());
            return false;
        }

        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = SearchBounds.around(level, playerAt, joinPos,
                tuning.searchHorizontalMargin(), PathfindingState.verticalSearchMargin(level, false),
                renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("chunk capture", captureLap);
        SearchLimits full = tuning.searchLimits();
        SearchLimits limits = new SearchLimits(Math.min(full.maxExpandedNodes(), SPLICE_MAX_EXPANDED_NODES),
                full.timeLimitMillis(), full.heuristicWeight());

        // Take the guide before submitting. The join segment's cost is measured from the current {@code playerAt},
        // so if the window has moved by completion, the same point falls outside it and the yardstick drops to the geometric bound
        WindowField guide = host.guide();
        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        // The join point is a cell that can actually be walked (this path passes through it), so aim at it exactly with no radius.
        // Loosening it with a radius lands on a different cell, and the segment beyond it doesn't connect.
        // Everything from the join point onward is kept, so blocks already committed to be placed there can't be used by the join segment.
        // Without carrying this over, the budget resets to full on every join and builds a path exceeding the inventory
        Carryover carried = new Carryover(0, Carryover.placements(result.steps(), joinIndex + 1));
        CompletableFuture<PathResult> spliceFuture = executor.submit(
                AvoidedCellSource.wrap(view, recentFailures.avoided()), playerAt, joinPos, limits,
                tuning.costToGoGuideEnabled(), 0, carried);
        generationGate.whenStillCurrent(spliceFuture, myGeneration, TickLaps.timed("receive/splice", (splice, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: Failed to join the path", error);
                    }
                    return;
                }
                if (host.displayed() != shown || !currentGoal.equals(host.goal())) {
                    return;
                }
                if (!splice.complete() || splice.steps().isEmpty()) {
                    blockedFrom = playerAt;
                    LOGGER.debug("XaeroNav: Could not join the path ({}, join point={}, expanded nodes={})",
                            splice.termination(), joinPos.toShortString(), splice.expandedNodes());
                    return;
                }
                double spliceCost = splice.steps().stream().mapToDouble(PathStep::cost).sum();
                if (!spliceWorthTaking(spliceCost, playerAt, joinPos, currentGoal, guide)) {
                    // Joinable, but only by backtracking onto the original path. Discard and redraw everything
                    // (next tick blockedFrom takes effect and it falls to the caller's recalculate)
                    //
                    // Record the reason for refusing in numbers. In the real game (2026-09-18 23:20), "a completed
                    // 233-step path was discarded after deviating just 5 blocks", but the log back then only had
                    // the join segment's ticks, so there was no way to trace what the guide said to refuse. With
                    // the yardstick's three terms side by side, one line tells "the guide overestimated the join point" from "it really was backtracking"
                    blockedFrom = playerAt;
                    LOGGER.debug("XaeroNav: Gave up joining because it would backtrack (join point={}, join segment={}tick, "
                                    + "remaining here={} join point={}, path's actual remaining={}tick, yardstick={})",
                            joinPos.toShortString(), Math.round(spliceCost),
                            remainingAt(playerAt, currentGoal, guide),
                            remainingAt(joinPos, currentGoal, guide),
                            Math.round(costAlong(result.steps(), joinIndex)),
                            measuredInWindow(playerAt, joinPos, guide) ? "guide" : "geometric bound");
                    return;
                }
                blockedFrom = null;
                refusalGate.reset();
                seamRepair.queue(joinPos);
                long spliceLap = TickLaps.start();
                host.setDisplayed(spliced(shown, splice, joinIndex));
                TickLaps.add("splice swap", spliceLap);
                LOGGER.debug("XaeroNav: Joined the path ({} steps to the join, {} steps carried over, expanded nodes={})",
                        splice.steps().size(), result.steps().size() - joinIndex - 1, splice.expandedNodes());
            } finally {
                onChanged.run();
            }
        }));
        return true;
    }

    /**
     * Cap on distance accepted for a join point. The smaller of {@link #SPLICE_MAX_JOIN_BLOCKS} and the loaded range.
     *
     * <p>It's capped by {@code renderRadius} because the join segment's search range only extends that
     * far via {@code SearchBounds.around}: allowing a join point outside it just scans unloaded =
     * impassable cells, wasting the budget and ending in a redraw anyway.
     */
    private static double joinDistanceLimit(int renderRadius) {
        return Math.min(SPLICE_MAX_JOIN_BLOCKS, renderRadius);
    }

    /**
     * Records why a join was abandoned (diagnostics). As long as this stays silent, real-game logs can't
     * tell which branch accounts for the rest of the user report "local repair is fine, but sometimes it redraws everything".
     *
     * <p>Logs only when the reason differs from the previous one, so the same reason isn't logged every tick.
     */
    private void noteSpliceRefused(String reason, int steps, int minJoinIndex, int joinIndex) {
        if (!refusalGate.changed(reason)) {
            return;
        }
        LOGGER.debug("XaeroNav: Gave up joining the path ({}, path={} steps, min index={}, join point index={})",
                reason, steps, minJoinIndex, joinIndex);
    }

    /**
     * Connects the join segment and the existing path from the join point onward into one. The part before the join point is discarded.
     *
     * <p>The termination reason is inherited <b>from the original path</b>. The join segment reached the
     * join point (otherwise it isn't connected), so whether this path reaches its target is decided by the original path.
     */
    private static PathfindingState.DisplayedPath spliced(PathfindingState.DisplayedPath shown,
                                                            PathResult splice, int joinIndex) {
        List<PathStep> steps = shown.result().steps();
        List<PathStep> merged = new ArrayList<>(splice.steps());
        merged.addAll(steps.subList(joinIndex + 1, steps.size()));
        // The join segment doesn't know where the path goes beyond the join point, so it may re-step the same position at the seam
        PathLoops.Folded folded = PathLoops.fold(merged);
        // Segment boundary indices shift by the amount removed before the join point
        int shift = splice.steps().size() - (joinIndex + 1);
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        for (PathfindingState.PathSegment segment : shown.segments()) {
            if (segment.endStep() > joinIndex) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[segment.endStep() + shift],
                        segment.waypointIndex()));
            }
        }
        if (segments.isEmpty()) {
            segments.add(new PathfindingState.PathSegment(folded.steps().size() - 1, shown.waypointIndex()));
        }
        PathResult combined = new PathResult(List.copyOf(folded.steps()), shown.result().termination(),
                splice.expandedNodes(), splice.distinctNodes(), shown.result().limitsHeld());
        return new PathfindingState.DisplayedPath(combined, shown.mode(), shown.waypointIndex(),
                List.copyOf(segments));
    }

    /**
     * Whether this join is worth it, judged by <b>whether it gets closer to the destination in proportion to the cost paid</b>.
     *
     * <p>Compares the actual cost to reach the join point against how much the remaining distance to the
     * destination shrank. If it pays more than that shrinkage + {@link #SPLICE_DETOUR_ALLOWANCE_TICKS},
     * the join is paying not to move forward but <b>to go back to the original path</b>.
     *
     * <p><b>The yardstick for the remainder must be in the same units as the cost paid.</b> Without a
     * {@code guide}, {@link Heuristic} is <b>a geometric lower bound assuming you can sprint</b>, including
     * none of the effort of modifying terrain. Over lava or void, each block forward takes a bridge (about
     * 35.6 ticks = 10 blocks of sprinting, {@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}), so even a
     * forward join's actual cost runs 10x the lower bound, which {@link #SPLICE_DETOUR_ALLOWANCE_TICKS}
     * (171 ticks) can't cover. In a real-game log (2026-09-18, a Nether lava sea), <b>5 of 8 joins</b> were
     * refused here, 2 of which directly discarded completed routes (259 steps, 129 steps) on the spot. One
     * refused join was a segment moving 15 blocks forward: actual cost 807 ticks, while the geometric bound
     * shrank by only 40 ticks. This check itself was producing the same breakage as the End island
     * hopping that the top of {@link Splice} says must not be discarded.
     *
     * <p>So when the real remaining cost inside the window ({@link WindowField}) is available at both ends,
     * measure with that: on terrain needing bridges, <b>the player's remainder carries the same bridges</b>,
     * so taking the difference cancels out the terrain's price. The cliff case doesn't cancel (the join
     * point's remainder doesn't shrink by the re-climb), so only what we want to stop remains.
     *
     * <p><b>This can only be checked after the search.</b> Even if we tried to decide first using the
     * lower bound (geometric) to the join point, the lower bound can't detect backtracking in the cliff case.
     *
     * @param guide Remaining cost to the destination. If {@code null} or either point is outside the window, measured with the geometric bound
     */
    static boolean spliceWorthTaking(double spliceCost, BlockPos player, BlockPos joinPos, BlockPos goal,
                                     @Nullable WindowField guide) {
        return spliceCost <= remainingGained(player, joinPos, goal, guide) + SPLICE_DETOUR_ALLOWANCE_TICKS;
    }

    /** For diagnostics. The point's "remaining to the destination", by the yardstick actually used. */
    private static String remainingAt(BlockPos at, BlockPos goal, @Nullable WindowField guide) {
        if (guide != null && guide.measuredInWindow(at.getX(), at.getZ())) {
            return Math.round(guide.estimate(at.getX(), at.getY(), at.getZ())) + "tick";
        }
        return Math.round(Heuristic.estimate(at.getX(), at.getY(), at.getZ(),
                goal.getX(), goal.getY(), goal.getZ())) + "tick(geometric)";
    }

    /** For diagnostics. The actual cost of walking from the join point onward along the currently drawn path. */
    private static double costAlong(List<PathStep> steps, int from) {
        double total = 0;
        for (int i = from; i < steps.size(); i++) {
            total += steps.get(i).cost();
        }
        return total;
    }

    private static boolean measuredInWindow(BlockPos player, BlockPos joinPos, @Nullable WindowField guide) {
        return guide != null && guide.measuredInWindow(player.getX(), player.getZ())
                && guide.measuredInWindow(joinPos.getX(), joinPos.getZ());
    }

    /** How much "remaining to the destination" shrinks by moving to the join point. */
    private static double remainingGained(BlockPos player, BlockPos joinPos, BlockPos goal,
                                          @Nullable WindowField guide) {
        if (guide != null && guide.measuredInWindow(player.getX(), player.getZ())
                && guide.measuredInWindow(joinPos.getX(), joinPos.getZ())) {
            return guide.estimate(player.getX(), player.getY(), player.getZ())
                    - guide.estimate(joinPos.getX(), joinPos.getY(), joinPos.getZ());
        }
        return Heuristic.estimate(player.getX(), player.getY(), player.getZ(),
                        goal.getX(), goal.getY(), goal.getZ())
                - Heuristic.estimate(joinPos.getX(), joinPos.getY(), joinPos.getZ(),
                        goal.getX(), goal.getY(), goal.getZ());
    }

    private static int joinableStepIndex(Level level, List<PathStep> steps, Vec3 position, int minIndex) {
        return joinableStepIndex(steps, position, minIndex,
                i -> PathValidator.stepFailure(level, steps.get(i), i) == null);
    }

    /**
     * Version detached from {@code Level}. Choosing the join point depends only on the path and the player
     * position, so extracting just this pins down the behaviour without a world ({@code SpliceJoinTest}).
     *
     * @param usable Whether the step is still passable (in production, {@link PathValidator})
     */
    static int joinableStepIndex(List<PathStep> steps, Vec3 position, int minIndex,
                                  IntPredicate usable) {
        int from = Math.max(0, minIndex);
        // First measure without checks. Path validation is heavy, so apply it only to adoptable candidates
        int join = latestWithinSlack(steps, position, from, nearestDistance(steps, position, from, i -> true),
                usable);
        if (join >= 0) {
            return join;
        }
        // Everything nearby was blocked. <b>Don't give up here</b>: the range is taken from "the nearest
        // step" measured without checks, so if that area is blocked, the whole range misses. Detouring
        // around a blocked spot (blocks placed in a row) is exactly that case, and giving up would fall to
        // the caller's full redraw even though a join is possible. Re-measure using only passable steps
        return latestWithinSlack(steps, position, from, nearestDistance(steps, position, from, usable),
                usable);
    }

    /** Shortest distance to a non-bridge, {@code usable} step at or after {@code from}. Infinity if none. */
    private static double nearestDistance(List<PathStep> steps, Vec3 position, int from,
                                           IntPredicate usable) {
        double nearest = Double.MAX_VALUE;
        for (int i = from; i < steps.size(); i++) {
            if (steps.get(i).bridging()) {
                continue;
            }
            double distance = PathfindingState.distanceTo(position, steps.get(i).pos());
            if (distance < nearest && usable.test(i)) {
                nearest = distance;
            }
        }
        return nearest;
    }

    /**
     * The furthest-along step within {@link #JOIN_SLACK_BLOCKS} of {@code nearest}.
     * Scans from the back, so the first one found is it.
     */
    private static int latestWithinSlack(List<PathStep> steps, Vec3 position, int from, double nearest,
                                          IntPredicate usable) {
        if (nearest == Double.MAX_VALUE) {
            return -1;
        }
        double limit = nearest + JOIN_SLACK_BLOCKS;
        for (int i = steps.size() - 1; i >= from; i--) {
            PathStep step = steps.get(i);
            if (step.bridging() || PathfindingState.distanceTo(position, step.pos()) > limit) {
                continue;
            }
            if (usable.test(i)) {
                return i;
            }
        }
        return -1;
    }
}
