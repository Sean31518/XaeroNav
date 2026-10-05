package net.prason.xaeronav.pathfinding.coarse;

/**
 * Coarse terrain for long-distance routes. 1 cell = 1 chunk (16×16 blocks), holding only the terrain kind and a representative height.
 *
 * <p>Unlike the detailed search, which can only see inside loaded chunks, this is built from the map of visited areas
 * that Xaero saves. Its purpose is deciding "which way around to go to avoid seas and lava", so
 * it holds no per-block passability (a 1-block-wide bridge can't be represented). The path actually followed is
 * replanned by the detailed search toward the intermediate targets produced here.
 *
 * <p>A cell holds <b>up to {@value #MAX_FLOORS} floors</b> (a floor = a {kind, height,
 * minHeight, maxHeight} tuple, sorted by ascending height). In dimensions with a ceiling (the Nether), Xaero's map is split into cave layers
 * per Y band, and several independent passages can stack vertically at the same XZ. Collapsing them into one height
 * (the old implementation) made vertically separated passages look connected by a "cheap step", or dropped waypoints
 * onto unreachable levels. The Overworld and the End always have 1 floor, so the existing 2.5D-style
 * behavior is preserved as is.
 *
 * <p>Immutable once built. Assumed to be assembled on the main thread and read from worker threads, so
 * don't give it mutable fields.
 */
public final class CoarseMap {

    public static final byte NO_DATA = 0;
    public static final byte LAND = 1;
    public static final byte WATER = 2;
    public static final byte LAVA = 3;

    /**
     * A cell mixed with lava that can still be walked through.
     *
     * <p>Kept separate from {@link #LAVA} because making a chunk impassable just because part of it is lava
     * turns the majority of Nether terrain into walls (measured: 58% of known cells were judged lava).
     * Whether lava can be avoided block by block isn't known from the coarse map, so here it stays
     * "passable but expensive", and the decision of whether it can actually be crossed is left to layers 2 and 3.
     */
    public static final byte LAVA_MIXED = 4;

    /**
     * A cell with no floor at all (the End's void, or a large Nether cavern whose bottom is below the read range).
     *
     * <p><b>Keeping it separate from {@link #NO_DATA} is the point.</b> Xaero always records visited columns, and for columns where
     * not a single opaque block was found it writes "air, height = world minimum Y" ({@code MapWriter#loadPixel}).
     * So <b>the void isn't "no data" but "data saying there's no floor"</b>, and it can be told apart from unvisited areas
     * by whether a tile exists. Back when it was lumped into {@link #NO_DATA} without distinction, the unknown-cell multiplier
     * (passable, nearly the cheapest) applied to the void too, and layer 1 lined up intermediate targets cutting straight across
     * between the End's islands; the detailed search couldn't bridge there and burned its budget every time.
     *
     * <p>It has no height ({@link #UNKNOWN_HEIGHT}). The void has no representative height, and putting in a concrete value like 0
     * would make layers 2 and 3 aim there.
     */
    public static final byte VOID = 5;

    /** Height of a cell with no data. */
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    /**
     * Maximum number of floors a cell can hold.
     *
     * <p><b>{@code CoarseMapBuilder#putFloor} discards the "highest floor" when the limit is exceeded.</b>
     * If this is too small in a dimension with a ceiling, what gets discarded tends to be <b>the upper level = the walkable level</b>:
     * a single Nether column easily exceeds 4 layers with "lava sea, lower cave, forest floor, passage near the ceiling".
     * {@code XaeroMapReader#MAX_CAVE_LAYERS} (the cave layer slots) is kept in line with this value, and
     * in dimensions without a ceiling {@code XaeroMapReader#layersFor} leaves one slot free for the surface layer.
     */
    public static final int MAX_FLOORS = 6;

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    /** Floor count per cell (0 to {@link #MAX_FLOORS}). Length {@code chunksX*chunksZ}. */
    private final byte[] floorCount;
    /** Length {@code chunksX*chunksZ*MAX_FLOORS}. Sorted by ascending height within a cell. */
    private final byte[] kind;
    private final short[] height;
    private final short[] minHeight;
    private final short[] maxHeight;
    private final int knownCells;
    /**
     * Landmass ID per cell ({@link #NO_ISLAND} for cells that aren't {@code LAND}). Length {@code chunksX*chunksZ}.
     * The same ID is given to a cluster of {@code LAND} cells connected via their 8 neighbors.
     */
    private final int[] islandId;
    /** Cell count per landmass ID. Looked up with {@code islandSize[islandId[cell]]}. */
    private final int[] islandSize;

    /** Value in {@link #islandId} meaning "not land". */
    public static final int NO_ISLAND = -1;

    CoarseMap(int minChunkX, int minChunkZ, int chunksX, int chunksZ, byte[] floorCount,
              byte[] kind, short[] height, short[] minHeight, short[] maxHeight, int knownCells,
              int[] islandId, int[] islandSize) {
        this.islandId = islandId;
        this.islandSize = islandSize;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.floorCount = floorCount;
        this.kind = kind;
        this.height = height;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.knownCells = knownCells;
    }

    public int minChunkX() {
        return minChunkX;
    }

    public int minChunkZ() {
        return minChunkZ;
    }

    public int chunksX() {
        return chunksX;
    }

    public int chunksZ() {
        return chunksZ;
    }

    /** Number of cells whose data could be read. If 0, this range isn't on Xaero's map (unvisited). */
    public int knownCells() {
        return knownCells;
    }

