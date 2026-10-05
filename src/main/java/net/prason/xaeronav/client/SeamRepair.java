package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.PlannedCellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;

/**
 * <b>Re-solves only the stretch that spans a seam.</b> If it got cheaper, only that stretch is swapped in.
 *
 * <p>With both extend and splice, the later leg is solved <b>without knowing where the earlier leg went</b>.
 * Leg A is solved to arrive optimally at an artificial intermediate target, so "how to get there" and
 * "how to leave from there" disagree, and a corner is left only at the seam ({@link #SPAN_BLOCKS}).
 * Only here, with both sides in place, can that corner be rounded off.
 *
 * <p><b>Replanning everything is no substitute.</b> In offline measurements ({@code SeamDetourTest}),
 * replanning removed only half of the seam detours (the replanned path gets new seams of its own),
 * and the line underfoot was redrawn 4-12 times. Here it's 1-3 times, and it's the line ahead that changes, not the one underfoot.
 *
 * <p>{@link PathfindingState}'s destination/path/computing flag are touched only via {@link Host}
 * ({@link FlightNavState} does the same with {@code stillFlyingTo}/{@code onChanged}).
 * On async completion it reads <b>the latest state at that moment, not values captured at call time</b>,
 * so that a destination change or path swap that happened before completion isn't missed.
 */
final class SeamRepair {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Length re-solved across a seam (how many blocks before and after the seam, each).
     *
     * <p><b>Detours accumulate only at seams</b> because leg A is solved to "arrive optimally at an artificial
     * intermediate target {@code detailHorizon} ahead"; <b>how it arrives</b> there and <b>how it leaves</b> from there are
     * separate problems, and can't be optimized until both sides are in place. In offline measurements ({@code SeamDetourTest}),
     * 64-block windows containing a seam averaged 1.02-1.21x with a worst case of 1.795x, clearly separated
     * from windows without a seam (1.00-1.05x).
     *
     * <p>48 is "half of one leg on one side". Shorter leaves no room to round the corner; longer lets the cost of
     * <b>significantly redrawing the line not yet walked</b> win out.
     */
    private static final double SPAN_BLOCKS = 48.0;

    /**
     * This much ahead of the player is never re-solved (blocks).
     *
     * <p>The line underfoot being redrawn is a symptom fixed once before ("the guidance changes just by walking"). Seams are
     * at the root of an extension = usually dozens of blocks ahead, so leaving this alone doesn't weaken the repair.
     */
    private static final double KEEP_BLOCKS = 16.0;

    /** If the re-solved stretch isn't cheaper by at least this ratio, the line isn't redrawn. */
    private static final double MIN_GAIN = 0.98;

    /**
     * Minimum number of steps in the re-solved stretch. Below this, the two sides of the seam aren't both in place
     * (it's too close to the end of the path), so re-solving has no corner to round.
     */
    private static final int MIN_STEPS = 4;

    /**
     * Weight used for seam repair. <b>1.0 here only</b>: a repair is adopted only when a "line definitely cheaper than now"
     * is found, and there's no point swapping for a different line hit upon with a greedy weight. The stretch is short at 96 blocks, so
     * it fits within the budget above even with weight 1.0.
     */
    private static final double HEURISTIC_WEIGHT = 1.0;

    /**
     * Number of seams awaiting re-solve to remember. When it overflows, the oldest are dropped.
     *
     * <p>Dropping is fine because the older a seam, the more likely <b>the player has already walked past it</b>;
     * fixing it wouldn't change the guidance. Remembering them all would make the queue grow as the path grows.
     */
    private static final int QUEUE_LIMIT = 4;

    /** Mutable state held by {@link PathfindingState} that must be read and written on async completion. */
    interface Host {
        /** Current destination. */
        @Nullable BlockPos goal();

        /** Currently displayed path. */
        PathfindingState.DisplayedPath displayed();

        /** Swaps the displayed path. */
        void setDisplayed(PathfindingState.DisplayedPath path);

        /** Sets and clears the computing flag. */
        void setComputing(boolean computing);
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final RecentFailures recentFailures;

    /**
     * Coordinates of seams not yet re-solved (the root of an extension, or a splice point).
     *
     * <p><b>Kept as a queue.</b> With deep lookahead, extensions run several ticks in a row, so remembering only one
     * would miss every seam but the last. Written by worker threads (extend/splice completion),
     * read on tick; capped by {@link #QUEUE_LIMIT}, dropping the oldest.
     *
     * <p>Kept as coordinates rather than indices. Indices can shift with splices or detours before the repair runs, and
     * if a coordinate isn't found it's clear that "that seam is gone", so it can be silently dropped.
     */
    private final Queue<BlockPos> pending = new ConcurrentLinkedQueue<>();

