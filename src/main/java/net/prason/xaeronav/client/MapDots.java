package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * The route baked into flat arrays for Xaero's world map and minimap.
 *
 * <p>The map side draws the route as a chain of 1x1-block rectangles. There's no need to walk the {@link PathStep} list
 * and redetermine colors every frame, so it's rebuilt only when the route changes and shared by both renderers.
 *
 * <p>The map is a projection onto the XZ plane, so consecutive steps differing only in Y (stairs, digging down) become the same rectangle.
 * Dropping consecutive duplicates doesn't change how it looks.
 */
final class MapDots {

    /** The world map and minimap both draw from the render thread, so sharing is fine. */
    private static final PathCache<MapDots> CACHE = new PathCache<>();

    final int[] x;
    final int[] z;
    /** RGB per point (point count x 3). */
    final float[] color;
    /** Index of each point's original {@link PathStep} (ascending). Needed to skip points already passed. */
    private final int[] stepIndex;
    final int count;

    private MapDots(int[] x, int[] z, float[] color, int[] stepIndex, int count) {
        this.x = x;
        this.z = z;
        this.color = color;
        this.stepIndex = stepIndex;
        this.count = count;
    }

    /**
     * The first point made from steps at or after {@code fromStep}.
     *
     * <p>Consecutive steps with the same XZ are collapsed into one point, so step indices and point indices don't match.
     * Points are in ascending order, so binary search works.
     */
    int firstDotFrom(int fromStep) {
        int low = 0;
        int high = count;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (stepIndex[mid] < fromStep) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    static MapDots forPath(PathResult result) {
        return CACHE.get(result, MapDots::build);
    }

    private static MapDots build(PathResult result) {
        List<PathStep> steps = result.steps();
        int[] x = new int[steps.size()];
        int[] z = new int[steps.size()];
        float[] color = new float[steps.size() * 3];
        int[] stepIndex = new int[steps.size()];
        int count = 0;

        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            int stepX = step.pos().getX();
            int stepZ = step.pos().getZ();
            if (count > 0 && x[count - 1] == stepX && z[count - 1] == stepZ) {
                continue;
            }
            float[] stepColor = PathColors.forStep(step);
            x[count] = stepX;
            z[count] = stepZ;
            color[count * 3] = stepColor[0];
            color[count * 3 + 1] = stepColor[1];
            color[count * 3 + 2] = stepColor[2];
            stepIndex[count] = i;
            count++;
        }

        return new MapDots(Arrays.copyOf(x, count), Arrays.copyOf(z, count),
                Arrays.copyOf(color, count * 3), Arrays.copyOf(stepIndex, count), count);
    }
}
