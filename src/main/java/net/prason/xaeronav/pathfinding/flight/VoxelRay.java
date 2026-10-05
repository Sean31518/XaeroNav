package net.prason.xaeronav.pathfinding.flight;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Enumerates every cell a line segment passes through (Amanatides–Woo voxel traversal).
 *
 * <p>Sampling points at fixed intervals misses walls and ridges that fall entirely between samples.
 * An elytra flies at around 30 blocks per second, so a missed wall means a crash. Coarse checks turn
 * directly into accidents here, so this looks at every cell without skipping.
 *
 * <p>The cell size is fixed at 1. Callers that want to traverse a coarser grid should divide the coordinates
 * by the cell width before passing them in ({@link AirGrid#clearLine} does this). Bringing the coordinate
 * conversion into the traversal only mixes the cell width into the boundary-distance math, for no gain.
 */
public final class VoxelRay {

    @FunctionalInterface
    public interface CellTest {

        /** Whether the cell may be passed through. Traversal stops as soon as this returns false. */
        boolean passable(int x, int y, int z);
    }

    private VoxelRay() {
    }

    /** Whether every cell the segment passes through satisfies {@code test}. */
    public static boolean traverse(Vec3 from, Vec3 to, CellTest test) {
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);
        int lastX = Mth.floor(to.x);
        int lastY = Mth.floor(to.y);
        int lastZ = Mth.floor(to.z);

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        // Distance to the next cell boundary and the distance of one cell, with the segment length taken as 1
        double nextX = boundaryFraction(from.x, stepX, dx);
        double nextY = boundaryFraction(from.y, stepY, dy);
        double nextZ = boundaryFraction(from.z, stepZ, dz);
        double spanX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double spanY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double spanZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        while (true) {
            if (!test.passable(x, y, z)) {
                return false;
            }
            if (x == lastX && y == lastY && z == lastZ) {
                return true;
            }
            // Cross exactly one boundary, the nearest. Past 1 we are outside the segment
            if (nextX <= nextY && nextX <= nextZ) {
                if (nextX > 1.0) {
                    return true;
                }
                x += stepX;
                nextX += spanX;
            } else if (nextY <= nextZ) {
                if (nextY > 1.0) {
                    return true;
                }
                y += stepY;
                nextY += spanY;
            } else {
                if (nextZ > 1.0) {
                    return true;
                }
                z += stepZ;
                nextZ += spanZ;
            }
        }
    }

    /** Distance to the first cell boundary in the direction of travel (as a fraction of the segment length). */
    private static double boundaryFraction(double position, int step, double delta) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double offsetInCell = position - Math.floor(position);
        return (step > 0 ? 1.0 - offsetInCell : offsetInCell) / Math.abs(delta);
    }
}
