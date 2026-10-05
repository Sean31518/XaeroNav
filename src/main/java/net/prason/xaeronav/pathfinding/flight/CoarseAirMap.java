package net.prason.xaeronav.pathfinding.flight;

import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.util.MathSupport;

/**
 * Coarse terrain for long-distance air routes. One cell = one chunk, holding only the <b>flyable altitude bands</b>
 * of that cell.
 *
 * <p>Derived from {@link CoarseMap} (up to 4 floor layers per chunk, used by walking layer 1). The space usable for
 * flight is not the floors themselves but the space <b>between</b> floors, so we convert it here.
 *
 * <p><b>Why this approximation holds</b>: Xaero's cave layers scan downward from {@code caveStart} and record the
 * first opaque block. If there is a rock ceiling above an open space, that ceiling itself is recorded as the floor of
 * another layer, so floors sorted by height really do enclose open space.
 *
 * <p><b>There is no guarantee you can get through.</b> It is chunk resolution, so a wall thinner than one chunk inside
 * a band is invisible. Exactly the same contract as walking layer 1; the reliable stretch is handled by
 * {@link AirGrid}, which looks at loaded chunks.
 *
 * <p>Immutable after construction. Built on the main thread and read from worker threads.
 */
public final class CoarseAirMap {

    /** Maximum number of altitude bands per cell. With N floor layers there are N bands (the top one reaches the dimension's ceiling). */
    public static final int MAX_BANDS = CoarseMap.MAX_FLOORS;

    /**
     * Margin (blocks) just above a floor that is excluded from the band. Floor height is the average within the
     * chunk, so bumps a few blocks higher are common in practice.
     */
    private static final int FLOOR_MARGIN = 4;

    /** A margin just below the ceiling too, for the same reason. */
    private static final int CEILING_MARGIN = 4;

    /** Bands thinner than this are discarded (blocks). The minimum thickness an elytra can pass with room to spare. */
    private static final int MIN_BAND_THICKNESS = 8;

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final int minY;
    private final int maxY;
    /** Number of bands per cell (0 to {@link #MAX_BANDS}). */
    private final byte[] bandCount;
    /**
     * Whether the original {@link CoarseMap} had a floor. <b>Zero bands is not the same as no data</b>: a cell whose
     * floor is packed up near the ceiling with no flyable thickness left also has zero bands. Treating "0 bands =
     * unknown = passable" without distinguishing them produces routes that pass straight through columns that are
     * actually blocked.
     */
    private final boolean[] known;
    private final short[] bottom;
    private final short[] top;

