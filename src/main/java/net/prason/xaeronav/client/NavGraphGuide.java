package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.util.GameCompat;

/**
 * Holds the in-progress and finished navigation graph guide.
 *
 * <p>Builds the loaded window section by section with the same move generation as the search ({@link NavGraph}), and computes the remaining cost to the goal
 * with a reverse Dijkstra ({@link WindowField}). Inside the window it is exact, so the search can aim straight at the goal with weight 1.0
 * (measured full walks: wide-area long distance 1.016/1.030x, End 1.013/1.029x, Nether 1.013/1.023x. Searching via layer 1's intermediate targets gives
 * 1.067/1.165 and 1.122/not reached; the Nether with only the 3D coarse layer gives 1.048/1.104).
 *
 * <p>The thread boundary has the same shape as {@link NetherVoxelGuide}: the main thread only collects chunk references, and a worker builds.
 * Until it is built it returns {@code null}, and the caller proceeds with the regular search.
 */
final class NavGraphGuide {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Thread that assembles and emits diagnostic logs including where values come from ({@link #origin}). {@link #origin} walks down the guide's edges,
     * so doing it on the main thread stalls extension handling for tens of ms. The guide doesn't change once built and is also read from
     * search threads, so it may be read from another thread.
     */
    private static final ExecutorService DIAGNOSTIC_LOG = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-diagnostic-log");
        thread.setDaemon(true);
        return thread;
    });

    /** Emits logs that use {@link #origin} via {@link #DIAGNOSTIC_LOG}. */
    static void logOffThread(Runnable log) {
        DIAGNOSTIC_LOG.execute(log);
    }

    /**
     * Window radius (blocks) when the heap has room. Cut off here even if the render distance is wider.
     *
     * <p>Route review ({@link net.prason.xaeronav.pathfinding.navgraph.RouteReview}) runs only after the goal enters the window,
     * so a narrow window notices detours late (in the real Nether it first noticed about 130 blocks from the goal, costing an extra 653 ticks).
     * Measured 160, 192, 224 and 240 on the full-walk model.
     * <ul>
     * <li>224: Nether average 1.044 -> 1.001 and worst 1.147 -> 1.003x, Overworld 1.018 -> 1.009x. On the trap in real saved terrain, 6207 -> 4177 ticks (true value 3941)</li>
     * <li>192: Nether worst is 1.229x, worse than 160. Widening doesn't improve things monotonically</li>
     * <li>240: one route on the End's outer islands stops producing a path</li>
     * </ul>
     * The edge count grows with area. At 224 there are up to 70 million edges, and the graph and guide together take up to about 270MB (about 150MB at 160; measured on the Nether trap terrain).
     * The worst single guide build goes from 1.3 to 2.1 seconds.
     *
     * <p>If the heap is below {@link #WIDE_WINDOW_MIN_HEAP_BYTES}, drops to {@link #NARROW_WINDOW_BLOCKS}.
     */
    private static final int WIDE_WINDOW_BLOCKS = 224;

    /** The window when the heap is small. 192, in between, isn't chosen because its Nether worst case is worse than 160. */
    private static final int NARROW_WINDOW_BLOCKS = 160;

    /**
     * Heap needed to use the 224 window. With the official launcher's default 2GB, the game's own usage plus the 224 window's up to about 270MB leave no headroom.
     *
     * <p>People who set {@code -Xmx3G} should get 224, but {@link Runtime#maxMemory} under SerialGC/ParallelGC returns the value minus one survivor space
     * (measured: 2,969MB and 2,731MB with {@code -Xmx3G}, 1,979MB and 1,820MB with {@code -Xmx2G}), so the cut is at 2.5GB, in between.
     */
    private static final long WIDE_WINDOW_MIN_HEAP_BYTES = 2560L << 20;

    private static final int WINDOW_BLOCKS = Runtime.getRuntime().maxMemory() >= WIDE_WINDOW_MIN_HEAP_BYTES
            ? WIDE_WINDOW_BLOCKS : NARROW_WINDOW_BLOCKS;

    /**
     * Window radius (blocks). Clip the search box to this too: outside the window the guide falls back to layer 1 or a geometric estimate,
     * so if the box and window disagree the search runs on unmeasured ground.
     */
    static int window(int renderRadius) {
        return Math.min(WINDOW_BLOCKS, renderRadius);
    }

