package net.prason.xaeronav.pathfinding.corridor;

import java.util.Arrays;

/**
 * Fills a {@link SurfaceGrid} one block column at a time. The block-resolution version of {@code CoarseMapBuilder}.
 */
public final class SurfaceGridBuilder {

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final byte[] kind;
    private final short[] groundHeight;
    private final short[] surfaceHeight;

    public SurfaceGridBuilder(int minX, int minZ, int sizeX, int sizeZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        int cells = sizeX * sizeZ;
        this.kind = new byte[cells];
        this.groundHeight = new short[cells];
        this.surfaceHeight = new short[cells];
        Arrays.fill(groundHeight, SurfaceGrid.UNKNOWN_HEIGHT);
        Arrays.fill(surfaceHeight, SurfaceGrid.UNKNOWN_HEIGHT);
    }

    /** When the water surface is at the same height as the ground (land, lava). */
    public void put(int x, int z, byte cellKind, int groundHeightValue) {
        put(x, z, cellKind, groundHeightValue, groundHeightValue);
    }

    /** Silently drops out-of-range coordinates. The reader runs per tile, so it always spills over the edge of the range. */
    public void put(int x, int z, byte cellKind, int groundHeightValue, int surfaceHeightValue) {
        int localX = x - minX;
        int localZ = z - minZ;
        if (localX < 0 || localX >= sizeX || localZ < 0 || localZ >= sizeZ) {
            return;
        }
        int index = localZ * sizeX + localX;
        kind[index] = cellKind;
        groundHeight[index] = (short) groundHeightValue;
        surfaceHeight[index] = (short) surfaceHeightValue;
    }

    public SurfaceGrid build() {
        return new SurfaceGrid(minX, minZ, sizeX, sizeZ, kind, groundHeight, surfaceHeight);
    }
}
