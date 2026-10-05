package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * <b>A world where only the area around the player is loaded.</b> Outside the window it returns unloaded ({@link CellData#ABSENT}).
 *
 * <p>Needed to reproduce offline how the real game assembles a path: chunks load as you walk, and the path
 * extends only as far as is visible. Passing {@link FakeCells} directly makes the whole world visible from the
 * start, so the most common breakage, <b>extension seams</b>, can't be reproduced.
 *
 * <p>The window is square, matching how vanilla's render distance loads chunks in a square.
 */
public record WindowedCells(CellSource all, BlockPos player, int radius, SearchBounds box)
        implements CellSource {

    /** Version that doesn't cut a box (the whole world is the search range). */
    public WindowedCells(CellSource all, BlockPos player, int radius) {
        this(all, player, radius, all.bounds());
    }

    /**
     * Returns <b>outside the search box</b> as unloaded too, in addition to outside the window. The real game's
     * {@code ChunkView.capture} only grabs chunks inside {@code SearchBounds}, so measuring without passing the box
     * makes a search aiming at a distant destination see a wider world than the real game does.
     */
    @Override
    public long cell(int x, int y, int z) {
        if (Math.abs(x - player.getX()) > radius || Math.abs(z - player.getZ()) > radius) {
            return CellData.ABSENT;
        }
        if (x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ()
                || y < box.minY() || y > box.maxY()) {
            return CellData.ABSENT;
        }
        return all.cell(x, y, z);
    }

    /**
     * Answers "in range" even outside the window. {@code AStarPathfinder} uses the combination of these two to
     * distinguish "unloaded" from "known to be nothing but air up to here", and answering out of range would make
     * unloaded chunks look like <b>a bottomless void</b>.
     */
    @Override
    public boolean isInBounds(int x, int y, int z) {
        return all.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return box;
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