    /**
     * After a failed build, don't rebuild for this long. The conditions that cause failure (out of memory, etc.) don't change right away; retrying without waiting
     * would repeat chunk collection (main thread) and a full-window build every tick while guidance is pending.
     */
    private static final long FAILURE_BACKOFF_MILLIS = 30_000L;

    /**
     * Rebuild after walking this far from the center it was built around. A rebuild is adding bands (0.02-0.3s) plus building the guide (0.5-1.3s),
     * and the next one doesn't start while building, so the actual lag is this plus the distance walked during the rebuild.
     *
     * <p>On the full-walk model (4 wide-area long-distance routes), 8 blocks gave the same paths as no lag. At 16 blocks one route degraded from 1.030 to 1.101x,
     * and at 32 blocks it recovered: the window edge misses in phase with the leg starts, so keep the interval tight to keep the meshing width small.
     */
    private static final int REBUILD_MOVE_BLOCKS = 8;

    /** Radius of chunks discarded when stuck. Digging and placing usually happen right underfoot. */
    private static final int STALL_INVALIDATE_CHUNKS = 1;

    /**
     * Minimum interval between rebuilds caused by being stuck. Being stuck repeats many times in the same place, so without throttling
     * the hundreds of sections underfoot would be rebuilt on every search.
     */
    private static final long STALL_REBUILD_INTERVAL_MILLIS = 15_000L;

