package net.prason.xaeronav.pathfinding.coarse;

import net.minecraft.core.BlockPos;
//? if >=1.17 {
import net.minecraft.world.level.LevelHeightAccessor;
//?} else {
/*import net.minecraft.world.level.Level;
*///?}
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.GameCompat;

/**
 * Coarse <b>3D</b> terrain covering the whole route. It exists solely for {@link VoxelCostToGo}.
 *
 * <p>It plays the same role as layer 1 ({@link CoarseMap}), "roughly knowing distant terrain", but there one cell
 * is one chunk column (2.5D), which <b>gives no information at all</b> in dimensions with a ceiling. Measured (a user's stuck route
 * {@code (-328,64,696)→(-259,64,379)}): a search using layer 1's cost-to-go could not move a single step from the start,
 * and without a guide it was still unreached at 3 million nodes even with the whole world visible. The same route is reached
 * with a guide built from the 3D grid made here. The vertically stacked tunnels of the Nether can't be
 * expressed by a representation that holds a few floors per column.
 *
 * <p><b>It doesn't need to be accurate.</b> Even a model that knows only floor positions and treats everything else as "open"
 * reached the goal. What Xaero's cave layers can provide is exactly "floor Y per column, per slice", so
 * the design assumes this granularity is enough.
 *
 * <p>There are 3 cell kinds, and <b>the default is {@link #OPEN}, meaning "floor unknown"</b>. Making {@link #OPEN} a wall
 * erases detours through unmapped areas, and the guide can only say "unreachable". Pricing is done in
 * {@link VoxelCostToGo}, using actual costs as-is (shrinking or lowering the ratio was measured and dropped).
 *
 * <p><b>The weak spot of this layer is the Y range of {@link #boxFor}</b>. Taking the full dimension height, in environments where the
 * dimension is taller than its contents, the space above the bedrock ceiling fills half the grid and the guide draws a "bridge over the ceiling" route.
 *
 * <p>The data source is <b>only Xaero's cave layers</b>. Overlaying real data from loaded chunks
 * <b>did not change the route by a single move in measurements</b> (painting 43,000 cells as walls on a user's stuck route gave
 * an identical result). That isn't worth reading the search {@code CellSource} millions of times.
 *
 * <p>After creation, fill it with {@link #markFloor}, and don't modify it after passing it to {@link VoxelCostToGo#build}
 * (the guide keeps reading this array).
 */
public final class VoxelTerrain {

    // The ordering of values is the overwrite priority. One cell receives multiple columns and layers, so
    // the result must not depend on write order
    /** No standable floor (open/unknown). Crossing requires a bridge. */
    public static final byte OPEN = 0;

    /** Lava surface. Not standable, and the bridgeable length is shorter than over air. */
    public static final byte LAVA = 1;

    /** Has a standable floor. Can be run across. */
    public static final byte STANDABLE = 2;

    /** Default grid cell edge (blocks). Measured: at this granularity, covering the whole route builds in about a second. */
    public static final int DEFAULT_CELL_BLOCKS = 4;

    /**
     * Upper limit on total grid cells. Any excess is absorbed by coarsening cells ({@link #cellBlocksFor}).
     *
     * <p>The farther the destination, the larger the box, so without this both memory and build time grow with the square of distance.
     * Measured: 500,000 cells (543 blocks square, full Nether height) took about 1.2 s and about 5 MB.
     */
    private static final int MAX_CELLS = 500_000;

    /** Coarsening order. If it still exceeds the limit after exhausting these, the box isn't handled at all. */
    private static final int[] CELL_LADDER = {DEFAULT_CELL_BLOCKS, 6, 8, 12, 16, 24, 32};

    /**
     * Margin (blocks) to extend around the start and destination.
     *
     * <p>A band connecting the two ends isn't enough: the correct route in the Nether bulges far sideways.
     *
     * <p><b>If this is narrow, routes passing outside the box don't exist for the coarse layer.</b> On a real save's lava sea (a route of about 300 blocks),
     * the optimal route passed <b>186 steps outside the box</b>, inflating the estimate to 1.41x the true value. The route across lava
     * fit in the box and looked like 0.73-0.91x, so <b>the corridor with the most optimistic out-of-window estimate</b> (the lava side) was chosen,
     * and walking all the way became 1.26-1.28x the optimum (normally 1.00-1.02x in the Nether).
     *
     * <p><b>A uniform factor can't fix it</b> ({@code NavGraphGuide.VOXEL_FAR_SCALE}). The error direction is opposite per corridor,
     * so multiplication moves both equally and can't change the ordering. Widening this <b>aligns</b> the calibration at 0.79-0.81,
     * and only then does a uniform factor take on its intended meaning (correcting for shrinkage).
     *
     * <p>Values are measured. 192, 256 and 320 all gave the same quality on 7 Nether routes (mean 1.006, worst 1.024); only 128 gave 1.082/1.279.
     * <b>256 is used rather than the minimum 192</b> because only 3 routes over a single lava sea were measured, and a borderline value
     * would fall short on other terrain. Widening increases the map scan area (about 2.6x at 256, and {@code NetherVoxelGuide} reads it
     * on the main thread), so it must not be widened without limit.
     *
     * <p>The {@link #MAX_CELLS} ladder absorbs it, so widening doesn't increase the cell count; instead the cell edge coarsens from 4 to 6.
     */
    public static final int MARGIN_BLOCKS = 256;