    /**
     * The two ends of a loop where an extension ran back alongside the earlier path ({@code Extend#noteLoop}).
     *
     * <p>Several are remembered, not just one. When a small loop from a following extension in the same tick overwrites a large loop, the large one remains
     * (in-game End: a 153-step loop was overwritten by a 26-step loop, and a V shape remained).
     *
     * <p>Kept separately from the seam queue. {@link PathLoops#fold} can only fold loops that step on the same coordinate twice;
     * loops that run back alongside remain. With the usual repair that re-solves {@link #SPAN_BLOCKS} around a seam, if the loop's entry
     * is inside {@link #KEEP_BLOCKS} (underfoot), the entry remains too. Using the loop's two ends as the stretch directly,
     * the line before the entry doesn't change by a single block.
     */
    private final ConcurrentLinkedQueue<Loop> pendingLoops = new ConcurrentLinkedQueue<>();

    /** Number of loops to remember. When it overflows, the oldest are dropped. */
    private static final int LOOP_QUEUE_LIMIT = 4;

    /** The loop's entry (path side) and where it came back (extension side). */
    record Loop(BlockPos entry, BlockPos rejoin) {
    }

    /** Most recently reported reason for skipping a seam repair. Deduplication so the same reason isn't printed every time. */
    private final ChangeGate<String> refusalGate = new ChangeGate<>();

    SeamRepair(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
               Runnable onChanged, Host host, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.recentFailures = recentFailures;
    }

    /** Whether there are no seams awaiting re-solve. */
    boolean isEmpty() {
        return pending.isEmpty() && pendingLoops.isEmpty();
    }

    /** Remembers a loop running back alongside. Re-solved on the next {@link #tryRepair}, before seams. */
    void queueLoop(Loop loop) {
        pendingLoops.add(loop);
        while (pendingLoops.size() > LOOP_QUEUE_LIMIT) {
            pendingLoops.poll();
        }
    }

    /** Remembers a seam awaiting re-solve. When it overflows, the oldest are dropped. */
    void queue(BlockPos seam) {
        pending.add(seam);
        while (pending.size() > QUEUE_LIMIT) {
            pending.poll();
        }
    }

    /** On a destination change, drops the queue of seams awaiting re-solve and the most recent skip reason. */
    void clear() {
        pending.clear();
        pendingLoops.clear();
        refusalGate.reset();
    }

    /** When replanning everything, drops only the seams that vanish along with the earlier path. The skip reason is still valid, so it's kept. */
    void dropPending() {
        pending.clear();
        pendingLoops.clear();
    }

    /** Most recently reported skip reason (for diagnostics). */
    @Nullable String currentRefusal() {
        return refusalGate.current();
    }

    /**
     * Each seam is tried only once. Re-measuring a refused seam every tick returns only the same answer,
     * since neither the terrain nor the path has changed.
     *
     * @return whether a re-solve was dispatched (if so, the result is applied asynchronously)
     */
    boolean tryRepair(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        long lap = TickLaps.start();
        try {
            return tryRepairNow(level, player, shown, renderRadius);
        } finally {
            TickLaps.add("seam repair", lap);
        }
    }

    private boolean tryRepairNow(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        Loop loop = pendingLoops.poll();
        if (loop != null) {
            return tryCutLoop(level, player, shown, renderRadius, loop);
        }
        BlockPos seam = pending.poll();
        if (seam == null) {
            return false;
        }
        PathResult result = shown.result();
        List<PathStep> steps = result.steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(result);
        int seamIndex = stepIndexOf(steps, seam, walkedTo);
        if (seamIndex < 0) {
            // The seam vanished with a splice or detour. Nothing to fix
            noteSeamRepairRefused("seam not on the path");
            return false;
        }
        // Keep the stretch underfoot. Cutting it would bring back "the guidance changes just by walking"
        int first = walkedTo + 1;
        while (first < steps.size()
                && pathLengthBetween(steps, walkedTo, first) < KEEP_BLOCKS) {
            first++;
        }
        int from = seamIndex;
        while (from > first && pathLengthBetween(steps, from - 1, seamIndex) < SPAN_BLOCKS) {
            from--;
        }
        int to = seamIndex;
        while (to < steps.size() - 1 && pathLengthBetween(steps, seamIndex, to + 1) <= SPAN_BLOCKS) {
            to++;
        }
        if (from < 1 || from >= seamIndex || to <= seamIndex || to - from < MIN_STEPS) {
            // The two sides of the seam aren't both in place (at the end of the path, or too close to the player's feet)
            noteSeamRepairRefused("both sides of the seam not in place (before=" + (seamIndex - from)
                    + " steps, after=" + (to - seamIndex) + " steps)");
            return false;
        }

        solve(level, player, shown, renderRadius, from, to, first, "seam=" + seam.toShortString());
        return true;
    }

