package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jspecify.annotations.Nullable;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Per-column bits for "heights you can stand at without digging or placing". Remembered per chunk and used as the core of the shell ({@link SectionShell}).
 *
 * <p>Water counts at <b>every depth</b>. Counting only the surface cuts the window's edges by 30% (34.45M → 24.59M edges in a
 * wide window), but the water between seabed and surface drops out of the graph and a start on the seabed no longer connects to
 * the destination (measured: wide long-distance 1.619x, worst 2.632x, against 1.015x for the closure window).
 *
 * <p>Safe to call concurrently from worker threads.
 */
public final class NaturalColumns {

    private final int minY;
    private final int height;
    private final int words;
    private final ConcurrentHashMap<Long, long[]> chunks = new ConcurrentHashMap<>();
    /**
     * Chunks that contained unloaded cells. Remembered only until {@link #forgetIncomplete}.
     *
     * <p><b>Don't skip remembering and re-read every time.</b> One shell pulls 32×32 columns, so chunks at the window edge would be
     * re-read whole column by column, making the whole window build 15x slower (measured: 41.9 s for a 4,410-section Nether window, a few seconds without re-reading).
     */
    private final ConcurrentHashMap<Long, long[]> incomplete = new ConcurrentHashMap<>();
    /**
     * Per-column shore heights and bridge lengths ({@link #shore}). Depends on the contents of columns behind, so everything is dropped if anything changes.
     * May include columns still loading, so it is also dropped by {@link #forgetIncomplete}.
     */
    private final ConcurrentHashMap<Long, AtomicReferenceArray<int[]>> shores = new ConcurrentHashMap<>();

    /** @param minY Height corresponding to bit 0. Inclusive up to {@code maxY} */
    public NaturalColumns(int minY, int maxY) {
        this.minY = minY;
        this.height = maxY - minY + 1;
        this.words = (height + 63) >> 6;
    }

    int minY() {
        return minY;
    }

    int words() {
        return words;
    }

    /**
     * Word {@code word} of the bits of column ({@code x},{@code z}).
     *
     * <p>Chunks containing unloaded cells are <b>not remembered</b>. If remembered, they'd stay empty even after loading later,
     * leaving no shell there = a hole in the graph.
     */
    long word(CellSource cells, int x, int z, int word) {
        return chunkBits(cells, x, z)[(Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16) * words + word];
    }

    /**
     * Height (blocks) above the lava surface to look at. Bridges across a lava sea are built at shore height, so cave floors far above the surface don't count as shores.
     * 16 isn't enough: small islands in the real Nether stand 17-21 blocks above the surface in places, and those shores went uncounted so no route formed.
     */
    private static final int LAVA_BRIDGE_BAND = 32;