    private CoarseAirMap(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int minY, int maxY,
                          byte[] bandCount, boolean[] known, short[] bottom, short[] top) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.minY = minY;
        this.maxY = maxY;
        this.bandCount = bandCount;
        this.known = known;
        this.bottom = bottom;
        this.top = top;
    }

    /**
     * Derives altitude bands from the sequence of floors.
     *
     * @param minY lower bound of flyable height (the dimension's bottom + margin)
     * @param maxY upper bound of flyable height. In the Nether, this must be <b>below</b> the bedrock ceiling: the
     *             ceiling is opaque, so it is not recorded as a cave-layer floor, and without capping here the top
     *             band extends into the rock
     */
    public static CoarseAirMap from(CoarseMap map, int minY, int maxY) {
        int cells = map.chunksX() * map.chunksZ();
        byte[] bandCount = new byte[cells];
        boolean[] known = new boolean[cells];
        short[] bottom = new short[cells * MAX_BANDS];
        short[] top = new short[cells * MAX_BANDS];

        for (int localZ = 0; localZ < map.chunksZ(); localZ++) {
            for (int localX = 0; localX < map.chunksX(); localX++) {
                int chunkX = map.minChunkX() + localX;
                int chunkZ = map.minChunkZ() + localZ;
                int cell = localZ * map.chunksX() + localX;
                int floors = map.floorCount(chunkX, chunkZ);
                known[cell] = floors > 0;
                int count = 0;
                // A void cell has one floor but no height (CoarseMap.VOID). Impassable for walking, but for
                // flying it is empty from top to bottom, so the full height of the dimension becomes one band.
                // Adding heightAtFloor's sentinel (UNKNOWN_HEIGHT) as-is and clamping to minY would give
                // the same value, but that looks like a coincidence relying on the sentinel's value, so we split it out explicitly
                if (floors == 1 && map.kindAtFloor(chunkX, chunkZ, 0) == CoarseMap.VOID) {
                    bottom[cell * MAX_BANDS] = (short) minY;
                    top[cell * MAX_BANDS] = (short) maxY;
                    bandCount[cell] = 1;
                    continue;
                }
                for (int floor = 0; floor < floors; floor++) {
                    int bandBottom = map.heightAtFloor(chunkX, chunkZ, floor) + FLOOR_MARGIN;
                    int bandTop = floor + 1 < floors
                            ? map.heightAtFloor(chunkX, chunkZ, floor + 1) - CEILING_MARGIN
                            : maxY;
                    bandBottom = Math.max(bandBottom, minY);
                    bandTop = Math.min(bandTop, maxY);
                    if (bandTop - bandBottom < MIN_BAND_THICKNESS) {
                        continue;
                    }
                    bottom[cell * MAX_BANDS + count] = (short) bandBottom;
                    top[cell * MAX_BANDS + count] = (short) bandTop;
                    count++;
                }
                bandCount[cell] = (byte) count;
            }
        }
        return new CoarseAirMap(map.minChunkX(), map.minChunkZ(), map.chunksX(), map.chunksZ(),
                minY, maxY, bandCount, known, bottom, top);
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

    public boolean containsChunk(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        return localX >= 0 && localX < chunksX && localZ >= 0 && localZ < chunksZ;
    }

    /** Number of flyable altitude bands in the cell. */
    public int bandCount(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return 0;
        }
        return bandCount[cellIndex(chunkX, chunkZ)];
    }

    /**
     * Whether the cell has no data in Xaero's map. Merely unvisited does not mean unflyable, so the whole range is
     * treated as one band (the same idea as walking layer 1 treating {@code NO_DATA} as passable).
     */
    public boolean unknown(int chunkX, int chunkZ) {
        return containsChunk(chunkX, chunkZ) && !known[cellIndex(chunkX, chunkZ)];
    }

    /**
     * Whether the cell has data but not a single flyable band. Floors packed up near the ceiling = a <b>wall</b>.
     * The only way the coarse layer can express "impassable".
     */
    public boolean blocked(int chunkX, int chunkZ) {
        return !containsChunk(chunkX, chunkZ)
                || (known[cellIndex(chunkX, chunkZ)] && bandCount[cellIndex(chunkX, chunkZ)] == 0);
    }

    /** Number of search states. An unknown cell has one ("the whole range"), a wall has 0. */
    public int stateBands(int chunkX, int chunkZ) {
        if (blocked(chunkX, chunkZ)) {
            return 0;
        }
        return unknown(chunkX, chunkZ) ? 1 : bandCount(chunkX, chunkZ);
    }

    public int bandBottom(int chunkX, int chunkZ, int band) {
        return unknown(chunkX, chunkZ) ? minY : bottom[cellIndex(chunkX, chunkZ) * MAX_BANDS + band];
    }

    public int bandTop(int chunkX, int chunkZ, int band) {
        return unknown(chunkX, chunkZ) ? maxY : top[cellIndex(chunkX, chunkZ) * MAX_BANDS + band];
    }

    /** The height within the band closest to {@code y}. {@code y} itself if it is inside the band. */
    public int clampToBand(int chunkX, int chunkZ, int band, int y) {
        return MathSupport.clamp(y, bandBottom(chunkX, chunkZ, band), bandTop(chunkX, chunkZ, band));
    }

    /** The band containing {@code y}, or the nearest band if none does. 0 if the cell has no bands (treated as an unknown cell). */
    public int bandAt(int chunkX, int chunkZ, int y) {
        int count = bandCount(chunkX, chunkZ);
        if (count == 0) {
            return 0;
        }
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int band = 0; band < count; band++) {
            int distance = Math.abs(clampToBand(chunkX, chunkZ, band, y) - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = band;
            }
        }
        return best;
    }

    private int cellIndex(int chunkX, int chunkZ) {
        return (chunkZ - minChunkZ) * chunksX + (chunkX - minChunkX);
    }
}
