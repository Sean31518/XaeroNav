package net.prason.xaeronav.pathfinding.coarse;

import java.util.Arrays;

/**
 * Fills a {@link CoarseMap} one cell (= one chunk) at a time.
 *
 * <p>Separated so it doesn't need to know where map data is read from (Xaero, live reading).
 * The reader only aggregates one chunk and calls {@link #putFloor};
 * this side only holds the array index math and the floor ordering and limit.
 */
public final class CoarseMapBuilder {

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final byte[] floorCount;
    private final byte[] kind;
    private final short[] height;
    private final short[] minHeight;
    private final short[] maxHeight;
    private int knownCells;

    public CoarseMapBuilder(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        int cells = chunksX * chunksZ;
        this.floorCount = new byte[cells];
        int floorSlots = cells * CoarseMap.MAX_FLOORS;
        this.kind = new byte[floorSlots];
        this.height = new short[floorSlots];
        this.minHeight = new short[floorSlots];
        this.maxHeight = new short[floorSlots];
        Arrays.fill(this.height, CoarseMap.UNKNOWN_HEIGHT);
        Arrays.fill(this.minHeight, CoarseMap.UNKNOWN_HEIGHT);
        Arrays.fill(this.maxHeight, CoarseMap.UNKNOWN_HEIGHT);
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

    /** When only a representative height is known and the internal relief isn't. Treated as flat (min=max=height). */
    public void putFloor(int chunkX, int chunkZ, byte cellKind, int cellHeight) {
        putFloor(chunkX, chunkZ, cellKind, cellHeight, cellHeight, cellHeight);
    }

    /**
     * Adds one floor to this cell. Out-of-range coordinates are silently dropped (the reader runs per region, so it
     * always overflows at the edges of the range).
     *
     * <p>Floors are inserted keeping ascending height order. Writing the same height band (one cave layer of
     * {@link net.prason.xaeronav.xaero.XaeroMapReader}) twice overwrites; this keeps floors from multiplying when the
     * same layer is passed again on a re-read with the same reference Y. Beyond {@link CoarseMap#MAX_FLOORS}, the
     * farthest floor added last is dropped (callers are expected to pass in order of closeness to the reference Y, so
     * what's dropped is always the farthest floor).
     */
    public void putFloor(int chunkX, int chunkZ, byte cellKind, int cellHeight, int cellMinHeight,
                          int cellMaxHeight) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return;
        }
        int cellIndex = localZ * chunksX + localX;
        int base = cellIndex * CoarseMap.MAX_FLOORS;
        int count = floorCount[cellIndex];

        int insertAt = 0;
        while (insertAt < count && height[base + insertAt] < cellHeight) {
            insertAt++;
        }
        // If a floor of the same height already exists, overwrite it (replace rather than add)
        if (insertAt < count && height[base + insertAt] == cellHeight) {
            kind[base + insertAt] = cellKind;
            minHeight[base + insertAt] = (short) cellMinHeight;
            maxHeight[base + insertAt] = (short) cellMaxHeight;
            return;
        }

