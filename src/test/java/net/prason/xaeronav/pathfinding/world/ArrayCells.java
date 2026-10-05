package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * A {@link CellSource} copying part of the terrain into an array. {@link FakeCells} looks cells up in a hash table, so reads are
 * much heavier than the real game's {@code ChunkView} (per-chunk arrays), and that dominates most of a measurement.
 * When a measurement should reflect the real game's proportions, wrap just the window range with this. Outside the range it passes through to the original terrain.
 */
public final class ArrayCells implements CellSource {

    private final CellSource all;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long[] cells;

    /** Copies the square of horizontal {@code radius} around {@code player}, over the full height range of {@code all}. */
    public ArrayCells(CellSource all, BlockPos player, int radius) {
        this.all = all;
        SearchBounds bounds = all.bounds();
        minX = player.getX() - radius;
        minZ = player.getZ() - radius;
        minY = bounds.minY();
        sizeX = 2 * radius + 1;
        sizeZ = 2 * radius + 1;
        sizeY = bounds.maxY() - bounds.minY() + 1;
        cells = new long[sizeX * sizeY * sizeZ];
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    cells[(y * sizeX + x) * sizeZ + z] = all.cell(minX + x, minY + y, minZ + z);
                }
            }
        }
    }

    @Override
    public long cell(int x, int y, int z) {
        int ix = x - minX;
        int iy = y - minY;
        int iz = z - minZ;
        if (ix < 0 || ix >= sizeX || iy < 0 || iy >= sizeY || iz < 0 || iz >= sizeZ) {
            return all.cell(x, y, z);
        }
        return cells[(iy * sizeX + ix) * sizeZ + iz];
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return all.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return all.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return all.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return all.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return all.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return all.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return all.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return all.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return all.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return all.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return all.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return all.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return all.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return all.avoidRiskyJumps();
    }

    @Override
    public boolean strictLimits() {
        return all.strictLimits();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return all.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return all.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return all.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return all.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return all.ridingBoat();
    }

    @Override
    public int openSkyY(int x, int z) {
        return all.openSkyY(x, z);
    }
}
