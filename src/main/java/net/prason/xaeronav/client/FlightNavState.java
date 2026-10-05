package net.prason.xaeronav.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.pathfinding.flight.AirGrid;
import net.prason.xaeronav.pathfinding.flight.CoarseAirMap;
import net.prason.xaeronav.pathfinding.flight.CoarseFlightField;
import net.prason.xaeronav.pathfinding.flight.CoarseFlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightHorizon;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightTuning;
import net.prason.xaeronav.pathfinding.flight.TurnBack;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.GameCompat;

/**
 * Guidance while gliding with an elytra. Holds the 3D air route (thick line) and the dotted line of intermediate
 * targets connecting beyond it.
 *
 * <p>A <b>separate pipeline</b> from walking ({@link PathfindingState}). Search cutoffs and freshness checks differ,
 * so state is not mixed. Whether you are gliding and the destination itself are owned by {@link PathfindingState};
 * this class handles only "the air route toward that destination".
 *
 * <p>Fields marked {@code volatile} are written by the worker thread and read by the client thread.
 * Everything else is client-thread only.
 */
final class FlightNavState {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Minimum interval (ticks) between re-throwing the air route. Deviation and movement triggers can fire almost every
     * tick while flying fast, so without this, searches keep piling up on the worker thread.
     */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /**
     * Re-plan once the player is this far from the long-distance air route (blocks).
     *
     * <p>The key point is measuring <b>distance off the route</b>, not distance moved. {@code XaeroMapReader.readSurface}
     * is <b>main-thread only and heavy</b>, and triggering on distance moved runs it every few seconds at elytra speed:
     * this is what caused the server thread to report "Can't keep up (7250ms behind)" in-game. The long-distance route
     * is drawn all the way to the destination, so <b>as long as you fly along it, there is no reason to re-plan</b>.
     */
    private static final double COARSE_OFF_ROUTE_BLOCKS = 192.0;

    /** When the remaining intermediate targets drop below this, re-plan to build what lies ahead. */
    private static final int COARSE_MIN_REMAINING_WAYPOINTS = 4;

    /**
     * Maximum horizontal distance the air route aims at in one go (blocks).
     *
     * <p><b>The key point is that it is a fixed value independent of terrain</b>: for exactly the same reason as walking's
     * {@code detailHorizonBlocks}, estimating the "reachable distance" from render distance always misses. Back when it
     * aimed at 75% of render distance (384), the search did not reach that far and <b>the route was cut where the budget
     * ran out</b>, so the cut point moved every time the player advanced a little and the end of the thick line jumped
     * around (user report: "the destination keeps changing a lot"). Fixed inside the measured reach (around 280 in the
     * Nether) so the route can be drawn all the way to where it aims.
     */
    private static final int DETAIL_HORIZON_BLOCKS = 256;

    /**
     * When the route's end gets closer than this, extend beyond the end (blocks).
     *
     * <p>Flying at 1.5 blocks/tick, 160 blocks is about 5 seconds. One search takes 1 to 2 seconds, so without about this
     * much slack <b>you fly all the way to the end before the next route comes out</b> (user report: "it went to where
     * there was no more route before searching for one").
     */
    private static final int EXTEND_LEAD_BLOCKS = 160;

    /** Extensions shorter than this are not thrown (blocks). Not worth one search. */
    private static final int MIN_EXTENSION_BLOCKS = 64;

    /**
     * Retry an end where extension failed once the player has moved this far (blocks).
     * Failures are usually temporary (what lies beyond is not loaded yet) and may succeed after advancing.
     */
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 48.0;

    /**
     * Fraction of the render radius that can be relied on as loaded. It is not always readable up to render distance
     * (measured: the Nether only had the equivalent of a 173-block radius loaded), so the edge is not relied on.
     */
    private static final double LOADED_MARGIN = 0.9;

    /**
     * Margin (blocks) kept below the bedrock ceiling. The ceiling is opaque, so it is not recorded as a floor in Xaero's
     * cave layers: without capping here, the top altitude band extends into the rock.
     */
    private static final int CEILING_MARGIN_BLOCKS = 10;

    /**
     * When extending ahead, if you are this much closer (blocks, horizontal) than the point closest to the destination
     * among the places re-planned so far, re-plan instead of extending from the end.
     *
     * <p>Extending from the end is tied to the exit chosen within the readable range at the time the end was decided.
     * Re-planning within the newly readable range after advancing gives a shorter route (bench optimal ratio:
     * Overworld 1.072→1.044, Nether 1.335→1.200). It compares against "the closest point reached" rather than "the
     * previous one" because on terrain where the estimate outside the readable range is off, the exit can swap back and
     * forth between two dead ends; using the closest point reached as the reference means oscillating cannot satisfy it.
     */
    private static final double REROUTE_PROGRESS_BLOCKS = 64.0;