    private final SearchBounds box;
    private final int cellBlocks;
    private final boolean lavaPassable;
    private final int nx;
    private final int ny;
    private final int nz;
    private final byte[] kind;
    private int floorMarks;

    private VoxelTerrain(SearchBounds box, int cellBlocks, boolean lavaPassable, int nx, int ny, int nz) {
        this.box = box;
        this.cellBlocks = cellBlocks;
        this.lavaPassable = lavaPassable;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.kind = new byte[nx * ny * nz];
    }

    /**
     * Margin (blocks) to keep above and below floors. The floors alone aren't enough: the route passes above the floor,
     * and there may be unmapped floors slightly above or below.
     */
    public static final int VERTICAL_MARGIN_BLOCKS = 24;

    /**
     * Build a box covering the start and destination. Y is <b>the range where floors actually exist on the map</b> plus margin,
     * <b>not the full dimension height</b>.
     *
     * <p><b>Don't use the full dimension height.</b> Since the purpose of this layer is to represent vertically stacked passages in
     * dimensions with a ceiling, it's tempting to think "Y must not be narrowed", but whether the narrowing is based on the <b>dimension</b>
     * or the <b>map</b> is a separate matter. In environments where the Nether is taller than 128 (the box reverse-engineered from a real log
     * was <b>256 high in Y</b>), the space above the bedrock ceiling fills <b>half</b> the grid. Two harms:
     *
     * <ul>
     *   <li>That space is all "cells with no floor", so the guide draws a <b>bridge route over the ceiling</b>.
     *       Measured (a 0..255 box): walking all the way became impossible, and the search was dragged to y=95, 100 blocks
     *       west of the route, the same shape as the "wide detour at the seam (x=-416)" in the real log</li>
     *   <li>The space eats into the {@link #MAX_CELLS} budget, so the grid coarsens. In practice it had coarsened to edge 6
     *       (narrowed to the range with floors, the same route fits in edge 4)</li>
     * </ul>
     *
     * @param lowestFloorY  Y of the lowest floor readable from the map
     * @param highestFloorY likewise, Y of the highest floor
     */
    public static SearchBounds boxFor(
            //? if >=1.17 {
            LevelHeightAccessor level,
            //?} else {
            /*Level level,
            *///?}
            BlockPos start, BlockPos goal,
            int lowestFloorY, int highestFloorY) {
        // The start and destination must always be inside the box. If the destination is outside, the guide has no origin and the table is empty;
        // if the start is outside, the estimate plateaus at the edge value
        int low = Math.min(lowestFloorY, Math.min(start.getY(), goal.getY()));
        int high = Math.max(highestFloorY, Math.max(start.getY(), goal.getY()));
        return new SearchBounds(
                Math.min(start.getX(), goal.getX()) - MARGIN_BLOCKS,
                Math.max(GameCompat.minBuildHeight(level), low - VERTICAL_MARGIN_BLOCKS),
                Math.min(start.getZ(), goal.getZ()) - MARGIN_BLOCKS,
                Math.max(start.getX(), goal.getX()) + MARGIN_BLOCKS,
                Math.min(GameCompat.maxBuildHeight(level) - 1, high + VERTICAL_MARGIN_BLOCKS),
                Math.max(start.getZ(), goal.getZ()) + MARGIN_BLOCKS);
    }

