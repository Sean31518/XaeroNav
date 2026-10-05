package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Computes "where on the path are we now" only once per tick and shares it.
 *
 * <p>Whether to recalculate ({@link PathfindingState}), the guidance display ({@link NavGuidance}) and render
 * trimming ({@link PathRenderer}) all need the answer to the same question. Computing it separately lets the
 * three point at different steps even within the same frame (guidance showing the next corner while the line is drawn from further back, etc.).
 *
 * <p>The search looks only around the previous mapping. Picking the closest point on the whole path would jump
 * to a distant segment on terrain where the path passes near itself (switchback stairs in caves, etc.).
 */
final class PathProgress {

    static final PathProgress INSTANCE = new PathProgress();

    private static final int WINDOW_AHEAD = 32;
    private static final int WINDOW_BEHIND = 8;

    /** If there's no near point in the window, treat as off the path and search the whole thing again (blocks, <b>horizontal distance</b>). */
    private static final double FULL_SCAN_DISTANCE = 8.0;

    private PathResult source;
    private int index;
    private double distance = Double.MAX_VALUE;
    private double horizontalDistance = Double.MAX_VALUE;

    private PathProgress() {
    }

    void update(PathResult result, Vec3 position) {
        if (result == null || result.steps().isEmpty()) {
            source = null;
            index = 0;
            distance = Double.MAX_VALUE;
            horizontalDistance = Double.MAX_VALUE;
            return;
        }
        List<PathStep> steps = result.steps();
        if (result != source) {
            source = result;
            index = 0;
        }
        int from = Math.max(0, index - WINDOW_BEHIND);
        int to = Math.min(steps.size() - 1, index + WINDOW_AHEAD);
        int best = nearest(steps, position, from, to);
        if (horizontalDistanceSq(steps.get(best).pos(), position)
                > FULL_SCAN_DISTANCE * FULL_SCAN_DISTANCE) {
            best = nearest(steps, position, 0, steps.size() - 1);
        }
        index = best;
        distance = Math.sqrt(distanceSq(steps.get(best).pos(), position));
        horizontalDistance = Math.sqrt(horizontalDistanceSq(steps.get(best).pos(), position));
    }

    /**
     * Whether this holds a value measured against this {@code result}.
     *
     * <p>{@link #distance()} is only "the distance to the path most recently passed to {@link #update}".
     * Right after the path is swapped, or when {@link #update} isn't called (after arrival, etc.), a
     * <b>distance measured against a different path</b> remains. Using it to judge deviation would throw away
     * the path for reasons unrelated to the one currently shown.
     */
    boolean tracking(PathResult result) {
        return result != null && result == source;
    }

    /** The step mapped for {@code result}. The first step if it's a different path. */
    int indexFor(PathResult result) {
        return result == source ? index : 0;
    }

    /**
     * Carries the mapping over as-is to a path that only had a segment appended at the end. Appending doesn't change
     * earlier steps' indices, so the current position remains valid.
     *
     * <p>Passing a new {@link PathResult} without calling this makes {@link #update} treat it as a different path,
     * resetting the index to 0, and since it's outside the window it falls to a full scan. A full scan jumps to a
     * distant segment on terrain where the path passes near itself (cave switchback stairs); the longer lookahead makes the path, the likelier this gets.
     */
    void carryOver(PathResult extended) {
        if (source == null) {
            return;
        }
        source = extended;
    }

    /** Most recently measured distance to the path (blocks). {@link Double#MAX_VALUE} if there's no mapping. */
    double distance() {
        return distance;
    }

    /**
     * Distance to the path (blocks) ignoring vertical offset.
     *
     * <p>Used only where you can move freely up and down: in water. There the path's Y isn't an instruction,
     * and counting surfacing for air as "off the path" would make redraws never stop
     * ({@code PathfindingState#offPathDistance}).
     */
    double horizontalDistance() {
        return horizontalDistance;
    }

    private static int nearest(List<PathStep> steps, Vec3 position, int from, int to) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double distance = distanceSq(steps.get(i).pos(), position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** Steps are block coordinates, the player continuous coordinates. Compares block center with the player's feet. */
    private static double distanceSq(BlockPos step, Vec3 position) {
        double dx = step.getX() + 0.5 - position.x;
        double dy = step.getY() - position.y;
        double dz = step.getZ() + 0.5 - position.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Distance ignoring vertical offset. Used for deciding whether to fall to a full scan ({@link #FULL_SCAN_DISTANCE})
     * and for deciding deviation in water ({@link #horizontalDistance()}).
     *
     * <p>Looking at Y here would fall to a full scan every tick when swimming on the surface while the path runs
     * underwater (the height difference alone exceeds 8 blocks). A full scan jumps to distant segments on terrain where
     * the path passes near itself, so the guidance ahead disappears entirely. The right view is <b>if you're directly above, you're following the path</b>.
     *
     * <p>{@link #nearest} keeps looking at Y. For paths passing the same XZ at different heights, like switchback
     * stairs, Y is the only clue.
     */
    private static double horizontalDistanceSq(BlockPos step, Vec3 position) {
        double dx = step.getX() + 0.5 - position.x;
        double dz = step.getZ() + 0.5 - position.z;
        return dx * dx + dz * dz;
    }
}