    /**
     * Even when re-planning, keep the current line this far ahead of the player (blocks). The near line is what you are
     * following right now, and redrawing it makes the guidance look shaky (the same idea as walking re-solving only
     * around seams).
     */
    private static final double REROUTE_KEEP_BLOCKS = 48.0;

    /**
     * Asks the owner whether an asynchronous result may be applied.
     *
     * <p>Freshness is checked by matching the destination and dimension, not via the {@code generation} shared with
     * walking A*: this is a separate pipeline, with no reason to be dragged into that one's cutoffs or generation advances.
     */
    @FunctionalInterface
    interface Current {
        /** Whether we are still gliding and neither the destination nor the dimension has changed since computing. */
        boolean stillFlyingTo(BlockPos goal, ResourceKey<Level> dimension);
    }

    /**
     * Long-distance air route. As in the walking version, it remembers the destination and computation point together so
     * the reader can check "is it for the current destination" and "is it not re-planned from the same place".
     */
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, List<BlockPos> waypoints,
                               @Nullable CoarseFlightField field) {
    }

    /** Intermediate targets of the long-distance route, and the remaining-cost field to the destination derived from the same map. */
    private record CoarseSolution(List<BlockPos> waypoints, @Nullable CoarseFlightField field) {
        static final CoarseSolution NONE = new CoarseSolution(List.of(), null);
    }

    /**
     * The air route and the curved dotted line used in its place. The computing side decides which to use.
     * {@code coarse} is non-null only when this job re-planned the long-distance route.
     */
    private record Guidance(FlightRoute route, List<Vec3> bend, BlockPos from, @Nullable CoarseSolution coarse) {
    }

    /**
     * Result of an extension. {@code cut} is the reconnection when it came back to the earlier route, and
     * {@code turnsBack} is whether it was heading back ({@link TurnBack}).
     */
    private record Extension(FlightRoute route, int segmentAtStart, TurnBack.@Nullable Cut cut) {
    }

    /** What to do this tick while gliding. */
    private enum Action {
        /** Re-plan everything. The near guidance gets redrawn too. */
        RECOMPUTE,
        /** Extend only beyond the end. The near part stays as-is by definition. */
        EXTEND,
        NOTHING
    }

    private final Current current;

    /**
     * Notification called on each asynchronous completion of the air route. Lets the owner ({@link PathfindingState})
     * re-publish the atomic snapshot for rendering. Changes via synchronous calls such as {@code tick}/{@code recalculate}
     * are published by the caller itself at the end of the tick, so calling this only from worker-completion
     * callbacks (after returning to the main thread) is enough.
     */
    private final Runnable onChanged;

    /**
     * Dedicated to computing the curved dotted line while gliding. It shares neither lifecycle nor cutoff behavior with
     * A*, so it owns one plain thread rather than a {@code PathfindingExecutor} (which cancels the previous job on each call).
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "xaeronav-flight-line");
                thread.setDaemon(true);
                return thread;
            });

    /** The air route (the main body drawn as a thick line). Empty if none could be drawn. */
    private volatile FlightRoute route = FlightRoute.NONE;

    /**
     * Whether a search is running on the worker thread. {@link #executor} does not cancel previous jobs but
     * <b>queues them</b>, so without this a search taking 2 seconds is thrown every second and the queue grows without
     * bound (in-game log: 100,000 nodes, 2.1 seconds in the Nether). The displayed route keeps falling further behind,
     * and the CPU keeps burning.
     */
    private volatile boolean computing;

    /**
     * Generation of the job that owns {@link #computing}. An identity that keeps old callbacks from lowering a new job's
     * running flag; submission and reset happen only from the client thread.
     */
    private volatile long jobGeneration;

    /** Player position when {@link #route} was computed. Moving away from here = new chunks are readable. */
    private volatile BlockPos computedFrom;

    /**
     * The end where extension failed and the player position at that time. Re-throwing from the same place gives the
     * same result since neither loaded chunks nor terrain have changed; without a brake, it keeps running full-budget
     * searches forever at a dead-end end.
     */
    private volatile Vec3 extendBlockedAt;
    private volatile BlockPos extendBlockedFrom;

    /**
     * Intermediate targets beyond render distance (from Xaero's map, only in dimensions with a ceiling). The air route,
     * which looks at loaded chunks, always plateaus at render distance, so this is the only thing connecting beyond it.
     */
    private volatile CoarseRoute coarseRoute;

    /** Minimum horizontal distance to the destination among the places re-planned so far ({@link #REROUTE_PROGRESS_BLOCKS}). */
    private double bestRerouteDistance = Double.POSITIVE_INFINITY;

    /**
     * Number of intermediate targets counted as passed. Used only to decide where to start drawing the dotted line on the
     * map and in the world. Advances monotonically, so passed targets do not come back even if the route shortens from
     * running out of budget and its end retreats.
     */
    private volatile int passedWaypoints;

    /** Fallback when the air route could not be drawn. 2 or 3 points bending the dotted line to the destination over or around mountains. */
    private volatile List<Vec3> guideWaypoints;

    /** Client thread only. */
    private int ticksSinceRecalc;

    FlightNavState(Current current, Runnable onChanged) {
        this.current = current;
        this.onChanged = onChanged;
    }

    /**
     * The air route. The first point is the player position <b>at computation time</b>, so by the time it arrives it may
     * be up to one recompute interval old. The renderer should drop the first point and draw from the current position.
     */
    FlightRoute route() {
        return route;
    }

    /**
     * The point from which to start drawing the polyline. An index for not drawing stretches already passed; it must
     * always be shared between in-world rendering and the map (trimming only one leaves a line behind you on the map only).
     */
    int routeFrom() {
        return FlightProgress.INSTANCE.segmentFor(route) + 1;
    }

    /**
     * Intermediate points the dotted line should follow. <b>Includes neither the start nor the destination</b>: the
     * renderer has both itself (the start is the end of the thick line or the current position, the end is the
     * destination), so including the ends would always require shifting indices.
     *
     * <p>Returns the long-distance route's intermediate targets if there is one, otherwise falls back to the curved
     * dotted line. From the caller's view it is a single question, "where to bend the dotted line", so the two sources
     * are merged into one here.
     */
    List<Vec3> dashWaypoints(boolean airborne, boolean done, BlockPos currentGoal) {
        if (!airborne) {
            return List.of();
        }
        List<BlockPos> coarse = coarseWaypoints(airborne, done, currentGoal);
        if (!coarse.isEmpty()) {
            return coarse.stream().map(Vec3::atCenterOf).toList();
        }
        List<Vec3> bend = guideWaypoints;
        // findGuideLine returns [start, bend points, end], so drop both ends
        return bend == null || bend.size() < 3 ? List.of() : List.copyOf(bend.subList(1, bend.size() - 1));
    }

    /** Drops only the drawn route. For cases like landing, arrival, or spectator where the long-distance route is still alive. */
    void dropRoute() {
        route = FlightRoute.NONE;
        guideWaypoints = null;
        computedFrom = null;
    }

    /** Drops everything including the destination and logically cancels the running job. */
    void reset() {
        jobGeneration++;
        computing = false;
        // A running supplier may continue to a safe interruption point, but all waiting old jobs are dropped, keeping only the latest.
        executor.getQueue().clear();
        dropRoute();
        coarseRoute = null;
        bestRerouteDistance = Double.POSITIVE_INFINITY;
        passedWaypoints = 0;
        extendBlockedAt = null;
        extendBlockedFrom = null;
    }

    /** One tick while gliding. Updates progress and, if needed, submits a re-plan or extension. */
    void tick(Level level, Player player, BlockPos currentGoal) {
        FlightProgress.INSTANCE.update(route, player.position());
        advancePassedWaypoints(player, currentGoal);
        ticksSinceRecalc++;
        switch (action(player)) {
            case RECOMPUTE -> recalculate(currentGoal);
            case EXTEND -> extend(level, player, currentGoal);
            case NOTHING -> {
            }
        }
    }

    /**
     * Re-plans the air route from scratch.
     *
     * <p>The long-distance route (reading Xaero's map) is done first on the main thread, and only immutable results are
     * passed to the worker, because {@code XaeroMapReader.readSurface} is main-thread only.
     */
    void recalculate(BlockPos currentGoal) {
        ticksSinceRecalc = 0;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            // Spectators pass through blocks (Player#isSpectator → noPhysics). Bending the line around terrain that need
            // not be avoided only makes a place you could fly straight through look like a detour.
            // Player#isSpectator() is not used because it goes through the tab list's PlayerInfo and
            // silently falls back to false if that has not been received (AbstractClientPlayer#isSpectator)
            dropRoute();
            return;
        }

        Vec3 start = player.position();
        BlockPos from = player.blockPosition();
        Vec3 goalVec = Vec3.atCenterOf(currentGoal);
        ResourceKey<Level> dimension = level.dimension();
        boolean rockets = hasRockets(player);
        boolean routing = XaeroNavConfig.INSTANCE.flightRoutingEnabled();
        FlightTuning tuning = tuning();
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // Match the horizontal margin to render distance so the whole loaded square is in the search range.
        // Routes around a wall leave the band connecting start and destination, so a narrow margin cannot go around
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), currentGoal,
                routing ? renderRadius : FlightLineRouter.HORIZONTAL_MARGIN_BLOCKS,
                FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        // Digging, block placement, gap jumps, and fall damage are all irrelevant to flight checks, so all false
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        // For the long-distance route only the map reading is done here (main thread); solving happens on the worker.
        // Solving takes several times longer than reading, and solving synchronously would freeze rendering each time the target changes
        CoarseRequest coarseRequest = routing ? coarseRequest(level, player, currentGoal) : CoarseRequest.NONE;
        int minAirY = GameCompat.minBuildHeight(level) + CEILING_MARGIN_BLOCKS;
        int maxAirY = GameCompat.maxBuildHeight(level) - 1 - CEILING_MARGIN_BLOCKS;
        FlightHorizon horizon = loadedHorizon(start, renderRadius);
        long myJob = ++jobGeneration;
        computing = true;

        CompletableFuture
                .supplyAsync(() -> {
                    CoarseSolution fresh = coarseRequest.fresh()
                            ? solveCoarse(coarseRequest.map(), minAirY, maxAirY, from, currentGoal, rockets)
                            : null;
                    CoarseFlightField field = fresh != null ? fresh.field() : coarseRequest.field();
                    // Aim at the destination itself. Even outside the readable range it is cut off at the edge (horizon), and which edge it
                    // exits from is estimated, detours included, by the coarse map's remaining-cost field. Aiming at a nearer intermediate target
                    // burned budget every time the target point landed inside rock, and combined with re-planning it oscillated
                    FlightRoute solved = routing
                            ? FlightRouter.route(view, start, goalVec, rockets, tuning, horizon, field,
                                    () -> jobGeneration != myJob)
                            : FlightRoute.NONE;
                    // The curved dotted line is needed only when no route could be drawn. Overlaying it when a route exists makes
                    // the dotted line extending from the end to the destination bend around distant mountains
                    List<Vec3> bend = solved.isEmpty()
                            ? new FlightLineRouter(view).findGuideLine(start, goalVec)
                            : null;
                    return new Guidance(solved, bend, from, fresh);
                }, executor)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: failed to compute the route while gliding", error);
                            return;
                        }
                        if (result.coarse() != null) {
                            // The list was rebuilt, so the meaning of the indices changes
                            coarseRoute = new CoarseRoute(currentGoal, from, result.coarse().waypoints(),
                                    result.coarse().field());
                            passedWaypoints = 0;
                        }
                        if (current.stillFlyingTo(currentGoal, dimension)) {
                            route = result.route();
                            guideWaypoints = result.bend();
                            computedFrom = result.from();
                        }
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /**
     * What to do this tick. The same priority as the walking side: <b>re-plan only when the route is wrong</b>, and
     * extending forward is the job of extension.
     *
     * <p>It used to try extending by "re-plan after moving 48 blocks", but as soon as the target was pinned (a brake to
     * stabilize the aim), re-planning only produced <b>a short route to the same target</b>.
     * The further you went the shorter the line got, and the next one came out only after reaching the end. Stabilizing
     * the target and extending the line forward cannot coexist with full replacement: that is why extension is needed.
     */
    private Action action(Player player) {
        if (computing) {
            // The previous search has not finished yet. Queuing more would just apply stale results first
            return Action.NOTHING;
        }
        if (ticksSinceRecalc < MIN_RECALC_INTERVAL_TICKS) {
            // Even if triggers fire in quick succession, the search submission interval is capped here
            return Action.NOTHING;
        }
        if (FlightProgress.INSTANCE.deviated(XaeroNavConfig.INSTANCE.flightDeviationThresholdBlocks())) {
            return Action.RECOMPUTE;
        }
        Vec3 tail = route.tail();
        if (tail == null) {
            // No route yet. Re-throw periodically, but from the same place the result will not change, so only after moving
            if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.flightRecalcIntervalTicks()) {
                return Action.NOTHING;
            }
            return computedFrom == null || !computedFrom.equals(player.blockPosition())
                    ? Action.RECOMPUTE : Action.NOTHING;
        }
        if (player.position().distanceTo(tail) > EXTEND_LEAD_BLOCKS) {
            return Action.NOTHING;
        }
        if (tail.equals(extendBlockedAt) && extendBlockedFrom != null
                && Math.sqrt(extendBlockedFrom.distSqr(player.blockPosition()))
                        < EXTEND_RETRY_MOVE_BLOCKS) {
            // Could not extend from this end. Wait until the player moves and new chunks become readable
            return Action.NOTHING;
        }
        return Action.EXTEND;
    }

    /**
     * Builds the air route tuning values from the config. Assembled in this one place so diagnostic commands measure under
     * exactly the same conditions as production (assembling separately makes measured numbers disagree with actual guidance).
     */
    static FlightTuning tuning() {
        return tuning(XaeroNavConfig.INSTANCE.flightMaxExpandedNodes());
    }

    private static FlightTuning tuning(int maxExpandedNodes) {
        XaeroNavConfig config = XaeroNavConfig.INSTANCE;
        return new FlightTuning(config.flightCellBlocks(),
                config.flightClearanceDetourBlocks() * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK,
                new SearchLimits(maxExpandedNodes, AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
                        config.flightHeuristicWeight()));
    }

    /**
     * Intermediate targets of the long-distance route: <b>only those not yet passed</b>. An empty list if none.
     *
     * <p>The dotted line follows these. Up to where the thick line (the air route looking at loaded chunks) reaches is a
     * reliable route, and beyond that only "which way to head" can be said; that distinction is shown directly in the visuals.
     */
    private List<BlockPos> coarseWaypoints(boolean airborne, boolean done, BlockPos currentGoal) {
        if (!airborne || done) {
            return List.of();
        }
        CoarseRoute existing = coarseRoute;
        if (existing == null || !existing.goal().equals(currentGoal)) {
            return List.of();
        }
        int from = passedWaypoints;
        return from >= existing.waypoints().size() ? List.of()
                : existing.waypoints().subList(from, existing.waypoints().size());
    }

    /**
     * Advances where the dotted line starts being drawn.
     *
     * <p><b>The reference is the end of the thick line, not the player.</b> The dotted line continues from the end, but
     * trimming was done relative to the player, so intermediate targets before the end remained: the dotted line went
     * from the end back near you before heading onward again, <b>looking like there were two routes</b>
     * (user report). The same thing walking already separates as "the map's dotted line uses the end of the route, the
     * HUD uses the leg the player is on".
     *
     * <p>It only ever advances so that passed targets do not come back when the route shortens from running out of
     * budget and its end retreats. When the long-distance route is re-planned, {@link #recalculate}'s completion callback
     * resets it to 0.
     */
    private void advancePassedWaypoints(Player player, BlockPos currentGoal) {
        CoarseRoute existing = coarseRoute;
        if (existing == null || !existing.goal().equals(currentGoal)) {
            return;
        }
        Vec3 tail = route.tail();
        Vec3 from = tail == null ? player.position() : tail;
        passedWaypoints = Math.max(passedWaypoints,
                nearestWaypointIndex(existing.waypoints(), from) + 1);
    }

    /** Index of the intermediate target closest to {@code position}. -1 for an empty list. */
    private static int nearestWaypointIndex(List<BlockPos> waypoints, Vec3 position) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double distance = centerDistanceSq(waypoints.get(i), position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * Reads the map if the long-distance route is to be re-planned, otherwise returns the cache. <b>Main thread only</b>
     * ({@code XaeroMapReader.readSurface} touches the same structures as Xaero's writer thread).
     *
     * <p>Built only in dimensions with a ceiling. In the Overworld and the End you can climb high and fly straight, so a
     * chunk-resolution coarse layer adds almost no information: only a bedrock ceiling forces horizontal detours.
     */
    private CoarseRequest coarseRequest(Level level, Player player, BlockPos currentGoal) {
        if (!level.dimensionType().hasCeiling()) {
            coarseRoute = null;
            return CoarseRequest.NONE;
        }
        CoarseRoute existing = coarseRoute;
        if (existing != null && existing.goal().equals(currentGoal) && stillFollowing(existing, player)) {
            return new CoarseRequest(false, null, existing.field());
        }
        return new CoarseRequest(true, readCoarseMap(player.blockPosition(), currentGoal), null);
    }

    /**
     * How to prepare the long-distance route. If {@code fresh}, re-solve from {@code map} (the map read, or null if
     * none); otherwise reuse the previous field ({@code field}) as-is.
     */
    private record CoarseRequest(boolean fresh, @Nullable CoarseMap map, @Nullable CoarseFlightField field) {
        static final CoarseRequest NONE = new CoarseRequest(false, null, null);
    }

    /**
     * Solves one long-distance air route from Xaero's map. <b>Main thread only</b>
     * ({@code XaeroMapReader.readSurface} touches the same structures as Xaero's writer thread).
     *
     * <p>The diagnostic command ({@code /xaeronav debug flight}) must go through here too. Assembling ranges and margins
     * separately makes measured numbers disagree with actual guidance: in fact, the diagnostic side's own implementation
     * always had a chunk range one smaller, and reported "0 intermediate targets" when the destination fell outside the
     * map (the same reason {@link #tuning()} lives in one place).
     */
    static CoarseRouter.Route solveCoarseRoute(Level level, BlockPos from, BlockPos goal, boolean rockets) {
        return solveCoarseRoute(readCoarseMap(from, goal), GameCompat.minBuildHeight(level) + CEILING_MARGIN_BLOCKS,
                GameCompat.maxBuildHeight(level) - 1 - CEILING_MARGIN_BLOCKS, from, goal, rockets);
    }

    /** Reads the map for the long-distance route. <b>Main thread only</b>. null if there is no Xaero map. */
    private static @Nullable CoarseMap readCoarseMap(BlockPos from, BlockPos goal) {
        return CoarseMapWindow.read(from, goal, CoarseAirMap.MAX_BANDS).map();
    }

    /** Solves the long-distance route from a map that has been read. Does not read Minecraft or Xaero state, so it can be called from any thread. */
    private static CoarseRouter.Route solveCoarseRoute(@Nullable CoarseMap map, int minAirY, int maxAirY,
                                                       BlockPos from, BlockPos goal, boolean rockets) {
        if (map == null) {
            return new CoarseRouter.Route(List.of(), false);
        }
        return CoarseFlightRouter.findRoute(CoarseAirMap.from(map, minAirY, maxAirY), from, goal, rockets);
    }

    /** In addition to {@link #solveCoarseRoute}, also builds the remaining-cost field to the destination from the same map. Callable from any thread. */
    private static CoarseSolution solveCoarse(@Nullable CoarseMap map, int minAirY, int maxAirY, BlockPos from,
                                              BlockPos goal, boolean rockets) {
        if (map == null) {
            return CoarseSolution.NONE;
        }
        CoarseAirMap air = CoarseAirMap.from(map, minAirY, maxAirY);
        return new CoarseSolution(CoarseFlightRouter.findRoute(air, from, goal, rockets).waypoints(),
                CoarseFlightField.toward(air, goal, rockets));
    }

    /**
     * Whether that long-distance route can still be followed. As long as it can, do not re-plan: the same map gives the
     * same result, and it would only burn one main-thread map read.
     */
    private boolean stillFollowing(CoarseRoute existing, Player player) {
        List<BlockPos> waypoints = existing.waypoints();
        if (waypoints.size() - passedWaypoints < COARSE_MIN_REMAINING_WAYPOINTS) {
            // Almost nothing left. The only way to build what lies ahead is to re-plan
            return false;
        }
        int nearest = nearestWaypointIndex(waypoints, player.position());
        return nearest >= 0 && Math.sqrt(centerDistanceSq(waypoints.get(nearest), player.position()))
                <= COARSE_OFF_ROUTE_BLOCKS;
    }

    /**
     * Extends beyond the end of the route. <b>The near part is never touched</b>, so the guidance does not flicker as it grows.
     *
     * <p><b>Place the extension target inside the loaded square centered on the player.</b> If it were set at a fixed
     * distance from the end, the target would be up to "render radius + that distance" from the player = <b>always
     * inside unloaded chunks</b>. Unloaded is unflyable, so the search fails every time and the extension never
     * succeeds once: the same shape as the hole the walking side actually fell into ({@code extendLead}).
     */
    private void extend(Level level, Player player, BlockPos currentGoal) {
        FlightRoute source = route;
        Vec3 tail = source.tail();
        if (tail == null || currentGoal == null) {
            return;
        }
        int renderRadius = level == null ? 0 : ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        // The "loaded room" remaining beyond the end. Targets beyond this fall inside unloaded chunks.
        //
        // <b>Also capped by the search horizon</b>. Loaded room can be as much as 460 blocks, but handing that to one
        // budget on intricate terrain does not reach: it burns up to the limit and returns a partial route of a few dozen
        // blocks. The in-game log showed 60,000 nodes × 1 second eight times in a row, growing 12 to 80 blocks
        // (the runs that did reach took 6 to 107 ms each and grew about 290 blocks). Whether the target is within reach
        // determines both speed and growth
        double lead = Math.min(DETAIL_HORIZON_BLOCKS,
                renderRadius * LOADED_MARGIN - player.position().distanceTo(tail));
        if (lead < MIN_EXTENSION_BLOCKS) {
            // Not enough room to extend yet. It opens up naturally as the player advances
            extendBlockedAt = tail;
            extendBlockedFrom = player.blockPosition();
            return;
        }

        if (horizontalDistance(player.position(), currentGoal) < bestRerouteDistance - REROUTE_PROGRESS_BLOCKS) {
            reroute(level, player, currentGoal, source, renderRadius);
            return;
        }
        // Aim at the destination itself (same reason as recalculate)
        Vec3 target = Vec3.atCenterOf(currentGoal);
        CoarseRoute existing = coarseRoute;
        CoarseFlightField field = existing != null && existing.goal().equals(currentGoal) ? existing.field() : null;
        if (tail.distanceTo(target) < MIN_EXTENSION_BLOCKS) {
            // The end is already just short of the destination. Nowhere to extend
            extendBlockedAt = tail;
            extendBlockedFrom = player.blockPosition();
            return;
        }

        boolean rockets = hasRockets(player);
        // The box is centered on the player. Making a box centered on the end while starting from it would spill outside for the same reason as above
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), new BlockPos(
                        Mth.floor(target.x), Mth.floor(target.y), Mth.floor(target.z)),
                renderRadius, FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        // Extensions join short stretches many times, so each budget is kept tight and makes up for it in count. Allowing
        // the full amount would take 2 seconds each time on cramped terrain for little growth, falling behind flight speed
        FlightTuning tuning = tuning(XaeroNavConfig.INSTANCE.flightExtendMaxExpandedNodes());
        BlockPos from = player.blockPosition();
        ResourceKey<Level> dimension = level.dimension();
        ticksSinceRecalc = 0;
        long myJob = ++jobGeneration;
        computing = true;

        long startedAt = System.nanoTime();
        // The exit is the edge of the readable range centered on the player. A circle centered on the end would put the
        // edge <b>behind</b> the end only a few dozen blocks away too, so as soon as the front is blocked it exits backward and the line turns back
        FlightHorizon horizon = loadedHorizon(player.position(), renderRadius);
        // "From the player to the end", used for the turn-back check. Measured at the time it was thrown
        int segmentAtStart = FlightProgress.INSTANCE.segmentFor(source);
        List<Vec3> ahead = ahead(source, segmentAtStart, player.position());
        CompletableFuture
                .supplyAsync(() -> {
                    FlightRoute grown = FlightRouter.route(view, tail, target, rockets, tuning, horizon, field,
                            () -> jobGeneration != myJob);
                    if (grown.isEmpty()) {
                        return new Extension(grown, segmentAtStart, null);
                    }
                    return new Extension(grown, segmentAtStart, TurnBack.cut(ahead, grown.points(),
                            new AirGrid(view, grown.cellBlocks())::clearLine));
                }, executor)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: failed to extend the air route", error);
                            return;
                        }
                        FlightRoute extension = result.route();
                        Vec3 grown = extension.tail();
                        LOGGER.debug("XaeroNav: air route extended ({}, expanded={}, {}ms, grew={} blocks, grid={})",
                                extension.termination(), extension.expandedNodes(),
                                (System.nanoTime() - startedAt) / 1_000_000L,
                                grown == null ? 0 : Mth.floor(tail.distanceTo(grown)), extension.cellBlocks());
                        if (!current.stillFlyingTo(currentGoal, dimension)) {
                            return;
                        }
                        // Discard if the extension target was swapped (a re-plan came in between)
                        if (route != source) {
                            return;
                        }
                        if (extension.isEmpty()) {
                            extendBlockedAt = tail;
                            extendBlockedFrom = from;
                            return;
                        }
                        if (!extension.complete() && tail.distanceTo(grown) < MIN_EXTENSION_BLOCKS) {
                            // It burned through the budget, or found there was nothing beyond and grew only a few dozen blocks.
                            // Re-throwing from this end would repeat the same thing, so wait until the player advances and the terrain changes.
                            // If we do not wait when there is nothing beyond, it keeps re-planning between two points every 10 ticks inside an enclosed space.
                            // The part that did grow is kept and joined
                            extendBlockedAt = extension.tail();
                            extendBlockedFrom = from;
                        } else {
                            extendBlockedAt = null;
                            extendBlockedFrom = null;
                        }
                        FlightRoute extended = result.cut() != null
                                ? spliced(source, result.segmentAtStart(), result.cut(), extension)
                                : source.append(extension);
                        if (result.cut() != null) {
                            LOGGER.debug("XaeroNav: air route extension came back toward the near part, so the out-and-back stretch was cut off");
                        }
                        // Without carrying over the mapping, passed stretches get redrawn just at the moment of extending
                        FlightProgress.INSTANCE.carryOver(extended);
                        route = extended;
                        computedFrom = from;
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /**
     * Instead of extending from the end, re-plans from {@link #REROUTE_KEEP_BLOCKS} ahead of the player to the
     * destination (see {@link #REROUTE_PROGRESS_BLOCKS}). The line up to there is kept, so the near part you are
     * following now is not redrawn.
     */
    private void reroute(Level level, Player player, BlockPos currentGoal, FlightRoute source, int renderRadius) {
        bestRerouteDistance = horizontalDistance(player.position(), currentGoal);
        int segment = FlightProgress.INSTANCE.segmentFor(source);
        List<Vec3> kept = keptAhead(source, segment, player.position(), REROUTE_KEEP_BLOCKS);
        Vec3 start = kept.get(kept.size() - 1);
        Vec3 goalVec = Vec3.atCenterOf(currentGoal);
        boolean rockets = hasRockets(player);
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), currentGoal, renderRadius,
                FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        FlightTuning tuning = tuning();
        FlightHorizon horizon = loadedHorizon(player.position(), renderRadius);
        CoarseRoute existing = coarseRoute;
        CoarseFlightField field = existing != null && existing.goal().equals(currentGoal) ? existing.field() : null;
        BlockPos from = player.blockPosition();
        ResourceKey<Level> dimension = level.dimension();
        ticksSinceRecalc = 0;
        long myJob = ++jobGeneration;
        computing = true;
        long startedAt = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> FlightRouter.route(view, start, goalVec, rockets, tuning, horizon, field,
                        () -> jobGeneration != myJob), executor)
                .whenComplete((solved, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: failed to re-plan the air route", error);
                            return;
                        }
                        LOGGER.debug("XaeroNav: re-planned the air route from {} blocks ahead ({}, expanded={}, {}ms)",
                                (int) REROUTE_KEEP_BLOCKS, solved.termination(), solved.expandedNodes(),
                                (System.nanoTime() - startedAt) / 1_000_000L);
                        if (!current.stillFlyingTo(currentGoal, dimension) || route != source || solved.isEmpty()) {
                            // If it could not be drawn, keep the current line. The next opportunity is an extension from the end
                            return;
                        }
                        List<Vec3> points = new java.util.ArrayList<>(source.points().subList(0, segment + 1));
                        points.addAll(kept);
                        points.addAll(solved.points().subList(1, solved.points().size()));
                        FlightRoute rerouted = new FlightRoute(points, solved.termination(),
                                source.expandedNodes() + solved.expandedNodes(), solved.cellBlocks());
                        FlightProgress.INSTANCE.carryOver(rerouted);
                        route = rerouted;
                        computedFrom = from;
                        extendBlockedAt = null;
                        extendBlockedFrom = null;
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /** The part of {@code route} from the leg the player is on onward (the first point is the player's position). */
    private static List<Vec3> ahead(FlightRoute route, int segment, Vec3 player) {
        List<Vec3> points = route.points();
        List<Vec3> result = new java.util.ArrayList<>();
        result.add(player);
        for (int i = segment + 1; i < points.size(); i++) {
            result.add(points.get(i));
        }
        return result;
    }

    /**
     * Reconnection when an extension comes back toward the near part. The original route is kept up to the leg the
     * player is on, so the progress mapping ({@link FlightProgress}) can be carried over as-is.
     */
    private static FlightRoute spliced(FlightRoute source, int segment, TurnBack.Cut cut, FlightRoute extension) {
        List<Vec3> points = new java.util.ArrayList<>(source.points().subList(0, segment + 1));
        points.addAll(cut.aheadKept());
        points.addAll(cut.rest());
        return new FlightRoute(points, extension.termination(), source.expandedNodes() + extension.expandedNodes(),
                source.cellBlocks());
    }

    /**
     * Points of {@code route} from beside the player (projection onto the current leg) up to {@code blocks} ahead; the
     * last element is that point. Up to the end if the route is shorter.
     */
    private static List<Vec3> keptAhead(FlightRoute route, int segment, Vec3 player, double blocks) {
        List<Vec3> points = route.points();
        Vec3 anchor = FlightProgress.INSTANCE.nearestOnRoute(route, player);
        List<Vec3> result = new java.util.ArrayList<>();
        Vec3 previous = anchor == null ? points.get(segment) : anchor;
        result.add(previous);
        double left = blocks;
        for (int i = segment + 1; i < points.size(); i++) {
            Vec3 next = points.get(i);
            double length = previous.distanceTo(next);
            if (length >= left) {
                result.add(length < 1.0e-6 ? next : previous.add(next.subtract(previous).scale(left / length)));
                return result;
            }
            result.add(next);
            left -= length;
            previous = next;
        }
        return result;
    }

    private static double horizontalDistance(Vec3 point, BlockPos goal) {
        return Math.hypot(goal.getX() + 0.5 - point.x, goal.getZ() + 0.5 - point.z);
    }

    /**
     * Exit of the air route search: a circle centered on the player that can be relied on as loaded
     * (see {@link FlightHorizon}). The diagnostic command goes through here too.
     */
    static FlightHorizon loadedHorizon(Vec3 player, int renderRadius) {
        return new FlightHorizon(player.x, player.z, renderRadius * LOADED_MARGIN);
    }

    /**
     * Whether you carry firework rockets. This switches the climb cost (the same shape as the water cost changing with
     * whether you have a boat).
     *
     * <p>An elytra without rockets cannot hold altitude in steady state = level flight itself becomes "climbing", so
     * this flag clearly changes how the route takes altitude.
     */
    private static boolean hasRockets(Player player) {
        return ChunkView.hasItem(GameCompat.inventory(player), stack -> stack.getItem() instanceof FireworkRocketItem);
    }
    private static double centerDistanceSq(BlockPos pos, Vec3 point) {
        double x = pos.getX() + 0.5 - point.x;
        double y = pos.getY() + 0.5 - point.y;
        double z = pos.getZ() + 0.5 - point.z;
        return x * x + y * y + z * z;
    }
}
