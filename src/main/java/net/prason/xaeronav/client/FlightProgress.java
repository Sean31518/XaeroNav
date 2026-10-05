package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.util.MathSupport;

/**
 * Computes "where on the aerial route am I now" once per tick and shares it (the same role as walking's {@link PathProgress}).
 *
 * <p>The decisive difference from walking is that <b>it measures distance to segments, not points</b>. Walking steps are
 * one block apart, so the distance to the nearest point is the distance to the path, but an aerial route is a smoothed
 * polyline whose vertices are tens of blocks apart. Measured against points, even flying exactly along the line would
 * count as "20 blocks off", completely defeating the purpose of having a wide tolerance.
 *
 * <p>The deviation is kept separately for horizontal and vertical. Elytra wobble more vertically than horizontally, so
 * bounding both with the same width would keep triggering replans just for being a few blocks off in altitude.
 */
final class FlightProgress {

    static final FlightProgress INSTANCE = new FlightProgress();

    /** How many times the horizontal tolerance the vertical deviation may be. */
    static final double VERTICAL_TOLERANCE_FACTOR = 1.5;

    /** Width (in segments) around the previous segment that's looked at. So it doesn't jump far away in terrain where the route comes back near itself. */
    private static final int WINDOW_AHEAD = 4;
    private static final int WINDOW_BEHIND = 1;

    private FlightRoute source;
    private int segment;
    private double horizontal = Double.MAX_VALUE;
    private double vertical = Double.MAX_VALUE;

    private FlightProgress() {
    }

    void update(FlightRoute route, Vec3 position) {
        if (route == null || route.isEmpty()) {
            source = null;
            segment = 0;
            horizontal = Double.MAX_VALUE;
            vertical = Double.MAX_VALUE;
            return;
        }
        List<Vec3> points = route.points();
        if (route != source) {
            source = route;
            segment = 0;
        }
        int last = points.size() - 2;
        int from = Math.max(0, segment - WINDOW_BEHIND);
        int to = Math.min(last, segment + WINDOW_AHEAD);
        int best = nearest(points, position, from, to);
        // If everything in the window is far, we're flying somewhere else entirely. Search the whole route again
        if (offsetOf(points, best, position).lengthSqr() > FULL_SCAN_DISTANCE * FULL_SCAN_DISTANCE) {
            best = nearest(points, position, 0, last);
        }
        segment = best;
        Vec3 offset = offsetOf(points, best, position);
        horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        vertical = Math.abs(offset.y);
    }

    /** Threshold (blocks) beyond which, if no segment in the window is close, the whole route is searched again. */
    private static final double FULL_SCAN_DISTANCE = 48.0;

    /**
     * Carries the mapping over as-is to a route that only had segments appended at the end. Appending doesn't change
     * the indices of earlier points, so the segment currently pointed at remains valid.
     *
     * <p>Passing a new {@link FlightRoute} without calling this makes {@link #update} treat it as a different route and
     * reset the index to 0. Trimming the dotted line uses that index, so at the moment of extending, already-passed
     * segments would be redrawn (same reason as walking's {@code PathProgress.carryOver}).
     */
    void carryOver(FlightRoute extended) {
        if (source == null) {
            return;
        }
        source = extended;
    }

    /** The segment mapped for {@code route}. The first one if it's a different route. */
    /**
     * The point on the route closest to the player (the projection onto the current segment). Drawing the line from
     * here keeps it fixed to the route while starting right beside you; drawing from the player's position makes the
     * near end of the line stick to the body and move, so the route itself appears to wobble. {@code null} if there's no mapping yet.
     */
    Vec3 nearestOnRoute(FlightRoute route, Vec3 position) {
        if (route != source || route.points().size() < 2) {
            return null;
        }
        return position.subtract(offsetOf(route.points(), segment, position));
    }

    int segmentFor(FlightRoute route) {
        return route == source ? segment : 0;
    }

    /** The most recently measured horizontal deviation from the route (blocks). */
    double horizontalOffset() {
        return horizontal;
    }

    /** The most recently measured vertical deviation from the route (blocks). */
    double verticalOffset() {
        return vertical;
    }

    /**
     * Whether the player is outside the tolerance. Checked as an <b>ellipsoid</b>, not a sphere: comparing horizontal and
     * vertical against separate thresholds lets a state that's moderately off both horizontally and vertically slip past both checks.
     */
    boolean deviated(double horizontalThreshold) {
        if (horizontal == Double.MAX_VALUE) {
            return false;
        }
        double verticalThreshold = horizontalThreshold * VERTICAL_TOLERANCE_FACTOR;
        double h = horizontal / horizontalThreshold;
        double v = vertical / verticalThreshold;
        return h * h + v * v > 1.0;
    }

    private static int nearest(List<Vec3> points, Vec3 position, int from, int to) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double distance = offsetOf(points, i, position).lengthSqr();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** The player's position as seen from the nearest point on segment {@code index}. */
    private static Vec3 offsetOf(List<Vec3> points, int index, Vec3 position) {
        Vec3 from = points.get(index);
        Vec3 to = points.get(index + 1);
        Vec3 along = to.subtract(from);
        double lengthSq = along.lengthSqr();
        double t = lengthSq > 0.0 ? position.subtract(from).dot(along) / lengthSq : 0.0;
        return position.subtract(from.add(along.scale(MathSupport.clamp(t, 0.0, 1.0))));
    }
}
