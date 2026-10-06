package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.GameCompat;

/**
 * Plans the whole route to the destination at once, in singleplayer, so the map can show all of it rather than
 * only the part inside render distance ({@link RoutePreview}).
 *
 * <p>The steps, each off the main thread except where the game state has to be read:
 * <ol>
 *   <li>Pick a corridor: the long-distance route's waypoints if there are any, else the straight line, widened by
 *       {@link #CORRIDOR_CHUNKS} chunks to each side.</li>
 *   <li>Read the corridor's chunks back from the save ({@link SavedChunks}).</li>
 *   <li>On the main thread, capture one view over the corridor: loaded chunks as they are, the rest from the save.</li>
 *   <li>Plan leg after leg on a background thread, publishing each leg as it is found.</li>
 * </ol>
 *
 * <p>Once it reaches the destination it becomes the route being followed ({@link PathfindingState#adoptFullRoute}):
 * drawn in the world, guiding the HUD and auto-walk, checked near the player like any route. When the live search
 * plans a stretch again (a deviation, a changed block) and that stretch ends on the whole route, the rest of the whole
 * route is attached to it again ({@link PathfindingState#attachFullRoute}), so the route never shrinks back to the
 * few hundred blocks the live search reaches. It is planned again when the destination, the movement options or the
 * long-distance route change, or when the player strays far from it.
 *
 * <p>Minecraft 26.3+ in singleplayer only ({@link SavedChunks#available()}).
 */
public final class FullRoutePlanner {

    public static final FullRoutePlanner INSTANCE = new FullRoutePlanner();

    /** Chunks of corridor on each side of the polyline. Room for the search to go round what the map didn't show. */
    static final int CORRIDOR_CHUNKS = 3;

    /** Most chunks a corridor may cover; beyond it, the corridor is cut short (and the preview stops there). */
    static final int MAX_CORRIDOR_CHUNKS = 6000;

    /** Plan again once the player is this far (blocks) from every point of the preview. */
    private static final int STRAY_BLOCKS = 48;

    /** Least time between two plans for the same destination (ms). */
    private static final long REPLAN_INTERVAL_MILLIS = 5_000L;

    /** Least time between two takeovers of the same whole route (ms), so it never fights the live search. */
    private static final long READOPT_INTERVAL_MILLIS = 10_000L;

    /** How close (blocks, horizontally) the player must be to the whole route for it to be taken over. */
    private static final int ADOPT_RADIUS = 4;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav full route");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private final AtomicLong generation = new AtomicLong();

    private volatile RoutePreview preview = RoutePreview.NONE;
    private volatile boolean planning;

    /** What the current plan was made for; a change in any of these plans again. */
    private @Nullable BlockPos plannedGoal;
    private @Nullable ResourceKey<Level> plannedDimension;
    private @Nullable MovementOptions plannedOptions;
    private List<BlockPos> plannedWaypoints = List.of();
    private long plannedAt;
    /** The whole route last taken over, and when. */
    private @Nullable RoutePreview adopted;
    private long adoptedAt;

    private FullRoutePlanner() {
    }

    public static boolean enabled() {
        return SavedChunks.INSTANCE.available() && XaeroNavConfig.INSTANCE.fullRoutePreviewEnabled();
    }

    /** The preview for the current destination; empty if there is none (yet). */
    public RoutePreview preview() {
        return preview;
    }

    public boolean planning() {
        return planning;
    }

    /** Drops the preview, e.g. when the destination is cleared. */
    public void clear() {
        generation.incrementAndGet();
        preview = RoutePreview.NONE;
        planning = false;
        plannedGoal = null;
        plannedDimension = null;
        plannedOptions = null;
        plannedWaypoints = List.of();
        adopted = null;
    }

