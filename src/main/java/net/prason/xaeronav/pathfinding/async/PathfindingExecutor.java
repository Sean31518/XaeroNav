package net.prason.xaeronav.pathfinding.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.astar.Tolerances;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Runs A* on a worker thread. When a new request arrives, the old job (running or not yet started)
 * is cancelled, so only the latest request ever returns a result.
 *
 * <p>Building the {@link CellSource} (collecting chunk references on the main thread) is the caller's
 * job. This class handles running A*, controlling its cancellation, and annotating hazards.
 * Annotation lives here so the view used to find the path and the view used to annotate it can't be mixed up.
 */
public final class PathfindingExecutor {

    private static final Logger LOGGER = LogManager.getLogger();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "xaeronav-pathfinding");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Second worker that {@link #submitWithDeepFallback} uses only for the deep-budget search.
     *
     * <p>The normal-budget search keeps running on {@link #executor} while the deep-budget search runs
     * here concurrently. When the normal budget reaches the goal, an {@link AtomicBoolean} stops this one,
     * so two cores stay busy only "when the normal budget ends up failing".
     */
    private final ExecutorService deepExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding-deep");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The layer-1 guide built last time, and the conditions it was built under.
     *
     * <p>While walking, it used to be <b>rebuilt on every search</b> even though neither the destination
     * nor the terrain had changed (measured 15-30 ms per build, mostly scanning the map).
     *
     * <p><b>Don't snap the box outward to a grid.</b> Reuse becomes more frequent, but the extra margin
     * becomes unknown cells (cheapest on the lower-bound side) and weakens the guide: in measurements the
     * Nether lava sea grew from 560,075 to 610,699 nodes, and <b>the End void crossing dropped from
     * reaching the goal to running out of budget</b>.
     *
     * <p><b>It's fine to keep using a stale table when terrain loads later.</b> On the lower-bound side
     * unknown cells fall to the cheapest value (multiplier 1.0), so the old table stays at or below the
     * new one, i.e. remains a lower bound. A change in the reference Y (which floor
     * {@link LiveCoarseSampler} picks), however, can break the lower bound, so it is part of the key and
     * must match exactly.
     *
     * <p>Conversely, only when <b>terrain is dug out and becomes cheaper</b> can the old table overestimate.
     * That happens only when "the terrain changes while the box, destination and reference Y stay the
     * same", i.e. the player digs without moving; walking one block moves the box and triggers a rebuild.
     *
     * <p>Only the single {@link #executor} worker touches this (the deep-budget search is handed the
     * finished table).
     */
    private record GuideKey(BlockPos goal, SearchBounds bounds, int referenceY,
                            CoarseRouter.BridgePolicy bridgePolicy) {
    }

    private GuideKey guideKey;

    private CostToGo guide;

    /**
     * Radius (blocks) within which an intermediate waypoint of the coarse waypoint chain counts as reached.
     *
     * <p>A waypoint is the representative point of one cell = one chunk (16 blocks), so the actual route
     * naturally deviates up to 8 blocks from its center. Requiring the exact coordinate puts a detour into
     * the path just to get there. The radius matches the cell's half-width.
     */
    private static final int COARSE_LEG_GOAL_RADIUS_BLOCKS = 8;

    /**
     * Horizontal margin (blocks) of the map used to build each leg's cost guide. Covering the leg's start
     * and end is enough: the guide is only an aid taken as a max with the geometric heuristic (see the
     * {@link CostToGo} doc) and doesn't need to see far.
     *
     * <p><b>Don't make this larger.</b> {@link CoarseRouter#costToGo} allocates a fresh array proportional
     * to the box area every time and runs Dijkstra over it ({@code chunksX*chunksZ*MAX_FLOORS} states).
     * Reusing the wide box from the overall leg-split plan ({@link CoarseRouter#findRoute}, run once) here
     * means paying the wide box's Dijkstra once per leg. In-game (End cliff edge, 2026-08-28), widening the
     * box 4x (64->256) stretched one leg's search from 0.7-0.9 s to 0.95-1.15 s, and widening it all the way
     * to `renderRadius` stretched it to 1.4-1.6 s: a single leg ate the whole chain's 2-second budget,
     * leaving no time for the cap-loosening stages to run.
     */
    private static final int COARSE_LEG_GUIDE_MARGIN_BLOCKS = 64;

    /**
     * Multipliers used to loosen caps when the search gets stuck. Finishes with {@link RunCaps#NONE}
     * (unlimited).
     *
     * <p>Going straight to unlimited could make the first dead end suddenly produce a huge bridge or a long
     * dive in the guidance. Stepping up keeps it close to the minimum length actually needed to make a way.
     */
    private static final int[] RUN_CAP_LOOSEN_MULTIPLIERS = {2, 4};

    /**
     * Weight {@link #refineQuality} uses for the redo. Chosen from measurements on saved in-game End terrain
     * as the point that <b>captures most of the improvement with the smallest increase in expanded nodes</b>
     * (path cost {@code -3.0%} / expansions {@code +40%}; going down to 1.15 only yields {@code -5.0%} /
     * {@code +46%}).
     */
    private static final double REFINE_HEURISTIC_WEIGHT = 1.25;

    /**
     * Weights {@link #retryGreedier} tries in order. Measurements show solutions start appearing at 2.5, so
     * that is the first step. 3.0 is also the upper limit of {@code XaeroNavConfig#heuristicWeight}, so
     * nothing beyond it is provided: the greedier the search, the more roundabout the path, so we want to
     * stop at the smallest weight that reaches the goal.
     */
    private static final double[] GREEDY_RETRY_WEIGHTS = {2.5, 3.0};

    /**
     * Share (%) of the budget and time given to the first search.
     *
     * <p><b>If it's used up, neither loosening nor {@link #retryGreedier} can run.</b> Both share
     * {@code looseningDeadline} with the first search, so if the first stage runs to the full limit there is
     * no time left for retries. That is exactly what happened in the in-game End (12 s even for the deep
     * search): even though "raising the weight solves it" was known, it was never tried once.
     *
     * <p><b>No loss on solvable terrain.</b> A* returns as soon as it pops the goal, so a reachable path is
     * found in the same number of steps regardless of the limit (the same reasoning as
     * {@code XaeroNavConfig#maxExpandedNodes} being "a ceiling for when it can't reach, not a cost paid").
     * What shrinks is only <b>the time an unreachable search takes to give up</b>, which becomes the retries'
     * time allowance.
     */
    private static final int FIRST_PASS_PERCENT = 40;

    /**
     * Multiplier applied to the caller's limit to get a leg's expanded-node limit.
     *
     * <p><b>A chain leg can need more nodes than the caller's limit.</b> Even giving each leg the full amount
     * is sometimes not enough, and a leg cut off by the limit returns no steps at all. Multiplying is safe
     * <b>because the chain is the last resort and solves legs one after another</b>: raising the deep budget
     * that runs in parallel ({@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}) would overlap memory peaks,
     * but these don't overlap. The time limit stays the same, so the increase doesn't all turn into extra time.
     */
    private static final int LEG_NODE_BUDGET_FACTOR = 3;

    /** The first search's share. The rest is kept free for loosening and {@link #retryGreedier}. */
    private static SearchLimits firstPassLimits(SearchLimits limits) {
        return new SearchLimits(
                Math.max(1, limits.maxExpandedNodes() * FIRST_PASS_PERCENT / 100),
                Math.max(1, limits.timeLimitMillis() * FIRST_PASS_PERCENT / 100),
                limits.heuristicWeight());
    }

    /**
     * {@link #refineQuality} redoes the search only when the first search used no more than this fraction of
     * its budget.
     *
     * <p><b>This measures the "slack" in "only reconsider quality when there's slack".</b> The redo expands
     * about 40% more than the first search, so paying again for a search that already burned most of its
     * budget doubles the wait for a few percent of quality. The in-game End <b>island-to-island crossing</b>
     * is exactly that: the {@code RealEndTerrainTest} terrain reaches the goal using 530k of 600k nodes (89%).
     * And there the only way is across the void, so a redo produces the same path anyway.
     *
     * <p>Meanwhile the valley crossing we're targeting uses 30k/600k = 5%, and still fits at 30% under the
     * in-game default budget (100k). Setting it at half separates the two.
     *
     * <p><b>The denominator is the budget {@link #firstPassLimits} handed out</b>: the first search doesn't
     * run with the full budget, so comparing against the full budget would always pass and remove the
     * protection.
     */
    private static final double REFINE_MAX_FIRST_PASS_FRACTION = 0.5;

    /**
     * When a path uses more than this fraction of the inventory, {@link #refineQuality} tries to economize.
     *
     * <p>It's set at half because <b>the problem is "using it all up" rather than running short</b>: even if
     * the crossing succeeds, an empty hand gets stuck at the next valley or pillar. Conversely, applying it
     * to paths that use only 10-20% would mean searching twice every time in the End, where paths with
     * placements are the norm.
     */
    private static final double THRIFT_TRIGGER_FRACTION = 0.5;

    /**
     * How much to multiply the cost of the action of placing one support block by when redoing for thrift
     * ({@code ActionCosts#PLACE_BLOCK_AIM_TICKS}).
     *
     * <p><b>The meaning of this value is "how many extra blocks of walking are worth saving one block"</b>.
     * Doubling adds the placing action ({@code ActionCosts#PLACE_BLOCK_AIM_TICKS} = 16.0) on top =
     * <b>equivalent to 4.5 blocks of sprinting</b>. Tripling is 9 blocks' worth, and beyond that it only adds
     * redos that get rejected by the {@link #THRIFT_MAX_COST_INCREASE} gate.
     *
     * <p>It applies only to the placing action, not to the part for interrupting movement
     * ({@code ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}). What we want to reduce is <b>the number of blocks
     * used</b>, so only the component proportional to the count is marked up; {@link #trueCost} can subtract
     * the markup for comparison precisely because every placement is inflated by the same amount.
     */
    private static final double THRIFT_PLACEMENT_COST_SCALE = 2.0;

    /**
     * How much worsening of total cost at the true prices is tolerated in order to take the thrifty path.
     *
     * <p>Must not be 0: the first path is nearly optimal at the true prices, so a path with fewer placements
     * is by definition more expensive. This redo is <b>buying blocks with a little time</b>, so this sets the
     * maximum purchase price. 10% is about 2.5 seconds on an in-game island crossing (legs of around 500 ticks).
     */
    private static final double THRIFT_MAX_COST_INCREASE = 0.10;

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits) {
        return submit(view, start, goal, limits, true);
    }

    /**
     * Variant that specifies {@code costToGoGuideEnabled} explicitly. The default ({@link #submit} without
     * the argument) is true: the layer-1 cost-to-go ({@link #buildCostToGoGuide}) is used alongside the
     * geometric heuristic. See {@code XaeroNavConfig#costToGoGuideEnabled} for why it can be turned off.
     *
     * <p>This setting isn't read directly from {@code XaeroNavConfig} here because {@link PathfindingExecutor}
     * is unit-tested ({@code PathfindingExecutorCoarseGuidedTest}) and must be callable from environments where
     * NeoForge's config system isn't loaded. Reading it is the caller's ({@code PathfindingState})
     * responsibility.
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled) {
        return submit(view, start, goal, limits, costToGoGuideEnabled, 0);
    }

    /**
     * Variant that specifies {@code goalRadius} explicitly. Goals that are only "a direction to head in",
     * like intermediate targets of a long-distance route, get a radius to avoid detours just to land on the
     * exact coordinate (see {@link AStarPathfinder#search(BlockPos, BlockPos, BooleanSupplier, int)}).
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled, int goalRadius) {
        return submit(view, start, goal, limits, costToGoGuideEnabled, goalRadius, Carryover.NONE);
    }

    /**
     * Variant that carries accumulated state over from the preceding leg ({@link Carryover}). Used by the
     * search that extends from the end of the displayed path and by the search that rejoins the path: both
     * solve <b>the continuation of a single path</b>, so the bridge run length and the inventory budget must
     * start with whatever this path has already committed to subtracted.
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled, int goalRadius, Carryover carried) {
        return submit(view, start, goal, limits, costToGoGuideEnabled, goalRadius, carried, null);
    }

    /** Variant that takes a prebuilt guide (experimental). */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled, int goalRadius, Carryover carried,
                                                 CostToGo prepared) {
        return submit(cancelled -> {
            // Searching from a coordinate you can't stand on yields no path at all. This snapping happens
            // where blocks can be read, so do it here instead of going back to the main thread
            BlockPos resolvedStart = StanceFinder.resolveStart(view, start);
            BlockPos resolvedGoal = StanceFinder.resolveGoal(view, goal);
            boolean goalInsideBounds = goalInsideBounds(view, resolvedGoal, goalRadius);
            // If the goal is outside the box, don't build the layer-1 guide. Even if built,
            // {@code CoarseRouter#costToGo} wouldn't have the goal's cell and would only produce
            // <b>a table that returns 0 everywhere</b>, while {@link LiveCoarseSampler} scans the whole
            // box (the full height in dimensions with a ceiling).
            //
            // The guide's origin is <b>the goal before snapping</b>. Shifting Y, even within the same
            // floor, changes layer 1's offset correction, and NetherLavaSeaTest regresses to "no path".
            // The reachability check and A* use the snapped goal below
            CostToGo costToGo = prepared != null ? prepared
                    : costToGoGuideEnabled && goalInsideBounds
                            ? buildCostToGoGuide(view, resolvedStart, goal, cancelled) : null;
            return search(view, limits, MonotonicTime.millis() + limits.timeLimitMillis(), cancelled,
                    costToGo, (pathfinder, c) ->
                    pathfinder.search(resolvedStart, resolvedGoal, c, carried, goalRadius),
                    goalInsideBounds);
        });
    }

    /**
     * Tries the normal budget and the deep budget <b>in parallel</b>. If the normal budget reaches the goal,
     * that result is used and the deep one is stopped; the deep result is awaited only when the normal
     * budget ends by running out of budget or time.
     *
     * <p><b>Running them serially (confirm the normal budget failed, then the deep budget next tick) adds
     * the two durations together.</b> Measurements (island-crossing terrain from a user report, same
     * conditions as {@code PlayerAreaEndReproTest}):
     *
     * <pre>
     * Normal budget (100k/2 s) only -> NODE_BUDGET, 1871ms
     * Deep budget (600k/15 s) only  -> reached, 1876ms
     * Serial total ~ 3747ms (even longer in-game once waiting for tick boundaries is included)
     * </pre>
     *
     * <p>If the deep search starts at the same time as the normal one, by the time the normal budget
     * confirms failure the deep one has <b>almost finished too</b>: as measured above, the two durations
     * barely differ. When the normal budget reaches the goal quickly (most cases), the deep one is stopped
     * immediately, so the added load is limited to "one more core for as long as the normal budget runs".
     *
     * <p>The poorest cost-effectiveness is short-range navigation where the normal budget finishes in an
     * instant anyway, but there the deep one is also stopped just as quickly, so the real harm is small.
     * Conversely, when the normal budget is known up front to be doomed to time out
     * ({@code plainSearchHopeless}), the deep budget alone is enough, so the caller ({@code PathfindingState})
     * doesn't use this and instead passes the deep {@link SearchLimits} to {@link #submit} as before.
     *
     * <p><b>Taking two views is not decoration.</b> A {@link CellSource} is promised to be owned by a single
     * worker thread (the {@code ChunkView} thread contract); passing the same instance to two searches makes
     * the cell cache get rewritten concurrently and corrupts it. That actually happened, producing
     * {@code ArrayIndexOutOfBoundsException} in-game. The caller is made to pass two because adding a copy
     * method to {@code CellSource} would let an implementation return {@code this} and silently revert to
     * the old behavior; forcing it through the arguments makes a mix-up impossible.
     *
     * @param normalView view owned by the normal-budget search
     * @param deepView   view owned by the deep-budget search. Must have the same terrain and search bounds,
     *                   and be a different instance from {@code normalView}
     */
    public CompletableFuture<PathResult> submitWithDeepFallback(CellSource normalView, CellSource deepView,
                                                                  BlockPos start, BlockPos goal,
                                                                  SearchLimits normalLimits, SearchLimits deepLimits,
                                                                  boolean costToGoGuideEnabled, int goalRadius) {
        return submitWithDeepFallback(normalView, deepView, start, goal, normalLimits, deepLimits,
                costToGoGuideEnabled, goalRadius, null);
    }

    /**
     * Variant that takes a prebuilt guide. The 3D coarse layer for dimensions with a ceiling
     * ({@code VoxelCostToGo}) comes in here.
     *
     * <p><b>{@code prepared} is used even when the goal is outside the box.</b> Unlike the layer-1 guide,
     * covering beyond the box is its whole reason to exist, and for long distances in the Nether the goal is
     * always outside the box.
     */
    public CompletableFuture<PathResult> submitWithDeepFallback(CellSource normalView, CellSource deepView,
                                                                  BlockPos start, BlockPos goal,
                                                                  SearchLimits normalLimits, SearchLimits deepLimits,
                                                                  boolean costToGoGuideEnabled, int goalRadius,
                                                                  CostToGo prepared) {
        return submit(cancelled -> {
            BlockPos resolvedStart = StanceFinder.resolveStart(normalView, start);
            BlockPos resolvedGoal = StanceFinder.resolveGoal(normalView, goal);
            boolean inside = goalInsideBounds(normalView, resolvedGoal, goalRadius);
            CostToGo costToGo = prepared != null ? prepared
                    : costToGoGuideEnabled && inside
                            ? buildCostToGoGuide(normalView, resolvedStart, goal, cancelled) : null;
            if (!inside) {
                // Both views share the same box. Even the deep budget can't finish, and its partial path
                // would be discarded by the selection below. As in submit, use the normal budget once at
                // full size and guide as far as possible. The check also runs on this worker to keep the
                // views' thread ownership.
                return search(normalView, normalLimits,
                        MonotonicTime.millis() + normalLimits.timeLimitMillis(), cancelled, costToGo,
                        (pathfinder, c) -> pathfinder.search(resolvedStart, resolvedGoal, c,
                                Carryover.NONE, goalRadius), false);
            }

            // If the normal budget arrives first, stop the still-running deep one here. deepExecutor itself
            // must be freed, or the next call would queue behind this job and wait for nothing
            AtomicBoolean normalWon = new AtomicBoolean(false);
            BooleanSupplier deepCancelled = () -> cancelled.getAsBoolean() || normalWon.get();
            CompletableFuture<PathResult> deepFuture = CompletableFuture.supplyAsync(() ->
                    search(deepView, deepLimits, deepCancelled, costToGo, (pathfinder, c) ->
                            pathfinder.search(resolvedStart, resolvedGoal, c, Carryover.NONE, goalRadius)),
                    deepExecutor);

            PathResult normal = search(normalView, normalLimits, cancelled, costToGo, (pathfinder, c) ->
                    pathfinder.search(resolvedStart, resolvedGoal, c, Carryover.NONE, goalRadius));

            if (normal.complete() || cancelled.getAsBoolean()) {
                normalWon.set(true);
                deepFuture.cancel(true);
                return normal;
            }
            // The normal budget ended by running out of budget or time. The deep one started at the same
            // time, so it should already be done or nearly so
            try {
                PathResult deep = deepFuture.get();
                return deep.complete() ? deep : normal;
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return normal;
            }
        });
    }

    /**
     * Builds the cost-to-go guide to this goal from a coarse map assembled with {@link LiveCoarseSampler}.
     * Limited to the {@code view.bounds()} box, one reverse Dijkstra of {@link CoarseRouter} finishes in a few
     * ms (65x65 cells for render distance 32, up to 4 floors). It looks only at raw data of loaded chunks,
     * not at Xaero's map, so it completes on the worker thread (without moving the main-thread boundary).
     *
     * <p>Boat ownership is ignored (fixed to {@code false}). The guide is only taken as a max with the
     * geometric heuristic in {@code AStarPathfinder}, so some coarseness does no real harm: the only loss is
     * "a looser tightening when you do have a boat", and it never becomes inadmissible.
     *
     * <p><b>If the goal is outside the box, the guide becomes entirely useless.</b>
     * {@code CoarseRouter#costToGo} sets every cost to infinity when the goal's cell isn't in the map, and
     * {@code estimate} returns 0 everywhere. The box is cut around the start at the render distance
     * ({@code SearchBounds#around}), so the design that aims straight at a distant destination (dimensions
     * with a ceiling; {@code PathfindingState#selectDetailTarget}) always ends up in this shape
     * -- <b>this is intentional</b> (measured in {@code NetherDetourBreakdownTest}).
     */
    private CostToGo buildCostToGoGuide(CellSource view, BlockPos start, BlockPos goal,
                                         BooleanSupplier cancelled) {
        CoarseRouter.BridgePolicy bridgePolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.BridgePolicy.BRIDGE : CoarseRouter.BridgePolicy.ALLOW;
        SearchBounds bounds = view.bounds();
        GuideKey key = new GuideKey(goal, bounds, start.getY(), bridgePolicy);
        if (key.equals(guideKey)) {
            return guide;
        }
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, bounds, start.getY(), cancelled);
        CostToGo built = CoarseRouter.costToGo(coarseMap, goal, false, bridgePolicy);
        if (!cancelled.getAsBoolean()) {
            guideKey = key;
            guide = built;
        }
        return built;
    }

    /**
     * Whether the snapped goal region overlaps the search bounds and, for an exact goal, whether its cell is
     * loaded. A goal with a radius can be reached around its center even if the center cell is missing. If the
     * ranges don't overlap, this search will never finish no matter how much budget it gets: outside the
     * bounds are cells treated as unloaded, with no move into them.
     *
     * <p>{@code SearchBounds#around} cuts around the start at the render distance, so the design that aims
     * straight at a distant destination (dimensions with a ceiling; {@code PathfindingState#selectDetailTarget})
     * always lands on this side.
     */
    private static boolean goalInsideBounds(CellSource view, BlockPos goal, int goalRadius) {
        SearchBounds bounds = view.bounds();
        int verticalRadius = AStarPathfinder.goalVerticalRadius(goalRadius);
        // If the exact goal's cell isn't in this snapshot, there's no move into it.
        // A goal with a radius can be accepted from a loaded neighbor, so keep those
        if (goalRadius <= 0 && !CellData.present(view.cell(goal.getX(), goal.getY(), goal.getZ()))) {
            return false;
        }
        return goal.getX() + goalRadius >= bounds.minX() && goal.getX() - goalRadius <= bounds.maxX()
                && goal.getZ() + goalRadius >= bounds.minZ() && goal.getZ() - goalRadius <= bounds.maxZ()
                && goal.getY() + verticalRadius >= bounds.minY()
                && goal.getY() - verticalRadius <= bounds.maxY();
    }

    /**
     * Thin variant of {@link #submit} that skips snapping via {@link StanceFinder} and hazard annotation via
     * {@link PathSafetyChecker}. Used when the caller has already resolved start and end to standable
     * coordinates and uses the result as intermediate data (e.g. waypoint selection) rather than as a path
     * to actually walk.
     */
    public CompletableFuture<PathResult> submitRaw(CellSource view, BlockPos start, BlockPos goal,
                                                    SearchLimits limits) {
        return submit(cancelled -> new AStarPathfinder(view, limits).search(start, goal, cancelled));
    }

    /**
     * Finds a path from underground to the surface by searching for "under open sky at y &gt;= surfaceY"
     * rather than for the spot directly below the destination (surface-first navigation; see
     * {@link net.prason.xaeronav.client.PathfindingState}).
     *
     * <p>Searches first with {@code onFoot} (a view that forbids digging), and searches again with
     * {@code digging} only when that doesn't reach the surface. Doing it in one pass with digging allowed
     * makes branching explode by orders of magnitude, stone included, and the expansion limit runs out a few
     * dozen blocks away. What comes back is then an unreached path that just "digs up a little from where you
     * are", and following it doesn't get you to the surface. With digging off, only existing cavities are
     * passable, so the same expansion count follows caves and tunnels much farther.
     */
    public CompletableFuture<PathResult> submitToSurface(CellSource onFoot, CellSource digging, BlockPos start,
                                                          int surfaceY, SearchLimits limits) {
        return submit(cancelled -> {
            PathResult walked = search(onFoot, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(onFoot, start), surfaceY, c));
            if (walked.complete()) {
                return walked;
            }
            return search(digging, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(digging, start), surfaceY, c));
        });
    }

    /**
     * Retry for when the detailed search hit the expanded-node limit without reaching the goal (countermeasure
     * for layer-3 local obstacles). Assembles a coarse map from raw data of loaded chunks
     * ({@link LiveCoarseSampler}), then follows the waypoints {@link CoarseRouter} draws on it with detailed A*,
     * one leg at a time. A sequence of short legs gets around local cliffs and lakes more easily than one long
     * search with the same budget.
     *
     * <p>{@link LiveCoarseSampler} only reads the {@code CellSource} (unrelated to Xaero's map), so everything
     * from assembling the coarse map to the per-leg searches completes on this worker thread (unlike layer 2's
     * corridor refinement, there's no need to go back to the main thread).
     */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits) {
        return submitCoarseGuided(view, bounds, start, goal, limits, true);
    }

    /** Variant that specifies {@code costToGoGuideEnabled} explicitly. For the same reason as {@link #submit(CellSource, BlockPos, BlockPos,
     * SearchLimits, boolean)}, reading the setting is left to the caller. */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits,
                                                             boolean costToGoGuideEnabled) {
        return submitCoarseGuided(view, bounds, start, goal, limits, costToGoGuideEnabled, 0);
    }

    /** Variant that specifies {@code goalRadius} explicitly. Only affects the final goal (leg waypoints are coarse points, so always regions). */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits,
                                                             boolean costToGoGuideEnabled, int goalRadius) {
        return submit(cancelled -> solveCoarseGuided(view, bounds, start, goal, limits, cancelled,
                costToGoGuideEnabled, goalRadius));
    }

    private static PathResult solveCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                                 SearchLimits limits, BooleanSupplier cancelled,
                                                 boolean costToGoGuideEnabled, int goalRadius) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, bounds, start.getY(), cancelled);
        // If bridges can be built, let lava through on the coarse side too. Making this ALLOW across the board
        // means that at the edge of a lava sea the start's own cell is LAVA = impassable, no leg split can be
        // made, and the search tries to cross the lava sea in one go and burns the budget (hit in-game: 200k
        // nodes with 0 steps)
        CoarseRouter.BridgePolicy bridgePolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.BridgePolicy.BRIDGE : CoarseRouter.BridgePolicy.ALLOW;
        // Water stays crossable at the swimming price if either swimming or a boat can do it (the boat price isn't
        // used here, as before); only when neither can does the leg split steer around it
        CoarseRouter.Route route = CoarseRouter.findRoute(coarseMap, start, goal, false,
                view.swimmingEnabled() || view.boatAvailable(), bridgePolicy);
        // The worst failure is "route found" while the coarse map is empty (all-NO_DATA cells are passable,
        // so a straight line ignoring lava can be drawn). Without logging the known cell count, you can't
        // tell whether "the leg split is bad" or "the terrain isn't visible at all".
        // Also log the per-kind breakdown (kindBreakdown). The known cell count alone can't tell whether
        // "the void is seen as void or there's simply no data": NO_DATA is passable at 1.6x land even at its
        // cheapest, so if the void falls into that, it explains why a line straight through the void looks cheap.
        // The breakdown is also the very input of CoarseRouter's NO_DATA calibration, so it's needed to check
        // that value too. This breakdown did in fact confirm "the void is detected correctly", ruling out one
        // candidate cause
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("XaeroNav: coarse waypoint chain map (known cells={}/{}, {}, intermediate targets={}, lava={})",
                    coarseMap.knownCells(), coarseMap.totalCells(), coarseMap.kindBreakdown(),
                    route.waypoints().size(), bridgePolicy);
        }
        if (route.waypoints().isEmpty()) {
            // No way found even on the coarse side (isolated terrain, etc.). Stick to the same result as a direct search
            CostToGo directCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(coarseMap, goal, false, bridgePolicy) : null;
            return search(view, limits, cancelled, directCostToGo, (pathfinder, c) ->
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c, Carryover.NONE, goalRadius));
        }

        List<BlockPos> rawLegGoals = new ArrayList<>(route.waypoints());
        rawLegGoals.add(goal);
        // Pass the full expansion limit without dividing by the number of legs. As SearchLimits says, this is
        // "a ceiling at which to stop when the goal can't be reached", not a cost paid, and shorter legs
        // actually expand fewer nodes. Dividing only makes legs that would reach the goal stop short (in-game,
        // 30000 / 3 legs = 10000 couldn't even complete the first leg in mountainous terrain).
        //
        // Instead, bound the whole chain by the same time as one single search. A fixed per-leg limit would
        // stretch up to the number of legs, making the chain, which is supposed to be the fallback, the only
        // unbounded thing. Stretching it is pointless: on the lava sea (NetherLavaSeaTest) the chain used twice
        // the nodes of a single search (1.04M vs 570k) and was slower, so adding time only delayed the dead-end
        // verdict. Within that, each leg splits the remaining time (legShare below)
        long chainDeadline = MonotonicTime.millis() + limits.timeLimitMillis();

        List<PathStep> steps = new ArrayList<>();
        // Among legs that didn't reach their goal, the longest partial path drawn. A last fallback to keep
        // the chain from returning completely empty: on the in-game Nether lava sea, all 3 legs failed with
        // "470k expansions, 0 steps" = not a single line shown after a 24-second wait.
        // Guidance grows by extending from the end, so if a line up to some point is shown, the next search
        // starts that much closer. Returning empty can't even create that origin, and the search retries from
        // the same place over and over
        List<PathStep> bestPartial = List.of();
        boolean complete = false;
        int totalExpanded = 0;
        int totalDistinct = 0;
        // The termination reason for the whole chain is that of the last leg solved. It remains EXHAUSTED only
        // when every leg ended with "no way within bounds", and is passed to the caller as a genuine dead end
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;
        boolean limitsHeld = false;
        BlockPos legStart = StanceFinder.resolveStart(view, start);
        for (int i = 0; i < rawLegGoals.size(); i++) {
            long remainingMillis = chainDeadline - MonotonicTime.millis();
            if (remainingMillis <= 0) {
                termination = PathResult.Termination.TIME_LIMIT;
                break;
            }
            // Each leg's time allowance <b>splits the remainder</b>. If one leg uses up the chain's budget,
            // the following legs are never tried: in-game (End, 2026-08-28), the typical failure was leg 1
            // using 1.4-1.6 s of 2 s and failing, with legs 2 and 3 dying of timeouts.
            // Meanwhile the run that actually succeeded looked like "leg 2 failed at 100,000 nodes -> leg 3
            // reached the goal at 27,340 nodes", i.e. <b>being guaranteed to try the following legs is itself
            // what determines the success rate</b> (the fallback below, aiming at the next waypoint from the
            // same point after missing one, is the core).
            //
            // No fixed limit (some ms per leg). If the caller's budget changes, the time available per leg
            // should change too; with a fixed value, raising the budget would leave it unused by the legs.
            // Whatever isn't used naturally rolls over to the next leg (because remainingMillis doesn't drop)
            int legsLeft = rawLegGoals.size() - i;
            long legShare = Math.max(1, remainingMillis / legsLeft);
            // Keep half the allowance for cap loosening. If the first search uses all of it, loosening can't
            // run, and the "search with a loosened bridge cap" needed to cross the void is never reached
            long legDeadline = MonotonicTime.millis() + legShare;
            SearchLimits thisLegLimits = new SearchLimits(
                    limits.maxExpandedNodes() * LEG_NODE_BUDGET_FACTOR,
                    Math.max(1, legShare / 2), limits.heuristicWeight());
            BlockPos legGoal = StanceFinder.resolveGoal(view, rawLegGoals.get(i));
            BlockPos currentLegStart = legStart;
            // Intermediate waypoints are just representative points made from chunk averages. Snapping to the
            // exact coordinate is not only pointless but creates detours. Only the last leg follows the caller
            boolean lastLegGoal = i == rawLegGoals.size() - 1;
            int legRadius = lastLegGoal ? goalRadius : COARSE_LEG_GOAL_RADIUS_BLOCKS;
            // Guide toward each leg's goal. Planning the leg split (route) needs the wide box's coarseMap, but
            // computing the guide only needs the area around that leg's start and end: reusing the wide box
            // inflates Dijkstra's state count in proportion to the box area and is paid once per leg
            // (see COARSE_LEG_GUIDE_MARGIN_BLOCKS). Build a separate narrow map just for the leg
            CostToGo legCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(legCoarseMap(view, currentLegStart, legGoal, bounds, cancelled),
                            legGoal, false, bridgePolicy)
                    : null;
            // Carry over what came before (bridge run length, placement count) so accumulation doesn't reset to 0 at leg boundaries
            Carryover carried = Carryover.after(steps);
            long legBegan = MonotonicTime.millis();
            PathResult legResult = search(view, thisLegLimits, legDeadline, cancelled, legCostToGo,
                    (pathfinder, c) -> pathfinder.search(currentLegStart, legGoal, c, carried, legRadius));
            // Log per leg. The total across the whole chain alone can't tell "which leg got stuck" or "whether
            // it can't move from the start, or only the last leg falls short": from the totals, in-game
            // "300k expansions, 2 steps" couldn't be classified either way.
            // The chain runs only after the normal search fails, but it emits one line per leg each time, so
            // keep it at debug
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("XaeroNav: leg {}/{} {} → {} (reached={}, {}, expanded nodes={}, steps={}, {}ms)",
                        i + 1, rawLegGoals.size(), currentLegStart.toShortString(), legGoal.toShortString(),
                        legResult.complete(), legResult.termination(), legResult.expandedNodes(),
                        legResult.steps().size(), MonotonicTime.millis() - legBegan);
            }
            totalExpanded += legResult.expandedNodes();
            totalDistinct += legResult.distinctNodes();
            boolean lastLeg = i == rawLegGoals.size() - 1;
            if (!legResult.complete()) {
                termination = legResult.termination();
                limitsHeld = legResult.limitsHeld();
            }

            if (legResult.complete()) {
                steps.addAll(legResult.steps());
                if (!legResult.steps().isEmpty()) {
                    legStart = legResult.steps().get(legResult.steps().size() - 1).pos();
                }
                if (lastLeg) {
                    complete = true;
                }
            } else if (lastLeg) {
                // The last leg is the real destination. Even if it falls short, use whatever was found (the provisional-path approach)
                steps.addAll(legResult.steps());
            } else if (legResult.steps().size() > bestPartial.size()) {
                bestPartial = legResult.steps();
            }
            // When an intermediate waypoint isn't reached, don't give up there; aim at the next waypoint from the
            // same point. The coarse map can't express "impassable" other than lava, and a vertical wall filling
            // a chunk looks like a flat plateau with zero relief, so a waypoint can land on an unreachable spot
            // like the top of a wall. Discarding the whole chain because one isn't reached would be worse than a
            // direct search on such terrain; the last leg is always the real destination, so even if every
            // waypoint fails the result settles to the same as a direct search.
            // The partial path isn't appended on the spot because the next leg is redrawn from the same point,
            // so the path would jump there. What was found is kept in bestPartial and used only if every leg misses
        }
        if (steps.isEmpty()) {
            // No leg reached its goal, and the last leg couldn't draw a single step. Returning empty here would make the guidance disappear
            steps.addAll(bestPartial);
        }
        return new PathResult(steps, complete ? PathResult.Termination.REACHED_GOAL : termination,
                totalExpanded, totalDistinct, !complete && limitsHeld);
    }

    /**
     * Builds a narrow coarse map just for a leg. See {@link #COARSE_LEG_GUIDE_MARGIN_BLOCKS}.
     *
     * <p>Chunk reads reuse {@code view} (the wide ChunkView shared across the whole leg split), so no extra
     * loading happens: only the {@code bounds} passed to {@link LiveCoarseSampler#sample} are narrowed, which
     * cuts the number of columns scanned.
     */
    private static CoarseMap legCoarseMap(CellSource view, BlockPos legStart, BlockPos legGoal,
                                           SearchBounds outer, BooleanSupplier cancelled) {
        int minX = Math.max(outer.minX(), Math.min(legStart.getX(), legGoal.getX()) - COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int maxX = Math.min(outer.maxX(), Math.max(legStart.getX(), legGoal.getX()) + COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int minZ = Math.max(outer.minZ(), Math.min(legStart.getZ(), legGoal.getZ()) - COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int maxZ = Math.min(outer.maxZ(), Math.max(legStart.getZ(), legGoal.getZ()) + COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        SearchBounds legBounds = new SearchBounds(minX, outer.minY(), minZ, maxX, outer.maxY(), maxZ);
        return LiveCoarseSampler.sample(view, legBounds, legStart.getY(), cancelled);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled, SearchCall run) {
        return search(view, limits, cancelled, null, run);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled,
                                     CostToGo costToGo, SearchCall run) {
        return search(view, limits, MonotonicTime.millis() + limits.timeLimitMillis(), cancelled, costToGo, run);
    }

    /**
     * Variant that specifies the deadline (absolute time) the cap-loosening stages may use.
     *
     * <p>Loosening must <b>always be left a turn</b>. Passing the same deadline as the first search means
     * loosening can't run once the first search has used it up: in-game (End island crossing) every search
     * that runs is a {@link #solveCoarseGuided} leg search, so loosening effectively never got to run (only
     * the total search time grew, with no change in the result). The caller passes only half the allowance
     * to the first search and the whole allowance as this deadline, so that "if the first search's share has
     * time left over, loosening runs that much longer".
     */
    private static PathResult search(CellSource view, SearchLimits limits, long looseningDeadline,
                                     BooleanSupplier cancelled, CostToGo costToGo, SearchCall run) {
        return search(view, limits, looseningDeadline, cancelled, costToGo, run, true);
    }

    /**
     * For <b>a search whose goal is known to be outside the search bounds</b>, none of the ladder below
     * (raising greediness, loosening caps, reconsidering quality) helps: they're all mechanisms for
     * "reaching the goal" and only matter for paths that reach it, whereas a goal outside the bounds is
     * <b>unreachable in principle</b>.
     *
     * <p>Running the ladder anyway leaves the first search with only {@link #FIRST_PASS_PERCENT} and spends
     * the rest on two misses. What comes back is a short partial path drawn with 40% of the budget, and
     * <b>the number of extensions from the end grows accordingly</b>: the seams are exactly where detours
     * come from, so short partial paths hurt quality too. Here it's solved once with the full budget.
     *
     * @param goalInsideBounds whether the goal is inside the search bounds ({@link #goalInsideBounds})
     */
    private static PathResult search(CellSource view, SearchLimits limits, long looseningDeadline,
                                     BooleanSupplier cancelled, CostToGo costToGo, SearchCall run,
                                     boolean goalInsideBounds) {
        if (!goalInsideBounds) {
            return PathSafetyChecker.annotate(view,
                    run.search(new AStarPathfinder(view, limits, costToGo), cancelled));
        }
        AStarPathfinder pathfinder = new AStarPathfinder(view, firstPassLimits(limits), costToGo);
        PathResult result = run.search(pathfinder, cancelled);
        boolean capBlocked = pathfinder.bridgeRunCapBlocked() || pathfinder.submergedRunCapBlocked()
                || pathfinder.fallDamageCapBlocked() || pathfinder.riskyJumpBlocked()
                || pathfinder.placedBudgetBlocked() || pathfinder.placementBlockedByEmptyInventory();
        // Raise greediness before loosening caps. <b>Raising the weight is far cheaper</b>: on the in-game End
        // island crossing, the loosening stages burn the full budget (600k nodes, 7 s) every time, while
        // weight 2.5 solves it in 198k. Putting loosening first uses up the allowance there, and the retry
        // never runs (measured with an in-game-equivalent 4.8 s window: 240k in stage 1 + loosening used it
        // all, goal not reached). If the caps really are the cause, raising the weight won't solve it, so only
        // then proceed to the loosening below
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED) {
            PathResult greedier = retryGreedier(view, limits, looseningDeadline, cancelled, costToGo, run, result);
            if (greedier.complete()) {
                return PathSafetyChecker.annotate(view, greedier);
            }
        }
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED && capBlocked
                && view.strictLimits()) {
            // The config says the caps are "a line to hold", not "a wish to loosen if needed". Record only that
            // loosening might reach the goal, for display as the reason the goal wasn't reached
            return PathSafetyChecker.annotate(view, result.withLimitsHeld());
        }
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED && capBlocked) {
            // Moves were discarded because of the caps. Try loosening the caps step by step, on the priority
            // that a long bridge or a dive needing breath is better than a dead end. Caps and fall damage are
            // loosened together: loosening only one just pays for the same result again if the other is
            // also stuck.
            //
            // <b>Loosen even on budget exhaustion (NODE_BUDGET/TIME_LIMIT).</b> It used to be EXHAUSTED-only,
            // but then loosening only triggers on terrain narrow enough that "every reachable cell within the
            // search bounds can be fully scanned". What we hit in-game (End cliff edge) was the flip side: with
            // a large island, the reachable cells alone used up the budget, and the search tried to cross a
            // 45-block void with the cap still at 30, <b>failing repeatedly without ever loosening</b>
            // (synthetic terrain: up to island radius 60 EXHAUSTED = loosening; radius 80 NODE_BUDGET = no
            // loosening). From a point where a bridge has been built partway across the same island, the
            // reachable cells shrink and it reaches EXHAUSTED, so it shows up as "no path only from the cliff edge".
            //
            // This isn't paying for the same search again because of a resource shortage: {@code capBlocked} is
            // the search's own report that "a cap actually discarded moves", and the loosened search is a
            // different search. Total time is bounded by looseningDeadline, so the loosening stages only run for
            // the remaining time.
            // Risky jumps alone are handled separately and opened only after every cap-loosening stage has been
            // tried ({@link #capStages}). It isn't necessarily the first search that discarded the jump: a place
            // reached only after loosening a cap may have a gap that can only be jumped, so also check the
            // first group's report
            boolean budgetBlocked = pathfinder.placedBudgetBlocked();
            boolean emptyInventoryBlocked = pathfinder.placementBlockedByEmptyInventory();
            Loosening capsOnly = runStages(view, limits, looseningDeadline, cancelled, costToGo, run,
                    capStages(view, !view.avoidRiskyJumps(), budgetBlocked, emptyInventoryBlocked));
            if (capsOnly.result() != null) {
                result = capsOnly.result();
            } else if (view.avoidRiskyJumps()
                    && (pathfinder.riskyJumpBlocked() || capsOnly.riskyJumpBlocked())) {
                Loosening withJumps = runStages(view, limits, looseningDeadline, cancelled, costToGo, run,
                        capStages(view, true, budgetBlocked, emptyInventoryBlocked));
                if (withJumps.result() != null) {
                    result = withJumps.result();
                }
            }
            // A path that got as far as loosening is a case of "there's no way at all", so quality isn't reconsidered (below)
            return PathSafetyChecker.annotate(view, result);
        }
        return refineQuality(view, limits, looseningDeadline, cancelled, costToGo, run,
                PathSafetyChecker.annotate(view, result), pathfinder.carriedPlacedBlocks());
    }

    /**
     * <b>If the budget burns out without reaching the goal, raise the search's greediness and try again.</b>
     *
     * <p>On terrain crossing a long void from a wide platform (End island crossing),
     * <b>the heuristic is nearly constant across the island</b>: wherever you are, the goal is beyond the void,
     * and the remaining estimate is "distance to the edge + cost of the bridge", so it barely differs. A
     * weight-1.5 search becomes close to breadth-first there and <b>exhausts the budget scanning the island
     * before reaching for a bridge</b>. One bridge block is worth 10 blocks of walking, so reaching a path
     * across a 100-block void requires first expanding "1000 blocks' worth of land on foot".
     *
     * <p>Measurements (the spot a user reported, from the tip of island at column 24339 to the island 99 blocks
     * northeast):
     *
     * <pre>
     * weight 1.5 -> not reached at 600k nodes    weight 2.5 -> reached at 198k
     * weight 2.0 -> not reached at 600k nodes    weight 3.0 -> reached at 107k
     * </pre>
     *
     * <p><b>Without the cost-to-go guide no weight solves it</b> (all unreached at 600k). It's the guide that
     * leads to the island's edge; the weight only raises how much it is trusted.
     *
     * <p>Quality definitely drops (it does the exact opposite of {@code refineQuality} lowering the weight),
     * but this is reached only when <b>not a single path has come out</b>: the comparison is between
     * roundabout guidance and no guidance.
     * It's placed after cap loosening has been fully tried for the same reason: first check "whether the way
     * disappeared because of the caps" before touching greediness.
     */
    private static PathResult retryGreedier(CellSource view, SearchLimits limits, long deadline,
                                             BooleanSupplier cancelled, CostToGo costToGo, SearchCall run,
                                             PathResult result) {
        if (result.complete() || result.termination() == PathResult.Termination.CANCELLED) {
            return result;
        }
        for (double weight : GREEDY_RETRY_WEIGHTS) {
            if (weight <= limits.heuristicWeight()) {
                continue;
            }
            long remainingMillis = deadline - MonotonicTime.millis();
            if (remainingMillis <= 0) {
                break;
            }
            SearchLimits greedy = new SearchLimits(limits.maxExpandedNodes(), remainingMillis, weight);
            PathResult attempt = run.search(new AStarPathfinder(view, greedy, costToGo), cancelled);
            if (attempt.complete()) {
                return attempt;
            }
        }
        return result;
    }

    /**
     * <b>Only when there's slack, reconsider the path's quality and redo it just once.</b> There are two
     * triggers, and if either fires it redoes <b>just once</b> (if both fire, one redo with both adjustments).
     *
     * <h4>Trigger 1: crossing the void (lower the weight)</h4>
     *
     * <p>Weighted A* pops by {@code f = g + w·h}, so it <b>systematically dislikes paths that first move away
     * from the destination</b>. That's exactly what we hit in the in-game End (2481,-488): to go 39 blocks east
     * across a valley, it produced a path that <b>built a 15-block bridge (7 of them over the void) straight
     * across</b>. And the cost model already said going around from the south was cheaper: weight 1.5's path was
     * 596.3 ticks, while at 1.3 it was 522.2 ticks with zero bridges. The search was merely greedy; the pricing
     * wasn't wrong.
     *
     * <p><b>The weight is lowered only here, not globally</b>. Averages measured on saved in-game terrain don't
     * pay off: path cost improves only {@code -1.9%} (1.35) to {@code -5.0%} (1.15), while expanded nodes grow
     * {@code +17%} to {@code +46%} (in the Overworld, {@code +5%} for {@code -0.7%}).
     * <b>The loss is concentrated in some paths rather than on average</b>, so only those are targeted.
     *
     * <p>The trigger is "passing over the void or a lethal drop" because that's <b>a decision where we want to
     * curb greediness</b>. {@code VOID_BRIDGE_PENALTY_TICKS} was originally priced as "the last resort when there
     * is no other way", not something to pay without checking for a way around. Paths without bridges don't
     * fire the trigger, so most searches finish in one pass.
     *
     * <h4>Trigger 2: using up most of the inventory (raise the placement cost)</h4>
     *
     * <p>The budget ({@link Tolerances#placedBlockBudget()}) only draws the line of <b>whether it's feasible</b>.
     * A path placing 40 of the 40 blocks in hand is "feasible", but if going around a little needs only 10, that
     * is better: <b>running short happens after you finish walking that path</b> (the path cache key is only the
     * destination, so it isn't redrawn when the count drops along the way).
     *
     * <p>So only when a path using more than {@link #THRIFT_TRIGGER_FRACTION} comes out, redo it with the
     * placement effort multiplied by {@link #THRIFT_PLACEMENT_COST_SCALE}. <b>The factor is a uniform value
     * fixed at search start</b>: changing the price by the remaining count would make the same edge's price
     * depend on the path that reached it, breaking A*'s assumptions.
     *
     * <p>It's taken only <b>when placements decrease and the total cost at the true prices worsens by no more
     * than {@link #THRIFT_MAX_COST_INCREASE}</b>. Since it was solved with marked-up prices, comparing the total
     * cost as-is would always count as "improved", so the markup is subtracted before comparing
     * ({@link #trueCost}).
     *
     * <h4>Common</h4>
     *
     * <p>Not applied to results that went through the loosening ladder: those are places where "no path comes
     * out at all unless the caps are removed", so the premise for reconsidering quality (another way exists)
     * doesn't hold.
     */
    private static PathResult refineQuality(CellSource view, SearchLimits limits,
                                             long deadline, BooleanSupplier cancelled,
                                             CostToGo costToGo, SearchCall run, PathResult result,
                                             int carriedPlacements) {
        if (!result.complete()) {
            return result;
        }
        // The denominator is <b>the budget the first search was actually given</b> ({@link #firstPassLimits}).
        // Comparing with the full budget, this condition always passes since stage 1's limit is smaller, and the protection is lost entirely
        if (result.expandedNodes()
                > firstPassLimits(limits).maxExpandedNodes() * REFINE_MAX_FIRST_PASS_FRACTION) {
            return result;
        }
        // The triggers fire independently. If both fire, run a single search with both adjustments applied
        boolean lowerWeight = limits.heuristicWeight() > REFINE_HEURISTIC_WEIGHT
                && result.steps().stream().anyMatch(step -> step.risk() == PathRisk.VOID_BELOW);
        boolean thrift = thrifty(view, result, carriedPlacements);
        if (!lowerWeight && !thrift) {
            return result;
        }
        long remainingMillis = deadline - MonotonicTime.millis();
        if (remainingMillis <= 0) {
            return result;
        }
        double scale = thrift ? THRIFT_PLACEMENT_COST_SCALE : 1.0;
        SearchLimits refined = new SearchLimits(limits.maxExpandedNodes(), remainingMillis,
                lowerWeight ? REFINE_HEURISTIC_WEIGHT : limits.heuristicWeight());
        PathResult attempt = PathSafetyChecker.annotate(view,
                run.search(new AStarPathfinder(view, refined, costToGo,
                        Tolerances.of(view), scale), cancelled));
        if (!attempt.complete()) {
            return result;
        }
        // The original path isn't marked up, so its total cost is the true price as-is
        double before = totalCost(result);
        double after = trueCost(attempt, scale, view.routeProfile().placementCostScale());
        // Take it if "it got cheaper" or "placements dropped at about the same price". Allowing the latter is the
        // heart of thrift, and the max purchase price is THRIFT_MAX_COST_INCREASE (0 = as before for redos not aiming at thrift)
        boolean worthIt = after < before || placements(attempt) < placements(result);
        if (worthIt && after <= before * (1.0 + (thrift ? THRIFT_MAX_COST_INCREASE : 0.0))) {
            return attempt;
        }
        return result;
    }

    /**
     * Whether this path uses up most of the inventory. When there's no budget (creative, setting off, etc.)
     * <b>the concept of scarcity doesn't exist</b>, so it isn't asked.
     *
     * <p>What's counted is <b>the total including what preceding legs have committed to use</b>. Looking only
     * at the placements of the path this search returned, a split into legs <b>always looks like it has slack</b>:
     * the budget itself is shared across all legs (the blocks in hand), so the comparison must also be against
     * the total across all legs to make sense.
     */
    private static boolean thrifty(CellSource view, PathResult result, int carriedPlacements) {
        int budget = view.placedBlockBudget();
        return budget > 0 && carriedPlacements + placements(result) > budget * THRIFT_TRIGGER_FRACTION;
    }

    private static int placements(PathResult result) {
        return Carryover.placements(result.steps(), 0);
    }

    /**
     * Total cost with the marked-up placement price reverted. <b>It's for comparing two paths at the same
     * prices</b>; comparing with the markup still applied just means "the marked-up search wins on the
     * marked-up objective".
     *
     * <p>Only supports placed underwater are slightly under-subtracted (because {@code relax} applies
     * {@code SUBMERGED_TRAVEL_PENALTY} to the whole edge cost). <b>The error leans to the safe side</b>: the
     * redone path's estimate comes out higher than reality, so it never tips toward over-accepting.
     *
     * <p>"True" here means the route profile's prices ({@code RouteProfile#placementCostScale}): the original path
     * was found with that markup already in it, so only the thrift markup on top of it is reverted.
     *
     * @param placementScale the placement cost multiplier used by the search that found the path. 1.0 passes through
     * @param profileScale   the route profile's placement multiplier both searches were run with
     */
    private static double trueCost(PathResult result, double placementScale, double profileScale) {
        return totalCost(result)
                - (placementScale - 1.0) * profileScale * ActionCosts.PLACE_BLOCK_AIM_TICKS * placements(result);
    }

    private static double totalCost(PathResult result) {
        double total = 0;
        for (PathStep step : result.steps()) {
            total += step.cost();
        }
        return total;
    }

    /**
     * Tries one group of loosening stages in order. Returns the result of the stage that reached the goal, if
     * any, otherwise {@code null}, wrapped in a {@link Loosening}.
     *
     * <p>{@code riskyJumpBlocked} is set if anywhere in this group "a move was discarded because of a risky
     * jump". The caller looks at it to decide whether to build the next group (the stages that open jumps).
     */
    private static Loosening runStages(CellSource view, SearchLimits limits, long looseningDeadline,
                                       BooleanSupplier cancelled, CostToGo costToGo, SearchCall run,
                                       List<Tolerances> stages) {
        boolean riskyJumpBlocked = false;
        for (Tolerances tolerances : stages) {
            long remainingMillis = looseningDeadline - MonotonicTime.millis();
            if (remainingMillis <= 0) {
                // The caps were suspected (capBlocked), but the allowance ran out before loosening was fully
                // tried. It's a hint that the problem is the budget, not the caps, so keep it; but on terrain
                // with a tight budget it fires every time, so keep it at debug (debug is off by default in-game)
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("XaeroNav: suspected the caps, but no time was left for loosening");
                }
                break;
            }
            SearchLimits stageLimits = new SearchLimits(limits.maxExpandedNodes(), remainingMillis,
                    limits.heuristicWeight());
            AStarPathfinder stage = new AStarPathfinder(view, stageLimits, costToGo, tolerances);
            PathResult attempt = run.search(stage, cancelled);
            if (attempt.complete()) {
                return new Loosening(attempt, riskyJumpBlocked);
            }
            riskyJumpBlocked |= stage.riskyJumpBlocked();
            // Anything other than EXHAUSTED (budget exhausted, cancelled) just hits the same wall even if loosened further
            if (attempt.termination() != PathResult.Termination.EXHAUSTED) {
                break;
            }
        }
        return new Loosening(null, riskyJumpBlocked);
    }

    /**
     * @param result           the path that reached the goal, or {@code null} if this group didn't reach it
     * @param riskyJumpBlocked whether a move was discarded somewhere in this group because of a risky jump
     */
    private record Loosening(PathResult result, boolean riskyJumpBlocked) {
    }

    /**
     * Cap-loosening stages ({@link #RUN_CAP_LOOSEN_MULTIPLIERS} times the caps, then unlimited). Fall damage is
     * a single step, {@link #loosenedFallDamagePoints}, across all stages.
     *
     * <p><b>The key is opening fall damage from the first stage</b>: the search that just failed ran with the
     * default tolerance itself, so putting the same value in the first stage would throw away a whole search
     * that changes nothing when fall damage was the only cause.
     *
     * <p><b>Only risky jumps (over the void or a lethal drop) are opened after this whole group has been tried</b>
     * (the caller builds this group again with {@code allowRiskyJumps=true}). They used to be opened
     * unconditionally from the first stage, but then <b>even a search that was merely stuck on the bridge cap
     * made jumping over the void legal anywhere along the path</b>: on terrain like the in-game End where
     * bridges are routine it opened every time, so it jumped even across cracks inside an island that could
     * be walked around (user report: "a jump across the void inside an End island"). The user's intent is
     * "within the same island go around the rim, between islands jump", and that distinction is
     * <b>"is there another way", including "can it go around even by building a bridge"</b>.
     *
     * <p>Legs that end up jumping get a warning color via {@code PathRisk.VOID_BELOW}, and
     * {@code ActionCosts#dropRiskPenalty} adds a risk charge for the gap's depth: <b>even after opening, a short
     * detour wins if there is one</b>.
     *
     * <p><b>Only the inventory block budget, unlike the other caps, is removed first.</b> Those just "don't
     * create that move" and leave the shape of the search unchanged, but <b>the budget prunes every placement
     * branch as the frontier advances</b>: {@code PathNode.placedTotal} is an approximation not part of the
     * node's identity, so if the accumulation left on a merged cell is higher than reality, bridges beyond it
     * vanish for no reason. As a result, on terrain not solvable within budget, the search endlessly looks for
     * ways other than bridges and burns the budget.
     *
     * <p>Measurements (in-game End island crossing 1233,1142->1288,1080, needing 43 bridge blocks):
     * <b>with a budget of 42 or more, or 8 or less, it reaches the goal, but with 16-40 it burns 600k nodes and
     * ends at 6 steps</b>. The low side works because bridges are cut off immediately and the search gives up
     * on bridges. Only the middle band breaks.
     *
     * <p>It isn't loosened by a multiplier because the count doesn't grow with terrain needs. If it's removed,
     * remove it at once. A path that gets this far is one where "what's in hand isn't enough, but there's no
     * other way", so the HUD reports the shortage.
     *
     * @param budgetBlocked whether the first search discarded placements because of the budget. If set, the
     *                      stage with the budget removed goes first: if the budget is the cause, loosening the
     *                      other caps however much just hits the same wall
     */
    // The stage order itself is what was fixed, so it's package-private to allow checking it directly without running a search
    static List<Tolerances> capStages(CellSource view, boolean allowRiskyJumps, boolean budgetBlocked,
                                       boolean emptyInventoryBlocked) {
        RunCaps base = RunCaps.of(view);
        int fallPoints = loosenedFallDamagePoints(view);
        int budget = view.placedBlockBudget();
        List<Tolerances> stages = new ArrayList<>(RUN_CAP_LOOSEN_MULTIPLIERS.length + 3);
        if (budgetBlocked && budget > 0) {
            stages.add(new Tolerances(base, fallPoints, allowRiskyJumps, 0, false));
        }
        // If placements were discarded because there isn't a single placeable block in hand, open this before
        // loosening caps too. Loosening other caps however much won't move the wall of "the bridge isn't generated at all"
        if (emptyInventoryBlocked) {
            stages.add(new Tolerances(base, fallPoints, allowRiskyJumps, 0, true));
        }
        for (int multiplier : RUN_CAP_LOOSEN_MULTIPLIERS) {
            stages.add(new Tolerances(scaleCaps(base, multiplier), fallPoints, allowRiskyJumps, budget,
                    emptyInventoryBlocked));
        }
        stages.add(new Tolerances(RunCaps.NONE, fallPoints, allowRiskyJumps, 0, emptyInventoryBlocked));
        return stages;
    }

    /**
     * Fall damage tolerance (in half-hearts) opened to avoid a dead end.
     *
     * <p><b>There is no unlimited stage.</b> Unlike bridge length or diving, removing the cap would put
     * instantly lethal falls into the guidance: it's the one item where "better than a dead end" doesn't hold,
     * so it stops at a cap derived from health. The default is 1/3 of health, so this opens up to 1.5x that =
     * 1/2 of health (a 13-block drop at full health). That's enough for the intended use: a player without an
     * elytra descending to a low island in the End.
     *
     * <p>If {@code fallDamageToleranceEnabled} is off it stays 0: since the config explicitly declined it,
     * painful falls aren't offered on its own initiative even to avoid a dead end.
     */
    private static int loosenedFallDamagePoints(CellSource view) {
        int configured = view.maxFallDamagePoints();
        return configured <= 0 ? 0 : configured * 3 / 2;
    }

    private static RunCaps scaleCaps(RunCaps base, int multiplier) {
        return new RunCaps(scaleCap(base.maxBridgeRunBlocks(), multiplier),
                scaleCap(base.maxLavaBridgeRunBlocks(), multiplier),
                scaleCap(base.maxVoidBridgeRunBlocks(), multiplier),
                scaleCap(base.maxSubmergedTicks(), multiplier));
    }

    /** {@code 0} is already unlimited, so multiplying keeps it unlimited. */
    private static int scaleCap(int cap, int multiplier) {
        return cap == 0 ? 0 : cap * multiplier;
    }

    /**
     * Discards the running search and the queued searches. For when there's no longer a receiver (destination
     * cleared, logout).
     *
     * <p>Merely advancing the generation only discards the result; the search keeps running until it uses up
     * its budget. Meanwhile the {@code ChunkView} keeps holding chunks, and the next search waits behind this worker.
     */
    public void cancelAll() {
        PathfindingJob previous = currentJob.getAndSet(null);
        if (previous != null) {
            previous.cancel();
        }
        executor.getQueue().clear();
    }

    private CompletableFuture<PathResult> submit(Function<BooleanSupplier, PathResult> work) {
        CompletableFuture<PathResult> future = new CompletableFuture<>();
        PathfindingJob job = new PathfindingJob(future);
        PathfindingJob previous = currentJob.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }
        // Leave the one running job to cooperative cancellation and drop old jobs that haven't started. The queue only ever holds the latest.
        executor.getQueue().clear();

        // Use execute(Runnable), not executor.submit(Runnable). submit wraps in a FutureTask, so the rethrow from
        // catch (Error) below would be swallowed by catch (Throwable) inside FutureTask#run and never reach the
        // thread's uncaught exception handler. With execute it really propagates, and ThreadPoolExecutor
        // replaces the dead worker (same approach as DiagnosticJobRunner.submit).
        executor.execute(() -> {
            try {
                PathResult result = work.apply(job::isCancelled);
                if (job.isCancelled()) {
                    future.cancel(false);
                } else {
                    future.complete(result);
                }
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            } catch (Error fatal) {
                future.completeExceptionally(fatal);
                throw fatal;
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface SearchCall {
        PathResult search(AStarPathfinder pathfinder, BooleanSupplier cancelled);
    }
}
