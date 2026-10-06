package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.corridor.CorridorWaypoints;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.RouteReview;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.util.GameCompat;

/**
 * Client-side pathfinding state.
 *
 * <p>Call {@link #setGoal}/{@link #onClientTick} from the client thread (main thread).
 * The main thread only builds the {@link ChunkView} (collecting references to loaded chunks);
 * both block reads and the A* search run on {@link PathfindingExecutor}'s worker thread.
 */
public final class PathfindingState {

    public static final PathfindingState INSTANCE = new PathfindingState();

    private static final Logger LOGGER = LogManager.getLogger();

    /** How deep to look for ground to land on (blocks). Matches {@code StanceFinder.VERTICAL_SEARCH}. */
    private static final int LANDING_GROUND_SEARCH_BLOCKS = 32;

    /** How long the arrival display stays up (ticks). After that the goal is cleared along with it. */
    private static final int ARRIVAL_DISPLAY_TICKS = 100;

    /** Minimum interval between recalculations after going off route (ticks). A cap so we don't keep firing searches the whole time we're off. */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /** Interval for checking whether the goal column has loaded. Each check scans the column vertically, so not every tick. */
    private static final int GOAL_RESOLVE_CHECK_TICKS = 20;

    /**
     * When the end of a truncated path comes within this distance, recompute what lies beyond it (blocks).
     *
     * <p>A search takes hundreds of ms and its result only lands on a later tick, so replanning after
     * reaching the end leaves a gap in guidance. Pick a distance whose running time comfortably exceeds the compute time.
     */
    static final double EXTEND_DISTANCE_BLOCKS = 64.0;

    /** How long to show the notice that the path was substantially replanned (ticks). */
    private static final int REROUTE_NOTICE_TICKS = 60;

    /** Distance to move after failing to produce a path before retrying (blocks). */
    private static final double RETRY_MOVE_BLOCKS = 4.0;

    /**
     * Minimum horizontal distance for an extension to count as "having moved forward" (blocks).
     *
     * <p>Extending from an end where the budget ran out makes sense, but even in a dead end
     * {@code AStarPathfinder#selectFallback} returns "the best point at least 5 blocks from the start", so
     * without a stop it keeps crawling a few blocks at a time.
     */
    static final double MIN_EXTEND_PROGRESS_BLOCKS = 12.0;

    /**
     * Distance within which a retry reservation ({@link #wideSearchNeededTarget} / {@link #coarseGuideNeededTarget})
     * counts as "the same goal" (blocks).
     *
     * <p>Don't match on exact coordinates. The detail-target is <b>re-interpolated from the player position onto
     * the route</b>, so it shifts by 1-3 blocks every time while walking. Requiring an exact match means that by the
     * tick after the reservation is made the coordinates have already changed and the retry never fires: in a real
     * log, while "hit the expanded-node limit" repeated 20-30 times at 0.5-0.7 s intervals, the coarse waypoint
     * chain ran only once (the symptom shows up as "it fires when you stand still").
     *
     * <p>What the reservation means is the <b>local fact</b> that "a normal search can't get through the terrain
     * around here", so matching on a neighbourhood rather than a point is the proper form. The width matches
     * {@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS} (the waypoint thinning spacing).
     */
    private static final double RETRY_TARGET_TOLERANCE_BLOCKS = 24.0;

    /**
     * Radius within which a long-range route waypoint is accepted as the goal (blocks).
     *
     * <p>Layer-1 waypoints are representative points of a chunk (16 blocks); even refined by layer 2 they are only
     * estimates from surface data. <b>A waypoint is a direction to head in, not a place to pass through</b>: that is
     * the very definition of layer 1's role, so demanding the exact coordinate puts needless detours on the path.
     * Allowing for a layer-2 refined version, keep it a bit smaller than half a cell (8).
     */
    private static final int WAYPOINT_GOAL_RADIUS_BLOCKS = 6;

    /**
     * Radius within which an interpolated point on the line toward a waypoint ({@link #pointAlong}) is accepted as the goal (blocks).
     *
     * <p>This one is an <b>artificial point</b> that ignores terrain entirely: it exists only to move in that direction
     * when the waypoint is too far to aim at in one go. Making it a strict goal is the biggest source of detours, so
     * it's larger than for the waypoint itself.
     */
    private static final int INTERPOLATED_GOAL_RADIUS_BLOCKS = 16;

    /**
     * Upper limit on waiting for the nav graph to be built for the first time for a distant goal (milliseconds).
     *
     * <p>Planning with the 3D coarse layer or waypoints without waiting redraws the line each time map loading, the 3D coarse layer and the nav graph come in,
     * leaving you unsure which way to walk for the first dozen-odd seconds (real Nether run: redrawn 3 times before settling on the nav graph route).
     * So we don't wait forever when it can't be built, fall back to the regular search once the limit passes. A path planned that way is still
     * replanned by the review if the finished guide shows it's a detour ({@link #reviewAgainstNavGraph}).
     */
    private static final long NAV_GRAPH_WAIT_MILLIS = 10_000L;

    /**
     * Detour amount (ticks) above which a review against the rebuilt guide triggers a replan. See {@link RouteReview}.
     *
     * <p>A ratio to the reviewed section's cost is not a condition. The longer the section, the bigger a ratio threshold gets; right after the goal entered the window,
     * a 45-tick detour slipped through as 4.6% and was carried to the end (1.03x optimal in the underground-start model).
     */
    private static final double REVIEW_MIN_EXTRA_TICKS = 40.0;

    /**
     * Distance to walk after a review-triggered replan before the next review (blocks). A brake so that where the guide and the search disagree and replanning
     * doesn't remove the detour, we don't keep redrawing the line on every rebuild.
     */
    private static final double REVIEW_RETRY_MOVE_BLOCKS = 32.0;

    /** Retry interval when no path could be produced and the player hasn't moved either (ticks). */
    private static final int NO_ROUTE_RETRY_TICKS = 200;

    /**
     * Movement distance that prevents replanning the long-range route from the same spot (blocks).
     *
     * <p>A layer-1 replan is {@link XaeroMapReader#readSurface} (main thread) plus two {@link CoarseRouter}
     * A* runs, and gets heavier in proportion to the distance to the goal. While no waypoint is reachable this runs
     * on every recalculation, but <b>replanning from the same spot gives the same route</b>: the result can only
     * change once you've moved enough to change what's on the map.
     */
    private static final double COARSE_ROUTE_RETRY_MOVE_BLOCKS = 32.0;

    /**
     * Interval before replanning a long-range route that was planned with gaps in the map (milliseconds).
     *
     * <p>Loading is asynchronous, so replanning right after the request just reads the same map. On the other hand
     * {@code XaeroMapReader#readSurface} is heavy on the main thread, so it mustn't be fired every tick.
     * Each replan also redoes the layer-2 refinement, so shortening this means more moments where the yellow line reverts to raw layer 1.
     */
    private static final long COARSE_MAP_RETRY_INTERVAL_MILLIS = 3_000;

    /**
     * Number of map-load-waiting replans to allow for one goal before giving up.
     *
     * <p>{@code pendingRegions} drops each time Xaero finishes loading (the detection info is discarded), so
     * it normally reaches 0 within a few rounds. The limit guards against it cycling without dropping (requests
     * discarded in the queue, a corrupt region that never finishes loading). Without it, a full replan every 3 seconds
     * wouldn't stop until reaching the goal.
     */
    private static final int COARSE_MAP_RETRY_LIMIT = 10;

    /**
     * Until we move this far from where a normal search ran out of budget, skip the normal search and start from
     * the coarse waypoint chain (blocks). See {@link #plainSearchHopeless}.
     */
    private static final double PLAIN_RETRY_MOVE_BLOCKS = 32.0;

    /**
     * How many times the budget is multiplied to persist where the normal budget couldn't solve it.
     *
     * <p><b>A value chosen from measurements.</b> Island hopping in the End (measured by importing real save data into
     * {@code RealEndTerrainTest}) needed <b>532,724 nodes</b> to produce the correct path of 105 steps including a
     * bridge up to 42 blocks long. The default 100,000 only produces a 6-step stub: that was the real cause of
     * "can't find a path across the void"; not the limit or the map, simply too small a budget.
     * 400,000 wasn't enough either; 600,000 solved it.
     *
     * <p><b>The Nether lava sea ({@code NetherLavaSeaTest}) needs even more.</b> It bridges more than 50 blocks of open
     * sky with lava 40 blocks below and takes 571,059 nodes; 600,000 leaves only 5% headroom, and slightly different
     * terrain would put it out of reach. 8x (800,000) gives 30% headroom.
     *
     * <p>Time is stretched by the factor too (capped by {@link #DEEP_SEARCH_MAX_MILLIS}). Raising only the node budget
     * would just run out of time first.
     *
     * <p>Using <b>only</b> the deep budget on its own happens only where {@link #plainSearchHopeless} is true.
     * Other normal searches also try the deep budget <b>in parallel</b> with the normal budget via
     * {@code PathfindingExecutor#submitWithDeepFallback}; on ordinary terrain the normal budget gets there quickly and
     * the deep one is cut off immediately, so the cost to responsiveness is about "one more core for as long as the normal budget runs".
     */
    private static final int DEEP_SEARCH_BUDGET_FACTOR = 8;

    /**
     * <b>A lighter weight used only on the normal-budget side of the parallel fallback.</b>
     *
     * <p>Weighted A* (default 1.5) cuts expanded nodes a lot, but since it doesn't reopen expanded nodes,
     * "a slightly worse path that arrived first" gets locked in. In measurements (9 real terrains, paths sampled with seeded
     * randomness, {@code PathOptimalityTest}), this weight accounts for nearly all of the gap to optimal and the wasted ups and downs:
     *
     * <pre>
     *          weight 1.5         weight 1.2         weight 1.0
     * savanna  ratio1.049/ud1.62  ratio1.026/ud1.24  ratio1.003/ud1.05
     * mountain ratio1.048/ud1.33  ratio1.018/ud1.06  ratio1.003/ud1.01
     * </pre>
     *
     * <p><b>Don't lower it across the board.</b> For 140-200 block paths, expanded nodes grow 3-5x, and
     * in mountains the paths the default budget (100k) can't reach go from 1 in 20 to 3.
     *
     * <p>Instead, use the fact that {@code PathfindingExecutor#submitWithDeepFallback} <b>already runs the normal and deep
     * budgets in parallel</b>: with this weight on the normal side only, easy paths are won by the normal side with
     * better quality, and hard paths are picked up as before by the deep side ({@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}).
     * The deep side's weight and budget are at least those of the old normal search, so <b>wait time doesn't increase</b>.
     *
     * <p>Not applied to extension, splicing or leg chains. Those have no deep-budget fallback, so if a lower
     * weight makes them fall short, "can't extend" / "can't splice" turns directly into a gap in guidance.
     */
    private static final double QUALITY_HEURISTIC_WEIGHT = 1.2;

    /**
     * Maximum time allowed for a deep-budget search (milliseconds). Deciding by the factor alone, a single search could
     * take minutes in setups with a large {@code maxExpandedNodes}, and guidance would stay stale the whole time.
     *
     * <p><b>A reported real-world Nether lava sea showed 15 seconds isn't enough.</b> Crossing it needs 571,059 nodes,
     * and the real expansion rate on that terrain is <b>about 20k nodes per second</b> (real log: 474,944 nodes / 24 s;
     * the many bridge branches make it an order of magnitude below the usual 70-100k), which works out to about 29 seconds.
     * Cut at 15 seconds the deep budget always failed, and the coarse waypoint chain that runs afterwards is <b>heavier than
     * a single search</b> (1.04M vs 570k nodes) so it failed too; that was what "can't cross" really was.
     *
     * <p>30 seconds is the compromise between "better than not crossing" and "feeling kept waiting". The cost of extending it
     * is that on truly stuck terrain it takes longer before "no way through" appears.
     */
    private static final long DEEP_SEARCH_MAX_MILLIS = 30_000;

    /** Time after which a long-range route map read counts as "slow". Equivalent to 1 tick (20 TPS). */
    private static final long SLOW_MAP_READ_THRESHOLD_MILLIS = 50L;
    /** Interval before warning again while slow reads continue. Every time would flood the log. */
    private static final long SLOW_MAP_READ_LOG_INTERVAL_MILLIS = 5_000L;
    private static final ChangeGate<Boolean> slowMapReadGate = new ChangeGate<>();

    /**
     * Log if the end of the replanned path is this much farther from the goal than the end of the displayed path
     * ({@link #noteRouteRegression}). Ends normally wobble by a few blocks due to waypoints and search cut-off points, so
     * the width is chosen to stay quiet on wobble and fire only when the line visibly shrinks.
     */
    private static final double ROUTE_REGRESSION_LOG_BLOCKS = 16.0;

    /**
     * Minimum depth for entering surface-first navigation ({@link #shouldClimbToSurface}) (blocks).
     * Just below the surface, cave entrances and cliffs are usually right at hand, so heading straight for the goal
     * is shorter than inserting a relay leg. Splitting guidance into two stages for a few blocks isn't worth it.
     */
    private static final int MIN_UNDERGROUND_DEPTH = 5;

    /** Distance to move from where no path to the surface was found before trying again (blocks). */
    private static final double SURFACE_RETRY_MOVE_BLOCKS = 16.0;

    /**
     * Multiplier on the horizontal search margin for the leg that climbs to the surface. A cave exit isn't necessarily
     * in the direction of the goal, so with the normal range the exit itself falls outside it.
     */
    private static final int SURFACE_SEARCH_MARGIN_FACTOR = 2;

    /**
     * Minimum spacing for thinning the waypoint list refined by the layer-2 corridor (blocks). Layer 2 returns a per-block point list, so
     * without thinning the HUD's "long-range route N/M" and the waypoint count blow up by orders of magnitude compared with layer 1.
     */
    private static final int REFINED_WAYPOINT_MIN_SPACING_BLOCKS = 24;

