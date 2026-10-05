package net.prason.xaeronav.pathfinding.flight;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Coarse voxel grid for air routes. One cell is a cube of {@code cellBlocks} blocks, and it counts as
 * flyable <b>only when every block it contains is empty</b>.
 *
 * <p>The key point is that the coarseness itself acts as clearance. Elytra fly at around 30 blocks per second, so
 * guidance that threads a 1-block gap is meaningless. By only offering "space you can pass with room to spare" at
 * grid granularity as route candidates, a margin of a few blocks naturally remains around the line. The user's
 * request for "a larger tolerance" is expressed here too, not just in the display and deviation checks.
 *
 * <p><b>No precomputation.</b> Covering a render radius of 192 and the full Nether height with 4-block cubes is about
 * 290,000 cells = 19 million block lookups, so filling it in advance is not viable. Only cells A* touches are computed
 * and memoized (the same approach as {@code ChunkView} memoizing per-block-state checks).
 *
 * <p><b>Thread contract:</b> the memo lives in mutable fields, so a single worker thread must own this
 * (the underlying {@link CellSource} already has the same constraint).
 */
public final class AirGrid {

    private static final byte UNKNOWN = 0;
    private static final byte FLYABLE = 1;
    private static final byte BLOCKED = 2;

    private final CellSource view;
    private final int cellBlocks;
    private final Long2ByteOpenHashMap known = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap blocked = new Long2ByteOpenHashMap();

    public AirGrid(CellSource view, int cellBlocks) {
        this.view = view;
        this.cellBlocks = cellBlocks;
        this.known.defaultReturnValue(UNKNOWN);
        this.blocked.defaultReturnValue(NOT_COUNTED);
    }

    public int cellBlocks() {
        return cellBlocks;
    }

    /** Coordinates of the cell containing the block coordinate. */
    public int toCell(double blockCoordinate) {
        return Math.floorDiv((int) Math.floor(blockCoordinate), cellBlocks);
    }

    /** Block coordinates of the cell's center. */
    public double toBlockCenter(int cell) {
        return cell * (double) cellBlocks + cellBlocks / 2.0;
    }

    public Vec3 center(int cellX, int cellY, int cellZ) {
        return new Vec3(toBlockCenter(cellX), toBlockCenter(cellY), toBlockCenter(cellZ));
    }

    /**
     * Whether the cell may be used for flight.
     *
     * <p>Out-of-range and unloaded chunks are {@link CellData#ABSENT} = {@code present} is false, so they
     * automatically become unflyable. {@code FlightLineRouter} lets unloaded areas pass, but that is a line that only
     * shows the direction; this is a route actually followed. <b>Never draw a route into the unknown.</b>
     * Beyond the readable range, the dotted line takes over.
     */
    public boolean flyable(int cellX, int cellY, int cellZ) {
        long key = BlockPos.asLong(cellX, cellY, cellZ);
        byte cached = known.get(key);
        if (cached != UNKNOWN) {
            return cached == FLYABLE;
        }
        boolean flyable = computeFlyable(cellX, cellY, cellZ);
        known.put(key, flyable ? FLYABLE : BLOCKED);
        return flyable;
    }

    private boolean computeFlyable(int cellX, int cellY, int cellZ) {
        int fromX = cellX * cellBlocks;
        int fromY = cellY * cellBlocks;
        int fromZ = cellZ * cellBlocks;
        for (int x = fromX; x < fromX + cellBlocks; x++) {
            for (int y = fromY; y < fromY + cellBlocks; y++) {
                for (int z = fromZ; z < fromZ + cellBlocks; z++) {
                    long cell = view.cell(x, y, z);
                    if (!CellData.present(cell) || !CellData.passableEmpty(cell)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Number of unflyable cells among the 26 neighbors. Sentinel meaning not yet counted. */
    private static final byte NOT_COUNTED = -1;

    /** Number of unflyable cells among the 26 neighbors (0 to 26). Used as a measure of tightness. */
    public int blockedNeighbours(int cellX, int cellY, int cellZ) {
        long key = BlockPos.asLong(cellX, cellY, cellZ);
        byte cached = blocked.get(key);
        if (cached != NOT_COUNTED) {
            return cached;
        }
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dy != 0 || dz != 0) && !flyable(cellX + dx, cellY + dy, cellZ + dz)) {
                        count++;
                    }
                }
            }
        }
        blocked.put(key, (byte) count);
        return count;
    }

    /**
     * Whether the straight line between two points passes only through flyable cells. Used to decide whether smoothing
     * may take a shortcut.
     *
     * <p>The key point is <b>checking at grid resolution, not block resolution</b>. Checking a single ray per block
     * would count even a line hugging a wall as "not hitting", and the clearance the grid secured would vanish.
     */
    public boolean clearLine(Vec3 from, Vec3 to) {
        double scale = 1.0 / cellBlocks;
        return VoxelRay.traverse(from.scale(scale), to.scale(scale), this::flyable);
    }

    /**
     * Searches outward from the cell containing {@code around} for a flyable cell, up to {@code maxCellRadius}.
     * {@code null} if none is found.
     *
     * <p>Neither the start nor the destination usually sits on a grid cell: the player may be grazing a rock corner,
     * and the destination is more often than not the ground to land on (= unflyable).
     */
    public long nearestFlyable(Vec3 around, int maxCellRadius) {
        int centerX = toCell(around.x);
        int centerY = toCell(around.y);
        int centerZ = toCell(around.z);
        for (int radius = 0; radius <= maxCellRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Only look at the shell (the inside was covered by the previous radius)
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != radius) {
                            continue;
                        }
                        if (flyable(centerX + dx, centerY + dy, centerZ + dz)) {
                            return BlockPos.asLong(centerX + dx, centerY + dy, centerZ + dz);
                        }
                    }
                }
            }
        }
        return NONE;
    }

    /** Sentinel meaning {@link #nearestFlyable} found nothing. */
    public static final long NONE = Long.MIN_VALUE;
}
