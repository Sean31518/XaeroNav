package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * The nav graph for one destination. Builds and stores the loaded range per section (16³), using the same move generation as the search.
 *
 * <p><b>Rebuild it per destination column.</b> Bridges over the void are only built in the direction approaching the destination
 * ({@code BuildMoves#addBridge}), so the edges themselves depend on the destination column (x, z). Height doesn't affect edges ({@link #retarget}).
 *
 * <p>Section construction ({@link #build}) may be called concurrently from worker threads. However, each call must be
 * passed a {@link CellSource} owned exclusively by that thread.
 */
public final class NavGraph {

    private BlockPos goal;
    private final int minSectionY;
    private final int maxSectionY;
    private final NaturalColumns naturals;
    private final MoveTable moves = new MoveTable();
    private final ConcurrentHashMap<Long, SectionEdges> sections = new ConcurrentHashMap<>();

    /**
     * Sections built at the edge of the loaded range with their surroundings missing, and the number of columns readable
     * when they were built. Rebuilt if more can be read in the current loaded range.
     *
     * <p><b>Don't leave the edge unbuilt.</b> In an empty band the guide falls to the outside value (or the geometric lower bound if none),
     * lower than the accurate values inside, attracting the search and partial-path endpoint selection (measured: End 1.009 -> 1.068x).
     *
     * <p><b>"Don't rebuild until fully readable" doesn't work either.</b> A section that became half readable as the window
     * approached keeps the edges from when only a few edge columns were readable, leaving a hole around the destination (measured: 1308 vs. the real 48).
     */
    private final ConcurrentHashMap<Long, Integer> provisional = new ConcurrentHashMap<>();

    /** Width (blocks) read outside the section: the shell's horizontal width, plus the few blocks moves read in neighboring cells. */
    static final int READ_MARGIN = SectionShell.HORIZONTAL + 4;

    /** @param minY the lowest height included in the graph (the world bottom). Includes up to {@code maxY} */
    public NavGraph(BlockPos goal, int minY, int maxY) {
        this.goal = goal.immutable();
        this.minSectionY = Math.floorDiv(minY, SectionMoves.SIZE);
        this.maxSectionY = Math.floorDiv(maxY, SectionMoves.SIZE);
        this.lowSectionY = minSectionY;
        this.naturals = new NaturalColumns(minY, maxY);
    }

    public BlockPos goal() {
        return goal;
    }

    /**
     * Below this depth from the lower of the player and the destination, nothing goes into the graph ({@link #floorBelow}).
     * Valleys, canyons, and water beds that surface routes descend into fit in this width (in the real game's coastal and wide surface areas, path costs matched on every route with and without the cut).
     */
    public static final int FLOOR_DEPTH_BLOCKS = 24;

    /** The lowest section included in the graph. Raised and lowered by {@link #floorBelow}. */
    private volatile int lowSectionY;

    /**
     * Doesn't build sections more than {@link #FLOOR_DEPTH_BLOCKS} below the lower of the player height {@code playerY} and the destination.
     *
     * <p>In the Overworld, caves continue for many layers underground, and about three quarters of the nodes in a 224 window are caves below the surface and the diggable rock around them
     * (on a real-game coast: 14.8 million nodes, a graph of about 900MB, 4-5 seconds per guide). A route between two surface points almost never goes through caves far below.
     * If the player descends into a cave, the lower bound drops with them.
     */
    public void floorBelow(int playerY) {
        int low = Math.max(minSectionY,
                Math.floorDiv(Math.min(playerY, goal.getY()) - FLOOR_DEPTH_BLOCKS, SectionMoves.SIZE));
        if (low == lowSectionY) {
            return;
        }
        lowSectionY = low;
        sections.keySet().removeIf(key -> BlockPos.getY(key) < low);
        provisional.keySet().removeIf(key -> BlockPos.getY(key) < low);
    }

    int lowSectionY() {
        return lowSectionY;
    }

    /**
     * Swaps only the destination height within the same column. Built sections remain usable. Don't call concurrently with
     * {@link #build} or {@link #refresh}.
     */
    public void retarget(BlockPos goal) {
        if (goal.getX() != this.goal.getX() || goal.getZ() != this.goal.getZ()) {
            throw new IllegalArgumentException("destination column differs: " + this.goal.toShortString() + " → "
                    + goal.toShortString());
        }
        this.goal = goal.immutable();
    }

    static long key(int sectionX, int sectionY, int sectionZ) {
        return BlockPos.asLong(sectionX, sectionY, sectionZ);
    }

    /**
     * Keys of sections overlapping the window (a square of horizontal {@code radius} around the center) that need building:
     * those not built yet, and those built with surroundings missing that can now be read more ({@link #readableColumns}).
     */
    public long[] missingSections(int centerX, int centerZ, int radius, LoadedArea loaded) {
        LongArrayList missing = new LongArrayList();
        forEachWindowSection(centerX, centerZ, radius, (sx, sy, sz) -> {
            long key = key(sx, sy, sz);
            if (!sections.containsKey(key)) {
                missing.add(key);
                return;
            }
            Integer readable = provisional.get(key);
            if (readable == null) {
                return;
            }
            int now = readableColumns(sx, sz, loaded);
            // Rebuilding every time a few more columns become readable would mean rebuilding the whole edge band every time the window moves
            if (now >= FULLY_READABLE || now - readable >= FULLY_READABLE / REBUILD_STEPS) {
                missing.add(key);
            }
        });
        return missing.toLongArray();
    }

    /** Number of readable columns in the section's columns plus the outside read width. {@link #FULLY_READABLE} if all are readable. */
    static int readableColumns(int sectionX, int sectionZ, LoadedArea loaded) {
        return loaded.columns(sectionX * SectionMoves.SIZE - READ_MARGIN,
                (sectionX + 1) * SectionMoves.SIZE - 1 + READ_MARGIN, sectionZ * SectionMoves.SIZE - READ_MARGIN,
                (sectionZ + 1) * SectionMoves.SIZE - 1 + READ_MARGIN);
    }

    /** Step for rebuilding provisional sections. Rebuilt when readable columns increase by this fraction, or when all become readable. */
    private static final int REBUILD_STEPS = 4;

    static final int FULLY_READABLE = (SectionMoves.SIZE + 2 * READ_MARGIN) * (SectionMoves.SIZE + 2 * READ_MARGIN);

    /**
     * Builds sections {@code keys[from..to)}. {@code cells} is a view owned exclusively by this call's thread.
     * Sections whose surrounding {@link #READ_MARGIN} columns aren't all readable are stored as provisional, with surroundings missing.
     *
     * @return {@code false} if cut off (sections already built are kept)
     */
    public boolean build(CellSource cells, long[] keys, int from, int to, LoadedArea loaded,
                         BooleanSupplier cancelled) {
        SectionShell shell = null;
        int shellX = Integer.MIN_VALUE;
        int shellZ = Integer.MIN_VALUE;
        for (int i = from; i < to; i++) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            long key = keys[i];
            int sx = BlockPos.getX(key);
            int sy = BlockPos.getY(key);
            int sz = BlockPos.getZ(key);
            // The shell is the same per column (sx, sz). Keys are often ordered by column, so reuse the previous one
            if (shell == null || sx != shellX || sz != shellZ) {
                shell = SectionShell.of(naturals, cells, sx, sz, goal.getX(), goal.getZ());
                shellX = sx;
                shellZ = sz;
            }
            SectionEdges edges = SectionEdges.build(cells, shell, moves, sx, sy, sz, goal.getX(), goal.getZ(), cancelled);
            if (edges == null) {
                return false;
            }
            sections.put(key, edges);
            int readable = readableColumns(sx, sz, loaded);
            if (readable >= FULLY_READABLE) {
                provisional.remove(key);
            } else {
                provisional.put(key, readable);
            }
        }
        return true;
    }

    /**
     * A chunk's contents changed. Drops the sections in the columns of that chunk and the surrounding chunks: the shell is
     * determined by standable points up to {@link SectionShell#HORIZONTAL} blocks horizontally away, and moves read outside
     * the section, so dropping only the changed chunk would leave neighboring edges stale.
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        naturals.invalidateChunk(chunkX, chunkZ);
        for (int sx = chunkX - 1; sx <= chunkX + 1; sx++) {
            for (int sz = chunkZ - 1; sz <= chunkZ + 1; sz++) {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    sections.remove(key(sx, sy, sz));
                    provisional.remove(key(sx, sy, sz));
                }
            }
        }
    }

    /** Drops sections farther than horizontal {@code radius} from the center. */
    public void retainWithin(int centerX, int centerZ, int radius) {
        int minX = Math.floorDiv(centerX - radius, SectionMoves.SIZE);
        int maxX = Math.floorDiv(centerX + radius, SectionMoves.SIZE);
        int minZ = Math.floorDiv(centerZ - radius, SectionMoves.SIZE);
        int maxZ = Math.floorDiv(centerZ + radius, SectionMoves.SIZE);
        provisional.keySet().removeIf(key -> {
            int sx = BlockPos.getX(key);
            int sz = BlockPos.getZ(key);
            return sx < minX || sx > maxX || sz < minZ || sz > maxZ;
        });
        sections.keySet().removeIf(key -> {
            int sx = BlockPos.getX(key);
            int sz = BlockPos.getZ(key);
            return sx < minX || sx > maxX || sz < minZ || sz > maxZ;
        });
    }

    /** Approximate byte size of the stored sections and the guide-construction arrays. */
    public long bytes() {
        long total;
        synchronized (this) {
            total = fieldBuffers.bytes();
        }
        for (SectionEdges edges : sections.values()) {
            total += edges == SectionEdges.EMPTY ? 16 : edges.bytes();
        }
        return total;
    }

    /** Total number of stored edges. */
    public long edgeCount() {
        long total = 0;
        for (SectionEdges edges : sections.values()) {
            total += edges.size();
        }
        return total;
    }

    /** The result of {@link #refresh} and the breakdown printed to the real-game log. */
    public record Refreshed(WindowField field, int sectionsBuilt, long buildMillis) {
    }

    /** Sections farther than this from the window are dropped. A width that avoids rebuilding the edge band when the window moves back a little. */
    static final int RETAIN_MARGIN = 32;

    /** Number of sections built consecutively with one view. Even when given a view that caches cells, re-acquire in small batches so it doesn't grow to the whole window. */
    private static final int SECTIONS_PER_VIEW = 16;

    /**
     * Builds the missing sections in the window in parallel and rebuilds the guide.
     *
     * @param views    on each call, returns a view the calling thread may own exclusively
     * @param pool     if {@code null}, builds on the calling thread only. Must not be called from a pool thread
     * @param workers  parallelism including the calling thread
     * @return {@code null} if cut off
     */
    public @Nullable Refreshed refresh(Supplier<CellSource> views, int centerX, int centerZ, int radius,
                                       LoadedArea loaded, FarField far, @Nullable Executor pool, int workers,
                                       BooleanSupplier cancelled) {
        long began = MonotonicTime.millis();
        naturals.forgetIncomplete();
        retainWithin(centerX, centerZ, radius + RETAIN_MARGIN);
        long[] missing = missingSections(centerX, centerZ, radius, loaded);
        Parallel parallel = new Parallel(pool, workers);
        boolean built = parallel.forEach(missing.length, SECTIONS_PER_VIEW, cancelled,
                (from, to) -> build(views.get(), missing, from, to, loaded, cancelled));
        if (!built) {
            return null;
        }
        long buildMillis = MonotonicTime.millis() - began;
        WindowField field = field(centerX, centerZ, radius, far, parallel, cancelled);
        return field == null ? null : new Refreshed(field, missing.length, buildMillis);
    }

    /**
     * Builds the guide by running reverse Dijkstra in the window. Puts {@code far}'s values at the far ends of edges leading outside the window or into sections not built yet.
     *
     * @return {@code null} if cut off
     */
    public @Nullable WindowField field(int centerX, int centerZ, int radius, FarField far,
                                       BooleanSupplier cancelled) {
        return field(centerX, centerZ, radius, far, Parallel.INLINE, cancelled);
    }

    private synchronized @Nullable WindowField field(int centerX, int centerZ, int radius, FarField far,
                                                     Parallel parallel, BooleanSupplier cancelled) {
        return WindowField.build(this, fieldBuffers, centerX, centerZ, radius, far, parallel, cancelled);
    }

    /** Construction arrays for {@link #field}. {@code field} is synchronized, so one set is enough. */
    private final WindowField.Buffers fieldBuffers = new WindowField.Buffers();

    MoveTable moves() {
        return moves;
    }

    @Nullable SectionEdges section(long key) {
        return sections.get(key);
    }

    @FunctionalInterface
    interface SectionVisitor {
        void visit(int sectionX, int sectionY, int sectionZ);
    }

    void forEachWindowSection(int centerX, int centerZ, int radius, SectionVisitor visitor) {
        int minX = Math.floorDiv(centerX - radius, SectionMoves.SIZE);
        int maxX = Math.floorDiv(centerX + radius, SectionMoves.SIZE);
        int minZ = Math.floorDiv(centerZ - radius, SectionMoves.SIZE);
        int maxZ = Math.floorDiv(centerZ + radius, SectionMoves.SIZE);
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sz = minZ; sz <= maxZ; sz++) {
                for (int sy = lowSectionY; sy <= maxSectionY; sy++) {
                    visitor.visit(sx, sy, sz);
                }
            }
        }
    }
}