    /**
     * Lower bound on the horizontal distance to the detail-target (blocks). It's a lower bound on a length that is meaningful as guidance and
     * unrelated to the waypoint spacing (a reach shorter than the spacing is expressed with {@link #pointAlong}).
     */
    static final int MIN_DETAIL_REACH_BLOCKS = REFINED_WAYPOINT_MIN_SPACING_BLOCKS;

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // Dedicated to layer-2 corridor refinement. If it shared executor, the frequent resubmits of the detail search (which
    // run on every deviation or end approach) would each have submit cancel "the previous job", always killing the corridor search before it finishes
    private final PathfindingExecutor corridorExecutor = new PathfindingExecutor();
    // Dedicated to solving the long-range route (layer 1). executor/corridorExecutor can't be shared because submit cancels the previous job
    private final ExecutorService coarseExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-coarse-route");
        thread.setDaemon(true);
        return thread;
    });
    // Guidance while gliding. We own the goal and "whether we're gliding now"; the other side owns only the
    // aerial path to that goal. It asks stillFlyingTo about the freshness of async results
    private final FlightNavState flight = new FlightNavState(this::stillFlyingTo, this::publishNavigationView);
    /** Pillar-and-arrow guidance while gliding under open sky. Client thread only. */
    private final SkyGuide sky = new SkyGuide();
    // Incremented on every clear() and new setGoal(). Checked right before applying an async result;
    // if it doesn't match, the result is discarded as "from a request that's already stale" (prevents the race where
    // a stale result revives currentResult after clear).
    private final AtomicLong generation = new AtomicLong();

    private volatile @Nullable BlockPos goal;
    // Dimension in which the goal was set. Remembering only coordinates would keep aiming at the same coordinates after moving to the Nether
    private volatile @Nullable ResourceKey<Level> goalDimension;
    // The coordinates exactly as given when the goal was set while its column was unloaded. Once loaded, they're snapped to a standable height
    // (resolveGoalStandable). null once snapped
    private volatile @Nullable BlockPos unresolvedGoal;
    private volatile @Nullable DisplayedPath displayed;
    private int ticksSinceGoalResolveCheck;
    private volatile boolean computing;
    private volatile boolean arrived;
    // Where no path to the surface could be produced. With digging disabled or in sealed spaces the relay leg itself
    // can't work, so near there we give up on surface-first navigation and head straight for the real goal
    private volatile @Nullable BlockPos surfaceLegFailedAt;
    // Waypoints of the long-range route. Terrain doesn't change, so it isn't replanned unless the goal changes
    private volatile @Nullable CoarseRoute coarseRoute;
    // Refined version of coarseRoute with each leg re-solved by the layer-2 corridor (block resolution). Layer 1 only sees terrain
    // as chunk averages, so a waypoint may actually point at a cliff top or into a lake. As soon as it's ready,
    // cachedOrFreshRoute/NavigationView#coarseRouteWaypoints switch over to it
    // (an extension of the existing idea of falling back to layer 1's coarseRoute if any precondition is missing)
    private volatile @Nullable RefinedRoute refinedRoute;
    // Flag showing that refinement finished in the background, to be picked up on the next tick (same structure as
    // pendingWideRetry). Set in whenComplete (worker thread), read in onClientTick (client thread)
    /** The completed refinement itself. Published to the current route after checking its origin. */
    private volatile @Nullable RefinedRoute pendingRefinedRouteReady;
    // The coarseRoute currently being refined. If the long-range route is replanned meanwhile, the result is discarded
    // for not matching its origin; the replan interval (min 0.5 s) can be shorter than refinement (up to 300 ms per leg), so
    // letting it through would mean the refined version never completes while the main-thread map reads keep spinning
    private volatile @Nullable CoarseRoute refiningRoute;
    // The long-range route request being solved in the background. A marker to avoid stacking requests for the same goal and to tell,
    // on completion, whether it's still the latest request (compared by identity). Touched only by the client thread
    private @Nullable CoarseSolve solvingCoarse;
    // The most recently read long-range route map. Estimates outside the window (nav graph) use it without waiting for the route to be solved;
    // waiting would make the nav graph built right after choosing a goal estimate outside the window with the geometric lower bound
    private volatile @Nullable CoarseMapForGoal latestCoarseMap;
    // The long-range route before a map-load-waiting replan. Compared with the next adopted one and logged. Touched only by the client thread
    private @Nullable CoarseRoute mapRetryBefore;
    // When the next map-load-waiting replan is due (COARSE_MAP_RETRY_INTERVAL_MILLIS). Touched only by the client thread
    private long coarseMapRetryAfterMillis;
    // Number of ticks spent without a goal (warmUpWhenIdle). Touched only by the client thread
    private int idleTicks;
    private static final int WARM_UP_IDLE_TICKS = 200;
    // Number of map-load-waiting replans (COARSE_MAP_RETRY_LIMIT). Touched only by the client thread
    private int coarseMapRetries;
    // Whether we aim directly at the goal without stopping at waypoints (dimensions with a ceiling, or when there's a nav graph guide).
    // Written by selectDetailTarget, read by the HUD (since the path carries no waypoint index)
    private volatile boolean aimingPastWaypoints;
    // 3D coarse layer for dimensions with a ceiling. Built once per goal and reused for extensions too
    private final NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
    // Nav graph for dimensions without a ceiling. Built per goal, and only the window delta is added as you walk
    private final NavGraphGuide navGraphGuide = new NavGraphGuide();
    // Waiting for the nav graph to be built for the first time (NAV_GRAPH_WAIT_MILLIS). Touched only by the client thread
    private boolean awaitingNavGraph;
    private long navGraphWaitStartedMillis;
    // The guide last used to review the path, the position of the review-triggered replan (REVIEW_RETRY_MOVE_BLOCKS) and the guide's value there. Touched only by the client thread
    private @Nullable WindowField reviewedField;
    private @Nullable BlockPos reviewReplannedAt;
    private double reviewReplannedValue;
    // The search goal the detail search couldn't reach with the normal margin. A marker to retry with a wider range on the next
    // recalculate. The real goal and long-range route waypoints aren't distinguished because both are "detail search goals
    // inside the render distance", and paths detouring around walls or lakes fall outside the range for the same reason.
    // Holding it as a boolean "was the last search unreached" would drop it in clear() every time the same place is re-specified,
    // restarting from the normal margin (the retry would never fire on the most likely action: re-specifying
    // because it couldn't be reached). Remembering and matching the goal itself keeps "this place needs a wide range"
    // regardless of how it was specified. So clear() doesn't erase it either; it doesn't match another goal
    // and so invalidates itself. Written in whenComplete (worker thread) and read in the next
    // recalculate (client thread), hence volatile
    private volatile BlockPos wideSearchNeededTarget;
    // Whether to resubmit the wide-range retry immediately. The other recalculation triggers (deviating from the path, approaching
    // a truncated end, terrain changes on the path) all assume the player moves, so without this
    // the wide search doesn't start until you "walk to the dead end". Used like specifying the next goal right after arriving and
    // watching the result while standing still, it would never fire
    private volatile boolean pendingWideRetry;
    // Retry marker paired with wideSearchNeededTarget/pendingWideRetry, for local obstacles (cliffs and lakes within render distance).
    // When the search ends unreached by hitting the expanded-node limit, widening the range just hits the same limit the same way
    // (the very reason wideRetry excludes it), so instead build a coarse map from the raw data of loaded chunks
    // and follow its waypoints leg by leg (layer 3)
    private volatile BlockPos coarseGuideNeededTarget;
    private volatile boolean pendingCoarseGuideRetry;
    // Upper bound on the horizontal distance to the detail-target (blocks). 0 means "not measured yet = allow up to renderRadius".
    //
    // renderRadius can't stand in for "how far the detail search reaches". Measured (Nether, render distance 18 = renderRadius
    // 288): of the 1369 chunks in the search range only 367 were loaded = equivalent to a 173-block radius, and
    // there are about 5.9 passable cells per column (a 3D maze, orders of magnitude different from the overworld where you just walk the surface), so
    // with the default 100k nodes paths could actually be planned only 70-90 blocks ahead. Even 4.5x the budget
    // only gave 2.8x the steps = reach grows only with the square root of the budget, so raising the limit
    // doesn't solve it. Repeatedly throwing an unreachable target expands to the limit every time and returns only partial paths, so
    // use the most recent actual result as the next limit.
    //
    // As long as it's solved within budget, as on the overworld, it stays pinned to renderRadius, so the old behaviour is unchanged.
    // Written in whenComplete (worker thread), read in the next recalculate (client thread)
    // The waypoint (raw coordinates) the previous detail-target pointed at. A brake against going backwards along the route.
    // Remembered by coordinates rather than index because replanning the route changes what indices mean; if the new list
    // doesn't contain the same point, the brake is released automatically
    private volatile BlockPos lastAimedWaypoint;
    // Whether we're gliding with an elytra. While gliding, ground A* and long-range route computation stop entirely and only a straight
    // (dotted) line to the goal is shown (automatic elytra detection; obstacle-avoiding paths aren't needed
    // because "the player can see and steer through the sky themselves")
    private volatile boolean flying;
    /** Decides whether elytra gliding counts as flight (hysteresis on time and height). */
    private final ElytraTrigger elytraTrigger = new ElytraTrigger();
    /**
     * Number of waypoints considered passed. Used only to decide where to start drawing the map's dotted line.
     *
     * <p>Advanced <b>monotonically</b> up to the index the path end is heading for. This also avoids drawing passed waypoints while
     * the displayed path isn't aimed at a waypoint (heading straight to the real goal, no path yet, arrival display); judging
     * by mode alone, the moment the last look-ahead leg reaches the goal and the mode switches to GOAL, all passed waypoints
     * get redrawn at once, and it looks as if the line stayed on the road you just walked.
     */
    private volatile int passedWaypoints;

    /**
     * Where the previous normal search (the side that isn't the coarse waypoint chain) hit the expanded-node limit.
     * See {@link #plainSearchHopeless}.
     */
    private volatile BlockPos plainBudgetExhaustedAt;

    /**
     * The search target most recently reported as "unstandable". Deduplication so the same target isn't logged every time
     * ({@link #noteTargetStandability}).
     */
    private final ChangeGate<BlockPos> unstandableTargetGate = new ChangeGate<>();

    /** Whether to resubmit a search with a raised budget on the next tick. One step before {@link #pendingCoarseGuideRetry}. */
    private volatile boolean pendingDeepRetry;

    /** Decides "can't reach the goal". See the class Javadoc of {@link StuckTracker}. */
    private final StuckTracker stuckTracker = new StuckTracker();

    /** Detects "moved away from the closest point reached". See the class Javadoc of {@link RetreatWatcher}. */
    private final RetreatWatcher retreatWatcher = new RetreatWatcher();

    /**
     * Number of times {@link #logSearchReach} wrote "can't move forward". Attached to the retreat log, it tells
     * whether a turnaround came from a <b>search dead end</b> or just from a decision change after rebuilding the guide
     * (in the real 2026-09-18 back-and-forth, the waypoint chain had reached=false all 3 times just before).
     */
    private static final AtomicInteger stalledSearches = new AtomicInteger();

    /**
     * Remaining ticks for the notice that "the path you were walking became unusable". Set only when replanning including the path ahead
     * due to a dead end or a world change. Deviation isn't included since that's just the player going off on their own.
     */
    private volatile int rerouteNoticeTicks;

    /**
     * A composite one-frame snapshot of the ground-navigation state read by the HUD and map/world rendering.
     *
     * <p>{@link #goal}, {@link #flying}, {@link #arrived} etc. are separate volatiles, so if the rendering side reads them
     * in several steps, a worker callback can slip in between and show for one frame "a combination that never existed
     * at any instant". Every entry point that changes a field included here
     * ({@link #setGoal}, {@link #clear}, {@link #onClientTick}, each whenComplete handler,
     * {@link FlightNavState}'s onChanged notification) must call {@link #publishNavigationView()}, and
     * the rendering side must read only the single instance returned by {@link #navigationView()}, not the individual getters.
     */
    private volatile NavigationView navigationView = NavigationView.empty();

    // Inputs used for the most recent search. Everything below is touched only from the client thread.
    private BlockPos lastStart;
    private int ticksSinceRecalc;
    private int ticksSinceValidation;
    private int arrivedTicks;

    /**
     * How async results get back to the main thread. In production it's {@code Minecraft.getInstance()::execute}, but
     * it's injectable for tests (to verify {@link GenerationGate}'s cancel propagation without a real client,
     * TEST-01). This class itself keeps using Minecraft.getInstance() directly elsewhere;
     * injecting just this one point is enough (generation management and async completion order checks are concentrated here).
     */
    private final Consumer<Runnable> onMainThread;
    /** The 5 async completion handlers each wrote the same "check generation -> back to main thread" separately, so it's shared. */
    private final GenerationGate generationGate;
    /** Seam re-solving (see {@link SeamRepair}). Touches goal/displayed/computing via {@link SeamRepair.Host}. */
    private final SeamRepair seamRepair;
    /** Splicing back after deviating from the path's band (see {@link Splice}). Likewise touches state only via Host. */
    private final Splice splice;
    /** Extending from the path's end (see {@link Extend}). Queries about long-range route selection also go through Host. */
    private final Extend extend;
    /** Cells that recently failed re-verification (see {@link RecentFailures}). Ground searches avoid them. */
    private final RecentFailures recentFailures = new RecentFailures();

    private PathfindingState() {
        this(runnable -> Minecraft.getInstance().execute(TickLaps.timed(runnable)));
    }

    PathfindingState(Consumer<Runnable> onMainThread) {
        this.onMainThread = onMainThread;
        this.generationGate = new GenerationGate(generation, onMainThread);
        this.seamRepair = new SeamRepair(executor, generation, generationGate, this::publishNavigationView,
                new SeamRepair.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }
                }, recentFailures);
        this.splice = new Splice(executor, generation, generationGate, this::publishNavigationView,
                new Splice.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }

                    @Override
                    public @Nullable WindowField guide() {
                        BlockPos currentGoal = goal;
                        return currentGoal == null ? null : navGraphGuide.latest(currentGoal);
                    }
                }, seamRepair, recentFailures);
        this.extend = new Extend(executor, generation, generationGate, this::publishNavigationView,
                new Extend.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }

                    @Override
                    public int coarseRoutePendingRegions(BlockPos currentGoal) {
                        CoarseRoute route = coarseRoute;
                        return route != null && route.goal().equals(currentGoal) ? route.pendingRegions() : -1;
                    }

                    @Override
                    public DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                                            int reach, boolean boatAvailable,
                                                            boolean playerAnchored, int minWaypointIndex,
                                                            boolean ceilingDimension, boolean navGraphGuided) {
                        return PathfindingState.this.selectDetailTarget(start, currentGoal, renderRadius, reach,
                                boatAvailable, playerAnchored, minWaypointIndex, ceilingDimension, navGraphGuided);
                    }

                    @Override
                    public @Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from,
                                                         BlockPos currentGoal, int renderRadius) {
                        return PathfindingState.this.goalGuide(level, player, from, currentGoal, renderRadius, false);
                    }

                    @Override
                    public void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result) {
                        PathfindingState.this.noteSearchOutcome(start, planEnd, result);
                    }
                }, seamRepair, recentFailures);
    }

    /** The finished guide to {@code goal}, used for the arrival time display. */
    @Nullable WindowField guideForDisplay(BlockPos goal) {
        WindowField field = navGraphGuide.latest(goal);
        return field != null && field.reachesGoal() ? field : null;
    }

    /** {@link NavGraphGuide#farScaleForDisplay}. */
    double guideFarScaleForDisplay() {
        return navGraphGuide.farScaleForDisplay();
    }

    /** The composite ground-navigation snapshot to use for the current frame. See {@link #publishNavigationView()}. */
    public NavigationView navigationView() {
        return navigationView;
    }

    private void publishNavigationView() {
        navigationView = new NavigationView(goal, flying, arrived, computing || awaitingNavGraph, stuckTracker.reason(), displayed,
                coarseRoute, refinedRoute, passedWaypoints, rerouteNoticeTicks > 0,
                flying ? flight.route() : FlightRoute.NONE, skyPillar());
    }

    /** Where to place the pillar, only while gliding under open sky. {@code null} otherwise. */
    private @Nullable BlockPos skyPillar() {
        Level level = Minecraft.getInstance().level;
        BlockPos currentGoal = goal;
        if (!flying || !sky.active() || level == null || currentGoal == null) {
            return null;
        }
        CoarseRoute route = coarseRoute;
        RefinedRoute refined = refinedRoute;
        List<BlockPos> waypoints = route == null || !route.goal().equals(currentGoal) ? List.of()
                : refined != null && refined.source() == route ? refined.waypoints() : route.waypoints();
        CoarseMapForGoal map = latestCoarseMap;
        return sky.pillar(level, currentGoal, waypoints,
                map != null && map.goal().equals(currentGoal) ? map.map() : null);
    }

    /**
     * Immutable snapshot returned by {@link #navigationView()}. Instead of getters that read fields individually,
     * the HUD and map/world rendering fetch this once per frame and use it.
     */
    public record NavigationView(BlockPos goal, boolean flying, boolean arrived, boolean computing,
                                  StuckReason stuckReason, DisplayedPath displayed, CoarseRoute coarseRoute,
                                  RefinedRoute refinedRoute, int passedWaypoints, boolean rerouted,
                                  FlightRoute flightRoute, @Nullable BlockPos skyPillar) {

        private static NavigationView empty() {
            return new NavigationView(null, false, false, false, null, null, null, null, 0, false,
                    FlightRoute.NONE, null);
        }

        /** Same rule as {@link PathfindingState#currentResult()}. */
        public PathResult currentResult() {
            if (flying || displayed == null) {
                return null;
            }
            return displayed.result();
        }

        /** Same rule as {@link PathfindingState#climbingToSurface()}. */
        public boolean climbingToSurface() {
            return displayed != null && displayed.mode() == PathMode.TO_SURFACE;
        }

        /** Same rule as {@link PathfindingState#currentPathEndsAtDestination()}. */
        public boolean currentPathEndsAtDestination() {
            return displayed != null && displayed.mode() == PathMode.GOAL && displayed.result().complete();
        }

        /** Same rule as {@link PathfindingState#coarseRouteWaypoints}. Only waypoints not yet passed. */
        public List<BlockPos> coarseRouteWaypoints() {
            if (flying || arrived) {
                return List.of();
            }
            List<BlockPos> all = routeWaypoints();
            if (all.isEmpty()) {
                return all;
            }
            if (displayed != null && displayed.mode() == PathMode.GOAL && displayed.result().complete()) {
                return List.of();
            }
            int from = displayed != null && displayed.mode() == PathMode.WAYPOINT
                    ? Math.max(passedWaypoints, displayed.waypointIndex())
                    : passedWaypoints;
            if (from <= 0) {
                return all;
            }
            return from >= all.size() ? List.of() : all.subList(from, all.size());
        }

        private List<BlockPos> routeWaypoints() {
            if (coarseRoute == null || goal == null || !coarseRoute.goal().equals(goal)) {
                return List.of();
            }
            return refinedRoute != null && refinedRoute.source() == coarseRoute
                    ? refinedRoute.waypoints() : coarseRoute.waypoints();
        }
    }

    /**
     * Sets the goal.
     *
     * @return the goal actually adopted, resolved to a standable height. {@code null} if there's no world
     */
    public @Nullable BlockPos setGoal(BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return null;
        }
        clear();
        this.goal = resolveGoalStandable(level, goal);
        this.goalDimension = level.dimension();
        this.unresolvedGoal = level.getChunkSource().getChunkNow(goal.getX() >> 4, goal.getZ() >> 4) == null
                ? goal : null;
        // A goal set while gliding doesn't get a walking path until back on the ground
        // (it would only be discarded without being shown)
        this.flying = airborne(level, player);
        GoalWaypoint.sync(this.goal);
        if (this.flying) {
            sky.begin(level, player);
            if (!sky.active()) {
                flight.recalculate(this.goal);
            }
        } else {
            recalculate("goal set");
        }
        publishNavigationView();
        return this.goal;
    }

    /**
     * Snaps the goal's Y to a height where you can actually stand in that column. Arrival is an exact coordinate match
     * ({@code AStarPathfinder}), so if Y is merely off from the ground the search exhausts the reachable space and ends unreached.
     * The goal's Y is normally the map's estimate for a map click or a rough value when typed in, and
     * can't be assumed correct to the block.
     *
     * <p>Picks the standable height closest to the requested Y (not necessarily the nearest surface; goals inside caves can be specified too).
     * If the column isn't loaded, falls back to Xaero's map data, and failing that to the original coordinates.
     */
    private static BlockPos resolveGoalStandable(Level level, BlockPos goal) {
        int x = goal.getX();
        int z = goal.getZ();
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            BlockPos fromMap = XaeroPresence.mapPresent() ? resolveGoalOnSurface(goal) : null;
            return fromMap != null ? fromMap : goal;
        }
        int minY = GameCompat.minBuildHeight(level) + 1;
        int maxY = GameCompat.maxBuildHeight(level) - 2;
        int requested = Mth.clamp(goal.getY(), minY, maxY);
        for (int offset = 0; offset <= maxY - minY; offset++) {
            int below = requested - offset;
            if (below >= minY && standableAt(level, x, below, z)) {
                return new BlockPos(x, below, z);
            }
            int above = requested + offset;
            if (offset > 0 && above <= maxY && standableAt(level, x, above, z)) {
                return new BlockPos(x, above, z);
            }
        }
        return goal;
    }

    /**
     * Once the column of a goal that was set while unloaded loads, snaps it to a standable height. {@code true} if it was snapped.
     *
     * <p>If the column couldn't be read when set, the goal's Y is the map's estimate or exactly as given; a map click's Y can land inside rock
     * (real Nether: inside a fortress pillar). Left as is, the nav graph doesn't connect to the goal and the guide becomes unusable as soon as it enters the window.
     * Only the height changes, so the planned path and nav graph are kept ({@link #retargetGoal}).
     *
     * <p>A just-loaded chunk may not have its contents yet (see {@link PathValidator}). As long as no standable spot
     * is found, don't give up on snapping; read again at the next opportunity.
     */
    private boolean resolveGoalOnceLoaded(Level level) {
        BlockPos requested = unresolvedGoal;
        BlockPos current = goal;
        if (requested == null || current == null || ticksSinceGoalResolveCheck++ < GOAL_RESOLVE_CHECK_TICKS
                || level.getChunkSource().getChunkNow(requested.getX() >> 4, requested.getZ() >> 4) == null) {
            return false;
        }
        ticksSinceGoalResolveCheck = 0;
        BlockPos resolved = resolveGoalStandable(level, requested);
        if (!standableAt(level, resolved.getX(), resolved.getY(), resolved.getZ())) {
            return false;
        }
        unresolvedGoal = null;
        if (resolved.equals(current)) {
            return false;
        }
        LOGGER.info("XaeroNav: Goal column loaded; snapped the goal to a standable height ({} → {})",
                current.toShortString(), resolved.toShortString());
        if (flying) {
            setGoal(requested);
        } else {
            retargetGoal(resolved);
        }
        return true;
    }

    /**
     * Replaces the goal with a different height in the same column. Unlike {@link #setGoal}, the planned path is kept.
     *
     * <p>Clearing it means no path until the nav graph is rebuilt from the whole window (real Nether: about 19 s after snapping). Even when the height changes,
     * the path mostly heads to the same place, and extension from its end aims at the new goal. Nav graph edges don't depend on height either,
     * so only the guide needs rebuilding.
     */
    private void retargetGoal(BlockPos resolved) {
        goal = resolved;
        GoalWaypoint.sync(resolved);
        CoarseRoute route = coarseRoute;
        if (route != null && !route.waypoints().isEmpty()) {
            // The long-range route identifies its owner by the goal's coordinates, so left as is it'd be discarded and the HUD's dotted line would vanish
            List<BlockPos> waypoints = route.reachedGoal() ? replaceLast(route.waypoints(), resolved) : route.waypoints();
            coarseRoute = new CoarseRoute(resolved, route.computedFrom(), route.reachedGoal(), route.pendingRegions(),
                    waypoints);
        }
        CoarseMapForGoal latestMap = latestCoarseMap;
        if (latestMap != null) {
            latestCoarseMap = new CoarseMapForGoal(resolved, latestMap.map());
        }
        // The long-range route being solved belongs to the old goal. Replan on the next recalculation
        solvingCoarse = null;
        navGraphGuide.retarget();
        reviewedField = null;
        reviewReplannedAt = null;
        // If we'd decided the goal inside rock was unreachable, that was about the height before snapping
        stuckTracker.reset();
        publishNavigationView();
    }

    /** Whether there's standable ground underfoot and the body's 2 cells can be entered without digging. Same premise as {@code AStarPathfinder}'s moves. */
    private static boolean standableAt(Level level, int x, int y, int z) {
        return CellData.standable(CellData.flagsOf(level.getBlockState(new BlockPos(x, y - 1, z))))
                && CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(new BlockPos(x, y, z))))
                && CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(new BlockPos(x, y + 1, z))));
    }

    public void clear() {
        // Once the generation advances, the results of running searches are discarded. Lower computing, which represents waiting for them, here too
        generation.incrementAndGet();
        executor.cancelAll();
        corridorExecutor.cancelAll();
        GoalWaypoint.sync(null);
        this.computing = false;
        this.goal = null;
        this.goalDimension = null;
        this.unresolvedGoal = null;
        this.displayed = null;
        this.lastStart = null;
        this.arrived = false;
        this.surfaceLegFailedAt = null;
        this.coarseRoute = null;
        this.refinedRoute = null;
        this.refiningRoute = null;
        this.pendingRefinedRouteReady = null;
        this.solvingCoarse = null;
        this.latestCoarseMap = null;
        this.mapRetryBefore = null;
        this.coarseMapRetryAfterMillis = 0L;
        this.coarseMapRetries = 0;
        this.aimingPastWaypoints = false;
        this.voxelGuide.clear();
        this.navGraphGuide.clear();
        this.awaitingNavGraph = false;
        this.navGraphWaitStartedMillis = 0L;
        this.reviewedField = null;
        this.reviewReplannedAt = null;
        this.pendingWideRetry = false;
        this.pendingCoarseGuideRetry = false;
        this.pendingDeepRetry = false;
        this.lastAimedWaypoint = null;
        this.passedWaypoints = 0;
        this.plainBudgetExhaustedAt = null;
        this.splice.clearBlock();
        this.seamRepair.clear();
        this.recentFailures.clear();
        this.stuckTracker.reset();
        this.retreatWatcher.reset();
        stalledSearches.set(0);
        mapReadsWithoutGain = 0;
        lastMapReadFrom = null;
        lastMapKnownCells = -1;
        this.extend.clear();
        this.rerouteNoticeTicks = 0;
        this.flying = false;
        this.flight.reset();
        this.sky.reset();
        // elytraTrigger isn't reset here. It tracks <b>the player's body state</b>, not the goal;
        // typing goto while gliding would lose the "already gliding" continuity, and for the 0.5 s until flight mode
        // is re-entered a ground search would run and the HUD would show "no path". If not gliding, the next tick's
        // update resets it on its own, so carrying it over does no harm
        this.arrivedTicks = 0;
        publishNavigationView();
    }

    public BlockPos goal() {
        return goal;
    }

    /**
     * Summary of the most recent recalculation decisions. For grasping "what's happening now" during real-world debugging
     * without digging back through logs ({@code /xaeronav debug summary}). Doesn't change state.
     */
    public record DiagnosticSummary(@Nullable String spliceRefusal, @Nullable String seamRepairRefusal,
                                     @Nullable BlockPos unstandableTarget) {
    }

    public DiagnosticSummary diagnosticSummary() {
        return new DiagnosticSummary(splice.currentRefusal(), seamRepair.currentRefusal(),
                unstandableTargetGate.current());
    }

    public PathResult currentResult() {
        if (flying) {
            return null;
        }
        DisplayedPath shown = displayed;
        return shown == null ? null : shown.result();
    }

    /**
     * Assembles what map rendering needs for one frame.
     *
     * <p>Don't fill it from individual getters. The worker threads replace the path, goal, long-range route and aerial path
     * each at different times, so reading them in sequence mixes old and new state:
     * a combination that never existed at any point, like "a dotted line from the end of an already discarded path to the new goal",
     * gets drawn for one frame. To avoid the same mismatch {@link MapPathOverlay.Snapshot} prevents
     * happening one level further in, build from the single snapshot already published by {@link #navigationView()}.
     * Only the aerial curved dotted line ({@code flight.dashWaypoints}) keeps the existing design of
     * referring to {@link FlightNavState}'s own internal state ({@code coarseRoute}/{@code guideWaypoints}), and
     * its mutual consistency isn't guaranteed here
     * (they're fallback paths only used when empty, so even if mixed the visual breakage is small).
     */
    public MapPathOverlay.Snapshot mapOverlaySnapshot(BlockPos playerPos) {
        NavigationView view = navigationView();
        boolean airborne = view.flying();
        boolean done = view.arrived();
        BlockPos currentGoal = view.goal();
        FlightRoute route = view.flightRoute();

        PathResult ground = view.currentResult();
        if (ground != null && ground.steps().isEmpty()) {
            ground = null;
        }
        List<Vec3> dash = flight.dashWaypoints(airborne, done, currentGoal);
        return new MapPathOverlay.Snapshot(ground,
                currentGoal,
                XaeroNavConfig.INSTANCE.straightLineEnabled(),
                XaeroNavConfig.INSTANCE.goalMarkerEnabled() && !GoalWaypoint.placed(),
                playerPos,
                view.coarseRouteWaypoints(),
                route.points(),
                FlightProgress.INSTANCE.segmentFor(route) + 1,
                dash);
    }

    /** Whether we're gliding with an elytra. While gliding no path is computed; only a straight (dotted) line to the goal is shown. */
    public boolean flying() {
        return flying;
    }

    /**
     * Whether an async aerial-path result may be applied ({@link FlightNavState.Current}). Only when the goal and dimension
     * haven't changed since it was computed and we're still gliding.
     */
    private boolean stillFlyingTo(BlockPos computedGoal, ResourceKey<Level> dimension) {
        return flying && computedGoal.equals(goal) && dimension.equals(goalDimension);
    }

    /**
     * The aerial path while gliding. Empty if not flying, not computed yet, or couldn't be planned.
     *
     * <p>The first point is the player position <b>at computation time</b>, so by the time it arrives it's up to one recalculation interval stale.
     * The rendering side should drop the first point and draw from the current position.
     */
    public FlightRoute flightRoute() {
        if (!flying) {
            return FlightRoute.NONE;
        }
        return flight.route();
    }

    /**
     * The point from which to start drawing the aerial path's polyline. An index for not drawing passed sections;
     * it must always be shared between in-world rendering and the map (truncating only one leaves a line extending
     * behind you on the map only).
     */
    public int flightRouteFrom() {
        return flight.routeFrom();
    }

    /** The point on the aerial path closest to {@code player}, where the line starts. {@code null} if there's no mapping yet. */
    public @Nullable Vec3 flightRouteAnchor(Vec3 player) {
        return FlightProgress.INSTANCE.nearestOnRoute(flight.route(), player);
    }

    /**
     * Intermediate points the dotted line should follow. <b>Includes neither the start nor the goal</b>: the rendering side has both itself
     * (the start is the thick line's end or the current position, the end is the goal), so including the ends would always require shifting indices.
     *
     * <p>Returns the long-range route's waypoints if there is one, otherwise falls back to the curved dotted line. From the caller's view
     * it's a single question, "where to bend the dotted line", so the two sources are merged into one here.
     */
    public List<Vec3> flightDashWaypoints() {
        return flight.dashWaypoints(flying, arrived, goal);
    }

    /** Whether a search is still running. Used by the guidance display to tell whether there's no path yet because it's being computed. */
    public boolean computing() {
        return computing;
    }

    /** Whether we've arrived at the goal. True only for {@link #ARRIVAL_DISPLAY_TICKS} from the moment of arrival. */
    public boolean arrived() {
        return arrived;
    }

    /** Whether we've just replanned because the path being walked became unusable. Read by the HUD to notify. */
    public boolean rerouted() {
        return rerouteNoticeTicks > 0;
    }

    /**
     * The reason we decided the goal is unreachable. {@code null} if not decided yet or resolved.
     *
     * <p>This is different from "no path found" (= this search came back empty). That is the state of one search and
     * the next search might produce one. This is the conclusion that <b>no matter how often we try, we can't get closer to the goal</b>, and
     * searching itself is stopped while in this state (the result won't change until the player moves).
     */
    public StuckReason stuckReason() {
        return stuckTracker.reason();
    }

    /**
     * Key of the text explaining why we're stuck.
     *
     * <p>"Please allow digging or placing blocks" only makes sense when they're actually disabled.
     * Shown to someone who already allows them, it's off-target advice and teaches them to skip the whole line; the value of advice
     * is that "reading it tells you what to do next", so advice that doesn't apply is better left out.
     */
    public static String stuckHintKey(StuckReason reason) {
        if (reason == StuckReason.NO_WAY_THROUGH
                && XaeroNavConfig.INSTANCE.diggingEnabled() && XaeroNavConfig.INSTANCE.bridgingEnabled()) {
            return "hud.xaeronav.unreachable_blocked_detour";
        }
        return reason.hintKey();
    }

    /** Whether the displayed path is a relay path "to reach the surface first" rather than to the real goal. */
    public boolean climbingToSurface() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.TO_SURFACE;
    }

    /** Whether the displayed solid line reaches the real goal rather than a relay point or the end of a search. */
    public boolean currentPathEndsAtDestination() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.GOAL && shown.result().complete();
    }

    /**
     * After spending {@link #WARM_UP_IDLE_TICKS} without a goal, prepare the nav graph build once
     * ({@link NavGraphGuide#warmUp}). Right after entering a world it's busy loading chunks, so wait a little first.
     */
    private void warmUpWhenIdle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks == WARM_UP_IDLE_TICKS && XaeroNavConfig.INSTANCE.costToGoGuideEnabled()) {
            navGraphGuide.warmUp(mc.level, mc.player, XaeroNavConfig.INSTANCE.movementOptions());
        }
    }

    public void onClientTick() {
        try {
            if (rerouteNoticeTicks > 0) {
                rerouteNoticeTicks--;
            }
            BlockPos currentGoal = goal;
            if (currentGoal == null) {
                warmUpWhenIdle();
                return;
            }
            GoalWaypoint.sync(currentGoal);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            if (!mc.level.dimension().equals(goalDimension)) {
                // Moved to a different dimension. Aiming at the same coordinates makes no sense, so drop the goal entirely
                clear();
                return;
            }
            if (resolveGoalOnceLoaded(mc.level)) {
                return;
            }
            StuckReason notice = stuckTracker.takePendingNotice();
            if (notice != null) {
                // The decision is made on a worker thread. Chat output is main-thread only, so pick it up here
                GameCompat.tell(mc.player, TextCompat.translatable("hud.xaeronav.unreachable_notice",
                        TextCompat.translatable(stuckHintKey(notice))), false);
            }
            if (arrived) {
                arrivedTicks++;
                if (arrivedTicks >= ARRIVAL_DISPLAY_TICKS) {
                    clear();
                }
                return;
            }
            // The path and mode are replaced as a pair, so one tick's decisions are made on the same snapshot
            DisplayedPath shown = displayed;
            // Put the mapping update before every early return below. It used to go un-updated while the map was open or while gliding,
            // so if the path was replaced meanwhile, "a distance measured against a different path" would
            // linger, and deviation detection, guidance and rendering all read that value. A path being finished
            // with the map open is the most common action (see the comment below), so that's where it hits most
            long progressLap = TickLaps.start();
            PathProgress.INSTANCE.update(shown == null ? null : shown.result(), mc.player.position());
            TickLaps.add("progress mapping", progressLap);
            // Placed before the early return for open screens. Below here we only stop in situations where the player
            // can't move, so dropping retreat observations misses nothing, but changing the order would create
            // holes like "don't count while gliding"
            long retreatLap = TickLaps.start();
            noteRetreat(mc.player.blockPosition(), currentGoal, shown);
            TickLaps.add("retreat watch", retreatLap);
            // While Xaero's world map or the inventory is open, the player can't move. Without stopping here,
            // searches on the same inputs would keep running the whole time you're just looking at the map.
            if (ClientCompat.screen(mc) != null) {
                // Only let the one-off retry through. Specifying a goal on the map and watching the path on the map is
                // the most common action, and stopping here means the path to a distant goal that "couldn't be reached
                // with the normal margin" wouldn't appear until the map is closed (all other recalculation triggers assume
                // the player moves, so there's no worry about them running while a screen is open)
                pendingEscalation(mc.player);
                return;
            }
            boolean nowFlying = airborne(mc.level, mc.player);
            if (nowFlying != flying) {
                flying = nowFlying;
                if (nowFlying) {
                    // Once the generation advances, results of running searches are discarded. But a whenComplete with a
                    // mismatched generation returns early and doesn't write computing, so lower it explicitly here
                    generation.incrementAndGet();
                    executor.cancelAll();
                    computing = false;
                    sky.begin(mc.level, mc.player);
                    if (!sky.active()) {
                        // We want the line to bend from the moment of takeoff. Waiting for the cycle makes it look like it cuts through mountains for the first few seconds
                        flight.recalculate(currentGoal);
                    }
                } else {
                    // Landed. The pre-takeoff path belongs to a place far away, so clear it before replanning
                    // (otherwise the old line lingers for the few ticks until the new path arrives)
                    displayed = null;
                    flight.dropRoute();
                    sky.reset();
                    recalculate("landed");
                    return;
                }
            }
            if (flying) {
                // While gliding, stop ground path following and A* recalculation and look only at the aerial path
                checkArrival(mc.player, currentGoal, null);
                if (sky.tick(mc.level, mc.player)) {
                    if (sky.active()) {
                        // Discard the results of running searches too. Arriving later, an aerial path would appear next to the pillar
                        flight.reset();
                    } else {
                        // Went under a roof. From here a line that avoids obstacles is needed
                        flight.recalculate(currentGoal);
                    }
                }
                if (!sky.active()) {
                    flight.tick(mc.level, mc.player, currentGoal);
                }
                return;
            }
            if (shown != null && shown.mode() == PathMode.WAYPOINT) {
                passedWaypoints = Math.max(passedWaypoints, shown.waypointIndex());
            }
            if (shown != null && shown.mode() == PathMode.TO_SURFACE && !computing
                    && surfaceLegDone(mc.level, mc.player, currentGoal, shown)) {
                // Reached the surface. From here, replan toward the real goal
                // (keep showing the relay path until the new path arrives. Reverting just the mode first would make
                // the relay path's end = current feet count as "the end of the path" and wrongly register arrival)
                recalculate("reached the surface");
                return;
            }
            PathResult result = shown == null ? null : shown.result();
            if (checkArrival(mc.player, currentGoal, shown)) {
                return;
            }
            if (computing) {
                // Pause recalculation triggers while computing. Otherwise, for the few ticks until the async result
                // comes back, a search would be resubmitted every tick.
                return;
            }
            if (awaitingNavGraph) {
                // Whether it's been built is checked by the side that submits searches (recalculate). Until then, return without submitting anything
                recalculate("waiting for nav graph");
                return;
            }
            ticksSinceRecalc++;
            ticksSinceValidation++;
            if (stuckTracker.reason() != null
                    && !stuckTracker.retryDue(lastStart, mc.player.blockPosition(),
                            ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS)) {
                // Already decided the goal is unreachable. Resubmitting from the same spot gives the same result since neither loaded chunks
                // nor terrain have changed; on a real client this kept burning a 100k normal search + 200k coarse waypoint chain nodes
                // every 3 seconds. Wait until the player moves or enough time passes for the world to possibly change
                return;
            }
            if (pendingEscalation(mc.player)) {
                return;
            }
            // Placed before extension. With deep look-ahead extension runs for several ticks, so placing this after
            // would starve it the whole time, and we'd keep walking on a big picture planned with an incomplete map.
            // The relay leg (TO_SURFACE) doesn't use the long-range route, so it's excluded
            if ((shown == null || shown.mode() != PathMode.TO_SURFACE)
                    && redrawCoarseRouteForLoadedMap(currentGoal)) {
                return;
            }

            if (result == null || result.steps().isEmpty()) {
                retryWithoutRoute(mc.player.blockPosition());
                return;
            }
            // Replan only when sticking out of the path's band. Rebuilding for a sideways shift of 1-2 blocks
            // produces a different path every time and the line never settles (guidance changes just by walking)
            if (offPathDistance(mc.level, mc.player, result)
                    > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
                if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS
                        && !splice.trySplice(mc.level, mc.player, shown, 0)) {
                    // Only paths that can't be spliced get fully replanned
                    recalculate("deviated and couldn't splice");
                }
                return;
            }
            // Extension goes after the deviation and arrival checks. With deep look-ahead legs may be searched back to back, so
            // placing it first stops deviation detection the whole time (all triggers below stop while computing)
            // Extension isn't only for paths heading to waypoints. A path heading straight to the goal can also be extended
            // from its end if it was truncated by running out of budget; restricting this to WAYPOINT would drop such paths into
            // "replan when approaching a truncated end" below and <b>fully replace</b> them every time, redrawing even the guidance
            // ahead of you. Only the relay leg to the surface (TO_SURFACE) is excluded since its goal means something different
            if (shown.mode() != PathMode.TO_SURFACE) {
                int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
                if (extend.shouldExtend(mc.player, shown, renderRadius)) {
                    // Extend from the end. Replanning after arriving is too late: a search takes hundreds of ms and lands
                    // on a later tick still, so the whole time you'd be shown "a path that's already over".
                    //
                    // No interval gate here because extension doesn't change the path ahead. Back when it was a full
                    // replacement, raising the frequency turned straight into guidance flicker, but extension has no such side effect
                    extend.extendPath(shown);
                    return;
                }
                if (reachedPathEnd(mc.player, shown)) {
                    // Reached the end but couldn't extend = dead end or out of budget. Only now replan the whole thing.
                    //
                    // By the time we get here guidance has broken: replanning takes seconds, during which the player
                    // stands without guidance (real report: "reached the end of the route and the computation hasn't caught up").
                    // Without knowing <b>why extension didn't make it in time</b> there's no way to fix it, so
                    // record the refusal reason here. Fires only once per path (computing is set right after this)
                    LOGGER.debug("XaeroNav: Reached the end of the path; replanning (reason extension was refused={}, {} steps)",
                            extend.extendRefusal(mc.player, shown, renderRadius), shown.result().steps().size());
                    recalculate("reached the path end");
                    return;
                }
                // Re-solve the previous seam only on ticks with nothing to extend toward and no end reached. Extending guidance
                // further always takes priority; repair is only about the quality of a line already drawn
                if (!seamRepair.isEmpty() && seamRepair.tryRepair(mc.level, mc.player, shown, renderRadius)) {
                    return;
                }
                if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS
                        && reviewAgainstNavGraph(mc.player, currentGoal, shown)) {
                    return;
                }
            }
            if (ticksSinceRecalc >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()
                    && !result.complete() && nearPathEnd(mc.player.position(), result)
                    && retryTruncatedNow(mc.player)) {
                // Approached a truncated end. From here it can be extended using newly loaded chunks
                recalculate("approaching truncated end");
                return;
            }
            if (ticksSinceValidation >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
                // The world can change even if the player doesn't move. Periodically check only the cells on the path
                ticksSinceValidation = 0;
                // This periodic validation is the primary entry point for "terrain changed": it also looks ahead at parts not yet walked,
                // so even the user just placing a block somewhere on the path gets caught here immediately.
                // Without a reason, "I placed a block and the path vanished" can't be explained
                // (a user actually reported that, and this line confirmed it)
                //
                // Only <b>from the current step onward</b> is checked. Changes to sections already walked are irrelevant to the road ahead,
                // and scanning from there would stop at a change behind you and miss changes ahead.
                // Beyond the render distance it misfires during streaming right after goto, so it's not checked (see PathValidator)
                int validationHorizon = ClientCompat.renderDistance(mc.options) * 16;
                long validationLap = TickLaps.start();
                PathValidator.Failure failure = PathValidator.firstFailureFrom(mc.level, result,
                        PathProgress.INSTANCE.indexFor(result), mc.player.blockPosition(), validationHorizon);
                TickLaps.add("path validation", validationLap);
                if (failure != null) {
                    noteUnusableCell(failure);
                    handleBlockedPath(mc.level, mc.player, shown, failure);
                }
            }
        } finally {
            long publishLap = TickLaps.start();
            publishNavigationView();
            TickLaps.add("view update", publishLap);
        }
    }

    /**
     * A cell on the path no longer works due to a world change. If we can rebuild <b>just around the blocked spot</b>,
     * do that; only otherwise replan everything.
     *
     * <p>Placing blocks yourself to cross a bridge is intended, and they're commonly placed right on the path line. Treating that
     * as a reason to fully replan would rebuild a 280-step path wholesale on every move; there's no guarantee the replan
     * gives the same path (see {@link #pathWorthKeeping}), so guidance would become something else with every block placed.
     */
    private void handleBlockedPath(Level level, Player player, DisplayedPath shown, PathValidator.Failure failure) {
        if (splice.trySplice(level, player, shown, failure.stepIndex() + 1)) {
            // The detour isn't announced on the HUD. Everything past the splice point stays, so it's not "the road I was walking suddenly vanished",
            // and announcing it would just keep warning on every block placed while building a bridge
            LOGGER.debug("XaeroNav: A cell on the path changed; detouring around the blocked spot ({})", failure.reason());
            return;
        }
        // Couldn't detour; replan everything. The reason guidance suddenly changes would otherwise be unclear, so announce
        // the change itself
        LOGGER.debug("XaeroNav: A cell on the path changed; replanning ({})", failure.reason());
        rerouteNoticeTicks = REROUTE_NOTICE_TICKS;
        recalculate("cell on path changed");
    }

    /**
     * Picks up a "one-off retry" reservation set by a worker thread and resubmits. {@code true} if submitted.
     *
     * <p>Unlike the other recalculation triggers (deviation, reaching the end, periodic validation), these don't assume
     * the player moves. They're set when a search finds "the range wasn't enough" or "the budget wasn't enough", and
     * the side that set them has no next move, so if not picked up here the reservation is never consumed.
     *
     * @return whether a replan was submitted (if so, the remaining recalculation triggers needn't be checked)
     */
    private boolean pendingEscalation(Player player) {
        if (flying || computing) {
            return false;
        }
        if (stuckTracker.reason() != null
                && !stuckTracker.retryDue(lastStart, player.blockPosition(), ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS)) {
            return false;
        }
        if (pendingWideRetry) {
            // Couldn't reach with the normal margin. Resubmit with a wider range (no need to space it out:
            // the wide search happens only once per goal, and even if it fails a second pendingWideRetry isn't set)
            pendingWideRetry = false;
            recalculate(Escalation.WIDE, "scheduled retry");
            return true;
        }
        if (pendingDeepRetry) {
            // Ended unreached by hitting the expanded-node limit. First resubmit with a raised budget: measurements showed
            // giving the single search more budget is more reliable than splitting into legs (see DEEP_SEARCH_BUDGET_FACTOR).
            // Holding it as a reservation is the key; without it the deep search wouldn't run until another trigger (deviation,
            // approaching the end) happened to fire
            pendingDeepRetry = false;
            recalculate(Escalation.DEEP, "scheduled retry");
            return true;
        }
        if (pendingCoarseGuideRetry) {
            // Ended unreached by hitting the expanded-node limit. Widening the range would just hit the same limit, so
            // instead split into legs with the coarse waypoint chain and resubmit
            pendingCoarseGuideRetry = false;
            recalculate(Escalation.COARSE_GUIDED, "scheduled retry");
            return true;
        }
        RefinedRoute pendingRefined = pendingRefinedRouteReady;
        if (pendingRefined != null && pendingRefined.source() == coarseRoute) {
            // Layer-2 corridor refinement finished in the background. If still heading to a layer-1-based waypoint,
            // replan to switch to the refined version
            pendingRefinedRouteReady = null;
            refinedRoute = pendingRefined;
            recalculate("layer-2 refinement done");
            return true;
        }
        if (pendingRefined != null) {
            // A stale event whose coarse route was replaced after completion. Discard it without involving the current search.
            pendingRefinedRouteReady = null;
        }
        return false;
    }

    /**
     * Replans a long-range route planned with gaps in the map once loading has progressed. {@code true} if replanned.
     *
     * <p>The same decision in {@link #cachedOrFreshRoute} exists only in the {@code playerAnchored} branch, that is,
     * inside {@link #recalculate}, and <b>while walking a completable path without deviating,
     * {@code recalculate} never runs</b> (it runs only on deviation, reaching the end, truncated paths, changes on the path
     * and escalation; extension uses {@code playerAnchored=false}). In the real Nether, a big picture decided on a map
     * with 32% no-data and 20 unloaded regions stayed until the goal: unknown cells are nearly the cheapest in
     * {@link CoarseRouter}, so a big picture cutting through a not-yet-visible lava sea was chosen, and
     * layers 2 and 3 detoured around it, bloating the path. Load requests are also only issued inside {@link #freshRoute},
     * so without this the requests themselves stop after the first one.
     *
     * <p><b>Just calling {@link #freshRoute} isn't enough.</b> When the waypoint list is swapped the indices change
     * meaning, and {@link #extendPath}, which uses the displayed path's {@code waypointIndex} as a lower bound, sticks to the new list's
     * last entry = the goal. With {@code recalculate} the whole path is swapped, so indices don't disagree.
     */
    private boolean redrawCoarseRouteForLoadedMap(BlockPos currentGoal) {
        CoarseRoute before = coarseRoute;
        if (before == null || !before.goal().equals(currentGoal) || before.pendingRegions() == 0
                || coarseMapRetries >= COARSE_MAP_RETRY_LIMIT) {
            return false;
        }
        // Replanning mid-refinement gets the result discarded for an origin mismatch (same condition as cachedOrFreshRoute)
        if (refiningRoute != null || solvingCoarse != null || MonotonicTime.millis() < coarseMapRetryAfterMillis) {
            return false;
        }
        coarseMapRetries++;
        DisplayedPath shown = displayed;
        Minecraft mc = Minecraft.getInstance();
        boolean guided = shown != null && shown.mode() == PathMode.GOAL && navGraphGuide.latest(currentGoal) != null;
        // The replan result is logged on adoption (adoptCoarseRoute), whether solved synchronously or in the background
        mapRetryBefore = before;
        if (guided && mc.player != null) {
            // A path planned with the nav graph doesn't use layer-1 waypoints. A filled-in map only changes estimates outside the window and the HUD's dotted line,
            // so don't replan the path: that would redraw the line on every loading step (detours are caught by reviewing against the rebuilt guide)
            freshRouteInBackground(mc.player.blockPosition(), currentGoal,
                    XaeroNavConfig.INSTANCE.boatsEnabled() && ChunkView.boatAvailable(mc.player), true);
        } else {
            recalculate("map loading progressed");
        }
        if (guided) {
            // No path was submitted, so there's no reason to stop the caller's other triggers this tick
            return false;
        }
        return true;
    }

    /**
     * Whether the relay leg (up to the surface) is done and we may replan toward the real goal.
     *
     * <p>Judging by height alone, even a cave tunnel under a ceiling would count as "reached the surface", and from there
     * we'd go back to a path straight to the goal = the straight-line digging we wanted to avoid. Judge by whether the relay
     * is no longer needed (came out under the sky, or gave up on the relay around here).
     *
     * <p>Also check whether we're standing at the relay path's end. The surface check uses the heightmap on the search side
     * and {@code canSeeSky} here, and the two can disagree in places like under a glass roof.
     * Without checking the end, you'd be stuck standing there unable to move on to the next leg.
     */
    private boolean surfaceLegDone(Level level, Player player, BlockPos currentGoal, DisplayedPath shown) {
        BlockPos at = player.blockPosition();
        if (!shouldClimbToSurface(level, at, currentGoal, surfaceReferenceY(level, at))) {
            return true;
        }
        return reachedPathEnd(player, shown);
    }

    /**
     * Whether we've reached the end of the displayed path. Waypoints are chunk-center representative points and depending on terrain
     * you may not be able to stand right on top of them, so judge by the path's end rather than the goal itself.
     */
    private boolean reachedPathEnd(Player player, DisplayedPath shown) {
        List<PathStep> steps = shown.result().steps();
        return !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), XaeroNavConfig.INSTANCE.arrivalRadiusBlocks());
    }

    /**
     * Whether we've arrived at the goal. Arrival is when within {@code arrivalRadiusBlocks} both horizontally and vertically.
     *
     * <p>The goal may be a coordinate you can't reach even by digging (the path only extends to the spot
     * {@link net.prason.xaeronav.pathfinding.world.StanceFinder} snapped to). In that case arrival is judged against the end of the actually followable path.
     * Relay paths up to the surface and long-range route waypoints ({@link DisplayedPath#mode} not {@code GOAL}) are
     * excluded from this; they aren't the real goal, so reaching them isn't "arrival" here
     * ({@link #onClientTick} hands over to the next leg).
     */
    private boolean checkArrival(Player player, BlockPos currentGoal, DisplayedPath shown) {
        double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
        if (near(player, currentGoal, radius)) {
            arrive();
            return true;
        }
        if (shown == null || shown.mode() != PathMode.GOAL) {
            return false;
        }
        PathResult result = shown.result();
        List<PathStep> steps = result.steps();
        if (result.complete() && !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), radius)) {
            arrive();
            return true;
        }
        return false;
    }

    private static boolean near(Player player, BlockPos pos, double radius) {
        return horizontalDistanceSq(player, pos) <= radius * radius
                && Math.abs(pos.getY() - player.blockPosition().getY()) <= radius;
    }

    private static double horizontalDistanceSq(Player player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void arrive() {
        // Advance the generation so running searches' results can't revive the path
        generation.incrementAndGet();
        executor.cancelAll();
        computing = false;
        displayed = null;
        flight.dropRoute();
        arrivedTicks = 0;
        arrived = true;
        stuckTracker.clearReason();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(
                    //? if >=1.19.3 {
                    SoundEvents.NOTE_BLOCK_BELL.value(),
                    //?} else {
                    /*SoundEvents.NOTE_BLOCK_BELL,
                    *///?}
                    0.4f, 1.5f);
        }
    }

    /**
     * Retry when no path could be produced. For unreachable goals (across the sea, unloaded) every attempt searches to the limit
     * and fails, so without spacing it out we'd just repeat the same computation every few seconds.
     */
    /**
     * Whether a normal search from this spot is certain to run out of budget. If so, skip the normal search and
     * solve with the coarse waypoint chain from the start.
     *
     * <p>On a real client (End island hopping) the normal search <b>never succeeded once</b>, and every cycle repeated "a normal search
     * that burns 300k nodes and fails -> coarse waypoint chain". While paying 1-1.5 s for a search we know will be discarded,
     * guidance stays stale and meanwhile the player drifts away from the path.
     *
     * <p>Expires after walking {@link #PLAIN_RETRY_MOVE_BLOCKS}: replanning from the same spot gives the same result,
     * but once the terrain changes the normal search can solve it (same idea as {@link #retryTruncatedNow}).
     */
    private boolean plainSearchHopeless(BlockPos start) {
        BlockPos exhausted = plainBudgetExhaustedAt;
        return exhausted != null
                && exhausted.distSqr(start) < PLAIN_RETRY_MOVE_BLOCKS * PLAIN_RETRY_MOVE_BLOCKS;
    }

    /**
     * Whether it's time to replan a truncated path.
     *
     * <p><b>Replanning from the same spot gives the same result.</b> Fully replacing every 2 seconds while neither loaded chunks
     * nor terrain have changed only makes the path appear to change often and gains nothing; replanning
     * means something when new chunks have loaded, that is, when the player has moved.
     *
     * <p>Only if a long time passes without moving might the world itself have changed, so try at a loose interval
     * (same idea as what {@link #retryWithoutRoute} does when there's no path at all).
     */
    private boolean retryTruncatedNow(Player player) {
        BlockPos start = lastStart;
        boolean moved = start == null
                || start.distSqr(player.blockPosition()) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        return moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS;
    }

    /**
     * Feeds this search's result into the stuck decision (see {@link StuckTracker}). Here we only derive the two conditions
     * {@link StuckTracker} needs (whether a completed ground path is still displayed, and whether
     * layer 1 fails to reach the goal) from this state machine's {@code displayed}/{@code coarseRoute}.
     */
    private void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result) {
        BlockPos currentGoal = goal;
        if (currentGoal == null) {
            return;
        }
        DisplayedPath shown = displayed;
        boolean hasCompleteGroundRoute = shown != null && shown.mode() != PathMode.TO_SURFACE
                && shown.result().complete() && !shown.result().steps().isEmpty();
        CoarseRoute route = coarseRoute;
        boolean routeUnmapped = route != null && route.goal().equals(currentGoal) && !route.reachedGoal();
        stuckTracker.noteOutcome(start, planEnd, currentGoal, hasCompleteGroundRoute, result, routeUnmapped, () -> {
            voxelGuide.noteStalled();
            navGraphGuide.noteStalled();
        });
    }

    private void retryWithoutRoute(BlockPos start) {
        if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            return;
        }
        boolean moved = lastStart == null
                || lastStart.distSqr(start) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        if (moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS) {
            recalculate("no path, retrying");
        }
    }

    /** Whether a retry reservation and this goal are "the same place". See {@link #RETRY_TARGET_TOLERANCE_BLOCKS}. */
    private static boolean sameRetryTarget(BlockPos target, BlockPos reserved) {
        return reserved != null && horizontalDistance(target, reserved) <= RETRY_TARGET_TOLERANCE_BLOCKS;
    }

    /**
     * Logs one line if the replan's result ends farther from the goal than the displayed path (or is empty).
     *
     * <p>Most recalculations don't log a reason, and swapping one partial path for another is outside {@link #pathWorthKeeping}'s
     * protection, so no line is left at all. Without this, "the path vanished" can't be traced back to "which trigger, which search,
     * replaced what with what". Replacements that move forward are ordinary events, so they aren't logged.
     */
    private void noteRouteRegression(String trigger, Escalation forced, BlockPos start, BlockPos currentGoal,
            PathResult replacement) {
        DisplayedPath before = displayed;
        if (before == null || before.result().steps().isEmpty()) {
            return;
        }
        PathResult old = before.result();
        double oldLeft = horizontalDistance(endOf(old, start), currentGoal);
        double newLeft = horizontalDistance(endOf(replacement, start), currentGoal);
        if (!replacement.steps().isEmpty() && newLeft <= oldLeft + ROUTE_REGRESSION_LOG_BLOCKS) {
            return;
        }
        LOGGER.debug("XaeroNav: Replan made the path regress (reason={}, retry={}, start={}, "
                        + "before={} steps/{}/{}/end to goal {}, new={} steps/{}/{}/end to goal {}, expanded={})",
                trigger, forced, start.toShortString(),
                old.steps().size(), old.complete() ? "complete" : "partial", before.mode(), Math.round(oldLeft),
                replacement.steps().size(), replacement.complete() ? "complete" : "partial", replacement.termination(),
                Math.round(newLeft), replacement.expandedNodes());
    }

    /** The point the path actually reached. The start itself if it couldn't advance a single step. */
    static BlockPos endOf(PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        return steps.isEmpty() ? start : steps.get(steps.size() - 1).pos();
    }

    static double distanceTo(Vec3 position, BlockPos pos) {
        double dx = pos.getX() + 0.5 - position.x;
        double dy = pos.getY() - position.y;
        double dz = pos.getZ() + 0.5 - position.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Height from the feet down to the ground directly below (blocks). If there's no ground within {@link #LANDING_GROUND_SEARCH_BLOCKS},
     * returns a number larger than that.
     */
    private static int groundClearance(Level level, Player player) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        int y = player.blockPosition().getY();
        for (int dy = 0; dy <= LANDING_GROUND_SEARCH_BLOCKS; dy++) {
            cursor.set(x, y - dy, z);
            if (cursor.getY() < GameCompat.minBuildHeight(level)) {
                break;
            }
            // Count water surfaces as places you can come down too. Since {@code StanceFinder#isStance} accepts water cells
            // as stances, above a sea or lake isn't "no ground" but "you can come down there";
            // it used to look only at solids, so while flying over water, guidance wasn't handed over to walking even when
            // the goal came within 48 blocks
            long flags = CellData.flagsOf(level.getBlockState(cursor));
            if (CellData.standable(flags) || CellData.water(flags)) {
                return dy;
            }
        }
        return LANDING_GROUND_SEARCH_BLOCKS + 1;
    }

    /**
     * Whether we're in the air in a state where a ground path is meaningless. Includes not just elytra gliding but also creative and
     * spectator flight.
     *
     * <p>In both cases "the player can see and steer through the sky themselves", so no obstacle-avoiding path is needed, and with
     * no floor underfoot {@code StanceFinder.resolveStart} can't resolve a start anyway; left running,
     * it would keep submitting searches every 2 seconds with not a single edge out of the start node, and the HUD would keep showing "no path"
     * (it <b>looks like a failure</b> rather than an intentional stop).
     *
     * <p>{@code getAbilities().flying} is state the client itself owns; both the double-jump toggle and
     * the automatic release on landing are written by {@code LocalPlayer#aiStep} within the same tick. For spectators,
     * {@code GameType#updatePlayerAbilities} and {@code MultiPlayerGameMode#isAlwaysFlying}
     * always pin it to true, so this one check captures every flight mode. <b>No height requirement is applied here</b>:
     * you genuinely can't stand, so a grace period would keep submitting searches from a start with no floor.
     *
     * <p>Only the elytra ({@code isFallFlying}) gets a dampened check. Switching is costly
     * (advance {@code generation} and discard running searches; on landing, clear the displayed path and replan),
     * so the dampening is the responsibility of {@link ElytraTrigger}.
     */
    private boolean airborne(Level level, Player player) {
        if (GameCompat.abilities(player).flying) {
            elytraTrigger.reset();
            return true;
        }
        if (!player.isFallFlying()) {
            return elytraTrigger.update(false, 0, 0);
        }
        int required = XaeroNavConfig.INSTANCE.elytraFlyingMinGroundClearanceBlocks();
        // If the setting doesn't care about height, scanning straight down is wasted work
        int clearance = required <= 0 ? 0 : groundClearance(level, player);
        return elytraTrigger.update(true, clearance, required);
    }

    /**
     * Maximum horizontal distance the detail search aims at in one go. Goals farther than this get long-range route waypoints inserted.
     *
     * <p>The key is that it's <b>a fixed value independent of terrain</b>. It used to measure and use the distance recent searches
     * actually planned ({@code detailReach}), but a value measured on explored terrain around the player was also applied to searches
     * extending from the path's end into unexplored terrain, so success and failure alternated and it never converged.
     * When it falls short, the partial path is used for guidance as is and extended from its end.
     *
     * <p>It's capped by the render distance because a target inside unloaded chunks
     * can't be reached (unloaded cells are impassable).
     */
    static int detailHorizon(int renderRadius) {
        return Math.min(renderRadius, XaeroNavConfig.INSTANCE.detailHorizonBlocks());
    }

    private boolean nearPathEnd(Vec3 position, PathResult result) {
        PathStep last = result.steps().get(result.steps().size() - 1);
        double dx = last.pos().getX() + 0.5 - position.x;
        double dy = last.pos().getY() - position.y;
        double dz = last.pos().getZ() + 0.5 - position.z;
        return dx * dx + dy * dy + dz * dz <= EXTEND_DISTANCE_BLOCKS * EXTEND_DISTANCE_BLOCKS;
    }

    /**
     * How this recalculation changes the way the search is built.
     *
     * <p>Previously it remembered "the coordinates of the goal that couldn't be reached last time" and escalated only on an <b>exact match</b>
     * with the newly chosen goal. But the detail-target is re-interpolated from the player position onto the route,
     * so one block of walking gives different coordinates; by the tick after the reservation it no longer matched,
     * and it became a loop that just kept running the normal search (in a real log, while "hit the expanded-node limit"
     * repeated 20-30 times at 0.5-0.7 s intervals, the coarse waypoint chain ran only once).
     *
     * <p>Escalation is a decision that "<b>in this situation, change how the search is built</b>", not "this goal can't be reached",
     * so it needn't depend on goal identity at all. The deciding side passes it as an argument.
     */
    private enum Escalation {
        NONE,
        /** Widen the box to the full render distance. For when paths making a big detour around walls or lakes fell outside the range. */
        WIDE,
        /**
         * Keep the search construction, just raise the budget. The <b>first</b> move when the expanded-node limit was hit.
         * See {@link #DEEP_SEARCH_BUDGET_FACTOR}.
         */
        DEEP,
        /** Don't widen the box; split into legs with the coarse waypoint chain. For when even the deep budget couldn't reach. */
        COARSE_GUIDED
    }

    /** @param trigger the reason for the replan. Used only in the log when the path regresses or disappears ({@link #noteRouteRegression}) */
    private void recalculate(String trigger) {
        recalculate(Escalation.NONE, trigger);
    }

    private void recalculate(Escalation forced, String trigger) {
        long lap = TickLaps.start();
        try {
            recalculateNow(forced, trigger);
        } finally {
            TickLaps.add("recalculation", lap);
        }
    }

    private void recalculateNow(Escalation forced, String trigger) {
        ticksSinceRecalc = 0;
        ticksSinceValidation = 0;
        // A full replan also discards the seams along with the path ahead
        seamRepair.dropPending();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = this.goal;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        BlockPos start = player.blockPosition();
        lastStart = start;
        boolean boatAvailable = XaeroNavConfig.INSTANCE.boatsEnabled() && ChunkView.boatAvailable(player);

        int surfaceY = surfaceReferenceY(level, start);
        boolean climbing = shouldClimbToSurface(level, start, currentGoal, surfaceY);
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // This can only be decided on the main thread (world access, mapping onto the path). By the time the result comes back
        // the inputs would be different, so snapshot the answer at submit time and pass it to the worker
        DisplayedPath worthKeeping = pathWorthKeeping(level, player);
        WindowField keepGuide = navGraphGuide.latest(currentGoal);
        boolean mayAwait = !climbing && mayAwaitNavGraph(start, currentGoal, renderRadius);
        if (mayAwait && XaeroPresence.mapPresent()) {
            // Prepare the out-of-window estimate (layer 1) and the HUD's dotted line while waiting. Once the graph is built it'd be too late for the first guide
            prepareCoarseRoute(start, currentGoal, boatAvailable, true, true);
        }
        GoalGuide goalGuide = goalGuide(level, player, start, currentGoal, renderRadius, climbing);
        boolean navGraphGuided = goalGuide != null && goalGuide.navGraph();
        awaitingNavGraph = mayAwait && !navGraphGuided && navGraphGuide.latest(currentGoal) == null
                && !navGraphGuide.failedRecently()
                && MonotonicTime.millis() - navGraphWaitStartedMillis <= NAV_GRAPH_WAIT_MILLIS;
        if (awaitingNavGraph) {
            return;
        }

        // Surface-first navigation takes top priority (the other way round would dig through the ground toward a long-range waypoint).
        // Otherwise, insert a long-range route waypoint only when the goal is outside the render distance
        PathMode mode;
        BlockPos target;
        int waypointIndex;
        int goalRadius;
        if (climbing) {
            mode = PathMode.TO_SURFACE;
            // During surface-first navigation, widening to the box of the distant real goal is pointless (the goal isn't one point
            // but "anywhere under the sky"). To make sure the vertical range reaches the surface, build the range as a virtual goal
            // at that height in the same column
            target = new BlockPos(start.getX(), surfaceY, start.getZ());
            waypointIndex = -1;
            // searchToSurface uses its own region goal (y >= surfaceY), so this value isn't read
            goalRadius = 0;
        } else {
            DetailTarget detail = landingOr(goalGuide, currentGoal, selectDetailTarget(start, currentGoal, renderRadius,
                    detailHorizon(renderRadius), boatAvailable, true, -1,
                    level.dimensionType().hasCeiling(), navGraphGuided));
            target = detail.target();
            mode = target.equals(currentGoal) ? PathMode.GOAL : PathMode.WAYPOINT;
            waypointIndex = detail.waypointIndex();
            goalRadius = detail.goalRadius();
        }

        // The search range is cut at the render distance. Outside loaded chunks nothing can be read, so widening that far
        // would only crawl over cells treated as unloaded. At the same time, on machines with a lowered render distance
        // the search load drops automatically.
        // In exchange, take a wider area around yourself. A cave exit isn't necessarily toward the goal, and with the normal
        // margin the exit itself falls outside the range. This leg is searched with digging disabled, so passable cells
        // are narrowed to open space only, and widening the range barely increases expansions
        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        int horizontalMargin = tuning.searchHorizontalMargin();
        boolean wideSearch = false;
        boolean coarseGuided = false;
        if (climbing) {
            horizontalMargin *= SURFACE_SEARCH_MARGIN_FACTOR;
        } else if ((forced == Escalation.WIDE || sameRetryTarget(target, wideSearchNeededTarget))
                && horizontalDistance(start, target) <= renderRadius) {
            // Last time, this search goal couldn't be reached with the normal margin. Chunks are loaded across the whole
            // renderRadius square, so widen the box that was cut at the normal margin (default 64) up to there and
            // retry. Addresses paths that make a big detour around walls or lakes not appearing just because they're
            // "outside the search range"
            horizontalMargin = renderRadius;
            wideSearch = true;
        } else if ((forced == Escalation.COARSE_GUIDED || sameRetryTarget(target, coarseGuideNeededTarget))
                && horizontalDistance(start, target) <= renderRadius) {
            // Split into legs with the coarse waypoint chain (layer 3's local-obstacle measure).
            //
            // <b>Widen the box too.</b> It used not to be widened on the grounds that "widening just hits the same limit", but
            // that was about a different case where paths making a big detour around a single obstacle (lake, wall) fell outside the range:
            // there's only one road there, so widening the box ends up searching for the same-length detour with the same
            // limit. This time the failure had a different shape: on a real client (an End cliff edge,
            // 2026-08-28), the box with the default horizontal margin (64) didn't contain the stepping-stone islands to detour by, and
            // layer 1's coarse map (LiveCoarseSampler, which only sees the same box as `view`) could only produce an unsolvable route
            // straight across the void. Only attempts with a slightly wider box (known cells 182/168)
            // saw those stepping stones; waypoints went from 2 to 3 and it succeeded in one go (51,000+17,000 nodes,
            // while the straight route failed at 300,000 nodes every time).
            //
            // Widening costs almost nothing: `ChunkView.capture` <b>only picks up already loaded
            // chunks</b> via `getChunkNow` and forces no new loads. If the stepping stones were merely outside the range,
            // widening makes them visible immediately without waiting for more chunks to load.
            //
            // <b>It's fine to widen all the way to renderRadius.</b> Doing so at first had the side effect on a real client of one leg's search
            // growing from 0.7-0.9 s to 1.4-1.6 s, but the cause was
            // `PathfindingExecutor#legCoarseMap` (each leg's cost guide reused the wide box used for planning the
            // leg split as is; the guide's Dijkstra has a state count proportional to the box's area
            // and is paid once per leg, so the widened box's burden multiplied), which has been fixed.
            // The guide is rebuilt on a narrow leg-specific box, so widening here doesn't increase the per-leg burden
            horizontalMargin = renderRadius;
            coarseGuided = true;
        }
        boolean aimingAtLanding = goalGuide != null && goalGuide.landing() != null
                && target.equals(goalGuide.landing().target());
        SearchBounds bounds = navGraphGuided
                ? navGraphBounds(level, start, target, start, renderRadius, horizontalMargin, aimingAtLanding,
                        goalGuide.costToGo())
                : SearchBounds.around(level, start, target, horizontalMargin, verticalSearchMargin(level, wideSearch),
                        renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("chunk capture", captureLap);
        if (!climbing) {
            noteTargetStandability(view, target, mode, waypointIndex);
        }

        SearchLimits limits = navGraphGuided ? navGraphLimits(tuning.searchLimits()) : tuning.searchLimits();
        // Here we know the normal budget can't solve it. <b>Raise the budget rather than escaping to leg splitting.</b>
        // In measurements (RealEndTerrainTest, real save data), leg splitting used twice the nodes on the same terrain
        // and was slower, and a single search simply given more budget was more reliable
        // (direct: reached with 600,000, 532,724 nodes / leg splitting needed 800,000, 628,593 nodes)
        SearchLimits deepLimits = new SearchLimits(limits.maxExpandedNodes() * DEEP_SEARCH_BUDGET_FACTOR,
                Math.min(DEEP_SEARCH_MAX_MILLIS, limits.timeLimitMillis() * DEEP_SEARCH_BUDGET_FACTOR),
                limits.heuristicWeight());
        boolean deepBudgetOnly = !climbing && (forced == Escalation.DEEP || plainSearchHopeless(start));
        // The first attempt, when it's not yet known whether the normal budget suffices, lands here. Try the deep budget in parallel too
        // (see PathfindingExecutor#submitWithDeepFallback); serially it would "wait until the normal budget is confirmed
        // exhausted, then resubmit the deep budget next tick", adding the two times together
        // (3.7 s measured). Starting both at once, the deep one is nearly done by the time the normal budget
        // is settled (about half, 1.9 s measured)
        boolean deepBudgetInParallel = !climbing && !coarseGuided && !deepBudgetOnly;
        if (deepBudgetOnly) {
            limits = deepLimits;
        }
        long myGeneration = generation.incrementAndGet();
        computing = true;
        PathMode finalMode = mode;
        BlockPos finalTarget = target;
        int finalWaypointIndex = waypointIndex;
        boolean finalWideSearch = wideSearch;
        boolean finalCoarseGuided = coarseGuided;
        // Even on rounds using the parallel fallback, no result means the same as having tried up to the deep budget
        // (it fell short including the deep side, not just the normal budget). This aligns the decision on the next escalation step
        // (coarse waypoint chain, or retrying from the deep budget)
        boolean finalDeepBudget = deepBudgetOnly || deepBudgetInParallel;
        // Settle the dimension on the main thread. whenComplete runs on a worker thread, and
        // by then the player may already have moved to another dimension
        ResourceKey<Level> searchDimension = level.dimension();
        CompletableFuture<PathResult> future;
        boolean costToGoGuideEnabled = tuning.costToGoGuideEnabled();
        CostToGo prepared = preparedGuide(goalGuide, currentGoal, finalTarget);
        // Don't let this search pick cells that recently failed re-verification either. Otherwise the replanned
        // path would go through the same cell again and immediately be judged invalid (see {@link RecentFailures})
        List<BlockPos> avoided = recentFailures.avoided();
        if (climbing) {
            future = executor.submitToSurface(AvoidedCellSource.wrap(view.withoutDigging(), avoided),
                    AvoidedCellSource.wrap(view, avoided), start, surfaceY, limits);
        } else if (coarseGuided) {
            future = executor.submitCoarseGuided(AvoidedCellSource.wrap(view, avoided), bounds, start,
                    finalTarget, limits, costToGoGuideEnabled, goalRadius);
        } else if (deepBudgetInParallel) {
            // The deep budget runs concurrently on another thread, so don't let them share the cell cache
            future = executor.submitWithDeepFallback(AvoidedCellSource.wrap(view, avoided),
                    AvoidedCellSource.wrap(view.forParallelSearch(), avoided), start, finalTarget,
                    qualityLimits(limits), deepLimits, costToGoGuideEnabled, goalRadius, prepared);
        } else {
            future = executor.submit(AvoidedCellSource.wrap(view, avoided), start, finalTarget, limits,
                    costToGoGuideEnabled, goalRadius, Carryover.NONE, prepared);
        }
        generationGate.whenStillCurrent(future, myGeneration, TickLaps.timed("receive/recalculation", (result, error) -> {
            try {
                computing = false;
                if (error != null) {
                    // Cancellation is a normal way to finish (replaced by a new request)
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: Pathfinding failed", error);
                    }
                    return;
                }
                // Once a search has finished, the retry reservations left by the previous search are spent. Re-set them later if needed
                pendingWideRetry = false;
                pendingCoarseGuideRetry = false;
                pendingDeepRetry = false;
                if (!result.complete() && LOGGER.isDebugEnabled()) {
                    // A path falls short of the goal either because the search was cut off or because there's really no way.
                    // Without logging the expanded node count there's no way to verify the effect of raising or lowering
                    // maxExpandedNodes, and "why does the line stop halfway" can't be answered
                    LOGGER.debug("XaeroNav: Path search ended without reaching the goal (coarse waypoint chain={}, expanded nodes={}, limit={}, steps={})",
                            finalCoarseGuided, result.expandedNodes(), XaeroNavConfig.INSTANCE.maxExpandedNodes(),
                            result.steps().size());
                }
                if (finalCoarseGuided) {
                    // Without a way to confirm the coarse waypoint chain actually fired and what it produced,
                    // we can't tell "it fired but wasn't enough" from "it never fired".
                    // It only happens when the expanded-node limit was hit, so no debug gate does no real harm
                    //
                    // Placeability and bridge count are logged to tell apart, when stopped by lava, "has no blocks",
                    // "bridged but not enough" and "not a single bridge generated".
                    // With no blocks in the hotbar, lava bridges can't be generated at all in principle
                    //
                    // The termination reason is logged because "not enough resources" and "no way within range"
                    // can't be told apart from reached=false. The former is solved by picking a nearer goal, while
                    // the latter is the same no matter how often you try; the remedies are completely different
                    LOGGER.debug("XaeroNav: Retried with the coarse waypoint chain"
                                    + " (target={}, reached={}, {}, expanded nodes={}, steps={}, can place={}, bridges={})",
                            finalTarget.toShortString(), result.complete(), result.termination(),
                            result.expandedNodes(), result.steps().size(),
                            view.canPlaceBlocks(), result.steps().stream().filter(PathStep::bridging).count());
                }
                if (finalMode == PathMode.TO_SURFACE && (!result.complete() || result.steps().isEmpty())) {
                    // Don't show a relay path that didn't reach the surface. Following it won't get you to the surface,
                    // so guiding partway just leads to the same unreached path being planned again further on.
                    // A relay that doesn't advance a single step (= already on the surface from the search's view) is treated the same. Both
                    // give up on the relay around here and head straight to the real goal (replanned next tick)
                    surfaceLegFailedAt = start;
                    PathResult withheld = new PathResult(List.of(), result.termination(),
                            result.expandedNodes(), result.distinctNodes(), result.limitsHeld());
                    noteRouteRegression(trigger, forced, start, currentGoal, withheld);
                    displayed = new DisplayedPath(withheld, PathMode.TO_SURFACE, -1);
                    return;
                }
                // Only the relay leg (TO_SURFACE) is excluded. Its goal isn't one point but "anywhere under the sky", so
                // unreached doesn't mean the range was narrow, and the horizontal margin has its own multiplier
                if (finalMode != PathMode.TO_SURFACE) {
                    // If the reason for not reaching was the expanded-node limit, widening the box just hits the same limit
                    // the same way and the result doesn't change (confirmed on a real client: expanded node counts matched between the normal margin
                    // and after widening, and both were cut off exactly at the limit). Widening is pointless in that case, so
                    // don't treat it as "needs a wide range", so we don't repeat a pointless widened search on every recalculation
                    //
                    // If finalCoarseGuided is true (this tick already was the coarse waypoint chain), don't escalate
                    // any further. The summed expandedNodes over several legs can't simply be compared with a single search's limit,
                    // and setting it true again here would try the same coarse waypoint chain next tick,
                    // fail for the same reason again, and so on, bouncing forever. Escalation stops at one step
                    //
                    // PathResult carries the termination reason. Back when only the expansion count was checked, a search where the 2 s time
                    // limit kicked in first was misjudged as "not out of budget" = range too narrow, and a pointless box widening was chosen
                    // instead of the coarse waypoint chain (and detailReach wasn't updated either)
                    boolean budgetExhausted = !finalCoarseGuided && result.budgetExhausted();
                    if (!finalCoarseGuided) {
                        // Whether the normal search ran out of budget here feeds the next recalculation's decision on "may we skip the
                        // normal search" (plainSearchHopeless). Not rewritten by the coarse waypoint chain's result;
                        // its success or failure says nothing about the normal search's prospects
                        plainBudgetExhaustedAt = budgetExhausted ? start : null;
                    }
                    // Write the distance limit before the retry reservation flags. The client thread enters recalculate the instant
                    // after it sees pendingCoarseGuideRetry, reads detailReach there and re-picks the
                    // target. In the reverse order the narrowed limit wouldn't make it in time, and the coarse waypoint chain (4x a
                    // single search's budget) would run on a target already known to be unreachable.
                    //
                    // The coarse waypoint chain also used up its compute. It's summed per leg so hitNodeBudget
                    // doesn't catch it, but its trigger condition is "the previous one hit the node limit", so running out of budget is certain
                    logSearchReach(start, finalTarget, result);
                    // Only set a retry reservation when it can actually fire. Both retries assume a goal within renderRadius
                    // (the box-widening side widens to renderRadius; the coarse waypoint chain side can only build
                    // a coarse map from loaded chunks). Setting a reservation whose trigger condition
                    // doesn't pass would loop forever, redoing the same search next tick and re-setting the same reservation
                    boolean retryTargetInBox = horizontalDistance(start, finalTarget) <= renderRadius;
                    boolean needsWideRetry = !result.complete() && !budgetExhausted && !finalCoarseGuided
                            && retryTargetInBox;
                    // Escape to leg splitting only when <b>even the deep budget wasn't enough</b>. The order is the point:
                    // the first answer to an insufficient budget is "raise the budget"; in measurements (RealEndTerrainTest),
                    // on the same terrain leg splitting used twice the nodes and was slower, while giving a single search more budget was
                    // more reliable. Arriving here without the deep budget in between, leg splitting fails from the same lack of budget
                    boolean needsDeepRetry = !result.complete() && budgetExhausted && !finalCoarseGuided
                            && !finalDeepBudget && retryTargetInBox;
                    // Don't escape to leg splitting while searching with the nav graph guide. Leg splitting doesn't receive that guide
                    // (submitCoarseGuided) and aims with per-leg coarse maps, so it can only produce worse guidance than the guided deep budget;
                    // in the real Nether 8 out of 9 were unreached, each taking 10-13 s, and the result was cut off short of the path it replaced
                    boolean needsCoarseGuideRetry = !result.complete() && budgetExhausted && !finalCoarseGuided
                            && finalDeepBudget && retryTargetInBox && !navGraphGuided;
                    // Revert to the normal margin on success or when widening was useless. Set pendingWideRetry after this
                    // write (the client thread reads wideSearchNeededTarget after seeing pendingWideRetry)
                    wideSearchNeededTarget = needsWideRetry ? finalTarget : null;
                    pendingWideRetry = needsWideRetry && !finalWideSearch;
                    coarseGuideNeededTarget = needsCoarseGuideRetry ? finalTarget : null;
                    pendingCoarseGuideRetry = needsCoarseGuideRetry;
                    pendingDeepRetry = needsDeepRetry;
                    if (needsCoarseGuideRetry && LOGGER.isDebugEnabled()) {
                        // A line that only sets a reservation stays at debug. What actually happened is recorded, target included,
                        // by "Retried with the coarse waypoint chain" a second later; on terrain where running out of budget
                        // is the norm (Nether), logging this at INFO would line up the same content 3 lines every 3 seconds
                        LOGGER.debug("XaeroNav: Hit the expanded-node limit; trying the coarse waypoint chain next tick"
                                + " (target={})", finalTarget.toShortString());
                    }
                    // While a retry reservation remains we haven't exhausted our options yet. The stuck decision waits until
                    // everything possible for this search is done (otherwise a situation that escalation
                    // should solve would be prematurely declared stuck, stopping its retry along with it)
                    if (!pendingWideRetry && !pendingCoarseGuideRetry && !pendingDeepRetry) {
                        long outcomeLap = TickLaps.start();
                        noteSearchOutcome(start, endOf(result, start), result);
                        TickLaps.add("stuck decision", outcomeLap);
                    }
                }
                if (!result.complete() && worthKeeping != null && displayed == worthKeeping) {
                    if (worthKeeping.result().complete()) {
                        // A completed path is proof that "you can actually walk from here to there", and an unreached result
                        // carries no such proof. Don't overwrite it with something that has no proof (see pathWorthKeeping)
                        LOGGER.debug("XaeroNav: Kept the completed path (displayed={} steps, new result={} steps, {})",
                                worthKeeping.result().steps().size(), result.steps().size(), result.termination());
                        return;
                    }
                    long compareLap = TickLaps.start();
                    PartialProgress kept = PartialProgress.compare(worthKeeping.result(), result, start, currentGoal,
                            keepGuide);
                    TickLaps.add("partial comparison", compareLap);
                    if (kept.oldAhead()) {
                        // Between two partial paths, the one planned closer to the goal is better guidance. A search out of budget
                        // is cut off at a different place each replan, so swapping without comparing would shrink a path that reached
                        // 16 blocks from the goal into one cut off 145 blocks short (real Nether)
                        LOGGER.debug("XaeroNav: Kept the partial path (displayed={} steps, new result={} steps, {}, "
                                        + "remaining from end displayed={} new={}, yardstick={})",
                                worthKeeping.result().steps().size(), result.steps().size(), result.termination(),
                                Math.round(kept.oldLeft()), Math.round(kept.newLeft()), kept.yardstick());
                        return;
                    }
                }
                if (leadsBackward(result, currentGoal)) {
                    // Look at how the player progresses, not at the path itself. If following the guidance
                    // takes you farther from the goal, it doesn't work as guidance
                    return;
                }
                // Whether splicing onto the new path works gets measured afresh. Failure records from the previous path aren't carried over
                splice.clearBlock();
                long shapeLap = TickLaps.start();
                RouteExplain.log("recalculation:" + trigger, level, start, finalTarget, currentGoal, result, prepared,
                        goalGuide == null ? null : goalGuide.costToGo(), view,
                        tuning.movementOptions(), renderRadius);
                TickLaps.add("shape check", shapeLap);
                long regressionLap = TickLaps.start();
                noteRouteRegression(trigger, forced, start, currentGoal, result);
                TickLaps.add("regression check", regressionLap);
                displayed = new DisplayedPath(result, finalMode, finalWaypointIndex);
            } finally {
                publishNavigationView();
            }
        }));
    }

    /**
     * Whether the current path may be kept <b>when this recalculation's result is unreached</b>. The path if it may be kept,
     * {@code null} if not.
     *
     * <p>A completed path is proof that "you can actually walk from here to there", and an unreached path carries no such
     * proof. Throwing away proof for a result without proof is irreversible: the detail search's goal
     * wobbles a little on every recalculation ({@link #resolveWaypointOnSurface}, interpolation from the player position), so
     * there's no guarantee of hitting the same path again. On a real client (End island hopping) a completed
     * 128-step path with 21 bridges was replaced 3 seconds later by an unreached 93-step one, then degraded monotonically
     * 78→39→15. Worse, {@code trimUnfinishedPlacements} drops trailing placements from unreached paths,
     * so what it was replaced with is a stump without a single bridge.
     *
     * <p>Partial paths are candidates too. They carry no proof, so they aren't kept unconditionally; only kept when planned
     * closer to the goal than the new result ({@link PartialProgress}).
     *
     * <p>It can be kept only while the proof still holds: your feet are still within the path's band (if you deviated,
     * it's no longer your path), you haven't reached the end yet (if you have, what you need is
     * the next leg, and keeping it would leave guidance stalled), and the world hasn't changed either. The relay leg
     * ({@code TO_SURFACE}) is excluded since its goal means something different.
     */
    private DisplayedPath pathWorthKeeping(Level level, Player player) {
        DisplayedPath shown = displayed;
        if (shown == null || shown.mode() == PathMode.TO_SURFACE) {
            return null;
        }
        PathResult result = shown.result();
        if (result.steps().isEmpty()) {
            return null;
        }
        // Deciding to let go of a completed path carries the risk this path can never be hit again. Without the reason
        // for letting go, when guidance gets worse we can't pin down "which trigger broke it" from real-world logs
        // (the reason for the recalculation itself isn't logged anywhere). It only happens on recalculations while holding a
        // completed path, so even without a debug gate the lines don't pile up
        boolean tracked = PathProgress.INSTANCE.tracking(result);
        double offPath = tracked ? offPathDistance(level, player, result)
                : distanceToPath(result, player.position());
        String dropped = null;
        // Log "terrain changed" down to the details. Without knowing what failed at which step,
        // we can't trace the cause of symptoms like a completed route being let go right after crossing
        PathValidator.Failure validationFailure = null;
        if (offPath > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
            dropped = "deviated";
        } else if (reachedPathEnd(player, shown)) {
            dropped = "reached the end";
        } else if ((validationFailure = PathValidator.firstFailureFrom(level, result,
                PathProgress.INSTANCE.indexFor(result), player.blockPosition(),
                ClientCompat.renderDistance(Minecraft.getInstance().options) * 16)) != null) {
            // Don't let go over changes in sections already walked. Breaking the bridge you crossed from behind
            // doesn't invalidate the proof that the road ahead is usable
            dropped = "terrain changed";
        }
        if (dropped != null && !result.complete()) {
            // Letting go of a partial path is ordinary (happens on every deviation), so do it silently
            return null;
        }
        if (dropped != null) {
            if (validationFailure != null) {
                noteUnusableCell(validationFailure);
            }
            LOGGER.debug("XaeroNav: Let go of the completed path"
                            + " (reason={}, {} steps, distance to path={}, tracked={}, position={}, path start={}{})",
                    dropped, result.steps().size(), Math.round(offPath), tracked,
                    player.blockPosition().toShortString(), result.steps().get(0).pos().toShortString(),
                    validationFailure == null ? "" : ", " + validationFailure.reason());
            return null;
        }
        return shown;
    }

    /**
     * Remembers a cell the search judged usable but that failed re-verification. The next search avoids it
     * (see {@link RecentFailures}).
     */
    private void noteUnusableCell(PathValidator.Failure failure) {
        if (!failure.placedAhead()) {
            recentFailures.note(failure.unusableCell());
        }
    }

    /**
     * Distance to the path (blocks) used for deviation detection.
     *
     * <p><b>While connected vertically within the same water, vertical offset isn't counted.</b> In water you can move up and down
     * freely, so the path's Y isn't an instruction to "pass through there"; swimming surfaces and dives on every breath,
     * so counting vertical offset as deviation would exceed the default threshold (4) just by following a path on the water surface,
     * and splicing and replanning would run every few seconds. The view that <b>being directly above or below means you're following the path</b>
     * is the same as in the decision on falling back to a full scan ({@link PathProgress#horizontalDistance()}).
     *
     * <p>Connectivity is checked so we don't miss the case where you've wandered into an underwater cave and genuinely left the path.
     */
    private static double offPathDistance(Level level, Player player, PathResult result) {
        double distance = PathProgress.INSTANCE.distance();
        double horizontal = PathProgress.INSTANCE.horizontalDistance();
        // No vertical offset = same either way. No need to probe the water
        if (distance - horizontal < 1.0e-6) {
            return distance;
        }
        BlockPos step = result.steps().get(PathProgress.INSTANCE.indexFor(result)).pos();
        return swimmableBetween(level, player.blockPosition(), step) ? horizontal : distance;
    }

    /**
     * Whether the player and this step are connected vertically within the same water. If so, the vertical offset
     * can be closed by swimming on the spot.
     *
     * <p>The scan uses <b>the player's column</b>. Looking at the step's column, it can appear connected across
     * intervening land (swimming right beside the shore while the path runs along the cliff top).
     *
     * <p>If the cell at your feet isn't water, start one below. A player swimming at the surface bobs across the surface,
     * so their block coordinate alternates between the surface cell and the one above; judging "not in the water"
     * only at the moments they're above would raise a deviation precisely at the moment of taking a breath.
     */
    private static boolean swimmableBetween(Level level, BlockPos player, BlockPos step) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int playerY = player.getY();
        if (!water(level, cursor.set(player.getX(), playerY, player.getZ()))) {
            // Only the player's side gets relaxed by one block. Relaxing the step's side too would make a path along a cliff above water
            // count as "connected by a water column"
            playerY--;
        }
        for (int y = Math.min(playerY, step.getY()); y <= Math.max(playerY, step.getY()); y++) {
            if (!water(level, cursor.set(player.getX(), y, player.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean water(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && CellData.water(CellData.flagsOf(level.getBlockState(pos)));
    }

    /**
     * Shortest distance to this path (blocks). Used instead when {@link PathProgress} doesn't have a value measured
     * <b>against this path</b>.
     *
     * <p>{@link PathProgress} searches within a window, whereas this looks at every step. The window is for tracking "where on the path
     * you're walking" and can't be applied in the first place to a path without a mapping.
     */
    private static double distanceToPath(PathResult result, Vec3 position) {
        List<PathStep> steps = result.steps();
        return distanceTo(position, steps.get(nearestStepIndex(steps, position)).pos());
    }

    /** Index of the step closest to this position. */
    private static int nearestStepIndex(List<PathStep> steps, Vec3 position) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            double distance = distanceTo(position, steps.get(i).pos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * Index of the step closest to this position <b>that you can actually stand on now</b> ({@code -1} if none).
     * Nothing before {@code minIndex} is considered.
     *
     * <p>A bridge step is "on top of a block yet to be placed", so below it is still air (void, lava).
     * Using it as a splice target makes {@code StanceFinder} snap to a standable spot, and the splice leg ends on a different
     * cell from the path and doesn't connect. Splicing is only possible onto a floor that actually exists.
     *
     * <p>Steps blocked right now by a world change are excluded for the same reason. When detouring around a blocked spot
     * blocks may have been placed in a row, and we want to splice onto the first step past that cluster.
     */

    /**
     * Limits passed to the normal-budget side of the parallel fallback. Budget and time unchanged; only the weight is lowered
     * to {@link #QUALITY_HEURISTIC_WEIGHT}. Values from people who configured it lower than that aren't raised.
     */
    static SearchLimits qualityLimits(SearchLimits limits) {
        return new SearchLimits(limits.maxExpandedNodes(), limits.timeLimitMillis(),
                Math.min(limits.heuristicWeight(), QUALITY_HEURISTIC_WEIGHT));
    }

    /**
     * Decides the detail search's goal. Long-range route waypoints are inserted only when the goal is outside the detail search's reach
     * and Xaero's map data is available. If there's no map, it's out of range, or even after replanning no waypoint
     * is within reach, it falls back to the old behaviour of heading straight to the real goal
     * (if any precondition is missing, the long-range route isn't entered).
     *
     * <p>The boundary is {@code reach} (the measured value from {@link #updateDetailReach}), not the render distance. Using the render distance
     * would assume "within range, the detail search should be solvable with full-resolution data", but that
     * only holds when walking on the surface. Measured: in the Nether at render distance 32 (512 blocks), the long-range route dropped
     * once the goal was 502 blocks away, and from then on it kept trying to solve 500 blocks ahead in one go and
     * kept failing (the coarse waypoint chain gave 0 steps every time = "no path found").
     */
    private DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                             int reach, boolean boatAvailable, boolean playerAnchored,
                                             int minWaypointIndex, boolean ceilingDimension, boolean navGraphGuided) {
        if (horizontalDistance(start, currentGoal) <= reach) {
            aimingPastWaypoints = false;
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (navGraphGuided) {
            // With a nav graph guide, don't stop at waypoints. The guide within the window is the true remaining cost for the same moves as the search,
            // and detouring to waypoints is itself a detour (measured walk-throughs: wide long-range 1.067→1.017x, End 1.122→1.014x).
            //
            // Layer 1 is still planned (HUD and map dotted lines, out-of-window estimates). Layer-2 refinement isn't used for targets, so it isn't run
            if (XaeroPresence.mapPresent()) {
                prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            }
            aimingPastWaypoints = true;
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (!XaeroPresence.mapPresent()) {
            aimingPastWaypoints = false;
            return goalOrPointToward(start, currentGoal, reach);
        }
        if (ceilingDimension) {
            // <b>In dimensions with a ceiling, don't stop at waypoints.</b> Aim directly at the goal and keep extending the
            // partial paths cut by the box. Direction comes from the 3D coarse layer ({@link NetherVoxelGuide}).
            //
            // Stopping at waypoints measured 2.175-3.100x the baseline. Layer 1 holds only a few floors per chunk and
            // can't represent the Nether's vertically stacked passages, so detouring to its waypoints is itself a detour.
            // <b>There's no need to switch by measuring terrain</b>: in dimensions with a ceiling the answer is always the same, and
            // the measuring side (ratio of actual search cost) reaches the same values in overworld mountains too, so they can't be separated.
            //
            // Layer 1 is still planned (HUD and map dotted lines). Its result just isn't used here.
            prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            aimingPastWaypoints = true;
            return new DetailTarget(currentGoal, -1, 0);
        }
        aimingPastWaypoints = false;
        DetailTarget target = reachableWaypointTarget(start, currentGoal,
                cachedOrFreshRoute(start, currentGoal, boatAvailable, playerAnchored, false),
                renderRadius, reach, playerAnchored,
                minWaypointIndex);
        if (target != null) {
            return target;
        }
        if (!playerAnchored) {
            // Extension doesn't own the route. Calling freshRoute here would replan the whole long-range route from the
            // end position, resubmitting layer-2 refinement too and swapping out the guidance ahead
            return goalOrPointToward(start, currentGoal, reach);
        }
        CoarseRoute inFlight = refiningRoute;
        if (inFlight != null && inFlight.goal().equals(currentGoal)) {
            // Layer-2 refinement is in progress for exactly this goal. Replanning here would discard its result and
            // just redo the same situation; with a small render distance none of layer 1's 96-block-spaced waypoints
            // are within reach so we always land here, and letting it through enters a loop of "every 0.5 s, re-read the map on the main thread
            // and discard the refinement", so the refined version never completes.
            // Once it completes, pendingRefinedRouteReady triggers a replan and the 24-block-spaced waypoints
            // come within reach. Until then, fall back to the old behaviour without inserting the long-range route
            return goalOrPointToward(start, currentGoal, reach);
        }
        CoarseRoute cached = coarseRoute;
        if (cached != null && cached.goal().equals(currentGoal)
                && horizontalDistance(start, cached.computedFrom()) < COARSE_ROUTE_RETRY_MOVE_BLOCKS) {
            // Replanning would give the same route anyway. A layer-1 replan costs a map read on the main thread plus
            // two layer-1 A* runs, which would be paid on every recalculation while no waypoint is within reach
            return goalOrPointToward(start, currentGoal, reach);
        }
        // None of the cached waypoints is within render distance = made a big detour and left the path.
        // The goal hasn't changed so the cache should apply, but even with unchanged terrain our own position changes, so
        // replan from the current position (the only exception to the principle of not replanning unless the terrain changes)
        target = reachableWaypointTarget(start, currentGoal,
                freshRoute(start, currentGoal, boatAvailable, false),
                renderRadius, reach, true, minWaypointIndex);
        return target != null ? target : goalOrPointToward(start, currentGoal, reach);
    }

    /**
     * Whether the first path may wait for the nav graph to be built before planning. Also records when the wait started.
     *
     * <p>Wait only when no path has been produced yet and the goal is farther than can be aimed at in one go. Near goals reach the shortest
     * path in one shot with the old search too, so there's no reason to make you wait seconds for the whole window. Clearing a shown path to wait would cut guidance mid-walk.
     */
    private boolean mayAwaitNavGraph(BlockPos start, BlockPos currentGoal, int renderRadius) {
        DisplayedPath shown = displayed;
        if (!XaeroNavConfig.INSTANCE.costToGoGuideEnabled() || (shown != null && !shown.result().steps().isEmpty())
                || horizontalDistance(start, currentGoal) <= detailHorizon(renderRadius)) {
            return false;
        }
        if (navGraphWaitStartedMillis == 0L) {
            navGraphWaitStartedMillis = MonotonicTime.millis();
        }
        return true;
    }

    /**
     * Reviews the rest of the planned path against the rebuilt nav graph guide. If it turns out to be a detour, replans and returns {@code true}.
     *
     * <p>The path is planned aiming outside the window by estimate, and afterwards only extended from its end without reviewing what's ahead. As you walk and the window
     * moves, what was an estimate becomes exact, and a closer direction may appear (real Nether: a line stretching west stayed even after going diagonally north turned out closer).
     * The review happens only once per guide rebuild, and only after the goal has entered the window ({@link RouteReview#detour}).
     */
    private boolean reviewAgainstNavGraph(Player player, BlockPos currentGoal, DisplayedPath shown) {
        // Paths heading to waypoints (planned without waiting for the nav graph) are reviewed too. The guide's values are to the final goal,
        // so if detouring to a waypoint is itself a detour, it's measured as one
        if (shown.mode() == PathMode.TO_SURFACE) {
            return false;
        }
        WindowField field = navGraphGuide.latest(currentGoal);
        if (field == null || field == reviewedField || !field.reachesGoal()) {
            return false;
        }
        BlockPos at = player.blockPosition();
        double value = field.estimate(at.getX(), at.getY(), at.getZ());
        // Don't review until closer to the goal, by the guide's value, than where we last replanned. Where the path can't reach the goal and is cut off midway,
        // both replanned lines appear to stray from the guide's shortest, and replans alternated between two points back and forth (measured: Nether)
        if (reviewReplannedAt != null && (horizontalDistance(at, reviewReplannedAt) < REVIEW_RETRY_MOVE_BLOCKS
                || !(value < reviewReplannedValue))) {
            return false;
        }
        reviewedField = field;
        List<PathStep> steps = shown.result().steps();
        int index = PathProgress.INSTANCE.indexFor(shown.result());
        RouteReview.Detour detour = RouteReview.detour(field, steps.get(index).pos(), steps, index + 1);
        if (!detour.worthReplanning(REVIEW_MIN_EXTRA_TICKS)) {
            return false;
        }
        LOGGER.debug("XaeroNav: The rebuilt guide shows a detour; replanning ({} extra ticks, reviewed section {} ticks, position={})",
                Math.round(detour.extraTicks()), Math.round(detour.walkedTicks()), at.toShortString());
        reviewReplannedAt = at;
        reviewReplannedValue = value;
        recalculate("detour found by guide review");
        return true;
    }

    /**
     * The guide passed to searches aiming straight at the goal. The nav graph if built (out-of-window estimates: 3D coarse layer in the Nether, layer 1 in the overworld,
     * straight-line distance in the End); if not yet, the 3D coarse layer in dimensions with a ceiling, otherwise {@code null}, giving the old search that stops at waypoints.
     *
     * <p>Don't apply it to searches aiming at a waypoint: the table's origin is fixed at the final goal, so
     * for a search aiming at another point the estimate takes the shape of "detour there, then on to the goal",
     * and taking the max with the geometric heuristic simply gives values that are too large.
     *
     * <p><b>Call from the main thread</b> (reads Xaero's map and chunks).
     */
    private @Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from, BlockPos currentGoal,
                                          int renderRadius, boolean climbing) {
        if (climbing) {
            return null;
        }
        boolean navGraphEnabled = XaeroNavConfig.INSTANCE.costToGoGuideEnabled();
        NavGraphGuide.Far far;
        CostToGo fallback = null;
        if (level.dimensionType().hasCeiling()) {
            if (!XaeroPresence.mapPresent()) {
                return null;
            }
            long voxelLap = TickLaps.start();
            CostToGo voxel = voxelGuide.forGoal(level, level.dimension(), player.blockPosition(), currentGoal,
                    XaeroNavConfig.INSTANCE.movementOptions().lavaBridgingEnabled());
            TickLaps.add("3D coarse layer startup", voxelLap);
            if (voxel == null || !navGraphEnabled) {
                return voxel == null ? null : new GoalGuide(voxel, false, null);
            }
            // With a geometric lower bound outside the window, the Nether is worse than with the 3D coarse layer alone (1.257x measured). Use the nav graph once the 3D coarse layer is built
            fallback = voxel;
            far = new NavGraphGuide.Far("3D coarse layer", voxel, () -> FarField.of(
                    (x, y, z) -> NavGraphGuide.VOXEL_FAR_SCALE * voxel.estimate(x, y, z)), false);
        } else if (!navGraphEnabled) {
            return null;
        } else {
            CoarseMapForGoal latestMap = latestCoarseMap;
            CoarseMap map = latestMap != null && latestMap.goal().equals(currentGoal) ? latestMap.map() : null;
            if (level.dimension() == Level.END) {
                // Outside the window: straight-line distance to the goal times the cost the long-range route (yellow line) assigns to unknown cells.
                // Bridges inside the window are counted exactly, so with 1x outside, an exit that "doesn't cross inside the window and cheaply counts as crossed outside" wins,
                // and an edge that barely gets closer to the goal is chosen (real saved terrain: 53 blocks of progress at 1x, 314 at 3x).
                // Layer 1 itself counts known land at 1x and unknown at about 5x, so it's pulled toward detours along known land and can't be used outside the window.
                // Edges that are behind according to the estimate aren't used as seeds (NavGraphGuide.Far#forwardOnly)
                double scale = map == null ? 1.0 : CoarseRouter.unknownMultiplier(map);
                far = new NavGraphGuide.Far("straight line x" + String.format(Locale.ROOT, "%.1f", scale),
                        map == null ? currentGoal : map, () -> FarField.straightLineTo(currentGoal, scale), true);
            } else {
                far = map == null ? null
                        : new NavGraphGuide.Far("layer 1", map, () -> layer1Far(map, currentGoal), false);
            }
        }
        long navGraphLap = TickLaps.start();
        WindowField field = navGraphGuide.forGoal(level, player, currentGoal, renderRadius,
                XaeroNavConfig.INSTANCE.movementOptions(), far);
        TickLaps.add("nav graph startup", navGraphLap);
        // Skip rounds where the goal in the window isn't connected to the shell. The whole window's values would come only from estimates outside the edge
        if (field != null && field.reachesGoal()) {
            return new GoalGuide(field, true, level.dimension() == Level.END ? landing(level, field, from) : null);
        }
        return fallback == null ? null : new GoalGuide(fallback, false, null);
    }

    /**
     * Layer 1 is placed on the edge only when the goal is outside the window. Inside the window its values alone suffice, and layer 1's values at the edge can be cheaper than actual cost,
     * so "leave the window and come back" looks cheaper than the real road inside the window (underground tunnels, etc.), and the direction flips every time the window moves
     * (over 100 random routes in a saved world: 8 unreached became reached, 2 got worse by over 2%, 10 improved).
     */
    private static FarField layer1Far(CoarseMap map, BlockPos goal) {
        return FarField.byGoal(FarField.of(CoarseRouter.farEstimate(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE)),
                FarField.UNKNOWN);
    }

    /**
     * When the goal is outside the window, the last point standing on a floor while descending the window's guide from {@code from}. Also comes with a guide for searches aiming there.
     *
     * <p>Aiming straight at the goal in the End, the search burns through its budget and then picks an intermediate point. That point is within 400 ticks
     * ({@code AStarPathfinder#FALLBACK_BUDGET_TICKS}), and half-built bridges get cut off (it couldn't show they can be crossed), so
     * for island hopping where one bridge takes thousands of ticks, you get a path that walks a bit of land, or 0 moves (on a real client "actual reach 0 blocks" kept repeating).
     * The guide knows the optimal road inside the window, so aiming at a landing point on it reaches across including the bridge (all 12 spots on real saved terrain, 265-58,331 nodes expanded).
     */
    private static @Nullable Landing landing(Level level, WindowField field, BlockPos from) {
        BlockPos[] landed = {null};
        BlockPos[] last = {null};
        WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ(), (x, y, z) -> {
            last[0] = new BlockPos(x, y, z);
            if (!level.isEmptyBlock(new BlockPos(x, y - 1, z))) {
                landed[0] = new BlockPos(x, y, z);
            }
        });
        // If it's void from the island's edge all the way to the window's edge, the only point on a floor is the start. Aiming straight at the goal doesn't reach the bridge for the reason above,
        // and it bounces between budget-exhausted intermediate points and the edge (stopped after more than 60 legs on saved End terrain). Aim at the last point descended inside the window
        BlockPos target = landed[0] != null && !landed[0].equals(from) ? landed[0] : last[0];
        if (descent == null || descent.reachedGoal() || target == null || target.equals(from)) {
            return null;
        }
        double base = field.exact(target.getX(), target.getY(), target.getZ());
        return new Landing(target, new CostToGo() {
            @Override
            public double estimate(int x, int y, int z) {
                return Math.max(0.0, field.estimate(x, y, z) - base);
            }

            @Override
            public double searchEstimate(int x, int y, int z) {
                double value = field.searchEstimate(x, y, z);
                return Double.isNaN(value) ? value : Math.max(0.0, value - base);
            }
        });
    }

    /** A landing point on the window's guide, and the guide for searches aiming there (the value to the goal minus the landing point's value). */
    record Landing(BlockPos target, CostToGo guide) {
    }

    /**
     * Result of {@link #goalGuide}. If {@code navGraph}, the search construction changes too ({@link #navGraphLimits}, {@link #navGraphBounds}).
     * If there's a {@code landing}, aim there instead of the goal (End only; {@link #landing}).
     */
    record GoalGuide(CostToGo costToGo, boolean navGraph, @Nullable Landing landing) {
    }

    /** If there's a landing point, replace the target that would have aimed at the goal with it. */
    static DetailTarget landingOr(@Nullable GoalGuide goalGuide, BlockPos currentGoal, DetailTarget detail) {
        Landing landing = goalGuide == null ? null : goalGuide.landing();
        return landing != null && detail.target().equals(currentGoal) ? new DetailTarget(landing.target(), -1, 0)
                : detail;
    }

    /**
     * The prebuilt guide passed to the search. The 3D coarse layer and nav graph are built for the final goal, so they aren't applied to searches aiming at waypoints:
     * the table's origin is fixed at the goal, so the direction is off for searches aiming at another point. Only the landing point is used, with its value subtracted.
     */
    static @Nullable CostToGo preparedGuide(@Nullable GoalGuide goalGuide, BlockPos currentGoal, BlockPos target) {
        if (goalGuide == null) {
            return null;
        }
        if (target.equals(currentGoal)) {
            return goalGuide.costToGo();
        }
        Landing landing = goalGuide.landing();
        return landing != null && target.equals(landing.target()) ? landing.guide() : null;
    }

    /**
     * Budget when searching with the nav graph guide. Only the weight is set to 1.0: the guide inside the window is the true remaining cost, so
     * applying a weight only strays from the optimal path without being faster (measured quality all at weight 1.0).
     */
    static SearchLimits navGraphLimits(SearchLimits limits) {
        return new SearchLimits(limits.maxExpandedNodes(), limits.timeLimitMillis(), 1.0);
    }

    /**
     * Box when searching with the nav graph guide. Covers the full height and is clipped to the window centered on the player ({@link NavGraphGuide#window}).
     *
     * <p>Outside the window the guide falls back to layer 1 or geometric estimates, so widening that far gives an unmeasured search.
     * Height isn't clipped so that when the guide points along a road digging up or down, it doesn't dead-end outside the box.
     * The trail descending the guide is included even outside the band ({@link WindowField#descentBox}).
     */
    static SearchBounds navGraphBounds(Level level, BlockPos from, BlockPos target, BlockPos player,
                                       int renderRadius, int horizontalMargin, boolean wholeWindow, CostToGo guide) {
        int window = NavGraphGuide.window(renderRadius);
        // When aiming at a landing point, look at the whole window. The guide's road can stray far from the band connecting start and target (real End: going around
        // 68 blocks north from due west of the target to cross to an island), and clipping at the band burns the budget without the search following that road a single step
        SearchBounds box = SearchBounds.around(level, from, target, wholeWindow ? 2 * window : horizontalMargin,
                level.getHeight(), window);
        int[] trail = guide instanceof WindowField field
                ? field.descentBox(from.getX(), from.getY(), from.getZ(), DESCENT_BOX_PAD_BLOCKS) : null;
        if (trail != null) {
            box = new SearchBounds(Math.min(box.minX(), trail[0]), box.minY(), Math.min(box.minZ(), trail[1]),
                    Math.max(box.maxX(), trail[2]), box.maxY(), Math.max(box.maxZ(), trail[3]));
        }
        return new SearchBounds(Math.max(box.minX(), player.getX() - window), box.minY(),
                Math.max(box.minZ(), player.getZ() - window), Math.min(box.maxX(), player.getX() + window),
                box.maxY(), Math.min(box.maxZ(), player.getZ() + window));
    }

    /** How much to pad the bounding box of the trail descending the guide. The search passes a few blocks beside the guide's road (measured going 5 blocks out). */
    static final int DESCENT_BOX_PAD_BLOCKS = 16;

    /**
     * The target when there's no aimable point on the route.
     *
     * <p><b>Don't pass a goal beyond the distance aimable in one go ({@code reach}) as is.</b> The search box is
     * clipped at the render distance, so a goal outside it is unreachable in principle: A\* burns the full budget every time and
     * just returns a partial path "up to the closest point reached". In a real-client probe, aiming at a goal 509 blocks away
     * without a limit used 465,536 nodes and 2 seconds and didn't reach it (box 140×305).
     *
     * <p>So aim at a point {@code reach} toward the goal. This applies to the no-route case the same thing
     * {@link #pointAlongRoute} does for waypoints.
     * Arrival checks look at the goal itself ({@code checkArrival}), so cutting short doesn't prevent arriving.
     *
     * <p><b>There's exactly one exception.</b> In dimensions with a ceiling, a goal outside the box is deliberately aimed at as is:
     * "burn the full budget and return a partial path" is the desirable behaviour there, and it arrives cheaper than stopping at
     * waypoints (2.599x→1.257x measured, and 1.02-1.13x more with the 3D coarse layer applied). The ladder of retries known to be
     * futile is folded up on the {@code PathfindingExecutor} side ({@code goalInsideBounds}).
     */
    private DetailTarget goalOrPointToward(BlockPos start, BlockPos currentGoal, int reach) {
        BlockPos aim = aimTowardGoal(start, currentGoal, reach);
        if (aim.equals(currentGoal)) {
            return new DetailTarget(currentGoal, -1, 0);
        }
        // Without Xaero's map, don't go into surface resolution. {@code XaeroMapReader} fails at class loading itself
        // when Xaero isn't installed (this function is also called from paths where mapPresent() is false).
        // It's fine to pass the interpolated Y as is: the search side's {@code StanceFinder#resolveGoal} snaps it to
        // a height you can actually stand on in the same column
        BlockPos resolved = XaeroPresence.mapPresent() ? resolveWaypointOnSurface(aim) : aim;
        return new DetailTarget(resolved, -1, INTERPOLATED_GOAL_RADIUS_BLOCKS);
    }

    /** The goal itself, or if too far, the point {@code reach} toward it. */
    static BlockPos aimTowardGoal(BlockPos start, BlockPos goal, int reach) {
        double distance = horizontalDistance(start, goal);
        return distance <= reach ? goal : pointAlong(start, goal, distance, reach);
    }

    /**
     * Whether targeting this point would yield only a zero-length path.
     *
     * <p>The goal is a region (radius {@link #WAYPOINT_GOAL_RADIUS_BLOCKS}), so if the start is inside it,
     * the search ends as arrived the moment it pops the start node. That's why the check uses
     * "radius + thinning spacing" rather than just "thinning spacing".
     */
    static boolean tooCloseToAim(BlockPos start, BlockPos aim) {
        return horizontalDistance(start, aim)
                < WAYPOINT_GOAL_RADIUS_BLOCKS + REFINED_WAYPOINT_MIN_SPACING_BLOCKS;
    }

    /**
     * <b>Records whether you can actually stand at the target right before it's passed to layer 3 (diagnostic).</b>
     *
     * <p>Among the suspects for "strangely makes you cross / detours", this is the one point where <b>whatever upstream path is the cause,
     * it always shows up last</b>. Pass an unstandable target and layer 3, trying to approach the {@code goalRadius} cylinder,
     * builds bridges with {@code addBridge} into the middle of void, lava or water (A/B on real End save data:
     * 8 bridges vs 0).
     *
     * <p>Offline, over 97% of the waypoints layer 1 produces are rescued by layer 2's snapping ({@code CorridorLegSolver}'s
     * radius 8), so this should <b>only happen when layer 2 is unavailable</b>;
     * that depends on Xaero's region loading state and can't be measured locally. The log for counting it on a real client
     * is placed here.
     *
     * <p>The check uses layer 3's own {@link StanceFinder#resolveGoal} (the same rules as production, including snapping
     * within 32 blocks in Y). To avoid reporting the same target repeatedly, it's logged only when the coordinates change.
     */
    private void noteTargetStandability(ChunkView view, BlockPos target, PathMode mode, int waypointIndex) {
        if (StanceFinder.resolveGoal(view, target) != null) {
            unstandableTargetGate.reset();
            return;
        }
        if (!unstandableTargetGate.changed(target)) {
            return;
        }
        LOGGER.debug("XaeroNav: Can't stand at the search target (target={}, kind={}, waypoint #{}, layer-2 refined version={})",
                target.toShortString(), mode, waypointIndex,
                refinedRouteInUse() ? "in use" : "none");
    }

    /** Whether {@link #cachedOrFreshRoute} is currently returning the layer-2 refined version (for the breakdown in the log above). */
    private boolean refinedRouteInUse() {
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return cached != null && refined != null && refined.source() == cached;
    }

    /**
     * Records how far this search got. This used to feed the next target distance limit
     * ({@code detailReach}), but that mechanism was removed: a value measured on explored terrain around the player
     * was also used as the limit for searches extending from the path's end into unexplored terrain,
     * so "succeed from the player and go up -> fail at the same value from the end and go down" alternated
     * and never converged (in a real log 24→48→24→48... lined up regularly).
     * Every time the target distance changed the path was replanned, so this showed up directly as "back and forth".
     *
     * <p>Now the target sits at "the edge of loaded chunks", and if it isn't reached the partial path is used as guidance
     * as is (even when cut off, the best partial path is returned). The number to aim at is gone altogether.
     */
    static void logSearchReach(BlockPos start, BlockPos target, PathResult result) {
        BlockPos end = endOf(result, start);
        if (!result.complete() && (result.steps().isEmpty()
                || horizontalDistance(start, end) < MIN_EXTEND_PROGRESS_BLOCKS)) {
            stalledSearches.incrementAndGet();
            LOGGER.debug("XaeroNav: Detail search can't make enough progress (start={}, target={}, end={}, {}, expanded={}, steps={})",
                    start.toShortString(), target.toShortString(), end.toShortString(),
                    result.termination(), result.expandedNodes(), result.steps().size());
        }
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug("XaeroNav: Detail search (target {} ({} blocks away), actually reached {} blocks, {}, expanded {})",
                target.toShortString(), Math.round(horizontalDistance(start, target)),
                Math.round(horizontalDistance(start, end)), result.termination(), result.expandedNodes());
    }

    /**
     * Whether this unreached path <b>ends even farther away than the closest point reached</b>. If so, it isn't used for guidance.
     *
     * <p>On a real client (2026-09-19 01:10, Nether) <b>the player was dragged 80 blocks back, from a point 197 blocks away to 277
     * blocks</b>. In a spot that was 92% lava, the detail search used 320k nodes to advance only 5-11 steps, and the "partial path"
     * chosen there stretched 110 steps east (away from the goal).
     *
     * <p><b>End-point selection ({@code AStarPathfinder#selectFallback}) isn't broken.</b> It only accepts candidates satisfying
     * {@code h(candidate) + g/c < h(start)}, so a single search always picks a point that gets closer to the goal according to
     * the guide. The problem is <b>that the guide evaluated it that way</b>, and moreover the farther the player goes, the more the
     * 3D coarse layer's box widens and its grid coarsens (edge 4→6 on a real client), so the guide gets worse with distance.
     * As long as the guide is trusted, this positive feedback doesn't stop.
     *
     * <p>So <b>cap actual progress rather than the guide</b>. A correct detour (going around the edge of a lava sea)
     * retreats at worst 67 blocks in measurements on the model Nether, so only beyond {@link RetreatWatcher#RETREAT_BLOCKS}
     * is rejected. When rejected no path appears, but <b>that beats being walked in the wrong direction</b>;
     * the closest-approach record isn't cleared, so as soon as a path that moves forward appears it's adopted as is.
     *
     * <p>Completed paths are excluded. If it's planned all the way to the goal, it gets there however much it detours along the way.
     */
    private boolean leadsBackward(PathResult result, BlockPos currentGoal) {
        BlockPos anchor = retreatWatcher.closestAt();
        if (result.complete() || result.steps().isEmpty() || anchor == null) {
            return false;
        }
        if (!retreatWatcher.leadsAway(() -> result.steps().stream().map(PathStep::pos).iterator(),
                currentGoal)) {
            return false;
        }
        BlockPos end = result.steps().get(result.steps().size() - 1).pos();
        LOGGER.info("XaeroNav: Rejecting guidance that leads away from the goal "
                        + "(end={} at {}, closest={} at {}, {} steps, {})",
                end.toShortString(), Math.round(horizontalDistance(end, currentGoal)), anchor.toShortString(),
                Math.round(retreatWatcher.closest()), result.steps().size(), result.termination());
        return true;
    }

    /**
     * When we move far away from the point closest to the goal, leave one line with the decision inputs at that moment.
     *
     * <p>On a real Nether client (2026-09-18) there was <b>a back-and-forth of about 300 blocks</b>, but the logs at the time had
     * not a single line about "moving away" itself; it only came to light by reconstructing the trajectory from seam re-solve positions.
     * Next time it happens, this one line gathers "where it turned back, whether the search was dead-ending, and
     * how many ticks the guide was saying at the time".
     */
    private void noteRetreat(BlockPos at, BlockPos currentGoal, @Nullable DisplayedPath shown) {
        RetreatWatcher.Retreat retreat = retreatWatcher.observe(at, currentGoal);
        if (retreat == null) {
            return;
        }
        WindowField field = navGraphGuide.latest(currentGoal);
        List<PathStep> steps = shown == null ? List.of() : shown.result().steps();
        LOGGER.info("XaeroNav: Moving away from the goal (position={}, to goal {}, closest={} at {}, moved away {}, "
                        + "path end={}, {}, can't move forward {} times, guide={})",
                at.toShortString(), Math.round(retreat.distance()),
                retreat.closestAt().toShortString(), Math.round(retreat.closest()),
                Math.round(retreat.retreated()),
                steps.isEmpty() ? "none" : steps.get(steps.size() - 1).pos().toShortString(),
                shown == null ? "no path" : shown.result().complete() ? "planned to the goal" : "partial",
                stalledSearches.get(),
                field == null ? "none"
                        : Math.round(field.estimate(at.getX(), at.getY(), at.getZ())) + "tick");
    }

    /**
     * Prefers the layer-2 refined version if there is one (same order as {@link NavigationView#coarseRouteWaypoints}). Layer 1
     * is chunk resolution and its waypoints can be nearly 100 blocks apart, so with a lowered render distance
     * not a single one satisfies {@link #reachableWaypointTarget}'s "within renderRadius" and the whole long-range route
     * misses. The refined version is spaced at {@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS}, so it fills this gap.
     */
    private List<BlockPos> cachedOrFreshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                               boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            return freshRoute(start, currentGoal, boatAvailable, ceilingDimension);
        }
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return refined != null && refined.source() == cached ? refined.waypoints() : cached.waypoints();
    }

    /**
     * For callers of {@link #cachedOrFreshRoute} that don't use the result right away. If replanning, read only the map and
     * leave the solving to the background (in situations where it's planned only for the HUD and map dotted lines and out-of-window estimates,
     * don't stall the main thread with solving).
     */
    private void prepareCoarseRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                    boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            freshRouteInBackground(start, currentGoal, boatAvailable, ceilingDimension);
        }
    }

    private boolean coarseRouteStale(BlockPos currentGoal, boolean playerAnchored) {
        CoarseRoute cached = coarseRoute;
        if (cached == null || !cached.goal().equals(currentGoal)) {
            return true;
        }
        // A route planned with gaps in the map is replanned once the data arrives. Unknown cells are nearly the cheapest, so
        // if there's a lava sea not loaded yet, a route going straight through it gets planned. And since it "doesn't replan
        // as long as at least one waypoint is reachable", walking doesn't fix it (user report:
        // "the yellow line keeps trying to go straight through magma"). Replanning also redoes the layer-2
        // refinement, so the yellow line gets more detailed the more you walk.
        //
        // Not replanned from the extension side. Its start is the path's end, so replanning the long-range route from there
        // would swap out the guidance ahead too (same reason as the goalOrPointToward branch below)
        return playerAnchored && cached.pendingRegions() > 0 && refiningRoute == null
                && MonotonicTime.millis() >= coarseMapRetryAfterMillis;
    }

    private List<BlockPos> freshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                       boolean ceilingDimension) {
        long coarseLap = TickLaps.start();
        CoarseAttempt attempt = solveCoarseRoute(readCoarseMapFor(start, currentGoal), start, currentGoal,
                boatAvailable, XaeroNavConfig.INSTANCE.swimmingEnabled());
        TickLaps.add("long-range route", coarseLap);
        // What was just planned synchronously is newer than the request being solved in the background
        solvingCoarse = null;
        return adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension).waypoints();
    }

    /**
     * Reads only the map here and hands the solving to {@link #coarseExecutor}. Once solved, {@link #adoptCoarseRoute}
     * on the main thread. While the same goal is still being solved, don't stack another request.
     */
    private void freshRouteInBackground(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                        boolean ceilingDimension) {
        CoarseSolve pending = solvingCoarse;
        if (pending != null && pending.goal().equals(currentGoal)) {
            return;
        }
        long readLap = TickLaps.start();
        CoarseRead read = readCoarseMapFor(start, currentGoal);
        TickLaps.add("long-range route map read", readLap);
        CoarseSolve solve = new CoarseSolve(currentGoal);
        solvingCoarse = solve;
        // Read the setting here on the main thread; the solve itself runs on coarseExecutor
        boolean swimmingEnabled = XaeroNavConfig.INSTANCE.swimmingEnabled();
        CompletableFuture.supplyAsync(() -> solveCoarseRoute(read, start, currentGoal, boatAvailable, swimmingEnabled),
                        coarseExecutor)
                .whenComplete((attempt, error) -> onMainThread.accept(() -> {
                    if (solvingCoarse != solve) {
                        return;
                    }
                    solvingCoarse = null;
                    if (error != null) {
                        LOGGER.error("XaeroNav: Failed to compute the long-range route", error);
                        return;
                    }
                    adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension);
                    publishNavigationView();
                }));
    }

    /** Reads the long-range route map and puts it in {@link #latestCoarseMap} so out-of-window estimates can use it right away. */
    private CoarseRead readCoarseMapFor(BlockPos start, BlockPos currentGoal) {
        CoarseRead read = readCoarseMap(start, currentGoal);
        latestCoarseMap = read.map() == null ? null : new CoarseMapForGoal(currentGoal, read.map());
        coarseMapRetryAfterMillis = MonotonicTime.millis() + COARSE_MAP_RETRY_INTERVAL_MILLIS;
        return read;
    }

    private CoarseRoute adoptCoarseRoute(BlockPos start, BlockPos currentGoal, CoarseAttempt attempt,
                                         boolean ceilingDimension) {
        CoarseRouter.Route route = attempt.route();
        List<BlockPos> waypoints = route.waypoints();
        if (!waypoints.isEmpty() && route.reachedGoal()) {
            // The coarse end point is a chunk center ±8 blocks with a representative height, so it can't be arrived at as is.
            // Replace just the last one with the real goal
            waypoints = replaceLast(waypoints, currentGoal);
        }
        CoarseRoute thisRoute = new CoarseRoute(currentGoal, start, route.reachedGoal(),
                attempt.pendingRegions(), waypoints);
        CoarseRoute before = mapRetryBefore;
        coarseRoute = thisRoute;
        // Indices mean something different in the new list. The replan starts from the current position, so the first entry
        // can't already be passed
        passedWaypoints = 0;
        // Don't null out an older generation's refinement result here. The reading side checks the origin with
        // RefinedRoute.source(), so a different generation is ignored automatically; clearing it here would drop
        // a still-valid refined version along with it
        // Always swap it even in a generation where no path could be planned. Making this conditional would leave the previous
        // generation's marker behind and the "refinement in progress" check would stay true forever
        // In dimensions with a ceiling, layer-2 refinement isn't applied. Waypoints there aren't used as search targets
        // (selectDetailTarget), so it would mean re-reading Xaero on the main thread just to make the map and HUD dotted lines
        // finer. Layer 2 is also 2.5D, so it can't fix the Nether's vertically stacked passages
        boolean refinable = !waypoints.isEmpty() && !ceilingDimension;
        refiningRoute = refinable ? thisRoute : null;
        if (refinable) {
            refineRouteAsync(start, currentGoal, waypoints, thisRoute);
        }
        if (before != null) {
            mapRetryBefore = null;
            // What we want to know is less that we replanned than "whether the filled-in map changed the big picture". If not,
            // the cause of the detour lies elsewhere, not in waiting for loading
            LOGGER.debug("XaeroNav: Replanned the long-range route after waiting for map loading"
                            + " (unloaded regions={}→{}, waypoints={}→{}, {}, attempt {}/{})",
                    before.pendingRegions(), thisRoute.pendingRegions(),
                    before.waypoints().size(), waypoints.size(),
                    waypoints.equals(before.waypoints()) ? "same route" : "changed",
                    coarseMapRetries, COARSE_MAP_RETRY_LIMIT);
        }
        return thisRoute;
    }

    /**
     * Re-solves each leg of {@link #coarseRoute} with the layer-2 corridor (long-range route layer-2
     * waypoint refinement). {@link CorridorLegSolver#prepare} touches Xaero's data structures and is
     * main-thread only, so all legs are done up front here (on this method's calling thread = the client thread),
     * and only immutable {@link CorridorLegSolver.PreparedLeg}s are passed to the later {@code thenCompose} chain.
     * Calling {@code prepare} per leg sequentially would run the 2nd leg onward on the worker thread that completed
     * the previous leg's {@link CompletableFuture}, violating the main-thread-only constraint.
     *
     * <p>The potentially heavy A* searches are submitted to {@link #corridorExecutor} one leg at a time in order (chained with
     * {@code thenCompose}). {@link PathfindingExecutor#submit} cancels "the previous job" on every call,
     * so submitting all legs at once would have the 2nd leg onward immediately cancel the 1st;
     * waiting for the previous leg to complete before submitting the next avoids this.
     *
     * <p>If a leg has no surface data, only that leg falls back to the single raw waypoint
     * (gradual per-leg degradation: missing data for one leg doesn't abandon refinement of the whole path).
     *
     * <p>{@code forRoute} marks "which {@link #coarseRoute} generation this refinement belongs to".
     * Matching {@code goal} alone can't detect, when replanning to the same goal (heading to the same coordinates
     * again after {@code clear()}, etc.), an older generation's refinement overtaking the new {@link #coarseRoute}, completing and
     * overwriting it: the goal's coordinates haven't changed, so the match check lets it through.
     * Checking whether the {@code coarseRoute} field is still the same instance as {@code forRoute}
     * detects it correctly regardless of generation ({@link #freshRoute} creates a new instance every time it's called).
     */
    private void refineRouteAsync(BlockPos start, BlockPos currentGoal, List<BlockPos> waypoints,
                                  CoarseRoute forRoute) {
        List<BlockPos> legs = new ArrayList<>();
        legs.add(start);
        legs.addAll(waypoints);

        List<CorridorLegSolver.PreparedLeg> prepared = new ArrayList<>();
        for (int i = 0; i < legs.size() - 1; i++) {
            prepared.add(CorridorLegSolver.prepare(legs.get(i), legs.get(i + 1)));
        }

        CompletableFuture<List<List<BlockPos>>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (int i = 0; i < prepared.size(); i++) {
            CorridorLegSolver.PreparedLeg leg = prepared.get(i);
            BlockPos rawTarget = waypoints.get(i);
            chain = chain.thenCompose(soFar -> solveLeg(leg, rawTarget).thenApply(points -> {
                soFar.add(points);
                return soFar;
            }));
        }
        chain.whenComplete((legPoints, error) -> {
            List<BlockPos> downsampled = error == null
                    ? CorridorWaypoints.downsample(CorridorWaypoints.stitch(legPoints),
                            REFINED_WAYPOINT_MIN_SPACING_BLOCKS)
                    : null;
            onMainThread.accept(() -> {
                if (refiningRoute == forRoute) {
                    refiningRoute = null;
                }
                if (downsampled == null) {
                    return;
                }
            // The generation check is done on the reading side (RefinedRoute.source()), not here. A
            // "check, then write" shape here would let a stale refined version through if the generation advanced in between
            // (stitch/downsample scan the whole point list, so that gap opens up in real time)
                pendingRefinedRouteReady = new RefinedRoute(currentGoal, forRoute, downsampled);
            });
        });
    }

    private CompletableFuture<List<BlockPos>> solveLeg(CorridorLegSolver.PreparedLeg leg, BlockPos rawTarget) {
        if (leg.view() == null) {
            // A leg layer 2 couldn't solve. The raw waypoint is layer 1's chunk center, with no guarantee you can stand there
            // (on a real End client 25% of LAND cells can't be stood on at the center). Layer 3 heads there with goalRadius
            // and gets close even by bridging, so if prepare at least managed to snap the end point, use that
            return CompletableFuture.completedFuture(List.of(leg.to() != null ? leg.to() : rawTarget));
        }
        return corridorExecutor.submitRaw(leg.view(), leg.from(), leg.to(), CorridorLegSolver.SEARCH_LIMITS)
                .thenApply(result -> result.steps().stream().map(PathStep::pos).toList());
    }

    /**
     * Rather than one waypoint at a time in order, picks the farthest unpassed point within the detail search's reach.
     * Waypoint spacing is much shorter than what the detail search reaches in one go, so passing them one at a time throws away search capability.
     * {@code null} if none is within render distance (the caller decides whether to replan).
     *
     * <p>"Within reach" is cut by {@code reach} (the measured value from {@link #updateDetailReach}), not the render distance.
     * It isn't necessarily loaded up to the render distance, and the distance solvable with the same budget varies several-fold with terrain density.
     */
    private @Nullable DetailTarget reachableWaypointTarget(BlockPos start, BlockPos currentGoal,
                                                  List<BlockPos> waypoints, int renderRadius, int reach,
                                                  boolean playerAnchored, int minWaypointIndex) {
        int farthestInRadius = -1;
        int farthestInReach = -1;
        int nearestInRadius = -1;
        int previouslyAimed = -1;
        double nearestDistance = Double.MAX_VALUE;
        // There are two backtracking brakes. The player-based one remembers "the point aimed at last time" by coordinates
        // (lastAimedWaypoint). Extension (start = the path's end) uses the index the end leg is heading for as
        // the lower bound; if both shared the same field, the brake after the end has advanced several legs ahead
        // would pin the player-based selection to a far index, and it couldn't re-pick a point suited to where you are
        //
        // <b>But the brake is applied only while making forward progress.</b> When stuck, going back around
        // behind may be exactly the answer; on terrain where a narrow spot to cross the void lies behind you
        // (End islands), the solution vanishes if waypoints short of the one currently aimed at can't be re-picked.
        // lastAimedWaypoint only advances monotonically until clear(), so once grabbed, anything before it can never be
        // picked again: using a value that can't improve as a brake means it never releases, the same shape we
        // stepped on in noteSearchOutcome (the "latch" item in [[xaeronav-architecture]]).
        // It's only released when nearly stuck, so during normal progress the oscillation of "forward and backward targets alternating"
        // (the very reason this brake was added) doesn't happen
        boolean strandedHere = stuckTracker.stranded();
        BlockPos aimedBefore = playerAnchored && !strandedHere ? lastAimedWaypoint : null;
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos waypoint = waypoints.get(i);
            if (waypoint.equals(aimedBefore)) {
                previouslyAimed = i;
            }
            double distance = horizontalDistance(start, waypoint);
            if (distance > renderRadius) {
                continue;
            }
            farthestInRadius = i;
            if (distance <= reach) {
                farthestInReach = i;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestInRadius = i;
            }
        }
        if (farthestInRadius < 0) {
            return null;
        }
        // If none is within reach, face the nearest waypoint. Aiming straight at it is too far, but it gets cut short below
        int farthestIndex = farthestInReach >= 0 ? farthestInReach : nearestInRadius;
        // Never go backwards along the route. If reach is shorter than the waypoint spacing (up to 90 blocks in layer 1), the selection above
        // almost always lands on "nearest", but right after passing a waypoint that's behind you; aiming at it as is would
        // give turn-back guidance (confirmed on a real client: forward and backward targets alternated, going back and forth).
        // If you strayed far from the path and the route was replanned, the previous point isn't in the new list, so this brake releases.
        //
        // Don't drag it beyond renderRadius. The brake is for "not grabbing points still behind",
        // not for pinning the target to a distant point that's no longer reachable
        farthestIndex = Math.max(farthestIndex, Math.min(previouslyAimed, farthestInRadius));
        if (!playerAnchored) {
            // Extension must not aim short of the leg the end is heading for. The "nearest" fallback below
            // can grab a passed point, so without this the path folds back
            // behind itself (on a real client it extended from the end at 245,-332 to 159,-375, 96 blocks behind).
            // If the lower bound is beyond renderRadius, the reach point on the road heading there (pointAlongRoute below) becomes the target
            farthestIndex = Math.max(farthestIndex, Math.min(minWaypointIndex, waypoints.size() - 1));
        }
        // The brakes so far are lower bounds for "not aiming short". The polyline followed when cutting short
        // passes only from this point onward: waypoints skipped by the brake are behind you, so
        // including them in the polyline would pull the target backwards
        int routeFrom = farthestIndex;
        // If the chosen point is too close, only a zero-length path comes out. Since the goal is a region, if the start is
        // inside that region the search ends as arrived the moment it pops the start node; unless the threshold
        // is "radius + thinning spacing" rather than "thinning spacing", 0-step results happen a lot
        if (tooCloseToAim(start, waypoints.get(farthestIndex))) {
            // This is an adjustment to move forward, so it must not go back before the brake
            farthestIndex = Math.max(farthestIndex, Math.min(farthestIndex + 1, farthestInRadius));
        }
        BlockPos aim = waypoints.get(farthestIndex);
        double distance = horizontalDistance(start, aim);
        // Too close yet couldn't move forward = this list has nothing further. <b>For a route that didn't reach the goal, the
        // final waypoint isn't replaced with the goal</b> (replaceLast only when reachedGoal), so
        // arriving there lands here. Returning it as is makes the target your own position, and the search returns
        // "reached" with 0 steps: the path is empty, and since it isn't a failure, escalation doesn't run either.
        // Return null and leave it to the caller's truncation toward the goal
        if (tooCloseToAim(start, aim) && !aim.equals(currentGoal)) {
            return null;
        }
        // The real goal isn't used for the brake. Even after replanning the route, its last element is always the goal, so
        // touching it once would permanently close the escape hatch of "the brake releases if not in the new list";
        // from then on it keeps aiming at the goal itself however far away, and the detail search burns its budget every time
        // (real log: kept aiming at a goal 291 blocks away with 200k nodes every few seconds)
        if (playerAnchored && !aim.equals(currentGoal)) {
            lastAimedWaypoint = aim;
        }
        // The real goal (the final waypoint replaced by replaceLast) was already resolved to standable coordinates in {@link #setGoal}.
        // Arrival is an exact coordinate match, so if it's within reach it must not be cut short
        // (cutting it would mean never arriving). Conversely, if out of reach, cut it short like other waypoints:
        // targeting a distance that can't be solved in one go just burns budget, and the arrival check looks at the goal itself
        if (aim.equals(currentGoal) && distance <= reach) {
            // The real goal can't be moved. It's exactly the point the user pointed at, so radius 0
            return new DetailTarget(aim, farthestIndex, 0);
        }
        // Waypoint spacing is kept shorter than the distance the detail search aims at in one go (see CoarseRouter's
        // WAYPOINT_SPACING_CELLS), so normally there's no need to cut short here. It's cut right after skipping one
        // too-close waypoint, and when you've strayed far from the route with no nearby point.
        // waypointIndex keeps pointing at where we're heading, so neither the HUD counter nor the map's dotted line
        // (which draws only the unpassed part) gets out of sync
        boolean interpolated = distance > reach;
        BlockPos target = interpolated ? pointAlongRoute(start, waypoints, routeFrom, farthestIndex, reach) : aim;
        return new DetailTarget(resolveWaypointOnSurface(target), farthestIndex,
                interpolated ? INTERPOLATED_GOAL_RADIUS_BLOCKS : WAYPOINT_GOAL_RADIUS_BLOCKS);
    }

    /**
     * The point reached by advancing horizontal distance {@code reach} from {@code start} along the coarse route's polyline
     * ({@code waypoints[fromIndex..aimIndex]}).
     *
     * <p><b>Don't measure along the straight line from the start to the target waypoint.</b> What layer 1 decides is "where to pass",
     * and that straight line cuts corners heavily where the route bends; the cut-off part is terrain layer 1 avoided
     * (lava seas, etc.), and an artificial target lands there. The detail search heads there even by bridging,
     * then returns to the proper road after passing it, so to the user it looks like "there's a better road, yet it detours
     * to a relay point before moving on" (real log: the target always appeared exactly reach=96 from the start, and
     * the path carried around 30 bridges, pinned at the {@code maxBridgeRunBlocks} limit).
     *
     * <p>It's fine if the {@code fromIndex} point is already a few blocks behind. The polyline just gets that much
     * longer and the target moves slightly closer; the target never leaves the route.
     */
    private static BlockPos pointAlongRoute(BlockPos start, List<BlockPos> waypoints, int fromIndex,
                                             int aimIndex, double reach) {
        BlockPos cursor = start;
        double remaining = reach;
        for (int i = Math.max(0, fromIndex); i <= aimIndex; i++) {
            BlockPos next = waypoints.get(i);
            double leg = horizontalDistance(cursor, next);
            if (leg >= remaining) {
                return pointAlong(cursor, next, leg, remaining);
            }
            remaining -= leg;
            cursor = next;
        }
        return waypoints.get(aimIndex);
    }

    /** The point reached by advancing horizontal distance {@code reach} on the line from {@code from} toward {@code to}. Y is interpolated proportionally too. */
    static BlockPos pointAlong(BlockPos from, BlockPos to, double distance, double reach) {
        double ratio = reach / distance;
        return new BlockPos(
                (int) Math.round(from.getX() + (to.getX() - from.getX()) * ratio),
                (int) Math.round(from.getY() + (to.getY() - from.getY()) * ratio),
                (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * ratio));
    }

    /**
     * Snaps a layer-1 waypoint (chunk center + representative height) to coordinates you can actually stand on, using layer 2's
     * block-resolution data. Only Y is adjusted, X,Z unchanged (the 2D map's dotted line and the progress display keep the waypoint's raw coordinates,
     * so changing only Y stays consistent without modification). If there's no data, returns the original waypoint as is
     * (like the long-range route's other preconditions, falls back to plain layer 1 if layer 2 is unavailable).
     */
    private static BlockPos resolveWaypointOnSurface(BlockPos waypoint) {
        return resolveOnSurface(waypoint, false);
    }

    /**
     * The goal version. The only difference is that in a water column it picks <b>the one closer to the requested Y</b> (surface or bottom).
     * A waypoint indicates a direction to head in, so the surface is fine, but the goal is exactly the point the user pointed at,
     * and if they pointed at the sea floor, it mustn't "arrive" at the surface.
     */
    private static BlockPos resolveGoalOnSurface(BlockPos goal) {
        return resolveOnSurface(goal, true);
    }

    private static BlockPos resolveOnSurface(BlockPos waypoint, boolean preferRequestedY) {
        int chunkX = waypoint.getX() >> 4;
        int chunkZ = waypoint.getZ() >> 4;
        int referenceY = waypoint.getY();
        XaeroMapReader.RegionStats stats = XaeroMapReader.surveyRegions(chunkX, chunkZ, 1, 1, referenceY);
        if (stats.pendingLoad() > 0) {
            XaeroMapReader.requestLoad(chunkX, chunkZ, 1, 1, referenceY);
        }
        SurfaceGrid grid = XaeroMapReader.readSurfaceDetailed(chunkX * 16, chunkZ * 16, 16, 16, referenceY);
        BlockPos resolved = preferRequestedY
                ? grid.resolveStandableNear(waypoint.getX(), waypoint.getZ(), referenceY)
                : grid.resolveStandable(waypoint.getX(), waypoint.getZ());
        return resolved != null ? resolved : waypoint;
    }

    /** Reads the long-range route map and logs how much could be read. <b>Main thread only</b> ({@link XaeroMapReader#readSurface}). */
    private static CoarseRead readCoarseMap(BlockPos start, BlockPos goal) {
        CoarseMapWindow.Window window = CoarseMapWindow.read(start, goal, CoarseMap.MAX_FLOORS);
        CoarseMap map = window.map();
        if (map == null) {
            return new CoarseRead(null, 0);
        }
        noteMapNotGrowing(start, window, map);
        // Record how much of the map was visible. Whether it "saw the lava as LAVA and still went through" or "hadn't
        // seen it yet as NO_DATA" can't be told apart from real-world logs if this stays silent;
        // unknown cells are nearly the cheapest in CoarseRouter, so if not visible, a route straight across the lava sea gets planned
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("XaeroNav: Long-range route map (known cells={}/{}, {}, per layer={}, unloaded regions={}, read={}ms)",
                    map.knownCells(), map.totalCells(), map.kindBreakdown(), window.layerBreakdown(),
                    window.pendingRegions(), window.readMillis());
        }
        // Continuously watch whether real-world stutter comes from the main thread's map reads. Above 50 ms, equivalent to
        // 1 tick (20 TPS), notify every 5 seconds until it drops back below the threshold (every time would be a flood)
        if (window.readMillis() > SLOW_MAP_READ_THRESHOLD_MILLIS
                && slowMapReadGate.changed(true, MonotonicTime.millis(), SLOW_MAP_READ_LOG_INTERVAL_MILLIS)) {
            LOGGER.warn("XaeroNav: Long-range route map read is slow ({}ms > {}ms)",
                    window.readMillis(), SLOW_MAP_READ_THRESHOLD_MILLIS);
        }
        return new CoarseRead(map, window.pendingRegions());
    }

    /**
     * Plans a waypoint list with {@link CoarseRouter} from the map that was read ({@link #readCoarseMap}). Doesn't read Minecraft or Xaero
     * state, so it can be called from any thread (most of the time is spent here, not reading the map).
     *
     * <p>Lava handling escalates in two steps. If layer 1 decides to cut straight through a lava area, the detail search
     * can't reach that waypoint in principle (you can't walk on lava), so you get stuck at the edge of a Nether lava sea:
     *
     * <ol>
     *   <li>{@link CoarseRouter.BridgePolicy#AVOID}: avoid lava completely. If there's a road with a big detour or backtrack,
     *       A* finds it</li>
     *   <li>{@link CoarseRouter.BridgePolicy#BRIDGE}: let it through assuming you bridge across. A last resort, but better than being stuck</li>
     * </ol>
     *
     * <p>The map is read once and reused for {@code AVOID} and {@code BRIDGE} ({@link XaeroMapReader#readSurface} is
     * heavy on the main thread). This ladder runs only in {@link #freshRoute} and {@link #freshRouteInBackground}
     * (cached per goal), not every tick.
     *
     * <p>There used to be another 3-step ladder here re-reading with a different reference Y ({@link CoarseMap} could hold only one level
     * per cell, so in dimensions with a ceiling the visible terrain depended on the reference Y). Now that {@link CoarseMap}
     * can hold several floors at once, a single {@code readSurface} gathers all layers near the reference Y
     * as floors, and the ladder is no longer needed.
     */
    private static CoarseAttempt solveCoarseRoute(CoarseRead read, BlockPos start, BlockPos goal,
                                                  boolean boatAvailable, boolean swimmingEnabled) {
        CoarseMap map = read.map();
        if (map == null) {
            return new CoarseAttempt(new CoarseRouter.Route(List.of(), false), 0);
        }
        CoarseRouter.Route avoided = CoarseRouter.findRoute(map, start, goal, boatAvailable, swimmingEnabled,
                CoarseRouter.BridgePolicy.AVOID);
        if (avoided.reachedGoal()) {
            return new CoarseAttempt(avoided, read.pendingRegions());
        }

        // <b>Don't skip ALLOW.</b> The void opens at {@link CoarseRouter.BridgePolicy#ALLOW}, and
        // lava seas open one step later at {@code BRIDGE}; that step is documented in
        // {@code BridgePolicy}'s javadoc and {@code CoarseRouterTest#voidOpensOneStepEarlierThanLava},
        // yet this jumped straight from AVOID to BRIDGE. In the End, AVOID fails on the void at every island crossing,
        // so it <b>always ran with BRIDGE</b>, and in the Nether too, merely "not being able to avoid void or lava patches"
        // also opened routes cutting straight across lava seas
        CoarseRouter.Route allowed = CoarseRouter.findRoute(map, start, goal, boatAvailable, swimmingEnabled,
                CoarseRouter.BridgePolicy.ALLOW);
        if (allowed.reachedGoal()) {
            LOGGER.info("XaeroNav: No road avoiding void/lava patches found; switched to a long-range route through them");
            return new CoarseAttempt(allowed, read.pendingRegions());
        }

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, start, goal, boatAvailable, swimmingEnabled,
                CoarseRouter.BridgePolicy.BRIDGE);
        if (bridged.reachedGoal()) {
            LOGGER.info("XaeroNav: No road avoiding lava found; switched to a long-range route bridging across it");
            return new CoarseAttempt(bridged, read.pendingRegions());
        }
        return new CoarseAttempt(furtherRoute(furtherRoute(avoided, allowed), bridged), read.pendingRegions());
    }

    /** Distance within which a re-read counts as from roughly the same place (blocks). Beyond this, counting restarts as a different area. */
    private static final double MAP_GAIN_SAME_PLACE_BLOCKS = 32.0;

    /**
     * Number of re-reads before deciding "the map doesn't grow even though loading was requested".
     * Re-reads are every {@link #COARSE_MAP_RETRY_INTERVAL_MILLIS}, so 5 is about 15 seconds.
     */
    private static final int MAP_GAIN_ATTEMPTS = 5;

    private static BlockPos lastMapReadFrom;
    private static int lastMapKnownCells = -1;
    private static int mapReadsWithoutGain;
    private static final ChangeGate<Boolean> mapNotGrowingGate = new ChangeGate<>();

    /**
     * <b>Reports that the map isn't growing even though loading has been requested.</b>
     *
     * <p>A state where {@link XaeroMapReader#requestLoad} works but the loaded region's contents are empty
     * occurred on a real client (2026-09-18: firing {@code /xaeronav debug mapdata} 3 times in a row at the same spot
     * progressed "30 awaiting → 0 awaiting", yet known cells stayed at 3213/4225
     * without a single increase). This happens when Xaero has moved that region's cache to {@code .outdated}.
     * <b>If we stay silent, nobody can notice routes keep being decided on a thin map</b>
     * (the only way to notice was the diagnostic command).
     */
    private static void noteMapNotGrowing(BlockPos start, CoarseMapWindow.Window window, CoarseMap map) {
        boolean samePlace = lastMapReadFrom != null
                && horizontalDistance(start, lastMapReadFrom) <= MAP_GAIN_SAME_PLACE_BLOCKS;
        if (!samePlace || map.knownCells() > lastMapKnownCells) {
            mapReadsWithoutGain = 0;
        } else if (window.pendingRegions() > 0) {
            mapReadsWithoutGain++;
        }
        lastMapReadFrom = start;
        lastMapKnownCells = map.knownCells();
        if (mapReadsWithoutGain < MAP_GAIN_ATTEMPTS) {
            return;
        }
        if (!mapNotGrowingGate.changed(true, MonotonicTime.millis(), MAP_NOT_GROWING_LOG_INTERVAL_MILLIS)) {
            return;
        }
        LOGGER.warn("XaeroNav: Map isn't growing despite load requests ({} times in a row, known cells={}/{}, unloaded regions={})"
                        + " — Xaero may be unable to read its cache for this area"
                        + " (showing this area on the world map may fix it)",
                mapReadsWithoutGain, map.knownCells(), map.totalCells(), window.pendingRegions());
    }

    /** Interval between repeats of the warning above. Logging it continuously while the same state persists is pointless. */
    private static final long MAP_NOT_GROWING_LOG_INTERVAL_MILLIS = 60_000L;

    /**
     * The result of {@link #solveCoarseRoute} and the number of regions that <b>weren't loaded yet</b> when it was planned.
     * If greater than 0, waiting and replanning may give a different route.
     */
    private record CoarseAttempt(CoarseRouter.Route route, int pendingRegions) {
    }

    /** The long-range route map read on the main thread. {@code map} is null if there's no Xaero map. */
    private record CoarseRead(@Nullable CoarseMap map, int pendingRegions) {
    }

    /** Compares routes that didn't reach the goal. Takes the one with more waypoints = the one that got farther. */
    private static CoarseRouter.Route furtherRoute(CoarseRouter.Route a, CoarseRouter.Route b) {
        return b.waypoints().size() > a.waypoints().size() ? b : a;
    }

    private static List<BlockPos> replaceLast(List<BlockPos> waypoints, BlockPos replacement) {
        List<BlockPos> copy = new ArrayList<>(waypoints);
        copy.set(copy.size() - 1, replacement);
        return List.copyOf(copy);
    }

    static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Vertical margin of the search range. Unlike the horizontal margin, the default {@code searchVerticalMargin}
     * never widens, and in dimensions with a ceiling (Nether) that's fatal: with the default 32 only a slice
     * 65 blocks thick in Y is visible, and paths making a big vertical detour around a lava sea simply don't exist
     * because they're outside the search range (outside the box is treated as undiggable wall).
     *
     * <p>In dimensions with a ceiling {@code getHeight()} itself is only around 128 at most, so always using the full height
     * costs little. {@code widen} (the same retry trigger as horizontal) widens it likewise,
     * removing the failure mode of "range too narrow to reach" in the vertical direction too.
     */
    static int verticalSearchMargin(Level level, boolean widen) {
        int configured = XaeroNavConfig.INSTANCE.searchVerticalMargin();
        if (level.dimensionType().hasCeiling() || widen) {
            return Math.max(configured, level.getHeight());
        }
        return configured;
    }

    /**
     * Whether to insert a "get to the surface first" leg (surface-first navigation).
     *
     * <p>When the goal is on the surface, a search aiming only at the goal point tends to pick vertical digging straight up from below
     * because it's shortest. Only when departing underground, first inserting a search where "anywhere under the sky with y &gt;= groundLevel is the goal"
     * lets it choose according to the situation: a nearby cave or cliff if there is one, otherwise digging
     * (the search itself, {@link net.prason.xaeronav.pathfinding.async.PathfindingExecutor#submitToSurface}, tries
     * a non-digging road first and a digging one only if none is found).
     *
     * <p>Y alone isn't used for the decision because being low and being underground are different things. Riverbeds, valley floors and coasts
     * lie below the default {@code groundLevelY} (60) all the time, and inserting a relay leg every time you walk there
     * swings guidance in directions unrelated to the goal. If the sky is visible, it's already treated as the surface.
     *
     * <p>{@code surfaceY} is <b>the surface of the player's column</b> ({@link #surfaceReferenceY}). The configured
     * {@code groundLevelY} is a single constant for the world, so under a mountain the surface is far above it;
     * with the default (60), a player in a mountain cave at y=70 was judged "already at surface height",
     * and didn't enter the relay leg even 60 blocks underground (#45).
     */
    private boolean shouldClimbToSurface(Level level, BlockPos start, BlockPos goal, int surfaceY) {
        if (!climbWorthwhile(start.getY(), goal.getY(), surfaceY)) {
            return false;
        }
        // Dimensions without sky or with a ceiling (End/Nether) have no "surface" in the first place.
        // Entering surface-first navigation in the Nether would give guidance digging toward the bedrock ceiling
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            return false;
        }
        // If the sky is visible overhead, we're on the surface. Insert the relay leg only when under a roof or in a cave.
        //
        // canSeeSkyFromBelowWater is used instead of canSeeSky because water attenuates skylight, so
        // canSeeSky is always false underwater. The sea floor is below the default groundLevelY (60), so
        // just diving was judged "in a cave" and entered the relay leg. Moreover the relay leg's goal is
        // openSkyY (MOTION_BLOCKING includes fluids, so one above the water surface = out of the water), which is air with no footing
        // = unreachable in principle on the open sea, and the relay path that fell short was displayed as an empty path.
        // That was the real cause of "the line doesn't extend from under the sea".
        if (level.canSeeSkyFromBelowWater(start.above())) {
            return false;
        }
        BlockPos failedAt = surfaceLegFailedAt;
        return failedAt == null
                || failedAt.distSqr(start) > SURFACE_RETRY_MOVE_BLOCKS * SURFACE_RETRY_MOVE_BLOCKS;
    }

    /**
     * Whether a "get to the surface first" leg is needed, judged by height alone. {@code surfaceY} is the surface to exit to
     * ({@link #surfaceReferenceY}).
     *
     * <p>{@code Level} is separated out because extracting just this lets the behaviour be pinned down without a world
     * (same as {@code Splice#joinableStepIndex}).
     *
     * <p>The key is looking at the goal <b>by the same standard</b>. A goal below the surface = moving from cave to cave, so
     * there's no reason to go out to the surface and dive back in (#45 "going through caves from cave to cave is fine").
     */
    static boolean climbWorthwhile(int startY, int goalY, int surfaceY) {
        return goalY >= surfaceY && startY <= surfaceY - MIN_UNDERGROUND_DEPTH;
    }

    /**
     * The Y that the "get to the surface first" leg exits to. <b>The surface of the player's column</b>; for unreadable columns
     * it falls back to the configured {@code groundLevelY}.
     *
     * <p>Don't turn this back into a single world constant: in mountains, seas and plateaus the actual surface is
     * dozens of blocks away from it. Not only the relay leg's goal but also <b>the search box</b> is built from this value
     * ({@code recalculate}'s virtual goal), so if it's off the box shifts below the player.
     */
    private static int surfaceReferenceY(Level level, BlockPos start) {
        int local = ChunkView.openSkyY(level, start.getX(), start.getZ());
        return local == Integer.MAX_VALUE ? XaeroNavConfig.INSTANCE.groundLevelY() : local;
    }

    /**
     * The reason we decided the goal is unreachable. Shared by the HUD text, chat notification and logs.
     *
     * <p>The text key lives on the enum so that adding a reason demands adding a translation key in the same
     * place (with a switch on the HUD side, adding to only one side would pass silently).
     */
    public enum StuckReason {
        /** Proven that there's no way to reach it within the search range (the open set was exhausted). */
        NO_WAY_THROUGH("hud.xaeronav.unreachable_blocked"),
        /** Can't get closer even after using up the resources. The terrain is too complex for the detail search to solve. */
        SEARCH_TOO_HARD("hud.xaeronav.unreachable_too_hard"),
        /** Not connected to the goal on the coarse map (Xaero's map data). */
        UNMAPPED("hud.xaeronav.unreachable_unmapped"),
        /** No way to reach it within the configured limits. The settings require strict limits, so they weren't relaxed. */
        LIMITS_HELD("hud.xaeronav.unreachable_limits");

        private final String hintKey;

        StuckReason(String hintKey) {
            this.hintKey = hintKey;
        }

        /** Key of the text summarizing the cause and what the user can do about it in one line. */
        public String hintKey() {
            return hintKey;
        }
    }

    /** The kind of destination the displayed path heads to. */
    enum PathMode {
        GOAL,
        TO_SURFACE,
        WAYPOINT
    }

    /**
     * The displayed path and the kind of destination it heads to ({@link PathMode}).
     *
     * <p>With the two in separate fields, at the moment the path is replaced only one would be in the new state.
     * In fact, when the mode was updated at search start and the path at completion, for one tick right after reaching the surface
     * it was "relay path + goal mode", and the relay path's end (= your current feet) was used in the arrival check
     * in place of the goal, ending guidance with "Arrived!" without having arrived.
     *
     * @param waypointIndex when {@code mode} is {@link PathMode#WAYPOINT}, which entry of {@link CoarseRoute#waypoints()}
     *         it points at (0-based). {@code -1} in other modes.
     *         This is where <b>the path's end</b> is heading; where the player is walking now is
     *         derived from {@link #segments} (with look-ahead the path extends several legs ahead, so the two differ)
     * @param segments boundaries of extended legs. With look-ahead, one path includes several waypoints' worth, so
     *         this is needed for the HUD's progress (which waypoint the player is heading to now)
     */
    record DisplayedPath(PathResult result, PathMode mode, int waypointIndex,
                          List<PathSegment> segments) {

        DisplayedPath(PathResult result, PathMode mode, int waypointIndex) {
            this(result, mode, waypointIndex,
                    List.of(new PathSegment(Math.max(0, result.steps().size() - 1), waypointIndex)));
        }

        /** The number of the waypoint the leg containing {@code stepIndex} is heading to. */
        int waypointIndexAtStep(int stepIndex) {
            for (PathSegment segment : segments) {
                if (stepIndex <= segment.endStep()) {
                    return segment.waypointIndex();
                }
            }
            return waypointIndex;
        }
    }

    /**
     * One extended leg. {@code endStep} is the index of this leg's last step (inclusive).
     *
     * <p>It's held as a step index because extension doesn't change earlier indices; holding coordinates would
     * misidentify leg boundaries on terrain where the path passes near itself.
     */
    record PathSegment(int endStep, int waypointIndex) {
    }

    /**
     * Cache of the long-range route's waypoints. Terrain doesn't change, so it isn't replanned unless the goal changes.
     *
     * @param computedFrom the start when this route was planned. Replanning from the same spot gives the same result, so
     *         it's used for matching to avoid that ({@link #COARSE_ROUTE_RETRY_MOVE_BLOCKS})
     * @param reachedGoal whether it reached the goal on the coarse map. If not, however much the detail search
     *         runs it won't get there (on the coarse map only lava is impassable; unexplored cells are
     *         treated as passable), so it's input for pinning down the stuck reason
     * @param pendingRegions the number of regions that were on disk but not yet loaded into memory when this route was
     *         planned. If greater than 0, <b>this route was planned on an incomplete map</b>,
     *         and it's replanned after waiting for loading ({@link #cachedOrFreshRoute})
     */
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, boolean reachedGoal, int pendingRegions,
                                List<BlockPos> waypoints) {
    }

    /** The long-range route map and the goal it was read for. */
    private record CoarseMapForGoal(BlockPos goal, CoarseMap map) {
    }

    /** A long-range route request being solved in the background ({@link #solvingCoarse}). */
    private record CoarseSolve(BlockPos goal) {
    }

    /**
     * The waypoint list refined by layer 2, and the {@link CoarseRoute} it came from.
     *
     * <p>Holding the origin is the point. Re-navigating to the same goal doesn't change {@code goal}, so
     * matching coordinates alone can't reject an older generation's refinement. Checking on the writing side leaves
     * a race where "the generation advances between checking and writing", so <b>at read time</b>
     * check whether it's the same instance as the current {@code coarseRoute} (order-independent).
     */
    private record RefinedRoute(BlockPos goal, CoarseRoute source, List<BlockPos> waypoints) {
    }

    /** Return value of {@link #selectDetailTarget}. The pair of the detail search's goal and which entry of the coarse route it is. */
    /**
     * @param goalRadius the radius within which the detail search counts this goal as "touched" (blocks).
     *                   A relay point is only <b>a direction to head in, not a place to pass through</b>, so demanding the exact
     *                   coordinate puts a detour for it on the path. The uncertainty differs by the target's origin,
     *                   so it varies per origin (see {@link #WAYPOINT_GOAL_RADIUS_BLOCKS})
     */
    record DetailTarget(BlockPos target, int waypointIndex, int goalRadius) {
    }
}
