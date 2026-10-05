package net.prason.xaeronav.pathfinding.astar;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Vertical scans downward from underfoot, and a memo of their results.
 *
 * <p>Every time a node is expanded, it walks "what's below the step-out spot" for four directions, and for jumps
 * "is it lava if missed". Where the floor is right below it stops after 1-2 blocks, but <b>when it's open below it walks
 * {@link #SCAN_DEPTH} blocks down</b>; End voids and Nether lava seas have that shape, and
 * measurements showed 23% of search time concentrated here (236-630 cell reads per node,
 * 31-47% of which were this scan).
 *
 * <p>Neighboring nodes walk the same columns again and again. So <b>only the classification of cells read is kept as per-column
 * bits, and later scans skip air word by word</b>. Rather than prefetching terrain into a table,
 * it remembers only the range the scan actually read, so <b>shallow columns never get a single extra read</b>:
 * scans per column differ by two orders of magnitude (53 in the Overworld vs. 3,500 in the End and 6,000 over lava seas),
 * so uniformly indexing whole columns would be a loss in the Overworld.
 *
 * <p><b>Nothing outside the search bounds is remembered.</b> What {@link CellSource} returns out of bounds is a per-implementation contract
 * ({@code WindowedCells} returns outside the window as unloaded while {@code isInBounds} returns true),
 * and getting ahead of it here would change what the scan means.
 */
final class ColumnScans {

    /**
     * Maximum depth (blocks) to walk down. Fall, placement and jump decisions all cut off at this depth.
     *
     * <p>Matches the value layer 1's {@code LiveCoarseSampler} uses for the same reason. It used to be 32, so
     * lava under cavities deeper than 32 blocks was missed and jumps over lava were sometimes suggested.
     *
     * <p><b>Don't use different values for fall/placement and jumps.</b> Doing so creates a mismatch where lava visible to falls
     * is invisible to jumps (in-game, a player died missing a jump into lava at the bottom of a deep crevice).
     * Cutting off at "a height that's safe to fall" is also wrong: falling into lava is fatal at any depth.
     */
    static final int SCAN_DEPTH = 128;

    /**
     * Walked only readable cells and hit nothing (i.e. no bottom).
     *
     * <p>Keeping this separate from {@link #UNREADABLE_BELOW} is the point: {@code ChunkView} represents both out-of-bounds and unloaded
     * as {@link CellData#ABSENT}, so cell values alone can't tell "void" from "unknown".
     */
    static final int NOTHING_BELOW = Integer.MIN_VALUE;

    /**
     * The scan stopped at an unloaded chunk (i.e. what's below is unknown).
     *
     * <p>Back when it made no distinction and "gave up if unreadable", <b>not a single bridge edge was
     * generated over the void</b> (the real reason paths broke off at the shore between End islands).
     */
    static final int UNREADABLE_BELOW = Integer.MIN_VALUE + 1;

    private static final int KNOWN = 0;
    /** Not air (not {@code passableEmpty}). Unloaded and out-of-bounds also fall here. */
    private static final int BLOCKER = 1;
    private static final int PRESENT = 2;
    private static final int STANDABLE = 3;
    private static final int LAVA = 4;
    private static final int PLANES = 5;

    private final CellSource view;
    private final Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
    private final int minY;
    private final int maxY;
    private final int words;

    ColumnScans(CellSource view) {
        this.view = view;
        this.minY = view.bounds().minY();
        this.maxY = view.bounds().maxY();
        this.words = ((maxY - minY) >> 6) + 1;
    }

    /**
     * Returns the Y of the first non-air cell going down from {@code topY}. Whether it stopped at water, ground or a ladder
     * is for the caller to decide by looking at that cell.
     */
    int firstNonAirBelow(int x, int topY, int z) {
        int lowestY = topY - SCAN_DEPTH + 1;
        long[] column = null;
        int y = topY;
        while (y >= lowestY) {
            if (y < minY || y > maxY) {
                long cell = view.cell(x, y, z);
                if (CellData.passableEmpty(cell)) {
                    y--;
                    continue;
                }
                if (CellData.present(cell)) {
                    return y;
                }
                return view.isInBounds(x, y, z) ? UNREADABLE_BELOW : NOTHING_BELOW;
            }
            if (column == null) {
                column = column(x, z);
            }
            int index = y - minY;
            if (!bit(column, KNOWN, index)) {
                remember(column, index, view.cell(x, y, z));
            }
            if (bit(column, BLOCKER, index)) {
                return bit(column, PRESENT, index) ? y
                        : view.isInBounds(x, y, z) ? UNREADABLE_BELOW : NOTHING_BELOW;
            }
            y = skipDown(column, index, Math.max(lowestY, minY), false);
        }
        return NOTHING_BELOW;
    }

    /**
     * Whether there is lava within {@link #SCAN_DEPTH} blocks below the feet, or it can't be seen through.
     *
     * <p>Void (walking readable cells without hitting bottom) returns {@code false}: falling in is as deadly as
     * lava, but that case is {@code PathSafetyChecker#assessJumpRisk}'s job, which warns with
     * {@link PathRisk#VOID_BELOW}. Forbidding it uniformly here would wipe out every jump move in the End,
     * where every gap is over the void.
     */
    boolean lavaOrUnknownBelow(int x, int y, int z) {
        int lowestY = y - SCAN_DEPTH;
        long[] column = null;
        int cellY = y - 1;
        while (cellY >= lowestY) {
            if (cellY < minY || cellY > maxY) {
                long cell = view.cell(x, cellY, z);
                if (CellData.lava(cell)) {
                    return true;
                }
                if (CellData.standable(cell)) {
                    return false;
                }
                if (!CellData.present(cell)) {
                    return view.isInBounds(x, cellY, z);
                }
                cellY--;
                continue;
            }
            if (column == null) {
                column = column(x, z);
            }
            int index = cellY - minY;
            if (!bit(column, KNOWN, index)) {
                remember(column, index, view.cell(x, cellY, z));
            }
            if (bit(column, LAVA, index)) {
                return true;
            }
            if (bit(column, STANDABLE, index)) {
                return false;
            }
            if (!bit(column, PRESENT, index)) {
                return view.isInBounds(x, cellY, z);
            }
            cellY = skipDown(column, index, Math.max(lowestY, minY), true);
        }
        return false;
    }

    /**
     * Skips word by word from just below {@code index} to {@code lowestY}, and returns the Y of the next cell to look at.
     * If everything in between is "known cells that need no look", returns {@code lowestY - 1} (i.e. the end of the scan).
     */
    private int skipDown(long[] column, int index, int lowestY, boolean lavaScan) {
        int lowIndex = lowestY - minY;
        int from = index - 1;
        if (from < lowIndex) {
            return lowestY - 1;
        }
        int topWord = from >> 6;
        for (int word = topWord; word >= (lowIndex >> 6); word--) {
            long stops = stopMask(column, word, lavaScan);
            if (word == topWord) {
                int top = from & 63;
                stops &= top == 63 ? -1L : (1L << (top + 1)) - 1;
            }
            if (stops != 0L) {
                int found = (word << 6) + (63 - Long.numberOfLeadingZeros(stops));
                return found >= lowIndex ? found + minY : lowestY - 1;
            }
        }
        return lowestY - 1;
    }

    /** The cells in that word where the scan should stop (including cells not yet read). */
    private long stopMask(long[] column, int word, boolean lavaScan) {
        if (lavaScan) {
            // Unread cells have PRESENT at 0, so ~PRESENT doubles as "re-read" as-is
            return column[LAVA * words + word] | column[STANDABLE * words + word]
                    | ~column[PRESENT * words + word];
        }
        return column[BLOCKER * words + word] | ~column[KNOWN * words + word];
    }

    private long[] column(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        long[] column = columns.get(key);
        if (column == null) {
            column = new long[PLANES * words];
            columns.put(key, column);
        }
        return column;
    }

    private void remember(long[] column, int index, long cell) {
        set(column, KNOWN, index);
        if (!CellData.passableEmpty(cell)) {
            set(column, BLOCKER, index);
        }
        if (CellData.present(cell)) {
            set(column, PRESENT, index);
        }
        if (CellData.standable(cell)) {
            set(column, STANDABLE, index);
        }
        if (CellData.lava(cell)) {
            set(column, LAVA, index);
        }
    }

    private boolean bit(long[] column, int plane, int index) {
        return (column[plane * words + (index >> 6)] & (1L << (index & 63))) != 0L;
    }

    private void set(long[] column, int plane, int index) {
        column[plane * words + (index >> 6)] |= 1L << (index & 63);
    }
}