    /**
     * Re-solves from the loop's entry to where it came back. Nothing before the entry is changed.
     *
     * <p>If steps after the loop depend on blocks placed or holes dug inside the loop, it isn't re-solved: removing the loop would remove the footing too
     * ({@link PathLoops#laterStepsDependOn}). If they don't depend on them, it's reconnected even if the loop has placements or digging.
     */
    private boolean tryCutLoop(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius,
            Loop loop) {
        List<PathStep> steps = shown.result().steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(shown.result());
        int entry = stepIndexOf(steps, loop.entry(), walkedTo);
        int rejoin = entry < 0 ? -1 : stepIndexOf(steps, loop.rejoin(), entry + 1);
        if (rejoin < 0) {
            // Already passed, or the loop vanished with a splice or replan
            noteSeamRepairRefused("loop not on the path");
            return false;
        }
        if (PathLoops.laterStepsDependOn(steps, entry + 1, rejoin)) {
            noteSeamRepairRefused("steps after the loop depend on placements/digging inside it");
            return false;
        }
        solve(level, player, shown, renderRadius, entry + 1, rejoin, walkedTo + 1,
                "loop=" + loop.entry().toShortString() + "→" + loop.rejoin().toShortString());
        return true;
    }

    /**
     * Replaces the path from {@code sectionFrom} to {@code sectionTo} (inclusive) with a line replanned from {@code sectionFrom - 1}
     * to {@code sectionTo}. Only when it gets cheaper.
     *
     * @param first index from which to start counting placed blocks (a little ahead of the player)
     */
    private void solve(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius,
            int sectionFrom, int sectionTo, int first, String label) {
        List<PathStep> steps = shown.result().steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(shown.result());
        BlockPos fromPos = steps.get(sectionFrom - 1).pos();
        BlockPos toPos = steps.get(sectionTo).pos();
        double current = stepsCost(steps, sectionFrom, sectionTo);
        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = SearchBounds.around(level, fromPos, toPos,
                tuning.searchHorizontalMargin(), PathfindingState.verticalSearchMargin(level, false),
                renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("chunk capture", captureLap);
        SearchLimits full = tuning.searchLimits();
        // The budget is the same as one leg. <b>Don't cap it</b>: when it was cut at 60k, the in-game log
        // showed "re-solve didn't reach past the seam (NODE_BUDGET)", and Nether seams full of bridges
        // were left unfixed (offline, local detours also went back from a worst case of 1.059x to 1.927x).
        // What bounds the wait was never the node count but the wall clock (default 2 seconds)
        SearchLimits limits = new SearchLimits(full.maxExpandedNodes(), full.timeLimitMillis(),
                HEURISTIC_WEIGHT);
        // Blocks committed to be placed in the stretches not being swapped can't be used in this stretch
        Carryover carried = new Carryover(Carryover.trailingBridgeRun(steps.subList(0, sectionFrom)),
                Carryover.placements(steps.subList(0, sectionFrom), first)
                        + Carryover.placements(steps, sectionTo + 1));

        BlockPos currentGoal = host.goal();
        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        // Layer 1's guide isn't applied. The big picture is already decided by the path this stretch replaces; all that's needed here is
        // <b>the cheapest line connecting its two ends</b>. <b>Applying it was measured to have no effect</b>: in a 96-block
        // stretch the 16-block-resolution guide falls below the geometric Heuristic and always loses in max, so the expanded node count
        // didn't change by a single node (identical on all 5 terrains)
        PlannedCellSource repairTerrain = new PlannedCellSource(view, steps.subList(0, sectionFrom),
                walkedTo + 1);
        CompletableFuture<PathResult> repairFuture = executor.submit(
                AvoidedCellSource.wrap(repairTerrain, recentFailures.avoided()),
                fromPos, toPos, limits, false, 0, carried);
        generationGate.whenStillCurrent(repairFuture, myGeneration, TickLaps.timed("receive/seam", (repaired, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: Failed to re-solve the seam", error);
                    }
                    return;
                }
                if (host.displayed() != shown || currentGoal == null || !currentGoal.equals(host.goal())) {
                    return;
                }
                if (!repaired.complete() || repaired.steps().isEmpty()
                        || !PathfindingState.endOf(repaired, fromPos).equals(toPos)) {
                    noteSeamRepairRefused("re-solve didn't reach past the seam (" + repaired.termination() + ")");
                    return;
                }
                double replacement = stepsCost(repaired.steps(), 0, repaired.steps().size() - 1);
                if (replacement >= current * MIN_GAIN) {
                    noteSeamRepairRefused("re-solve isn't cheaper (" + Math.round(current) + "→"
                            + Math.round(replacement) + "tick)");
                    return;
                }
                refusalGate.reset();
                long replaceLap = TickLaps.start();
                host.setDisplayed(withSection(shown, repaired.steps(), sectionFrom, sectionTo));
                TickLaps.add("seam repair swap", replaceLap);
                LOGGER.debug("XaeroNav: Re-solved the seam ({}, {}→{}tick, {}→{} steps, expanded nodes={})",
                        label, Math.round(current), Math.round(replacement),
                        sectionTo - sectionFrom + 1, repaired.steps().size(), repaired.expandedNodes());
            } finally {
                onChanged.run();
            }
        }));
    }

    /**
     * Records why a seam couldn't be fixed (diagnostics). If this stayed silent, then when
     * "Re-solved the seam" doesn't appear in-game, you couldn't tell <b>whether it's refusing or not running at all</b>.
     * To avoid printing the same reason every time, it's printed only when it differs from the previous one.
     */
    private void noteSeamRepairRefused(String reason) {
        if (!refusalGate.changed(reason)) {
            return;
        }
        LOGGER.debug("XaeroNav: Skipped re-solving the seam ({})", reason);
    }

    /** Index of the step at or after {@code from} that stands on this coordinate, or {@code -1} if none. */
    private static int stepIndexOf(List<PathStep> steps, BlockPos pos, int from) {
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            if (steps.get(i).pos().equals(pos)) {
                return i;
            }
        }
        return -1;
    }

    /** Length along the path from {@code from} to {@code to} (blocks). */
    private static double pathLengthBetween(List<PathStep> steps, int from, int to) {
        double length = 0;
        for (int i = Math.max(1, from + 1); i <= to && i < steps.size(); i++) {
            length += Math.sqrt(steps.get(i).pos().distSqr(steps.get(i - 1).pos()));
        }
        return length;
    }

    /** Total cost from {@code from} to {@code to} (inclusive). */
    private static double stepsCost(List<PathStep> steps, int from, int to) {
        double total = 0;
        for (int i = from; i <= to && i < steps.size(); i++) {
            total += steps.get(i).cost();
        }
        return total;
    }

    /**
     * Builds the path with {@code from} through {@code to} swapped out. Everything before and after stays as is.
     *
     * <p>Leg boundaries that were inside the swapped part are dropped; those seams no longer exist. The intermediate target
     * numbers of the dropped ones are taken over by the following leg (the HUD counter and the map's dotted line both look at that one).
     */
    static PathfindingState.DisplayedPath withSection(PathfindingState.DisplayedPath shown,
                                                        List<PathStep> section, int from, int to) {
        List<PathStep> steps = shown.result().steps();
        List<PathStep> merged = new ArrayList<>(steps.subList(0, from));
        merged.addAll(section);
        merged.addAll(steps.subList(to + 1, steps.size()));
        // The swapped stretch doesn't know where its neighbors go, so it may step on the same position again at the seam
        PathLoops.Folded folded = PathLoops.fold(merged);
        int shift = section.size() - (to - from + 1);
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        int tailWaypointIndex = shown.waypointIndex();
        for (PathfindingState.PathSegment segment : shown.segments()) {
            int endStep = segment.endStep();
            if (endStep < from) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[endStep], segment.waypointIndex()));
            } else if (endStep > to) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[endStep + shift],
                        segment.waypointIndex()));
            } else {
                tailWaypointIndex = segment.waypointIndex();
            }
        }
        int last = folded.steps().size() - 1;
        if (segments.isEmpty() || segments.get(segments.size() - 1).endStep() < last) {
            segments.add(new PathfindingState.PathSegment(last, tailWaypointIndex));
        }
        PathResult combined = new PathResult(List.copyOf(folded.steps()), shown.result().termination(),
                shown.result().expandedNodes(), shown.result().distinctNodes(), shown.result().limitsHeld());
        // Only the part ahead of where the player walked was swapped, so the position currently pointed at remains valid
        PathProgress.INSTANCE.carryOver(combined);
        return new PathfindingState.DisplayedPath(combined, shown.mode(), shown.waypointIndex(),
                List.copyOf(segments));
    }
}
