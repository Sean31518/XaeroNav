package net.prason.xaeronav.pathfinding.navgraph;

/**
 * The range of readable columns. Used to count how much of the surroundings was readable when a section was built,
 * and to rebuild it later once more becomes readable ({@link NavGraph#missingSections}).
 *
 * <p>Besides gaps at the edge of the window (a square around the center), in practice there are gaps from
 * <b>chunks inside the window that have not arrived yet</b>. Without counting the latter, sections built
 * mid-load stay behind with holes in them.
 */
@FunctionalInterface
public interface LoadedArea {

    /** Number of readable columns within {@code [minX, maxX]×[minZ, maxZ]}. */
    int columns(int minX, int maxX, int minZ, int maxZ);

    /** Whether the chunk is loaded. */
    @FunctionalInterface
    interface ChunkLoaded {
        boolean test(int chunkX, int chunkZ);
    }

    /** The whole square of horizontal {@code radius} around the center is readable. */
    static LoadedArea square(int centerX, int centerZ, int radius) {
        return (minX, maxX, minZ, maxZ) -> {
            int width = Math.min(maxX, centerX + radius) - Math.max(minX, centerX - radius) + 1;
            int depth = Math.min(maxZ, centerZ + radius) - Math.max(minZ, centerZ - radius) + 1;
            return Math.max(0, width) * Math.max(0, depth);
        };
    }

    /** Columns of loaded chunks within the square of horizontal {@code radius} around the center. */
    static LoadedArea chunks(int centerX, int centerZ, int radius, ChunkLoaded loaded) {
        return (minX, maxX, minZ, maxZ) -> {
            int x0 = Math.max(minX, centerX - radius);
            int x1 = Math.min(maxX, centerX + radius);
            int z0 = Math.max(minZ, centerZ - radius);
            int z1 = Math.min(maxZ, centerZ + radius);
            int total = 0;
            for (int chunkX = x0 >> 4; chunkX <= x1 >> 4; chunkX++) {
                int width = Math.min(x1, chunkX * 16 + 15) - Math.max(x0, chunkX * 16) + 1;
                for (int chunkZ = z0 >> 4; chunkZ <= z1 >> 4; chunkZ++) {
                    if (width > 0 && loaded.test(chunkX, chunkZ)) {
                        total += width * Math.max(0, Math.min(z1, chunkZ * 16 + 15) - Math.max(z0, chunkZ * 16) + 1);
                    }
                }
            }
            return total;
        };
    }
}