    /** Bit one above (the standing height of) the topmost lava with open space directly above in column ({@code x},{@code z}). -1 if none. */
    private int lavaSurface(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + column] - 1;
    }

    /** Whether column ({@code x},{@code z}) is empty all the way down (The End's void). Columns containing unloaded cells are not. */
    boolean voidColumn(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (bits[256 * words + (column >> 6)] >>> column & 1L) != 0;
    }

    /**
     * Number of empty cells (bits) continuing up from the bottom of column ({@code x},{@code z}). Below floating islands and self-built bridges is void up to here.
     * The full height if empty to the bottom, 0 if the bottom is filled.
     */
    private int voidBelow(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + 256 + column];
    }

    /** Bit one above the topmost block of column ({@code x},{@code z}). Empty from here up. 0 if the column is empty. */
    private int voidAbove(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + 512 + column];
    }

    private long[] chunkBits(CellSource cells, int x, int z) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        long[] bits = chunks.get(key);
        if (bits == null) {
            bits = incomplete.get(key);
        }
        return bits != null ? bits : scan(cells, chunkX, chunkZ, key);
    }

    /**
     * Bits of heights a bridge toward the destination could pass through in column ({@code x},{@code z}) over void. Columns with a filled bottom look only at the lava sea.
     *
     * <p>{@link SectionShell} only holds 8 blocks horizontally from naturally standable points, so the middle of a bridge across wider void
     * drops out of the graph and the island on the far side doesn't connect to the destination (outer End islands in the real game: 80-100 blocks
     * between islands, the guide fell to the geometric lower bound and not a single path came out).
     *
     * <p>Bridges over void are only extended toward the destination ({@code BuildMoves#addBridge}), so a bridge can reach this column only
     * when there's a shore within {@code reach} on the side away from the destination (behind on each axis). Bridges can also bend in an L, so
     * the shore behind is searched not only along the axes but in the quadrant behind ({@link #behind}). Mismatched shore heights mean stacking a
     * pillar on arrival, so fill up to the height of the shore on the destination-side axis, plus {@link SectionShell#VERTICAL} above and below.
     */
    long[] bridgeCorridor(CellSource cells, int x, int z, int goalX, int goalZ, int reach, int lavaReach) {
        long[] corridor = new long[words];
        int voidBelow = voidBelow(cells, x, z);
        if (voidBelow < 3) {
            return lavaReach > 0 ? lavaCorridor(cells, x, z, lavaReach, corridor) : corridor;
        }
        long[] pass = passable(cells, x, z);
        int[] behind = behind(cells, x, z, goalX, goalZ, reach, pass);
        if (behind.length == 0) {
            return corridor;
        }
        int nearLow = Integer.MAX_VALUE;
        int nearHigh = Integer.MIN_VALUE;
        int[][] axes = {{Integer.signum(goalX - x), 0}, {0, Integer.signum(goalZ - z)}};
        for (int[] axis : axes) {
            if (axis[0] == 0 && axis[1] == 0) {
                continue;
            }
            int[] span = nearestShoreSpan(cells, x, z, axis[0], axis[1], reach, voidBelow - 2);
            if (span != null) {
                nearLow = Math.min(nearLow, span[0]);
                nearHigh = Math.max(nearHigh, span[1]);
            }
        }
        int closest = -1;
        for (int entry : behind) {
            int bit = shoreHeight(entry);
            fill(corridor, bit - SectionShell.VERTICAL, bit + SectionShell.VERTICAL);
            if (nearLow <= nearHigh && (closest < 0 || gap(bit, nearLow, nearHigh) < gap(closest, nearLow, nearHigh))) {
                closest = bit;
            }
        }
        // Mismatched shore heights mean stacking a pillar on arrival, so fill from the nearest height up to the far shore's height
        if (closest >= 0) {
            fill(corridor, Math.min(closest, nearLow) - SectionShell.VERTICAL,
                    Math.max(closest, nearHigh) + SectionShell.VERTICAL);
        }
        for (int w = 0; w < words; w++) {
            corridor[w] &= pass[w];
        }
        return corridor;
    }

    private static int gap(int bit, int low, int high) {
        return bit < low ? low - bit : bit > high ? bit - high : 0;
    }

    private void fill(long[] bits, int from, int to) {
        for (int bit = Math.max(0, from); bit <= Math.min(height - 1, to); bit++) {
            bits[bit >> 6] |= 1L << bit;
        }
    }

    /**
     * Heights where a bridge can pass in column ({@code x},{@code z}) (the cell for the foothold and the two cells for the body are empty). Heights empty
     * all the way down, or above the topmost block. There are bridges ducking under floating islands and bridges crossing above them (real End: bridges
     * 4 blocks below a small island and 12 blocks above an island's top dropped out of the graph, and what lay beyond didn't connect to the destination).
     */
    private long[] passable(CellSource cells, int x, int z) {
        long[] pass = new long[words];
        fill(pass, 0, voidBelow(cells, x, z) - 2);
        fill(pass, voidAbove(cells, x, z) + 1, height - 2);
        return pass;
    }

    /**
     * Number of shore heights one column remembers. Nearest shores are kept.
     *
     * <p>Without a cap, crossing void carries along the heights of one island after another in the quadrant behind and the shell bloats (in a prototype
     * holding heights as ranges, the real End window's graph grew 2.3x to about 530 MB. With 8, +13-37%).
     */
    private static final int MAX_SHORE_HEIGHTS = 8;

    /** No shore behind within bridge reach. */
    private static final int[] NO_SHORE = new int[0];

    private static int shoreEntry(int height, int distance) {
        return distance << 16 | height;
    }

    private static int shoreHeight(int entry) {
        return entry & 0xFFFF;
    }

    private static int shoreDistance(int entry) {
        return entry >>> 16;
    }

    /**
     * Heights at which column ({@code x},{@code z}) can be reached by bridge from the neighbouring columns on the side away from the destination (behind on each axis),
     * and the bridge length from the shore at that height. Heights not passable in this column (outside {@code pass}) and heights whose bridge exceeds {@code reach} are dropped.
     */
    private int[] behind(CellSource cells, int x, int z, int goalX, int goalZ, int reach, long[] pass) {
        int sx = Integer.signum(goalX - x);
        int sz = Integer.signum(goalZ - z);
        int[] merged = new int[2 * MAX_SHORE_HEIGHTS];
        int count = 0;
        for (int axis = 0; axis < 2; axis++) {
            if ((axis == 0 ? sx : sz) == 0) {
                continue;
            }
            int[] shore = shore(cells, axis == 0 ? x - sx : x, axis == 0 ? z : z - sz, goalX, goalZ, reach);
            for (int entry : shore) {
                int bit = shoreHeight(entry);
                int distance = shoreDistance(entry) + 1;
                if (distance > reach || (pass[bit >> 6] >>> bit & 1L) == 0) {
                    continue;
                }
                count = addShore(merged, count, bit, distance);
            }
        }
        return nearest(merged, count);
    }

    /** For the same height, keep the shorter one when adding. */
    private static int addShore(int[] entries, int count, int bit, int distance) {
        for (int i = 0; i < count; i++) {
            if (shoreHeight(entries[i]) == bit) {
                if (shoreDistance(entries[i]) > distance) {
                    entries[i] = shoreEntry(bit, distance);
                }
                return count;
            }
        }
        entries[count] = shoreEntry(bit, distance);
        return count + 1;
    }

    /** Up to {@link #MAX_SHORE_HEIGHTS}, from the nearest shore. */
    private static int[] nearest(int[] entries, int count) {
        if (count == 0) {
            return NO_SHORE;
        }
        int[] sorted = Arrays.copyOf(entries, count);
        // Distance is in the upper bits, so sorting as-is gives nearest first
        Arrays.sort(sorted);
        return sorted.length > MAX_SHORE_HEIGHTS ? Arrays.copyOf(sorted, MAX_SHORE_HEIGHTS) : sorted;
    }

    /**
     * Heights and bridge lengths of column ({@code x},{@code z}) seen as a bridge shore. Standable heights have length 0; if open below or above, heights
     * that can be passed through from behind ({@link #behind}) are added with their length unchanged. Inherited column by column from behind, so even
     * shores of L-shaped bridges are found by looking at just two neighbours per column; tracing along the axes on every lookup misses L shapes, and catching them would mean reading the whole quadrant behind (about 4,600 columns).
     */
    private int[] shore(CellSource cells, int x, int z, int goalX, int goalZ, int reach) {
        if (!cells.isInBounds(x, minY, z)) {
            return NO_SHORE;
        }
        long key = ((long) Math.floorDiv(x, 16) << 32) | (Math.floorDiv(z, 16) & 0xFFFFFFFFL);
        AtomicReferenceArray<int[]> memo = shores.computeIfAbsent(key, k -> new AtomicReferenceArray<>(256));
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        int[] known = memo.get(column);
        if (known != null) {
            return known;
        }
        int[] through = voidBelow(cells, x, z) >= 3 ? behind(cells, x, z, goalX, goalZ, reach, passable(cells, x, z))
                : NO_SHORE;
        int[] entries = new int[MAX_SHORE_HEIGHTS * 2 + height];
        int count = 0;
        for (int w = 0; w < words; w++) {
            long bits = word(cells, x, z, w);
            while (bits != 0) {
                count = addShore(entries, count, (w << 6) + Long.numberOfTrailingZeros(bits), 0);
                bits &= bits - 1;
            }
        }
        for (int entry : through) {
            count = addShore(entries, count, shoreHeight(entry), shoreDistance(entry));
        }
        int[] result = nearest(entries, count);
        memo.set(column, result);
        return result;
    }

    /**
     * Heights a bridge could pass through in a lava-sea column. The lava sea has the same hole as {@link #bridgeCorridor}'s void: the shell holds only 8 blocks
     * from shore, so islands separated by lava wider than 16 blocks don't connect in the graph, and the guide lures toward a distant detour (real Nether: the shortcut
     * from a small island across lava to the island to the west was treated as nonexistent, and the route went out to the lava sea's perimeter and back. The 3D coarse layer knows that shortcut, so the directions inside and outside the window disagreed).
     *
     * <p>Lava bridges aren't constrained to the destination's direction ({@code BuildMoves#addBridge}), so it's passable if a shore lies within {@code reach} in any of the 4 directions.
     * A shore is a column standable within {@link #LAVA_BRIDGE_BAND} of the lava surface. Between the found shore heights, fill up to {@link SectionShell#VERTICAL} above and below.
     */
    private long[] lavaCorridor(CellSource cells, int x, int z, int reach, long[] corridor) {
        int surface = lavaSurface(cells, x, z);
        if (surface < 0) {
            return corridor;
        }
        int bandTop = Math.min(height - 1, surface + LAVA_BRIDGE_BAND);
        long[] band = new long[words];
        for (int bit = surface; bit <= bandTop; bit++) {
            band[bit >> 6] |= 1L << bit;
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            for (int k = 1; k <= reach; k++) {
                int px = x + direction[0] * k;
                int pz = z + direction[1] * k;
                if (!cells.isInBounds(px, minY, pz)) {
                    break;
                }
                long[] chunk = chunkBits(cells, px, pz);
                int column = Math.floorMod(px, 16) + Math.floorMod(pz, 16) * 16;
                int shoreLow = Integer.MAX_VALUE;
                int shoreHigh = Integer.MIN_VALUE;
                for (int w = 0; w < words; w++) {
                    long bits = chunk[column * words + w] & band[w];
                    if (bits != 0) {
                        shoreLow = Math.min(shoreLow, (w << 6) + Long.numberOfTrailingZeros(bits));
                        shoreHigh = Math.max(shoreHigh, (w << 6) + 63 - Long.numberOfLeadingZeros(bits));
                    }
                }
                if (shoreLow <= shoreHigh) {
                    low = Math.min(low, shoreLow);
                    high = Math.max(high, shoreHigh);
                    break;
                }
                if (chunk[256 * words + 4 + column] == 0) {
                    // The lava sea ended but there's nowhere to stand (a wall, an unloaded column). Don't bridge beyond it
                    break;
                }
            }
        }
        if (low > high) {
            return corridor;
        }
        int from = Math.max(surface, low - SectionShell.VERTICAL);
        int to = Math.min(bandTop, high + SectionShell.VERTICAL);
        for (int bit = from; bit <= to; bit++) {
            corridor[bit >> 6] |= 1L << bit;
        }
        return corridor;
    }

    /**
     * Min and max standable-height bits of the first shore column hit from ({@code x},{@code z}) in direction ({@code dx},{@code dz}).
     *
     * <p>{@code ceiling} is the topmost bit at which a bridge can pass in this column. Columns where every standable spot is above that and which are themselves open below
     * (small islands floating in void, self-built bridges) aren't counted as shores and the search continues past them: the height of a bridge ducking under them is decided by a shore further on
     * (real End: with height 66 on top of a small island taken as the shore, the bridge passing at height 60, 4 blocks below, was missing).
     */
    private int @Nullable [] nearestShoreSpan(CellSource cells, int x, int z, int dx, int dz, int reach,
                                              int ceiling) {
        for (int k = 1; k <= reach; k++) {
            int px = x + dx * k;
            int pz = z + dz * k;
            if (!cells.isInBounds(px, minY, pz)) {
                return null;
            }
            if (voidColumn(cells, px, pz)) {
                continue;
            }
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (int w = 0; w < words; w++) {
                long bits = word(cells, px, pz, w);
                if (bits != 0) {
                    low = Math.min(low, (w << 6) + Long.numberOfTrailingZeros(bits));
                    high = Math.max(high, (w << 6) + 63 - Long.numberOfLeadingZeros(bits));
                }
            }
            if (low <= high && low > ceiling + SectionShell.VERTICAL && voidBelow(cells, px, pz) >= 3) {
                continue;
            }
            // Don't bridge from shores with nowhere to stand (unloaded columns, floating pillars)
            return low > high ? null : new int[] {low, high};
        }
        return null;
    }

    /** A chunk's contents changed. Re-read next time it is looked up. */
    public void invalidateChunk(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        chunks.remove(key);
        incomplete.remove(key);
        shores.clear();
    }

    /** Forget chunks that were mid-load. Called before the readable range changes (at the start of a rebuild). */
    public void forgetIncomplete() {
        incomplete.clear();
        shores.clear();
    }

    public void clear() {
        chunks.clear();
        incomplete.clear();
        shores.clear();
    }

    private long[] scan(CellSource cells, int chunkX, int chunkZ, long key) {
        // The last 4 words are per-column "empty to the bottom" flags, the next 256 words per-column lava surfaces (lavaSurface + 1),
        // a further 256 words per-column count of empty cells from the bottom (voidBelow), and 256 words the bit one above the topmost block (voidAbove)
        long[] bits = new long[256 * words + 4 + 256 + 256 + 256];
        boolean absent = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkX * 16 + lx;
                int z = chunkZ * 16 + lz;
                int base = (lx + lz * 16) * words;
                long below = cells.cell(x, minY, z);
                long feet = cells.cell(x, minY + 1, z);
                absent |= !CellData.present(below);
                boolean empty = CellData.passableEmpty(below) && CellData.passableEmpty(feet);
                int voidBelow = !CellData.passableEmpty(below) ? 0 : !CellData.passableEmpty(feet) ? 1 : -1;
                int voidAbove = !CellData.passableEmpty(feet) ? 2 : !CellData.passableEmpty(below) ? 1 : 0;
                int lavaTop = -1;
                for (int y = minY + 1; y < minY + height - 1; y++) {
                    long head = cells.cell(x, y + 1, z);
                    empty &= CellData.passableEmpty(head);
                    if (!CellData.passableEmpty(head)) {
                        if (voidBelow < 0) {
                            voidBelow = y + 1 - minY;
                        }
                        voidAbove = y + 2 - minY;
                    }
                    if (CellData.lava(below) && CellData.passableEmpty(feet)) {
                        lavaTop = y - minY;
                    }
                    if (natural(below, feet, head)) {
                        bits[base + ((y - minY) >> 6)] |= 1L << (y - minY);
                    }
                    below = feet;
                    feet = head;
                }
                int column = lx + lz * 16;
                if (empty) {
                    bits[256 * words + (column >> 6)] |= 1L << column;
                }
                bits[256 * words + 4 + column] = lavaTop + 1;
                bits[256 * words + 4 + 256 + column] = voidBelow < 0 ? height : voidBelow;
                bits[256 * words + 4 + 512 + column] = voidAbove;
            }
        }
        (absent ? incomplete : chunks).put(key, bits);
        return bits;
    }

    /** Feet and head cells enterable without digging, and below the feet is a floor, water, or something climbable. */
    static boolean natural(long below, long feet, long head) {
        if (!CellData.occupiableWithoutDigging(feet) || !CellData.occupiableWithoutDigging(head)) {
            return false;
        }
        return CellData.standable(below) || CellData.water(feet) || CellData.climbable(feet);
    }
}