    /** Parallelism for the first build. Leaves one core for the main thread (rendering). No guidance appears until it's built, so wait time takes priority. */
    private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    /**
     * Parallelism for rebuilds while walking. {@link Thread#MIN_PRIORITY} has no effect on macOS and Linux, so building on all cores pushes aside rendering and
     * the integrated server's threads (in-game: on 10 cores (4 performance), 9 threads pinned every 8 blocks, and walking felt heavy).
     * The old guide can still be searched during a rebuild, so even if half speed causes lag, guidance isn't interrupted.
     */
    private static final int REBUILD_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);

    /**
     * Window radius built to warm up the JIT. With a cold JIT the first build takes 2.5x as long as when warm (on real saved terrain,
     * 2.3-2.7s -> 0.9s). Building radius 64 once shrinks the first build by about 28%, and widening to 128 didn't shrink it further.
     */
    private static final int WARM_UP_WINDOW = 64;

    /** Parallelism for warm-up. Right after joining a world, chunk loading and rendering are busy, so it doesn't build at full speed. */
    private static final int WARM_UP_WORKERS = 2;

    /** Whether warmed up. Warm code persists across worlds, so once per game session is enough. */
    private static final AtomicBoolean WARMED_UP = new AtomicBoolean();

    /** Interval for in-game logs. Rebuilds run with every step, so logging each one would flood. */
    private static final long LOG_INTERVAL_MILLIS = 10_000L;

    /** Interval for emitting the load summary ({@link Load}). */
    private static final long LOAD_LOG_INTERVAL_MILLIS = 300_000L;

    /** A single thread that runs the build coordination. Kept separate so it doesn't block the search workers. */
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav navigation graph");
        thread.setDaemon(true);
        return thread;
    });

    /** Hands that build sections side by side. The coordinator thread also does work, so this is one fewer. */
    private final @Nullable ExecutorService pool = WORKERS <= 1 ? null
            : Executors.newFixedThreadPool(WORKERS - 1, new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "XaeroNav navigation graph-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    // Yield to rendering. If the build is late the search still proceeds as before, but dropped frames make it unplayable
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    private final AtomicLong generation = new AtomicLong();
    private final ChangeGate<Boolean> logGate = new ChangeGate<>();

    /**
     * Which conditions the graph is for. If this changes, discard and rebuild: edges change with whether digging and placing are allowed
     * (passed by {@link ChunkView} to move generation), and bridges over the void are only laid in the direction toward the goal.
     */
    /** @param floored whether to cut the navigation graph's bottom at the player's and goal's heights ({@link NavGraph#floorBelow}) */
    private record Key(ResourceKey<Level> dimension, BlockPos goal, MovementOptions options, boolean canPlaceBlocks,
                       int window, int minY, int maxY, boolean floored) {

        /** Whether the edges would be the same. The goal height doesn't affect edges ({@link NavGraph#retarget}). */
        boolean sameEdges(@Nullable Key other) {
            return other != null && dimension.equals(other.dimension) && goal.getX() == other.goal.getX()
                    && goal.getZ() == other.goal.getZ() && options.equals(other.options)
                    && canPlaceBlocks == other.canPlaceBlocks && window == other.window && minY == other.minY
                    && maxY == other.maxY;
        }
    }

    private record Built(Key key, BlockPos center, WindowField field) {
    }

    /**
     * Source of the estimate outside the window. Not rebuilt while {@code source} is the same (layer 1's reverse Dijkstra runs over the whole map, so it isn't paid on every rebuild).
     *
     * @param name name shown in logs
     * @param make called on the coordinator thread
     * @param forwardOnly on every rebuild, removes from the seeds edges farther from the goal than the window center by the estimate ({@link FarField#forwardOf})
     */
    record Far(String name, Object source, Supplier<FarField> make, boolean forwardOnly) {
    }

    /**
     * Factor applied when using the 3D coarse layer as the estimate outside the window. The 3D coarse layer shrinks to about 0.77x of the true remainder, mismatching the scale of the exact values inside the window.
     *
     * <p>Measured (4 Nether routes, average/worst): 1.016/1.035 at 1.0x, 1.013/1.023 at 1.3x (currently 1.048/1.104 with only the 3D coarse layer).
     */
    static final double VOXEL_FAR_SCALE = 1.3;

    private volatile @Nullable Built built;
    private volatile boolean building;
    // After a height realignment. The path is still showing, so there's no reason to build at full speed even without a guide
    private volatile boolean retargeted;
    // The last search failed to make progress. Set by the worker thread (whenComplete), cleared by forGoal
    private volatile boolean stalled;
    private long nextStallRebuildMillis;
    // Time until recovering from a build failure, and the number of consecutive failures. Written by the thread receiving completion, read by forGoal
    private volatile long retryAfterMillis;
    private volatile int failures;

    /** Touched only by the coordinator thread. */
    private final Load load = new Load();
    /** Only the coordinator thread learns. The factor may be read from anywhere. */
    private final FarScaleCalibration farScale = new FarScaleCalibration();

    /** Touched only by the coordinator thread. */
    private @Nullable NavGraph graph;
    private @Nullable Key graphKey;
    private @Nullable Object farSource;
    private FarField far = FarField.UNKNOWN;

    /**
     * The guide for the current goal. If there is none, starts building and returns {@code null}. <b>Call from the main thread.</b>
     *
     * @param far estimate outside the window. If {@code null}, straight-line distance to the goal (placed only when the goal is outside the window)
     * @return even while rebuilding, the old guide for the same conditions if there is one
     */
    @Nullable WindowField forGoal(Level level, Player player, BlockPos goal, int renderRadius, MovementOptions options,
                                  @Nullable Far far) {
        BlockPos at = player.blockPosition();
        int window = window(renderRadius);
        int minY = GameCompat.minBuildHeight(level);
        int maxY = GameCompat.maxBuildHeight(level) - 1;
        int logicalTop = minY + level.dimensionType().logicalHeight() - 1;
        if (level.dimensionType().hasCeiling() && at.getY() <= logicalTop && goal.getY() <= logicalTop) {
            // Above the Nether's bedrock ceiling can't be entered by digging from below (bedrock can't be mined). Building it doubles the window's sections,
            // and the whole flat bedrock surface above the ceiling becomes nodes (in-game: 7,056 sections, first build 7.2 seconds)
            maxY = logicalTop;
        }
        // The Nether stacks passages vertically, and paths through lower layers are common. The End is only islands to begin with and has few nodes
        boolean floored = !level.dimensionType().hasCeiling() && level.dimension() != Level.END;
        Key key = new Key(level.dimension(), goal, options, canPlaceBlocks(player, options), window, minY, maxY,
                floored);
        Built current = built;
        boolean usable = current != null && current.key().equals(key);
        boolean moved = !usable || Math.max(Math.abs(at.getX() - current.center().getX()),
                Math.abs(at.getZ() - current.center().getZ())) >= REBUILD_MOVE_BLOCKS;
        boolean stallRebuild = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if ((moved || stallRebuild) && !building && !failedRecently()) {
            start(level, player, key, at, far);
        }
        return usable ? current.field() : null;
    }

    /** Whether a build failed and rebuilding is on hold. During this time, proceed with the regular search without waiting for a build. */
    boolean failedRecently() {
        return MonotonicTime.millis() < retryAfterMillis;
    }

    /**
     * The guide for this goal that is currently built. Doesn't start a rebuild. Unlike {@link #forGoal} it doesn't check conditions (inventory, config),
     * so use it only to review a path already drawn.
     */
    @Nullable WindowField latest(BlockPos goal) {
        Built current = built;
        return current != null && current.key().goal().equals(goal) ? current.field() : null;
    }

    /**
     * Factor applied to the guide's outside-window estimate for the arrival time display ({@link FarScaleCalibration}). Don't use it for searching.
     */
    double farScaleForDisplay() {
        return farScale.scale();
    }

    /**
     * Where the guide's value at {@code from} came from ({@link WindowField#descend}), in one word. For telling apart in in-game logs whether what set the path's direction was
     * the real cost inside the window or the outside estimate read at the window edge.
     */
    static String origin(CostToGo guide, BlockPos from) {
        if (!(guide instanceof WindowField field)) {
            return "not navigation graph";
        }
        WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ());
        if (descent == null) {
            return "not a node";
        }
        if (descent.reachedGoal()) {
            return "goal(inside window %d)".formatted(Math.round(descent.inside()));
        }
        BlockPos exit = descent.exit();
        BlockPos goal = field.goal();
        return "edge %s(inside window %d + outside estimate %d, straight from edge to goal %d)".formatted(exit.toShortString(),
                Math.round(descent.inside()), Math.round(descent.outside()),
                Math.round(Math.hypot(exit.getX() - goal.getX(), exit.getZ() - goal.getZ())));
    }

    /** Inventory is checked every time. Picking up or using up placeable blocks makes bridge edges appear or disappear. */
    private static boolean canPlaceBlocks(Player player, MovementOptions options) {
        return options.bridgingEnabled()
                && (GameCompat.abilities(player).instabuild || ChunkView.countPlaceableBlocks(player) > 0);
    }

    /**
     * Reports that the last search failed to make progress. Discards the chunks underfoot and rebuilds: block updates aren't tracked,
     * so edges where you dug or placed stay stale (on the full-walk model, measuring your own digging and placing as stale didn't degrade quality).
     *
     * <p><b>Called from a worker thread</b> (the search's {@code whenComplete}).
     */
    /**
     * Before there is a goal, builds and discards one small window around the player to warm up the JIT. <b>Call from the main thread.</b>
     * The result isn't used, so the goal can be a dummy point. Once a goal is set and the real build starts, this is cancelled (the generation advances).
     */
    void warmUp(Level level, Player player, MovementOptions options) {
        if (WARMED_UP.getAndSet(true)) {
            return;
        }
        BlockPos at = player.blockPosition();
        int minY = GameCompat.minBuildHeight(level);
        int maxY = GameCompat.maxBuildHeight(level) - 1;
        if (level.dimensionType().hasCeiling()) {
            maxY = Math.min(maxY, minY + level.dimensionType().logicalHeight() - 1);
        }
        SearchBounds bounds = new SearchBounds(at.getX() - WARM_UP_WINDOW, minY, at.getZ() - WARM_UP_WINDOW,
                at.getX() + WARM_UP_WINDOW, maxY, at.getZ() + WARM_UP_WINDOW);
        ChunkView view = ChunkView.capture(level, player, bounds, options);
        BlockPos goal = at.offset(WARM_UP_WINDOW, 0, WARM_UP_WINDOW);
        int graphMinY = minY;
        int graphMaxY = maxY;
        long myGeneration = generation.get();
        CompletableFuture.runAsync(() -> {
                    long began = MonotonicTime.millis();
                    NavGraph.Refreshed warmed = new NavGraph(goal, graphMinY, graphMaxY).refresh(view::forGraphBuild,
                            at.getX(), at.getZ(), WARM_UP_WINDOW,
                            LoadedArea.chunks(at.getX(), at.getZ(), WARM_UP_WINDOW, view::chunkLoaded),
                            FarField.straightLineTo(goal), pool, WARM_UP_WORKERS, () -> generation.get() != myGeneration);
                    LOGGER.info("XaeroNav: Navigation graph warm-up ({}ms, {})", MonotonicTime.millis() - began,
                            warmed == null ? "cancelled because a goal was set" : "sections " + warmed.sectionsBuilt());
                }, coordinator)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        LOGGER.warn("XaeroNav: Navigation graph warm-up failed (does not affect guidance)", error);
                    }
                });
    }

    void noteStalled() {
        stalled = true;
    }

    private void start(Level level, Player player, Key key, BlockPos at, @Nullable Far farMap) {
        boolean invalidateAround = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if (invalidateAround) {
            nextStallRebuildMillis = MonotonicTime.millis() + STALL_REBUILD_INTERVAL_MILLIS;
        }
        stalled = false;
        int window = key.window();
        SearchBounds bounds = new SearchBounds(at.getX() - window, key.minY(), at.getZ() - window,
                at.getX() + window, key.maxY(), at.getZ() + window);
        long captureBegan = MonotonicTime.millis();
        ChunkView view = ChunkView.capture(level, player, bounds, key.options());
        long captureMillis = MonotonicTime.millis() - captureBegan;
        int minY = key.minY();
        int maxY = key.maxY();
        // No guide for this goal yet, i.e. guidance is pending: build at full speed only during that time
        int workers = retargeted || built != null && built.key().equals(key) ? REBUILD_WORKERS : WORKERS;
        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
                    long began = MonotonicTime.millis();
                    NavGraph.Refreshed refreshed = refresh(key, view, at, minY, maxY, farMap, invalidateAround, workers,
                            () -> generation.get() != myGeneration);
                    load.record(began, MonotonicTime.millis(), captureMillis, refreshed);
                    if (refreshed != null) {
                        farScale.observe(refreshed.field(), at);
                    }
                    return refreshed;
                }, coordinator)
                .whenComplete((refreshed, error) -> {
                    if (error != null) {
                        // Cancellation returns null rather than throwing, so even for a stale generation this is a real failure
                        fail(error);
                    }
                    if (generation.get() != myGeneration) {
                        // A new build has started. Clearing its flag would make builds overlap
                        return;
                    }
                    building = false;
                    if (refreshed == null) {
                        return;
                    }
                    failures = 0;
                    built = new Built(key, at, refreshed.field());
                    retargeted = false;
                    if (LOGGER.isDebugEnabled() && logGate.changed(true, MonotonicTime.millis(), LOG_INTERVAL_MILLIS)) {
                        NavGraph current = graph;
                        LOGGER.debug("XaeroNav: Navigation graph (sections built={}, build {}ms, guide {}ms, edges={}, nodes={}, "
                                        + "graph {}MB, guide {}MB, window {}(heap limit {}MB), parallel {}, outside window={}, outside-window factor for arrival time={}, "
                                        + "origin of value at center {}={})",
                                refreshed.sectionsBuilt(), refreshed.buildMillis(), refreshed.field().buildMillis(),
                                refreshed.field().edges(), refreshed.field().nodes(),
                                current == null ? 0 : current.bytes() >> 20, refreshed.field().bytes() >> 20,
                                key.window(), Runtime.getRuntime().maxMemory() >> 20, workers,
                                farMap == null ? "straight-line distance" : farMap.name(), "%.2f".formatted(farScale.scale()),
                                at.toShortString(), origin(refreshed.field(), at));
                    }
                });
    }

    /**
     * The build failed. Don't rebuild for a while, and also release the built guide and graph: so as not to keep hundreds of MB
     * after running out of memory, and so as not to reuse a crashed build's arrays for the next one.
     */
    private void fail(Throwable error) {
        failures++;
        built = null;
        retryAfterMillis = MonotonicTime.millis() + FAILURE_BACKOFF_MILLIS;
        LOGGER.error("XaeroNav: Failed to build the navigation graph ({} times in a row). Not rebuilding for {} seconds; guiding without the navigation graph",
                failures, FAILURE_BACKOFF_MILLIS / 1000, error);
        // Attaching whenComplete to an already completed run calls it on the main thread, so the graph is released on the coordinator thread
        coordinator.execute(this::forgetGraph);
    }

    /** Called on the coordinator thread. */
    private void forgetGraph() {
        graph = null;
        graphKey = null;
        farSource = null;
        far = FarField.UNKNOWN;
    }

    /** Runs on the coordinator thread. */
    private NavGraph.@Nullable Refreshed refresh(Key key, ChunkView view, BlockPos at, int minY, int maxY,
                                                 @Nullable Far farMap, boolean invalidateAround, int workers,
                                                 BooleanSupplier cancelled) {
        NavGraph current = graph;
        if (current == null || !key.sameEdges(graphKey)) {
            // A graph whose conditions changed can't be kept and rebuilt incrementally (the edges themselves depend on the conditions)
            current = new NavGraph(key.goal(), minY, maxY);
            graph = current;
            graphKey = key;
            farScale.reset();
            // The outside estimate is also relative to the goal
            far = FarField.UNKNOWN;
            farSource = null;
        } else if (!key.equals(graphKey)) {
            current.retarget(key.goal());
            graphKey = key;
            far = FarField.UNKNOWN;
            farSource = null;
        }
        if (invalidateAround) {
            int chunkX = at.getX() >> 4;
            int chunkZ = at.getZ() >> 4;
            for (int dx = -STALL_INVALIDATE_CHUNKS; dx <= STALL_INVALIDATE_CHUNKS; dx++) {
                for (int dz = -STALL_INVALIDATE_CHUNKS; dz <= STALL_INVALIDATE_CHUNKS; dz++) {
                    current.invalidateChunk(chunkX + dx, chunkZ + dz);
                }
            }
        }
        Object source = farMap == null ? null : farMap.source();
        if (source != farSource || far == FarField.UNKNOWN) {
            far = farMap == null ? FarField.straightLineTo(key.goal()) : farMap.make().get();
            farSource = source;
        }
        int window = key.window();
        if (key.floored()) {
            current.floorBelow(at.getY());
        }
        FarField seeds = farMap != null && farMap.forwardOnly() ? FarField.forwardOf(far, at.getX(), at.getY(), at.getZ())
                : far;
        return current.refresh(view::forGraphBuild, at.getX(), at.getZ(), window,
                LoadedArea.chunks(at.getX(), at.getZ(), window, view::chunkLoaded), seeds, pool, workers, cancelled);
    }

    /**
     * Only the goal's height changed. The in-progress guide targets the old height, so it's cancelled, but the built sections are reused by the next {@link #forGoal}:
     * rebuilding would mean building the whole window (about 9,000 sections).
     */
    void retarget() {
        generation.incrementAndGet();
        built = null;
        building = false;
        retargeted = true;
        logGate.reset();
    }

    /** The goal changed or guidance stopped. Cancels the in-progress build and also releases the remembered graph. */
    void clear() {
        generation.incrementAndGet();
        built = null;
        building = false;
        stalled = false;
        retargeted = false;
        nextStallRebuildMillis = 0L;
        retryAfterMillis = 0L;
        failures = 0;
        logGate.reset();
        coordinator.execute(() -> {
            load.flush(MonotonicTime.millis());
            forgetGraph();
            farScale.reset();
        });
    }

    /**
     * How many rebuilds are running while walking. Emitted in bulk every {@link #LOAD_LOG_INTERVAL_MILLIS}.
     * It goes into users' logs too, so this one line can triage reports of heaviness or running out of memory on small heaps.
     * <b>Touched only by the coordinator thread.</b>
     */
    private static final class Load {

        private long since;
        private long busyMillis;
        private long buildMillis;
        private long guideMillis;
        private long maxMillis;
        private long maxCaptureMillis;
        private int runs;
        private int cancelled;
        private long gcSince;

        void record(long began, long ended, long captureMillis, NavGraph.@Nullable Refreshed refreshed) {
            if (runs == 0) {
                since = began;
                gcSince = TickLaps.gcPauseMillis();
            }
            runs++;
            busyMillis += ended - began;
            maxMillis = Math.max(maxMillis, ended - began);
            maxCaptureMillis = Math.max(maxCaptureMillis, captureMillis);
            if (refreshed == null) {
                cancelled++;
            } else {
                buildMillis += refreshed.buildMillis();
                guideMillis += refreshed.field().buildMillis();
            }
            if (ended - since >= LOAD_LOG_INTERVAL_MILLIS) {
                flush(ended);
            }
        }

        void flush(long now) {
            if (runs == 0) {
                return;
            }
            long span = Math.max(1L, now - since);
            Runtime runtime = Runtime.getRuntime();
            LOGGER.info("XaeroNav: Navigation graph load (last {}s, rebuilds {}(cancelled {}), coordinator utilization {}%, build total {}ms, guide total {}ms, "
                            + "max per run {}ms, max chunk collection {}ms(main thread), GC {}ms, heap {}/{}MB)",
                    span / 1000, runs, cancelled, 100 * busyMillis / span, buildMillis, guideMillis, maxMillis,
                    maxCaptureMillis, TickLaps.gcPauseMillis() - gcSince, (runtime.totalMemory() - runtime.freeMemory()) >> 20,
                    runtime.maxMemory() >> 20);
            runs = 0;
            cancelled = 0;
            busyMillis = 0;
            buildMillis = 0;
            guideMillis = 0;
            maxMillis = 0;
            maxCaptureMillis = 0;
        }
    }
}
