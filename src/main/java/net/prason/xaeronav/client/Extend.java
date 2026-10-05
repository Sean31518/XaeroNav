package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.PlannedCellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Extension: extends the currently displayed path by the next leg <b>from its end</b> (not from the player).
 *
 * <p>That's the only difference from {@link PathfindingState#recalculate}, but the result differs greatly. Replanning
 * from the player rebuilds even the near part already being walked every time, and the whole guidance is redrawn when the
 * target moves slightly. Extending from the end leaves the near part untouched by definition, and the search only ever looks at new ground.
 *
 * <p>{@link PathfindingState}'s destination/path/computing flag are touched only via {@link Host}
 * (same structure as {@link FlightNavState}/{@link SeamRepair}/{@link Splice}). Long-distance route selection
 * ({@code selectDetailTarget}, {@code preparedVoxelGuide}, feeding into the stuck check) is the core of the state machine
 * still living in PathfindingState itself, so it's also queried via Host.
 */
final class Extend {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Distance (blocks) the player must move after a failed extension from the end before trying again at the same end.
     *
     * <p>Extension failures are usually temporary: the chunks beyond simply haven't loaded yet.
     * Walking can load them and make it succeed, but if failures are remembered only by the end's coordinates, it never
     * tries again until the path is replaced; none of the other recalculation triggers (deviation, reaching the end, terrain
     * changes) fire, so in practice no search runs at all until the player walks all the way to the end.
     */
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 16.0;

    /**
     * Horizontal distance (per axis) at which {@link #noteLoop} considers the path to have "come back". A line turning back from a dead end
     * beyond the window's edge can run parallel to the incoming line a dozen or so blocks apart (the V shape in the real-game End).
     */
    private static final int LOOP_NEAR_BLOCKS = 16;

    /** Height difference at which {@link #noteLoop} considers the path to have "come back". */
    private static final int LOOP_NEAR_Y = 6;

    /** Detour steps along the path required per block of separation between the two ends. So a line normally walked between two distant points isn't treated as a loop. */
    private static final int LOOP_GAP_PER_BLOCK = 3;

    /**
     * Minimum steps along the path for {@link #noteLoop} to consider it a loop. An extension that merely changes direction
     * near the end always comes close to a few steps before the end, so this is a length that doesn't trigger on that.
     */
    private static final int LOOP_MIN_GAP_STEPS = 20;

    /**
     * Increase in horizontal distance to the destination at which {@link #noteRetreatingTail} considers it to have "moved away".
     * Moving a few blocks away while going around is normal, so this is a width that doesn't trigger on that.
     */
    private static final double RETREATING_TAIL_LOG_BLOCKS = 16.0;

    /** Mutable state held by {@link PathfindingState} that must be read and written on async completion, plus long-distance route selection. */
    interface Host {
        /** The current destination. */
        @Nullable BlockPos goal();

        /** The currently displayed path. */
        PathfindingState.DisplayedPath displayed();

        /** Replaces the displayed path. */
        void setDisplayed(PathfindingState.DisplayedPath path);

        /** Raises and lowers the searching flag. */
        void setComputing(boolean computing);

        /**
         * Whether the long-distance route for this destination is still incomplete, waiting for the map to load.
         * {@code -1} if there's no matching long-distance route, otherwise the number of unloaded regions
         * (0 means not waiting for loading).
         */
        int coarseRoutePendingRegions(BlockPos currentGoal);

        /** Decides the detailed search's goal (delegates to {@code PathfindingState#selectDetailTarget}). */
        PathfindingState.DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                                           int reach, boolean boatAvailable, boolean playerAnchored,
                                                           int minWaypointIndex, boolean ceilingDimension,
                                                           boolean navGraphGuided);

