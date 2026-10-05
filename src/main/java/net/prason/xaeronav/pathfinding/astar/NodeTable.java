package net.prason.xaeronav.pathfinding.astar;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;

/**
 * Table for looking up nodes by coordinate. Same paged array as {@link MemoCells}, but this one holds
 * {@link PathNode}s instead of cells.
 *
 * <p>A node is looked up about 50 times per expansion (once per move candidate). The area around expanded nodes
 * fills in contiguously, so building array indices directly from coordinates avoids hash table lookups.
 */
final class NodeTable {

    /** Page edge size (as a power-of-two exponent). 4x4x4 for the same reason as {@link MemoCells}. */
    private static final int PAGE_BITS = 2;
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    private static final int PAGE_CELLS = 1 << (PAGE_BITS * 3);

    private static final int RECENT_BITS = 4;
    private static final int RECENT = 1 << RECENT_BITS;
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final Long2ObjectOpenHashMap<PathNode[]> pages = new Long2ObjectOpenHashMap<>();
    private final long[] recentKeys = new long[RECENT];
    private final PathNode[][] recentPages = new PathNode[RECENT][];

    /** The page containing the coordinate. Created if absent. */
    PathNode[] page(int x, int y, int z) {
        long key = BlockPos.asLong(x >> PAGE_BITS, y >> PAGE_BITS, z >> PAGE_BITS);
        int slot = (int) ((key * MIX) >>> (64 - RECENT_BITS));
        PathNode[] page = recentPages[slot];
        if (page != null && recentKeys[slot] == key) {
            return page;
        }
        page = pages.get(key);
        if (page == null) {
            page = new PathNode[PAGE_CELLS];
            pages.put(key, page);
        }
        recentKeys[slot] = key;
        recentPages[slot] = page;
        return page;
    }

    static int index(int x, int y, int z) {
        return ((y & PAGE_MASK) << (PAGE_BITS * 2)) | ((x & PAGE_MASK) << PAGE_BITS) | (z & PAGE_MASK);
    }
}