    /** The smallest cell edge that fits this box in {@link #MAX_CELLS}. 0 if it doesn't fit. */
    public static int cellBlocksFor(SearchBounds box) {
        for (int candidate : CELL_LADDER) {
            long cells = (long) axisCells(box.minX(), box.maxX(), candidate)
                    * axisCells(box.minY(), box.maxY(), candidate)
                    * axisCells(box.minZ(), box.maxZ(), candidate);
            if (cells <= MAX_CELLS) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * A grid whose cell edge is chosen to fit the box. {@code null} if the box is too large to handle.
     *
     * @param lavaPassable whether lava may be crossed by placing footing ({@code CellSource#lavaBridgingEnabled}).
     *                     If not, the price of {@link #LAVA} jumps ({@link VoxelCostToGo})
     */
    public static VoxelTerrain of(SearchBounds box, boolean lavaPassable) {
        int cellBlocks = cellBlocksFor(box);
        return cellBlocks == 0 ? null : of(box, cellBlocks, lavaPassable);
    }

    public static VoxelTerrain of(SearchBounds box, int cellBlocks, boolean lavaPassable) {
        return new VoxelTerrain(box, cellBlocks, lavaPassable,
                axisCells(box.minX(), box.maxX(), cellBlocks),
                axisCells(box.minY(), box.maxY(), cellBlocks),
                axisCells(box.minZ(), box.maxZ(), cellBlocks));
    }

    private static int axisCells(int min, int max, int cellBlocks) {
        return (max - min) / cellBlocks + 1;
    }

    /**
     * Copy one floor that Xaero's map records for one column. <b>Call this per block resolution and per
     * layer</b>: flattening to a chunk-average floor (layer 1's {@link CoarseMap}) leaves passages in the Nether
     * mostly unfilled, and the guide answers "bridging straight across unknown places is cheaper"
     * (measured: with chunk averages only 6% of all cells were walkable floor, and the search was pulled toward the ceiling).
     *
     * <p>Marks the cell <b>above</b> the floor. {@code floorTopY} is "the Y of the topmost solid block", the same as Xaero's
     * {@code MapBlock#getHeight}, and you stand one above it.
     *
     * @param lava lava surface. Not standable, so {@link #LAVA}. Marked even when bridging across is enabled:
     *             giving a lava sea the same price as "a place that's merely unmapped" loses the direction to detour
     */
    public void markFloor(int x, int z, int floorTopY, boolean lava) {
        int standY = floorTopY + 1;
        if (!box.contains(x, standY, z)) {
            return;
        }
        floorMarks++;
        int index = indexOfBlock(x, standY, z);
        byte mark = lava ? LAVA : STANDABLE;
        if (kind[index] < mark) {
            kind[index] = mark;
        }
    }

    /** Number of floors copied into the box by {@link #markFloor} (diagnostic). 0 means nothing was obtained from the map. */
    public int floorMarks() {
        return floorMarks;
    }

    public SearchBounds box() {
        return box;
    }

    public int cellBlocks() {
        return cellBlocks;
    }

    /** Whether lava may be crossed by placing footing ({@code CellSource#lavaBridgingEnabled}). */
    boolean lavaPassable() {
        return lavaPassable;
    }

    public int cellCount() {
        return kind.length;
    }

    int nx() {
        return nx;
    }

    int ny() {
        return ny;
    }

    int nz() {
        return nz;
    }

    byte kindAt(int index) {
        return kind[index];
    }

    /** Cell count per kind (diagnostic). The floor ratio directly reflects how effective this layer is. */
    public String breakdown() {
        int lava = 0;
        int standable = 0;
        for (byte value : kind) {
            if (value == LAVA) {
                lava++;
            } else if (value == STANDABLE) {
                standable++;
            }
        }
        return "floor=" + standable + ", lava=" + lava + ", open=" + (kind.length - lava - standable);
    }

    /** Whether block coordinates are inside the box. */
    public boolean contains(int x, int y, int z) {
        return box.contains(x, y, z);
    }

    int indexOfBlock(int x, int y, int z) {
        return index(cellIndex(x, box.minX()), cellIndex(y, box.minY()), cellIndex(z, box.minZ()));
    }

    /** Coordinates outside the box are clamped to the nearest edge cell. */
    int clampedIndexOfBlock(int x, int y, int z) {
        return index(clamp(cellIndex(clampBlock(x, box.minX(), box.maxX()), box.minX()), nx),
                clamp(cellIndex(clampBlock(y, box.minY(), box.maxY()), box.minY()), ny),
                clamp(cellIndex(clampBlock(z, box.minZ(), box.maxZ()), box.minZ()), nz));
    }

    static int clampBlock(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }

    private int cellIndex(int block, int min) {
        return (block - min) / cellBlocks;
    }

    int index(int i, int j, int k) {
        return (j * nz + k) * nx + i;
    }

    int cellX(int index) {
        return index % nx;
    }

    int cellZ(int index) {
        return (index / nx) % nz;
    }

    int cellY(int index) {
        return index / (nx * nz);
    }
}