        /** The guide for a search aiming directly at the destination (delegates to {@code PathfindingState#goalGuide}). */
        PathfindingState.@Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from,
                                                       BlockPos currentGoal, int renderRadius);

        /** Feeds this search's result into the stuck check (delegates to {@code PathfindingState#noteSearchOutcome}). */
        void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result);
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final SeamRepair seamRepair;
    private final RecentFailures recentFailures;

    /**
     * The point beyond which the path's end couldn't be extended. A brake against repeatedly trying to extend at the same end;
     * it clears naturally when the path is replaced (= a different end).
     */
    private volatile BlockPos blockedAt;

    /** Player position when {@link #blockedAt} was set. See {@link #EXTEND_RETRY_MOVE_BLOCKS}. */
    private volatile BlockPos blockedFrom;

    // True only right after goto, while extension is held to one leg waiting for the map to load. Marker for logging just once
    private boolean heldForStreaming;

    Extend(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
           Runnable onChanged, Host host, SeamRepair seamRepair, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.seamRepair = seamRepair;
        this.recentFailures = recentFailures;
    }

    /** Discards all extension brakes on a destination change or full replan. */
    void clear() {
        blockedAt = null;
        blockedFrom = null;
        heldForStreaming = false;
    }

    /**
     * Whether the path should be extended from its end now.
     *
     * <p>With deep look-ahead ({@code deepLookAheadEnabled}), it keeps extending <b>until the end reaches the edge of the loaded
     * chunks</b>. This avoids a magic number, and since walking loads new chunks and it extends again, it naturally becomes
     * "the farther you go, the farther you see". Once fully extended, it stops on its own.
     *
     * <p>With shallow look-ahead, it starts {@link PathfindingState#EXTEND_DISTANCE_BLOCKS} before the end as before. However, that value
     * assumes a surface world with paths hundreds of blocks long, and in dimensions where {@code detailReach} shrinks (24 measured in the Nether)
     * it exceeds the path length, becoming "always before the end" = not working as look-ahead. The path length itself is used as a lower bound.
     */
    boolean shouldExtend(Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        PathResult result = shown.result();
        if (!extendableTail(result)) {
            return false;
        }
        List<PathStep> steps = result.steps();
        BlockPos end = steps.get(steps.size() - 1).pos();
        BlockPos currentGoal = host.goal();
        if (end.equals(currentGoal) || extendBlocked(player, end)) {
            return false;
        }
        int pendingRegions = host.coarseRoutePendingRegions(currentGoal);
        boolean streaming = pendingRegions > 0;
        if (heldForStreaming && !streaming) {
            heldForStreaming = false;
            LOGGER.debug("XaeroNav: Map is complete, returning to normal extension");
        }
        if (streaming
                && PathfindingState.horizontalDistance(player.blockPosition(), end)
                        > PathfindingState.detailHorizon(renderRadius)) {
            // Right after goto, while the map is still streaming in, the end is held to one leg ahead.
            // extendLead only looks at the loaded margin, so left alone, extensions chain every time a chunk arrives,
            // the end grows hundreds of steps ahead, and the seam moves every tick (real-game log "101->334->467 steps",
            // "Dropped the completed path"). The player always has detailHorizon's worth of guidance, so it
            // doesn't break off. Returns to normal look-ahead once pendingRegions reaches 0
            if (!heldForStreaming) {
                heldForStreaming = true;
                LOGGER.debug("XaeroNav: Holding extension to {} blocks while the map is loading"
                                + " (unloaded regions={}, {} blocks to the end, {} steps)",
                        PathfindingState.detailHorizon(renderRadius), pendingRegions,
                        Math.round(PathfindingState.horizontalDistance(player.blockPosition(), end)), steps.size());
            }
            return false;
        }
        if (XaeroNavConfig.INSTANCE.deepLookAheadEnabled()) {
            // Extend only while enough loaded ground remains to be meaningful as guidance.
            // Allowing it right up to renderRadius puts the target outside the loaded square (see extendLead)
            return extendLead(player, end, renderRadius) >= PathfindingState.MIN_DETAIL_REACH_BLOCKS;
        }
        double lead = Math.min(PathfindingState.EXTEND_DISTANCE_BLOCKS, pathLength(steps));
        return PathfindingState.distanceTo(player.position(), end) <= lead;
    }

    /**
     * Why {@link #shouldExtend} declined. Used only to trace from real-game logs why the player walked all the way to the end.
     * Keep the order of checks the same as {@link #shouldExtend}; if they diverge, a reason other than the condition that actually applied is reported.
     */
    String extendRefusal(Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        PathResult result = shown.result();
        if (!extendableTail(result)) {
            return "termination was " + result.termination();
        }
        List<PathStep> steps = result.steps();
        BlockPos end = steps.get(steps.size() - 1).pos();
        BlockPos currentGoal = host.goal();
        if (end.equals(currentGoal)) {
            return "end is the destination itself";
        }
        if (extendBlocked(player, end)) {
            return "end where the previous extension failed";
        }
        int pendingRegions = host.coarseRoutePendingRegions(currentGoal);
        if (pendingRegions > 0
                && PathfindingState.horizontalDistance(player.blockPosition(), end)
                        > PathfindingState.detailHorizon(renderRadius)) {
            return "waiting for map to load (unloaded regions " + pendingRegions + ")";
        }
        if (XaeroNavConfig.INSTANCE.deepLookAheadEnabled()) {
            return "not enough loaded margin (remaining " + extendLead(player, end, renderRadius)
                    + " blocks, need " + PathfindingState.MIN_DETAIL_REACH_BLOCKS + ")";
        }
        return "to the end: " + Math.round(PathfindingState.distanceTo(player.position(), end)) + " blocks (extension starts "
                + Math.round(Math.min(PathfindingState.EXTEND_DISTANCE_BLOCKS, pathLength(steps))) + " blocks before)";
    }

    /**
     * Whether it's OK to extend beyond this end.
     *
     * <p>Not "only paths that arrived". <b>An end cut off by running out of budget is a legitimate frontier</b>:
     * a path that can actually be walked has been drawn up to there ({@code buildResult} only follows the chain of predecessor nodes),
     * and what's needed to solve the rest is resources, not a different place. Since the target is cut at a fixed horizon,
     * running out of budget is the norm for distant destinations, so stopping here would mean extension never happens.
     *
     * <p>Only {@code EXHAUSTED} (the open set within range ran out = a dead end is proven) and
     * {@code CANCELLED} (the result itself is discarded) are different. Extending from the former would mean digging the same dead end
     * over and over, so it's left to the existing retries (range expansion, coarse via-point chain).
     */
    private static boolean extendableTail(PathResult result) {
        return switch (result.termination()) {
            case REACHED_GOAL, NODE_BUDGET, TIME_LIMIT -> true;
            case EXHAUSTED, CANCELLED -> false;
        };
    }

    /**
     * Horizontal distance (blocks) the search may go beyond the path's end.
     *
     * <p><b>Loaded chunks form a square centered on the player</b>, so an extension starting at the end can only use
     * that radius minus "the distance from the player to the end". If this isn't subtracted and
     * {@code renderRadius} or {@code detailReach} is passed as-is as an end-relative cap, the target lands up to
     * {@code renderRadius + reach} from the player = <b>always inside unloaded chunks</b>.
     * Unloaded cells are {@code CellData.ABSENT} = impassable, so the search exhausts the open set and
     * ends with {@code EXHAUSTED}, and {@code complete()} is never true.
     */
    private static int extendLead(Player player, BlockPos end, int renderRadius) {
        return renderRadius - (int) Math.round(PathfindingState.horizontalDistance(player.blockPosition(), end));
    }

    /**
     * Whether this end is marked "couldn't extend" and the mark hasn't expired yet.
     * Walking {@link #EXTEND_RETRY_MOVE_BLOCKS} loads new chunks, so the mark is dropped then.
     */
    private boolean extendBlocked(Player player, BlockPos end) {
        if (!end.equals(blockedAt)) {
            return false;
        }
        BlockPos from = blockedFrom;
        if (from != null && from.distSqr(player.blockPosition())
                > EXTEND_RETRY_MOVE_BLOCKS * EXTEND_RETRY_MOVE_BLOCKS) {
            blockedAt = null;
            blockedFrom = null;
            return false;
        }
        return true;
    }

    /** Straight-line distance from one end of the path to the other. A guide so the look-ahead margin isn't taken longer than the path. */
    private static double pathLength(List<PathStep> steps) {
        BlockPos first = steps.get(0).pos();
        BlockPos last = steps.get(steps.size() - 1).pos();
        return Math.sqrt(first.distSqr(last));
    }

    /**
     * Extends the currently displayed path by the next leg <b>from its end</b> (not from the player).
     *
     * <p>Solving leg by leg was always the case; global optimality is held by layer 1's coarse route.
     * So nothing is lost by extending; if anything, replanning "every time from the player toward a moving target"
     * is what creates zigzags.
     *
     * <p>Only paths whose end reached the target can be the <b>source</b> of an extension ({@link #shouldExtend}).
     * Extending further from an unreached end would mean digging on into a dead end, so it's left to the existing retries
     * (range expansion, coarse via-point chain). On the other hand, if the <b>result</b> of the extension didn't arrive,
     * whatever could be drawn is attached: the same treatment as {@link PathfindingState#recalculate} showing a provisional path as-is,
     * and since the combined {@code complete} becomes false, the triggers above naturally take over from then on.
     */
    void extendPath(PathfindingState.DisplayedPath shown) {
        long lap = TickLaps.start();
        try {
            extendPathNow(shown);
        } finally {
            TickLaps.add("extend", lap);
        }
    }

    private void extendPathNow(PathfindingState.DisplayedPath shown) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = host.goal();
        if (level == null || player == null || currentGoal == null) {
            return;
        }
        List<PathStep> steps = shown.result().steps();
        BlockPos from = steps.get(steps.size() - 1).pos();
        boolean boatAvailable = ChunkView.boatAvailable(player);
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // The continuation runs on a worker thread, so copy the player and dimension here before passing them
        BlockPos playerAt = player.blockPosition();
        ResourceKey<Level> searchDimension = level.dimension();
        boolean ceilingDimension = level.dimensionType().hasCeiling();

        // The end-relative cap is cut by the "remainder" of the loaded square centered on the player (see extendLead)
        int lead = extendLead(player, from, renderRadius);
        // The smaller of the search horizon and the remainder of the loaded chunks. Neither is a terrain measurement,
        // so it doesn't oscillate with success/failure the way detailReach once did
        int reach = Math.min(PathfindingState.detailHorizon(renderRadius), lead);
        PathfindingState.GoalGuide goalGuide = host.goalGuide(level, player, from, currentGoal, renderRadius);
        boolean navGraphGuided = goalGuide != null && goalGuide.navGraph();
        if (navGraphGuided && extendLead(player, from, NavGraphGuide.window(renderRadius))
                < PathfindingState.MIN_DETAIL_REACH_BLOCKS) {
            // The box searched with the nav graph is cut by the window, not the render distance (navGraphBounds). Passing the margin as measured by render distance,
            // an extension whose end is at the box's edge burns 100,000 nodes without moving a step (real game: repeated every few seconds in the Nether and End at render distance 15)
            blockExtend(from, playerAt);
            return;
        }
        PathfindingState.DetailTarget detail = PathfindingState.landingOr(goalGuide, currentGoal,
                host.selectDetailTarget(from, currentGoal, lead, reach, boatAvailable, false, shown.waypointIndex(),
                        ceilingDimension, navGraphGuided));
        BlockPos target = detail.target();
        // When aiming directly at the destination, don't stop even if it's far (the box cuts it, so the search is finite).
        // Only when aiming at an intermediate target is "extending beyond the loaded chunks" used as a brake.
        // The landing point is a node of the window's graph = loaded, so don't stop
        boolean aimingAtGoal = target.equals(currentGoal);
        boolean landing = goalGuide != null && goalGuide.landing() != null
                && target.equals(goalGuide.landing().target());
        if (target.equals(from)
                || (!aimingAtGoal && !landing && PathfindingState.horizontalDistance(from, target) > lead)) {
            // There's nowhere further to extend, or the extension target is outside the loaded chunks (if no intermediate target
            // lies within the remainder, selectDetailTarget falls back to the real destination).
            // Without setting the brake, shouldExtend would keep returning true every tick, each time
            // running selectDetailTarget (= map reads on the main thread)
            blockExtend(from, playerAt);
            return;
        }

        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = navGraphGuided
                ? PathfindingState.navGraphBounds(level, from, target, playerAt, renderRadius,
                        tuning.searchHorizontalMargin(), landing, goalGuide.costToGo())
                : SearchBounds.around(level, from, target, tuning.searchHorizontalMargin(),
                        PathfindingState.verticalSearchMargin(level, false), renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("chunk capture", captureLap);
        SearchLimits limits = navGraphGuided ? PathfindingState.navGraphLimits(tuning.searchLimits())
                : tuning.searchLimits();

        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        boolean reachesGoal = aimingAtGoal;
        int newWaypointIndex = reachesGoal ? -1 : detail.waypointIndex();
        boolean costToGoGuideEnabled = tuning.costToGoGuideEnabled();
        // Solve the continuation with resources minus what the near path will use. Only count <b>from where the player is onward</b>;
        // what's already passed has already been placed and has also been deducted from the count on hand
        Carryover carried = new Carryover(Carryover.trailingBridgeRun(steps),
                Carryover.placements(steps, PathProgress.INSTANCE.indexFor(shown.result()) + 1));
        PlannedCellSource futureTerrain = new PlannedCellSource(view, steps,
                PathProgress.INSTANCE.indexFor(shown.result()) + 1);
        CostToGo prepared = PathfindingState.preparedGuide(goalGuide, currentGoal, target);
        CompletableFuture<PathResult> extendFuture = executor.submit(
                AvoidedCellSource.wrap(futureTerrain, recentFailures.avoided()), from, target, limits,
                costToGoGuideEnabled, detail.goalRadius(), carried, prepared);
        generationGate.whenStillCurrent(extendFuture, myGeneration, TickLaps.timed("receive/extend", (result, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: Failed to extend the path", error);
                    }
                    return;
                }
                // Discard if what we were extending has been replaced. Even with the same generation, displayed may have
                // been replaced wholesale by a destination change or deviation
                PathfindingState.DisplayedPath current = host.displayed();
                if (current != shown || !currentGoal.equals(host.goal())) {
                    return;
                }
                PathfindingState.logSearchReach(from, target, result);
                List<PathStep> tail = result.steps();
                if (result.complete()) {
                    // The extension reached its target = we can move forward. Clear the stuck marker here.
                    // Conversely, not reaching isn't grounds for being stuck: extension failures are usually just that
                    // what's beyond isn't loaded yet, and the displayed path can still be walked. Only the side replanning from the player
                    // (recalculate) answers "can we get to the destination from here"
                    host.noteSearchOutcome(playerAt, PathfindingState.endOf(result, from), result);
                }
                if (tail.isEmpty()) {
                    // Couldn't advance a single step. Keep the near path as-is and leave it to normal recalculation
                    blockExtend(from, playerAt);
                    return;
                }
                if (!result.complete()
                        && PathfindingState.horizontalDistance(from, tail.get(tail.size() - 1).pos())
                                < PathfindingState.MIN_EXTEND_PROGRESS_BLOCKS) {
                    // Extending from an out-of-budget end is OK, but if it barely moved forward, going further is pointless.
                    // selectFallback returns "the best point at least 5 blocks from the start", so even in a dead-end
                    // pocket a slightly advanced path comes back every time; without a brake it keeps crawling a few blocks at a time
                    //
                    // Discard without attaching. complete belongs to the last leg, so attaching a merely crawling tail
                    // would treat even a path that had arrived as unreached, falling into "replan when nearing a cut-off end"
                    // and rebuilding the whole path. A proven path would be lost for a gain of a few blocks
                    blockExtend(from, playerAt);
                    return;
                }
                // Even if unreached, attach whatever could be drawn. The recalculate side already does this (provisional path).
                // Throwing it away would waste, every time, a path that had been drawn up to the loaded edge
                // The seam is here (the near end). Re-solve it once things settle ({@link SeamRepair})
                long loopLap = TickLaps.start();
                SeamRepair.Loop loop = noteLoop(steps, tail, PathProgress.INSTANCE.indexFor(current.result()) + 1,
                        target, result, navGraphGuided);
                TickLaps.add("loop detection", loopLap);
                long retreatLap = TickLaps.start();
                noteRetreatingTail(from, tail.get(tail.size() - 1).pos(), currentGoal, target, result, goalGuide);
                TickLaps.add("retreat check", retreatLap);
                seamRepair.queue(from);
                if (loop != null) {
                    seamRepair.queueLoop(loop);
                }
                RouteExplain.log("extend", level, from, target, currentGoal, result, prepared,
                        goalGuide == null ? null : goalGuide.costToGo(), view,
                        tuning.movementOptions(), renderRadius);
                long appendLap = TickLaps.start();
                host.setDisplayed(append(current, result, newWaypointIndex, reachesGoal));
                TickLaps.add("extension append", appendLap);
                blockedAt = null;
                blockedFrom = null;
            } finally {
                onChanged.run();
            }
        }));
    }

    /**
     * If the extended leg's end is farther from the destination than the end before extension, logs one line on how the guide saw both ends at that time.
     *
     * <p>Extension's endpoint selection always picks a point that gets closer to the destination on the guide, so if it extended in a receding direction,
     * either "the guide rated the detour as closer" or "it was comparing estimates outside the window".
     * Listing both ends' values alongside whether they were actually traced inside the window ({@link WindowField#measuredInWindow}) settles it in one line.
     */
    private static void noteRetreatingTail(BlockPos from, BlockPos end, BlockPos currentGoal, BlockPos target,
            PathResult result, PathfindingState.@Nullable GoalGuide goalGuide) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        double fromLeft = PathfindingState.horizontalDistance(from, currentGoal);
        double endLeft = PathfindingState.horizontalDistance(end, currentGoal);
        if (endLeft <= fromLeft + RETREATING_TAIL_LOG_BLOCKS) {
            return;
        }
        NavGraphGuide.logOffThread(() -> {
            String guide = "none";
            if (goalGuide != null) {
                CostToGo costToGo = goalGuide.costToGo();
                guide = "%s before=%d%s after=%d%s, source of value before=%s, source of value after=%s".formatted(
                        goalGuide.navGraph() ? "nav graph" : "3D coarse layer etc.",
                        Math.round(costToGo.estimate(from.getX(), from.getY(), from.getZ())), windowNote(costToGo, from),
                        Math.round(costToGo.estimate(end.getX(), end.getY(), end.getZ())), windowNote(costToGo, end),
                        NavGraphGuide.origin(costToGo, from), NavGraphGuide.origin(costToGo, end));
            }
            LOGGER.debug("XaeroNav: Extension moved away from the destination (end before={} at {} from destination, end after={} at {}, "
                            + "{} steps/{}, target={}, guide={})",
                    from.toShortString(), Math.round(fromLeft), end.toShortString(), Math.round(endLeft),
                    result.steps().size(), result.termination(), target.toShortString(), guide);
        });
    }

    private static String windowNote(CostToGo costToGo, BlockPos pos) {
        if (!(costToGo instanceof WindowField field)) {
            return "";
        }
        return field.measuredInWindow(pos.getX(), pos.getZ()) ? "(in window)" : "(estimate outside window)";
    }

    /**
     * Logs one line if the extended leg comes back near the earlier part of the existing path. Extension only solves beyond the end,
     * so even if it comes back the near path isn't reviewed, and the line is displayed still drawing a loop. The loop may later be cut by
     * seam repair, but "why the extension extended in a returning direction" can't be learned from there.
     * Reports the pair that detours the most steps along the path.
     *
     * @param fromIndex index of the step the player steps on next. Loops back to already-walked places can't be cut off
     * @return both ends of the loop (passed to {@link SeamRepair#queueLoop} to cut it off). {@code null} if there's no loop
     */
    private static SeamRepair.@Nullable Loop noteLoop(List<PathStep> route, List<PathStep> tail, int fromIndex,
            BlockPos target, PathResult result, boolean navGraphGuided) {
        PathLoops.Return found = PathLoops.widestReturn(route, tail, fromIndex, LOOP_NEAR_BLOCKS, LOOP_NEAR_Y,
                LOOP_MIN_GAP_STEPS, LOOP_GAP_PER_BLOCK);
        if (found == null) {
            return null;
        }
        LOGGER.debug("XaeroNav: Extension came back toward the earlier path (extension step {}={}, near path step {}={}, "
                        + "loop of {} steps along the path, path={} steps, extension={} steps/{}, end={}, target={}, nav graph={})",
                found.rejoin(), tail.get(found.rejoin()).pos().toShortString(), found.entry(),
                route.get(found.entry()).pos().toShortString(), found.gap(), route.size(), tail.size(),
                result.termination(), route.get(route.size() - 1).pos().toShortString(), target.toShortString(),
                navGraphGuided);
        return new SeamRepair.Loop(route.get(found.entry()).pos(), tail.get(found.rejoin()).pos());
    }

    /**
     * Records that this end couldn't be extended. Expires after walking {@link #EXTEND_RETRY_MOVE_BLOCKS}.
     *
     * <p>No "Path recalculated" notification is shown here. The near path hasn't changed by a single block, so
     * only the warning would flash even though nothing changed from the user's point of view.
     */
    private void blockExtend(BlockPos end, BlockPos playerAt) {
        blockedAt = end;
        blockedFrom = playerAt;
    }

    /**
     * Assembles the extended path. Concatenates the step lists and records the leg boundaries.
     *
     * <p>This is where the carry-over is communicated to {@link PathProgress}. Extension doesn't change earlier indices,
     * so the mapping stays valid, but without telling it, it's treated as a different path and falls back to a full scan.
     */
    private static PathfindingState.DisplayedPath append(PathfindingState.DisplayedPath current, PathResult tail,
                                                           int tailWaypointIndex, boolean reachesGoal) {
        List<PathStep> merged = new ArrayList<>(current.result().steps());
        merged.addAll(tail.steps());
        // The extended leg doesn't know where the near part went, so it may step on the same position again at the seam
        PathLoops.Folded folded = PathLoops.fold(merged);
        // complete means "did this path reach its target", not "did it reach the final destination"
        // (a path toward an intermediate target is also complete if it reached that target). Making this reachesGoal
        // would treat it as unreached the moment it's extended, stopping shouldExtend so it only extends once
        PathResult combined = new PathResult(List.copyOf(folded.steps()), tail.termination(),
                tail.expandedNodes(), tail.distinctNodes(), tail.limitsHeld());
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        for (PathfindingState.PathSegment segment : current.segments()) {
            segments.add(new PathfindingState.PathSegment(folded.newIndex()[segment.endStep()],
                    segment.waypointIndex()));
        }
        segments.add(new PathfindingState.PathSegment(folded.steps().size() - 1, tailWaypointIndex));
        PathProgress.INSTANCE.carryOver(combined);
        return new PathfindingState.DisplayedPath(combined,
                reachesGoal ? PathfindingState.PathMode.GOAL : PathfindingState.PathMode.WAYPOINT,
                tailWaypointIndex, List.copyOf(segments));
    }
}
