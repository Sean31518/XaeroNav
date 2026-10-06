package net.prason.xaeronav.pathfinding.astar;

import org.jspecify.annotations.Nullable;
import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.Mount;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * A {@link CellSource} wrapper that remembers read cells in an array of small cubes (pages).
 *
 * <p>The search reads cells 197 to 413 times per node, but only about 20,000 columns are touched over the whole
 * search: most reads are <b>re-reads of the same cell</b>, and each one did a random access into a hash table
 * with millions of entries (measured at about 50% of search time). Computing an array index directly from the
 * coordinates lets every read after the first skip the table.
 *
 * <p>Pages are cubes with side {@link #PAGE_BITS} because holding the whole search range in one array would allocate
 * space even where nothing is read (the Nether search range exceeds 100 million cells).
 * Only the area around what is read gets allocated.
 *
 * <p>To skip even the page table lookup, the {@link #RECENT} most recently used pages are kept in an array.
 * <b>A few is not enough</b>: vertical scans cross page boundaries every 4 cells and move generation at x/z±1,
 * so with 2 pages the Nether measurement regresses from 1815 to 2158 ms. 64 pages is no different, so it plateaus at 16.
 *
 * <p><b>Assumption: the delegate's {@code cell} returns the same value throughout the search.</b> This holds because
 * neither {@code ChunkView} nor {@code PlannedCellSource} re-reads the world after construction.
 * If you add a {@code CellSource} whose terrain changes mid-search, it must not go through here.
 *
 * <p><b>The thread contract is the same as the delegate's</b>: owned by a single worker thread.
 */
final class MemoCells implements CellSource {

    /**
     * Sentinel for a cell not yet read. The upper 32 bits are the float bits of the dig tick count; all bits set
     * means a negative NaN, which is never produced, so it cannot collide with a real cell (the same reasoning
     * {@code ChunkView}'s cell cache relies on). {@code CellData#ABSENT} is 0, so 0 cannot be used.
     */
    private static final long UNREAD = -1L;

    /**
     * Size of one page side (exponent of a power of 2). 2 = 4×4×4 = 64 cells. 8×8×8 was within measurement noise
     * for speed and only made each page's allocation 8 times larger.
     */
    private static final int PAGE_BITS = 2;
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    private static final int PAGE_CELLS = 1 << (PAGE_BITS * 3);

    /** Number of recent pages to remember (exponent of a power of 2). At 0 the slot calculation becomes a 64-bit shift = no shift. */
    private static final int RECENT_BITS = 4;
    private static final int RECENT = 1 << RECENT_BITS;

    /** Multiplier derived from the golden ratio. Page keys just bit-pack the coordinates, so the low bits only vary with Y. */
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final CellSource source;
    private final Long2ObjectOpenHashMap<long[]> pages = new Long2ObjectOpenHashMap<>();
    private final long[] recentKeys = new long[RECENT];
    private final long[][] recentPages = new long[RECENT][];

    MemoCells(CellSource source) {
        this.source = source;
    }

    @Override
    public long cell(int x, int y, int z) {
        long key = BlockPos.asLong(x >> PAGE_BITS, y >> PAGE_BITS, z >> PAGE_BITS);
        int slot = (int) ((key * MIX) >>> (64 - RECENT_BITS));
        long[] page = recentPages[slot];
        if (page == null || recentKeys[slot] != key) {
            page = pages.get(key);
            if (page == null) {
                page = new long[PAGE_CELLS];
                Arrays.fill(page, UNREAD);
                pages.put(key, page);
            }
            recentKeys[slot] = key;
            recentPages[slot] = page;
        }
        int index = ((y & PAGE_MASK) << (PAGE_BITS * 2))
                | ((x & PAGE_MASK) << PAGE_BITS)
                | (z & PAGE_MASK);
        long cached = page[index];
        if (cached != UNREAD) {
            return cached;
        }
        long computed = source.cell(x, y, z);
        page[index] = computed;
        return computed;
    }

    // Everything below is pass-through. Write out every method, including default ones: leaving defaults the delegate
    // overrides (surfacedY, etc.) to inheritance changes behavior the moment it is wrapped.

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return source.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return source.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return source.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return source.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return source.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return source.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return source.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return source.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return source.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return source.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return source.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return source.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return source.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return source.avoidRiskyJumps();
    }

    @Override
    public boolean strictLimits() {
        return source.strictLimits();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return source.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return source.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return source.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return source.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return source.ridingBoat();
    }

    @Override
    public @Nullable Mount mount() {
        return source.mount();
    }

    @Override
    public RouteProfile routeProfile() {
        return source.routeProfile();
    }

    @Override
    public boolean swimmingEnabled() {
        return source.swimmingEnabled();
    }

    @Override
    public int openSkyY(int x, int z) {
        return source.openSkyY(x, z);
    }

    @Override
    public int surfacedY(int x, int z) {
        return source.surfacedY(x, z);
    }
}
