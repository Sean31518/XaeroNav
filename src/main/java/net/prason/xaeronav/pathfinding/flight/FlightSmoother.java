package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Straightens the staircase polyline returned by grid A* as far as it can pass (string pull).
 *
 * <p>Outputting a path that follows the grid as a line makes it bend every few blocks even where you could
 * actually fly in a straight line. A person flying follows the line's <b>direction</b>, so these bends turn
 * directly into "I can't tell where to point the nose".
 *
 * <p>A shortcut is taken only when both conditions hold:
 * <ol>
 * <li>{@link AirGrid#clearLine}: every <b>grid cell</b> crossed is flyable. It doesn't look at block resolution
 *     because a line hugging a wall would count as "not touching" and the clearance would vanish</li>
 * <li>It <b>costs no more</b> than the section it replaces. Gliding gets descent over horizontal distance for
 *     free, so a detour can in principle be cheaper (see {@link FlightCosts})</li>
 * </ol>
 *
 * <p><b>Decide acceptance with exactly the same cost function as A*</b> (including the narrowness surcharge). Putting it in only one
 * side makes smoothing pull a path A* detoured into open space back into tight space; this actually happened. See {@link Clearance}.
 *
 * <h2>This can be heavier than the search</h2>
 *
 * In the real Nether, smoothing after the search was cut off at its time limit (2 s) took <b>6.5 s</b>. Naive
 * string pull tries every (from, to), so O(n^2), and each check scans every cell on the line via
 * {@link Clearance#alongLine}, demanding 26-neighbour flyability per cell: it ends up evaluating large
 * regions the search never touched. Two things keep it in check:
 *
 * <ul>
 * <li>The original polyline's cost is computed <b>once as a prefix sum</b>. Sections aren't re-walked per replacement candidate</li>
 * <li>The shortcut search range is limited to {@link #LOOKAHEAD_POINTS} points. Long straights just get folded in
 *     several passes, which looks nearly the same</li>
 * </ul>
 *
 * <p>A deadline is passed too. Once past it, the rest is returned unfolded: a line with leftover bends only looks
 * a little worse, while being unable to show a line for seconds to someone in flight is worse.
 */
final class FlightSmoother {

    /** From one point, how many points ahead to consider as shortcut candidates. */
    private static final int LOOKAHEAD_POINTS = 64;

    private FlightSmoother() {
    }

    static List<Vec3> smooth(List<Vec3> points, AirGrid grid, boolean rockets,
                              double clearancePenaltyTicks, long deadline) {
        if (points.size() < 3) {
            return points;
        }
        // Prefix sum of the original polyline's cost. Re-walking sections per replacement candidate would make this alone O(n^2)
        double[] prefix = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            prefix[i] = prefix[i - 1]
                    + segmentTicks(grid, points.get(i - 1), points.get(i), rockets, clearancePenaltyTicks);
        }

        List<Vec3> result = new ArrayList<>();
        result.add(points.get(0));
        int from = 0;
        while (from < points.size() - 1) {
            int next = from + 1;
            if (MonotonicTime.millis() < deadline) {
                int limit = Math.min(points.size() - 1, from + LOOKAHEAD_POINTS);
                // Try from the far end. The first one found folds the most bends
                for (int to = limit; to > from + 1; to--) {
                    if (!grid.clearLine(points.get(from), points.get(to))) {
                        continue;
                    }
                    if (segmentTicks(grid, points.get(from), points.get(to), rockets, clearancePenaltyTicks)
                            > prefix[to] - prefix[from]) {
                        continue;
                    }
                    next = to;
                    break;
                }
            }
            result.add(points.get(next));
            from = next;
        }
        return result;
    }

    private static double segmentTicks(AirGrid grid, Vec3 from, Vec3 to, boolean rockets,
                                        double clearancePenaltyTicks) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return FlightCosts.segmentTicks(Math.sqrt(dx * dx + dz * dz), to.y - from.y, rockets)
                + Clearance.alongLine(grid, from, to, clearancePenaltyTicks);
    }
}
