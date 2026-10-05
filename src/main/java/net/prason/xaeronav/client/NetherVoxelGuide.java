package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
//? if >=1.17 {
import net.minecraft.world.level.LevelHeightAccessor;
//?}
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * Holds the in-progress and finished 3D coarse layer used in dimensions with a ceiling.
 *
 * <p>Its job is <b>to confine the boundary between two threads to one place</b>. Xaero's map can only be read from the
 * main thread ({@link XaeroMapReader}'s thread contract), while the reverse Dijkstra takes hundreds of milliseconds and
 * would freeze the game on the main thread. So the work is split as <b>map reading = main thread, Dijkstra = worker</b>.
 * For the few hundred milliseconds until it is built, the search runs without a guide as before (better than no line).
 *
 * <p>It is rebuilt when the goal or dimension changes, when the player is about to leave the box,
 * after walking {@link #REBUILD_MOVE_BLOCKS}, and <b>when the search failed to make progress</b>.
 * <b>Building on every search is out of the question</b>: it would pay for area-proportional allocation and Dijkstra every time,
 * so every trigger is throttled by {@link #MIN_REBUILD_INTERVAL_MILLIS}.
 */
final class NetherVoxelGuide {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * How many blocks apart to read the map. Half a grid cell side: unless sampled finely enough for several points per cell,
     * narrow passages drop out.
     */
    private static final int SAMPLE_STEP = VoxelTerrain.DEFAULT_CELL_BLOCKS / 2;

    /** Don't rebuild while at least this far inside the box edge (same idea as {@code FLIGHT_COARSE_RECALC}). */
    private static final int REBUILD_INSET_BLOCKS = 32;

    /**
     * Rebuild after walking this far from where it was built. The key is <b>measuring by distance, not time</b>:
     * the map only grows as far as you walk, and rebuilding while standing still produces the same table.
     *
     * <p><b>This alone can't rebuild when stuck</b> ({@link #noteStalled}). Precisely when stuck,
     * neither "left the box" nor "walked" happens, so failing to make progress is a separate trigger.
     */
    private static final double REBUILD_MOVE_BLOCKS = 128.0;

    /**
     * Minimum interval between rebuilds. Map reading is on the main thread, so even if the conditions fire repeatedly
     * it isn't paid more often than this.
     */
    private static final long MIN_REBUILD_INTERVAL_MILLIS = 15_000L;

    /** A single thread just for Dijkstra. Kept separate so it doesn't block the search workers. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav 3D coarse layer");
        thread.setDaemon(true);
        return thread;
    });

    /** Generation. Checks whether a finished result is still the one wanted. */
    private final AtomicLong generation = new AtomicLong();

    private volatile Built built;
    private volatile boolean building;
    // The last search failed to make progress. Set by the worker thread (whenComplete), cleared by forGoal
    private volatile boolean stalled;
    /** The conditions of the last build attempt. Avoids paying for map reading every time it keeps failing under the same conditions. */
    private Key attempted;
    private long nextAttemptMillis;

    /**
     * The range of floor Y <b>seen on the map so far</b> for this goal. The box height is decided from this
     * (the accumulated range, not a single {@link FloorRange}).
     *
     * <p><b>Don't decide from a single read.</b> What {@code forEachCaveFloor} sees is
     * "the layers Xaero has in memory at the time", and that set changes while walking.
     * In-game (2026-09-18 15:07-15:10, the player moved only 60 blocks)
     * the reported floor count swung 4x back and forth, 34k -> 133k -> 34k -> 89k, and on reads where deep layers were loaded
     * the box <b>slid down</b> from {@code Y=19..98} to {@code Y=0..89}, pushing the walkable corridor in the y90s
     * entirely out of the box (walkable grid cells 8801 -> 4310). The guide was replaced wholesale each time,
     * so the choice between the east and west corridors was redone about every 20 seconds, i.e. the "going in circles" seen in-game.
     *
     * <p>What the map reveals only grows as you walk, so <b>the range only ever widens</b>.
     * Even if the player's Y jumps from falling or climbing a cliff, {@link VoxelTerrain#boxFor} only widens,
     * so the box doesn't shrink.
     */
    private Key floorRangeKey;
    private int floorLowest = Integer.MAX_VALUE;
    private int floorHighest = Integer.MIN_VALUE;

    /**
     * The floors read from the map so far (each packed into one long by {@link #packFloor}).
     * This is what feeds the grid, not just that read's floors.
     *
     * <p><b>Fixing the box isn't enough.</b> The layers read still fluctuate, so the box's contents
     * change. In-game (2026-09-18 22:38-22:39) lava swung 6,464 -> 602 -> 477, walkable cells
     * 6,054 -> 11,614, and inflation 2.22 -> 3.16 -> 1.4, and right after that the path <b>switched to a detour</b>, from 64 steps (229 to the goal)
     * to 259 steps (252 to the goal).
     *
     * <p>Difference measured on the model (3 runs with the layer set fluctuating while walking):
     * building each time gives 1.700/1.286/1.331x optimal, remembering gives <b>1.060/1.127/1.205x</b>,
     * nearly matching a full walk with a complete map, i.e. <b>almost all the quality lost to fluctuation comes back</b>.
     *
     * <p>Anything that leaves the box is discarded ({@link #forgetOutside}). The box shrinks as the goal gets closer,
     * so the amount remembered doesn't grow without bound as you walk.
     */
    private final LongOpenHashSet rememberedFloors = new LongOpenHashSet();

    /**
     * Remembers every floor seen in one read while also measuring the Y range. The range is accumulated by {@link #rememberFloors}
     * before being used for the box, and the floors themselves go into {@link #rememberedFloors} to feed the grid.
     */
    private static final class FloorRange implements XaeroMapReader.FloorVisitor {
        private final LongOpenHashSet into;
        private int lowest = Integer.MAX_VALUE;
        private int highest = Integer.MIN_VALUE;

        FloorRange(LongOpenHashSet into) {
            this.into = into;
        }

        @Override
        public void floor(int x, int z, int floorTopY, boolean lava) {
            lowest = Math.min(lowest, floorTopY);
            highest = Math.max(highest, floorTopY);
            into.add(packFloor(x, z, floorTopY, lava));
        }
    }

    /**
     * Packs one floor into a single long. X and Z get 26 bits (enough for the Nether's coordinate limit of ±3.75M), Y gets 10 bits
     * ({@code -64..959}), and the last bit is lava.
     */
    static long packFloor(int x, int z, int floorTopY, boolean lava) {
        return ((long) (x & 0x3FF_FFFF) << 37) | ((long) (z & 0x3FF_FFFF) << 11)
                | ((long) ((floorTopY + 64) & 0x3FF) << 1) | (lava ? 1L : 0L);
    }

    static int unpackX(long floor) {
        return (int) (floor << 1 >> 38);
    }

    static int unpackZ(long floor) {
        return (int) (floor << 27 >> 38);
    }

    static int unpackY(long floor) {
        return (int) ((floor >>> 1) & 0x3FF) - 64;
    }

    static boolean unpackLava(long floor) {
        return (floor & 1L) != 0L;
    }

    /** Which conditions the table is for. If this changes, rebuild without waiting for the interval. */
    private record Key(ResourceKey<Level> dimension, BlockPos goal, boolean lavaPassable) {
    }

    /** The built table, plus the box and the player position when it was built. */
    private record Built(Key key, SearchBounds box, BlockPos from, CostToGo costToGo) {
    }

    /**
     * The guide for the current goal. If there is none, starts building and returns {@code null} (the caller proceeds as before).
     * <b>Call from the main thread.</b>
     *
     * @param goal the <b>final goal</b>. Don't pass an intermediate target: if the guide's origin moves,
     *             each leg gets a table pointing a different way
     */
    CostToGo forGoal(
            //? if >=1.17 {
            LevelHeightAccessor level,
            //?} else {
            /*Level level,
            *///?}
            ResourceKey<Level> dimension, BlockPos player,
                      BlockPos goal, boolean lavaPassable) {
        Key key = new Key(dimension, goal, lavaPassable);
        Built current = built;
        boolean usable = current != null && current.key().equals(key);
        boolean stale = !usable || stalled || !insideBox(current.box(), player)
                || horizontal(current.from(), player) >= REBUILD_MOVE_BLOCKS;
        // Skip the interval only when the conditions change. When it keeps failing under the same conditions, without an interval
        // the map would be re-read on the main thread for every search
        boolean mayAttempt = !key.equals(attempted) || MonotonicTime.millis() >= nextAttemptMillis;
        if (stale && !building && mayAttempt) {
            start(level, key, player);
        }
        // Even while rebuilding, keep using the old table for the same goal (better than falling back to no guide)
        return usable ? current.costToGo() : null;
    }

    /**
     * Reports that the last search failed to make progress. Triggers a rebuild on the next {@link #forGoal}
     * (still throttled by {@link #MIN_REBUILD_INTERVAL_MILLIS}).
     *
     * <p><b>Being stuck is exactly when a rebuild is wanted.</b> The {@code requestLoad} fired by {@link #start} is
     * async and doesn't take effect that round, so a table built from a sparse map isn't updated merely because
     * "loading finished". In-game (2026-09-09), 33 unloaded regions arrived 3 seconds later, but
     * because the player was stuck in place the distance trigger never fired, and the sparse table was used for 43 more seconds.
     *
     * <p><b>Called from a worker thread</b> (the search's {@code whenComplete}).
     */
    void noteStalled() {
        stalled = true;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dz = (double) a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean insideBox(SearchBounds box, BlockPos player) {
        return player.getX() >= box.minX() + REBUILD_INSET_BLOCKS
                && player.getX() <= box.maxX() - REBUILD_INSET_BLOCKS
                && player.getZ() >= box.minZ() + REBUILD_INSET_BLOCKS
                && player.getZ() <= box.maxZ() - REBUILD_INSET_BLOCKS;
    }

    /**
     * Copies the map into the grid on the main thread and hands only Dijkstra to the worker.
     *
     * <p>The map is <b>read twice</b>. The first read only measures the Y range that has floors, from which the box height is decided
     * ({@link VoxelTerrain#boxFor}). An in-game map read takes 13ms, so two are still cheap.
     *
     * <p>The goal is the raw coordinate, not passed through {@code StanceFinder}. The guide's origin is decided by searching up to
     * 24 up/down and 16 sideways ({@code VoxelCostToGo}), so an offset of a few blocks is absorbed.
     */
    private void start(
            //? if >=1.17 {
            LevelHeightAccessor level,
            //?} else {
            /*Level level,
            *///?}
            Key key, BlockPos player) {
        stalled = false;
        attempted = key;
        nextAttemptMillis = MonotonicTime.millis() + MIN_REBUILD_INTERVAL_MILLIS;
        if (!XaeroPresence.mapPresent()) {
            return;
        }
        BlockPos goal = key.goal();
        long began = MonotonicTime.millis();
        int minX = Math.min(player.getX(), goal.getX()) - VoxelTerrain.MARGIN_BLOCKS;
        int minZ = Math.min(player.getZ(), goal.getZ()) - VoxelTerrain.MARGIN_BLOCKS;
        int sizeX = Math.max(player.getX(), goal.getX()) + VoxelTerrain.MARGIN_BLOCKS - minX + 1;
        int sizeZ = Math.max(player.getZ(), goal.getZ()) + VoxelTerrain.MARGIN_BLOCKS - minZ + 1;
        int referenceY = (player.getY() + goal.getY()) / 2;
        // The box built from the actual floor range includes at least the start, the goal and their margins. If even this minimal box
        // doesn't fit within the cap, scanning millions of map cells would always end up discarded.
        SearchBounds minimumBox = VoxelTerrain.boxFor(level, player, goal,
                Math.min(player.getY(), goal.getY()), Math.max(player.getY(), goal.getY()));
        if (VoxelTerrain.cellBlocksFor(minimumBox) == 0) {
            LOGGER.debug("XaeroNav: Skipping map read because the 3D coarse layer range is too large ({})", minimumBox);
            return;
        }
        // Without a request, only regions Xaero already has in memory can be read. The request is async, so
        // it won't make it in time for this round, but it takes effect on the next rebuild
        XaeroMapReader.requestLoad(minX >> 4, minZ >> 4,
                ((minX + sizeX - 1) >> 4) - (minX >> 4) + 1,
                ((minZ + sizeZ - 1) >> 4) - (minZ >> 4) + 1, referenceY);

        // The map is read <b>only once</b>. Floors seen are remembered as-is, and once the box is decided the remembered
        // ones feed the grid. Taking the box's Y as the dimension's full height makes the space above the ceiling fill half the grid,
        // giving a guide that "runs on a bridge above the ceiling" (VoxelTerrain#boxFor), so the height is decided from the floors
        forgetOutside(key, minX, minZ, sizeX, sizeZ);
        FloorRange range = new FloorRange(rememberedFloors);
        int floors = XaeroMapReader.forEachCaveFloor(minX, minZ, sizeX, sizeZ, referenceY,
                SAMPLE_STEP, range);
        if (floors == 0 && rememberedFloors.isEmpty()) {
            // Xaero doesn't have the map for this range yet. A table built from a grid with no floors at all is
            // just a constant multiple of straight-line distance, saying nothing the geometric heuristic doesn't
            LOGGER.debug("XaeroNav: No map to build the 3D coarse layer from ({}, {})", minX, minZ);
            return;
        }
        rememberFloors(key, range);
        SearchBounds box = VoxelTerrain.boxFor(level, player, goal, floorLowest, floorHighest);
        VoxelTerrain terrain = VoxelTerrain.of(box, key.lavaPassable());
        if (terrain == null) {
            // The goal is too far to fit even in the coarsest grid
            LOGGER.debug("XaeroNav: 3D coarse layer box is too large ({})", box);
            return;
        }
        LongIterator remembered = rememberedFloors.iterator();
        while (remembered.hasNext()) {
            long floor = remembered.nextLong();
            terrain.markFloor(unpackX(floor), unpackZ(floor), unpackY(floor), unpackLava(floor));
        }
        long read = MonotonicTime.millis() - began;
        int rememberedCount = rememberedFloors.size();

        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() ->
                        VoxelCostToGo.build(terrain, goal, () -> generation.get() != myGeneration), worker)
                .whenComplete((guide, error) -> {
                    building = false;
                    if (generation.get() != myGeneration) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.error("XaeroNav: Failed to build the 3D coarse layer", error);
                        return;
                    }
                    if (guide == null) {
                        // Don't silently fall back to no guide. This is exactly why no line appears for long-distance Nether routes
                        LOGGER.debug("XaeroNav: Could not determine the 3D coarse layer origin (goal={}, box={})",
                                goal.toShortString(), box);
                        return;
                    }
                    built = new Built(key, box, player, guide);
                    // Inflation = start estimate / straight-line distance. <b>Only this shows whether the layer is effective</b>:
                    // near 1x it says nothing the geometric heuristic doesn't.
                    // Also log the box: if the Y range is wider than walkable heights, most of the grid is empty space above the ceiling
                    LOGGER.debug("XaeroNav: 3D coarse layer (floors={}, {}, cells={}, side={}, inflation {}x, box={}, "
                                    + "floor Y this read={}, remembered floors={}, map {}ms, Dijkstra {}ms)",
                            floors, terrain.breakdown(), terrain.cellCount(), terrain.cellBlocks(),
                            round(inflation(guide, player, goal)), box,
                            floors == 0 ? "unreadable" : range.lowest + ".." + range.highest, rememberedCount,
                            read, MonotonicTime.millis() - began - read);
                });
    }

    /**
     * If the goal changed, discards remembered floors; otherwise discards those outside the current scan range.
     *
     * <p>The scan range shrinks as the goal gets closer, so this alone caps the amount remembered.
     */
    private void forgetOutside(Key key, int minX, int minZ, int sizeX, int sizeZ) {
        if (!key.equals(floorRangeKey)) {
            rememberedFloors.clear();
            return;
        }
        LongIterator floors = rememberedFloors.iterator();
        while (floors.hasNext()) {
            long floor = floors.nextLong();
            int x = unpackX(floor);
            int z = unpackZ(floor);
            if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ) {
                floors.remove();
            }
        }
    }

    /**
     * Adds the floor Y seen this time to the range for this goal. Recounts if the goal changes.
     *
     * <p>The key is that it only ever widens ({@link #floorRangeKey}).
     */
    private void rememberFloors(Key key, FloorRange range) {
        if (!key.equals(floorRangeKey)) {
            floorRangeKey = key;
            floorLowest = Integer.MAX_VALUE;
            floorHighest = Integer.MIN_VALUE;
        }
        floorLowest = Math.min(floorLowest, range.lowest);
        floorHighest = Math.max(floorHighest, range.highest);
    }

    /** How many times the straight-line distance the estimate at the start is. Near 1x, this layer adds nothing. */
    private static double inflation(VoxelCostToGo guide, BlockPos player, BlockPos goal) {
        double straight = Heuristic.estimate(player.getX(), player.getY(), player.getZ(),
                goal.getX(), goal.getY(), goal.getZ());
        return straight <= 0.0 ? 0.0
                : guide.estimate(player.getX(), player.getY(), player.getZ()) / straight;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** The goal changed or guidance stopped. Rebuild on the next request. */
    void clear() {
        generation.incrementAndGet();
        built = null;
        building = false;
        stalled = false;
        attempted = null;
        nextAttemptMillis = 0L;
        floorRangeKey = null;
        floorLowest = Integer.MAX_VALUE;
        floorHighest = Integer.MIN_VALUE;
        rememberedFloors.clear();
    }
}
