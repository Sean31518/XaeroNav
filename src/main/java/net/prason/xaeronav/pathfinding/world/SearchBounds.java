package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;
//? if >=1.17 {
import net.minecraft.world.level.LevelHeightAccessor;
//?} else {
/*import net.minecraft.world.level.Level;
*///?}
import net.prason.xaeronav.util.GameCompat;

/**
 * Limits on the search range. A bounding box containing the start and destination, plus a margin.
 */
public record SearchBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * Adds a horizontal margin to the bounding box containing the start and end, then clips it to a square of
     * {@code maxRadius} centered on the start.
     *
     * <p>It's clipped because trying to solve a distant goal in one go is pointless. Outside the loaded chunks
     * nothing can be read anyway, so widening the search range that far just scans cells treated as unloaded.
     * Clipping the range cuts the path off short of the goal, but as the player advances the next leg is recalculated
     * (handled the same as a provisional path).
     */
    public static SearchBounds around(
            //? if >=1.17 {
            LevelHeightAccessor level,
            //?} else {
            /*Level level,
            *///?}
            BlockPos start, BlockPos goal,
                                       int horizontalMargin, int verticalMargin, int maxRadius) {
        int minX = Math.max(start.getX() - maxRadius, Math.min(start.getX(), goal.getX()) - horizontalMargin);
        int maxX = Math.min(start.getX() + maxRadius, Math.max(start.getX(), goal.getX()) + horizontalMargin);
        int minZ = Math.max(start.getZ() - maxRadius, Math.min(start.getZ(), goal.getZ()) - horizontalMargin);
        int maxZ = Math.min(start.getZ() + maxRadius, Math.max(start.getZ(), goal.getZ()) + horizontalMargin);
        int minY = Math.max(GameCompat.minBuildHeight(level), Math.min(start.getY(), goal.getY()) - verticalMargin);
        int maxY = Math.min(GameCompat.maxBuildHeight(level) - 1, Math.max(start.getY(), goal.getY()) + verticalMargin);
        return new SearchBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