    /** Decides each tick whether to plan (again). Cheap when nothing changed. */
    void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        PathfindingState state = PathfindingState.INSTANCE;
        BlockPos goal = state.goal();
        if (player == null || mc.level == null || goal == null || !enabled()) {
            if (plannedGoal != null) {
                clear();
            }
            return;
        }
        if (state.flying()) {
            return;
        }
        ResourceKey<Level> dimension = mc.level.dimension();
        MovementOptions options = XaeroNavConfig.INSTANCE.movementOptions();
        List<BlockPos> waypoints = state.navigationView().coarseRouteWaypoints();
        boolean changed = !goal.equals(plannedGoal) || !dimension.equals(plannedDimension)
                || !options.equals(plannedOptions);
        long now = System.currentTimeMillis();
        boolean due = now - plannedAt >= REPLAN_INTERVAL_MILLIS;
        // A better long-distance route only matters while the whole route doesn't reach the destination yet
        boolean better = due && !preview.complete() && waypoints.size() != plannedWaypoints.size();
        boolean strayed = due && !planning && !preview.isEmpty() && strayed(player.blockPosition());
        if (!changed && !better && !strayed) {
            follow(player, state, now);
            return;
        }
        if (changed) {
            preview = RoutePreview.NONE;
        }
        plannedGoal = goal;
        plannedDimension = dimension;
        plannedOptions = options;
        plannedWaypoints = waypoints;
        plannedAt = now;
        start(mc, player, goal, dimension, waypoints);
    }

    /** Makes the finished whole route the one being followed, or attaches its rest to the live route. */
    private void follow(LocalPlayer player, PathfindingState state, long now) {
        RoutePreview full = preview;
        if (!full.complete() || full.steps().isEmpty() || state.currentPathEndsAtDestination()) {
            return;
        }
        if (state.attachFullRoute(full.steps())) {
            return;
        }
        if (full == adopted && now - adoptedAt < READOPT_INTERVAL_MILLIS) {
            return;
        }
        int nearest = nearestStep(full, player.blockPosition());
        if (nearest >= 0) {
            state.adoptFullRoute(full.steps(), nearest);
            adopted = full;
            adoptedAt = now;
        }
    }

    /** Index of the step nearest to {@code at}, if within {@link #ADOPT_RADIUS} horizontally and 3 vertically; else -1. */
    static int nearestStep(RoutePreview full, BlockPos at) {
        int best = -1;
        long bestDistance = (long) ADOPT_RADIUS * ADOPT_RADIUS;
        for (int i = 0; i < full.steps().size(); i++) {
            BlockPos pos = full.steps().get(i).pos();
            long dx = pos.getX() - at.getX();
            long dz = pos.getZ() - at.getZ();
            long distance = dx * dx + dz * dz;
            if (distance <= bestDistance && Math.abs(pos.getY() - at.getY()) <= 3) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private boolean strayed(BlockPos at) {
        RoutePreview current = preview;
        BlockPos nearest = current.points().get(current.nearestIndex(at.getX(), at.getZ()));
        long dx = nearest.getX() - at.getX();
        long dz = nearest.getZ() - at.getZ();
        return dx * dx + dz * dz > (long) STRAY_BLOCKS * STRAY_BLOCKS;
    }

    private void start(Minecraft mc, LocalPlayer player, BlockPos goal, ResourceKey<Level> dimension,
                       List<BlockPos> waypoints) {
        long myGeneration = generation.incrementAndGet();
        planning = true;
        BlockPos start = player.blockPosition();
        List<BlockPos> polyline = new ArrayList<>();
        polyline.add(start);
        polyline.addAll(waypointsAhead(waypoints, start));
        polyline.add(goal);
        Set<Long> corridor = corridor(polyline);

        SavedChunks.INSTANCE.prefetch(dimension, corridor).whenComplete((ignored, error) -> mc.execute(() -> {
            if (generation.get() != myGeneration) {
                return;
            }
            LocalPlayer now = mc.player;
            if (now == null || mc.level == null || !mc.level.dimension().equals(dimension)) {
                planning = false;
                return;
            }
            SearchBounds bounds = boundsOf(corridor, GameCompat.minBuildHeight(mc.level),
                    GameCompat.maxBuildHeight(mc.level) - 1);
            ChunkView view = ChunkView.capture(mc.level, now, bounds, XaeroNavConfig.INSTANCE.movementOptions(),
                    SavedChunks.INSTANCE.view(dimension));
            BlockPos from = now.blockPosition();
            worker.execute(() -> plan(view, from, polyline, myGeneration));
        }));
    }

    private void plan(ChunkView view, BlockPos from, List<BlockPos> polyline, long myGeneration) {
        try {
            List<BlockPos> targets = RoutePreview.legTargets(view, polyline, RoutePreview.LEG_BLOCKS);
            long startNanos = System.nanoTime();
            RoutePreview result = RoutePreview.plan(view::forParallelSearch, from, targets,
                    () -> generation.get() != myGeneration,
                    progress -> {
                        if (generation.get() == myGeneration) {
                            preview = progress;
                        }
                    });
            if (generation.get() == myGeneration) {
                XaeroNav.LOGGER.info("XaeroNav: full route {} ({} points, {} legs, {} ms)",
                        result.complete() ? "planned" : "planned partly", result.points().size(), targets.size(),
                        (System.nanoTime() - startNanos) / 1_000_000);
            }
        } catch (RuntimeException e) {
            XaeroNav.LOGGER.error("XaeroNav: full route planning failed", e);
        } finally {
            if (generation.get() == myGeneration) {
                planning = false;
            }
        }
    }

    /** The long-distance waypoints still ahead of {@code start} (those it has passed would send the plan backwards). */
    static List<BlockPos> waypointsAhead(List<BlockPos> waypoints, BlockPos start) {
        if (waypoints.isEmpty()) {
            return List.of();
        }
        int first = MapPathOverlay.firstAheadWaypoint(waypoints, start.getX(), start.getZ());
        return waypoints.subList(first, waypoints.size());
    }

    /** Chunk keys within {@link #CORRIDOR_CHUNKS} of the polyline, nearest the start first, at most {@link #MAX_CORRIDOR_CHUNKS}. */
    static Set<Long> corridor(List<BlockPos> polyline) {
        Set<Long> keys = new LinkedHashSet<>();
        for (int i = 1; i < polyline.size() && keys.size() < MAX_CORRIDOR_CHUNKS; i++) {
            BlockPos from = polyline.get(i - 1);
            BlockPos to = polyline.get(i);
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / 8.0));
            for (int s = 0; s <= steps && keys.size() < MAX_CORRIDOR_CHUNKS; s++) {
                int cx = (int) Math.floor((from.getX() + dx * s / steps) / 16.0);
                int cz = (int) Math.floor((from.getZ() + dz * s / steps) / 16.0);
                for (int ox = -CORRIDOR_CHUNKS; ox <= CORRIDOR_CHUNKS; ox++) {
                    for (int oz = -CORRIDOR_CHUNKS; oz <= CORRIDOR_CHUNKS; oz++) {
                        keys.add(GameCompat.chunkKey(cx + ox, cz + oz));
                    }
                }
            }
        }
        return keys;
    }

    private static SearchBounds boundsOf(Set<Long> corridor, int minY, int maxY) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : corridor) {
            int cx = (int) key;
            int cz = (int) (key >> 32);
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);
        }
        return new SearchBounds(minX * 16, minY, minZ * 16, maxX * 16 + 15, maxY, maxZ * 16 + 15);
    }
}