        if (count == 0) {
            knownCells++;
        }
        int newCount = Math.min(count + 1, CoarseMap.MAX_FLOORS);
        // Over the limit, drop "the highest floor" (if the insertion point is the end, the new floor itself is it).
        // There's no height reference here, so nothing more can be decided; if which height bands to keep matters,
        // the caller should narrow to MAX_FLOORS before passing (LiveCoarseSampler does)
        if (count == CoarseMap.MAX_FLOORS && insertAt == count) {
            return;
        }
        for (int i = Math.min(count, CoarseMap.MAX_FLOORS - 1); i > insertAt; i--) {
            kind[base + i] = kind[base + i - 1];
            height[base + i] = height[base + i - 1];
            minHeight[base + i] = minHeight[base + i - 1];
            maxHeight[base + i] = maxHeight[base + i - 1];
        }
        kind[base + insertAt] = cellKind;
        height[base + insertAt] = (short) cellHeight;
        minHeight[base + insertAt] = (short) cellMinHeight;
        maxHeight[base + insertAt] = (short) cellMaxHeight;
        floorCount[cellIndex] = (byte) newCount;
    }

    /**
     * Number of cells with at least one floor stacked so far. Needed by the reader to measure <b>each layer's
     * share</b> ({@code XaeroMapReader#readSurface}).
     */
    public int knownCells() {
        return knownCells;
    }

    /**
     * Number of floors stacked in this cell. Needed by the reader to determine "cells that got no floor from any
     * layer" after reading all layers.
     */
    public int floorCount(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return 0;
        }
        return floorCount[localZ * chunksX + localX];
    }

    /** When only a representative height is known and the internal relief isn't. Treated as flat (min=max=height). */
    public void replaceCell(int chunkX, int chunkZ, byte cellKind, int cellHeight) {
        replaceCell(chunkX, chunkZ, cellKind, cellHeight, cellHeight, cellHeight);
    }

    /**
     * Clears all floors of this cell and replaces them with a single floor. {@link #putFloor} is an operation that
     * accumulates "this height band has this data", so new data at a different height from existing floors gets
     * added as a separate level (even if meant as an update of the same physical cell). Use this when the caller is
     * sure "this is the whole truth for this cell" (diagnostics, rebuilding terrain in tests, etc.).
     */
    public void replaceCell(int chunkX, int chunkZ, byte cellKind, int cellHeight, int cellMinHeight,
                             int cellMaxHeight) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return;
        }
        int cellIndex = localZ * chunksX + localX;
        if (floorCount[cellIndex] == 0) {
            knownCells++;
        }
        floorCount[cellIndex] = 1;
        int base = cellIndex * CoarseMap.MAX_FLOORS;
        kind[base] = cellKind;
        height[base] = (short) cellHeight;
        minHeight[base] = (short) cellMinHeight;
        maxHeight[base] = (short) cellMaxHeight;
    }

    public CoarseMap build() {
        int cells = chunksX * chunksZ;
        int[] islandId = new int[cells];
        Arrays.fill(islandId, CoarseMap.NO_ISLAND);
        int[] sizes = new int[cells];
        int nextId = 0;
        int[] stack = new int[cells];
        for (int seed = 0; seed < cells; seed++) {
            if (islandId[seed] != CoarseMap.NO_ISLAND || !isLand(seed)) {
                continue;
            }
            int id = nextId++;
            int top = 0;
            stack[top++] = seed;
            islandId[seed] = id;
            int size = 0;
            while (top > 0) {
                int cell = stack[--top];
                size++;
                int cx = cell % chunksX;
                int cz = cell / chunksX;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = cx + dx;
                        int nz = cz + dz;
                        if ((dx == 0 && dz == 0) || nx < 0 || nx >= chunksX || nz < 0 || nz >= chunksZ) {
                            continue;
                        }
                        int neighbor = nz * chunksX + nx;
                        if (islandId[neighbor] != CoarseMap.NO_ISLAND || !isLand(neighbor)) {
                            continue;
                        }
                        islandId[neighbor] = id;
                        stack[top++] = neighbor;
                    }
                }
            }
            sizes[id] = size;
        }
        return new CoarseMap(minChunkX, minChunkZ, chunksX, chunksZ, floorCount, kind, height, minHeight, maxHeight,
                knownCells, islandId, Arrays.copyOf(sizes, nextId));
    }

    /**
     * Whether this cell is considered part of a landmass. The representative is <b>the first floor</b>, the same rule
     * as {@code CoarseMap#kindBreakdown}; a simplification to avoid recounting islands per level in dimensions with a ceiling.
     */
    private boolean isLand(int cellIndex) {
        return floorCount[cellIndex] > 0 && kind[cellIndex * CoarseMap.MAX_FLOORS] == CoarseMap.LAND;
    }
}