    public int totalCells() {
        return chunksX * chunksZ;
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        return localX >= 0 && localX < chunksX && localZ >= 0 && localZ < chunksZ;
    }

    /** Number of floors this cell has. 0 if out of range or without data. */
    public int floorCount(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return 0;
        }
        return floorCount[cellIndex(chunkX, chunkZ)];
    }

    public byte kindAtFloor(int chunkX, int chunkZ, int floor) {
        return kind[floorIndex(chunkX, chunkZ, floor)];
    }

    /** Representative height of that floor. For water, the height of the water surface rather than the bottom. */
    public short heightAtFloor(int chunkX, int chunkZ, int floor) {
        return height[floorIndex(chunkX, chunkZ, floor)];
    }

    /**
     * Minimum and maximum heights observed inside that floor. The average alone can't distinguish a chunk with a cliff from one with a gentle slope,
     * so this difference ({@code maxHeightAtFloor - minHeightAtFloor}) is used as a cliff indicator.
     */
    public short minHeightAtFloor(int chunkX, int chunkZ, int floor) {
        return minHeight[floorIndex(chunkX, chunkZ, floor)];
    }

    public short maxHeightAtFloor(int chunkX, int chunkZ, int floor) {
        return maxHeight[floorIndex(chunkX, chunkZ, floor)];
    }

    /**
     * Picks the floor whose height is closest to {@code fromFloorHeight}. Used for choosing the landing spot of a vertical transition (crossing levels within the same cell)
     * and for resolving waypoint coordinates. -1 if there are no floors.
     */
    public int nearestFloor(int chunkX, int chunkZ, int fromFloorHeight) {
        int count = floorCount(chunkX, chunkZ);
        if (count == 0) {
            return -1;
        }
        int bestFloor = 0;
        int bestDistance = Math.abs(heightAtFloor(chunkX, chunkZ, 0) - fromFloorHeight);
        for (int floor = 1; floor < count; floor++) {
            int distance = Math.abs(heightAtFloor(chunkX, chunkZ, floor) - fromFloorHeight);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestFloor = floor;
            }
        }
        return bestFloor;
    }

    /**
     * A string counting cells per kind (for diagnostics). When a cell has several floors, the first floor represents it.
     *
     * <p>Needed in-game (the End, 2026-08-28): route choices across the void wobbled from attempt to attempt, but
     * the log showed only {@code knownCells} (the number of cells with at least one floor), so it couldn't be told <b>whether the void
     * was seen as void, or there was no data at all</b>. {@code NO_DATA}
     * is passable at as little as 1.6x the cost of land, so if the void had fallen into {@code NO_DATA},
     * that alone would explain "the line cutting across the void looks cheap". <b>This breakdown not only explains
     * but moves the price itself</b>: {@link CoarseRouter} calibrates the {@code NO_DATA} multiplier from the known land:void ratio,
     * so if the void falls into {@code NO_DATA}, the calibration material is lost at the same time.
     */
    public String kindBreakdown() {
        int[] counts = kindCounts();
        return "land=" + counts[LAND] + ", void=" + counts[VOID] + ", water=" + counts[WATER]
                + ", lava=" + (counts[LAVA] + counts[LAVA_MIXED])
                + ", no data=" + counts[NO_DATA];
    }

    /**
     * Cell count per kind (indexed by the constant values {@link #NO_DATA} through {@link #VOID}). When a cell has several floors,
     * the first floor represents it. Shares {@link #kindBreakdown}'s internal tally with {@link CoarseRouter}'s
     * unknown-cell calibration (estimating the price of {@code NO_DATA} from the known land:void ratio).
     */
    int[] kindCounts() {
        int[] counts = new int[VOID + 1];
        for (int index = 0; index < chunksX * chunksZ; index++) {
            if (floorCount[index] == 0) {
                counts[NO_DATA]++;
                continue;
            }
            byte cellKind = kind[index * MAX_FLOORS];
            if (cellKind >= 0 && cellKind < counts.length) {
                counts[cellKind]++;
            }
        }
        return counts;
    }

    /**
     * ID of the landmass this cell belongs to. {@link #NO_ISLAND} if it isn't land.
     *
     * <p>Needed to distinguish "is the other side the same island or a different one": {@link CoarseRouter}
     * factors island size into the price <b>only at the moment it moves to a different landmass</b>. Charging per cell
     * would charge many times while crossing a small island.
     */
    public int islandIdAt(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return NO_ISLAND;
        }
        return islandId[cellIndex(chunkX, chunkZ)];
    }

    /**
     * Cell count of the landmass this cell belongs to (1 cell = 1 chunk = 16×16 blocks). 0 if it isn't land.
     *
     * <p>Material for meeting the request, in the End, "avoid island-to-island crossings where possible; travel across large islands".
     * Layer 1 is at chunk resolution, so this is the only information
     * usable as "island size".
     */
    public int islandSizeAt(int chunkX, int chunkZ) {
        int id = islandIdAt(chunkX, chunkZ);
        return id == NO_ISLAND ? 0 : islandSize[id];
    }

    private int cellIndex(int chunkX, int chunkZ) {
        return (chunkZ - minChunkZ) * chunksX + (chunkX - minChunkX);
    }

    private int floorIndex(int chunkX, int chunkZ, int floor) {
        return cellIndex(chunkX, chunkZ) * MAX_FLOORS + floor;
    }
}
