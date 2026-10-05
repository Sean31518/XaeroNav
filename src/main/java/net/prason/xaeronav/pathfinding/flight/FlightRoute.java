package net.prason.xaeronav.pathfinding.flight;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * An aerial route. A polyline that includes the start point.
 *
 * <p>Kept as a separate type from walking's {@code PathResult}. {@code PathStep} is built from concepts of
 * <b>movement with footing</b>, such as dig cells, body cells, swimming, climbing, and bridges, and an aerial route
 * has none of them. Forcing them to share would make {@code PathValidator} (which checks that the floor is still
 * there) and {@code PathGeometry} (which picks colors by danger and work) meaningless for flight.
 *
 * @param points      the polyline's vertices. The first is the player position <b>at the time of computation</b>,
 *                    so the renderer should drop it and redraw from the current position
 * @param termination why the search ended. The same distinction as walking (out of budget vs. "no path within
 *                    range") is needed as-is, so the enum is shared
 * @param expandedNodes number of cells expanded. For the diagnostic command
 * @param cellBlocks  edge length (blocks) of the grid that solved this route. <b>The value actually used, not the
 *                    configured one</b>: if escalation fell back to a finer grid, the margin around the line is
 *                    correspondingly narrower
 */
public record FlightRoute(List<Vec3> points, PathResult.Termination termination, int expandedNodes,
                           int cellBlocks) {

    public FlightRoute {
        points = List.copyOf(points);
    }

    public static final FlightRoute NONE =
            new FlightRoute(List.of(), PathResult.Termination.EXHAUSTED, 0, 0);

    public boolean isEmpty() {
        return points.size() < 2;
    }

    /** Whether it reached the target. If not, the dotted line takes over beyond the end. */
    public boolean complete() {
        return termination == PathResult.Termination.REACHED_GOAL;
    }

    /**
     * Whether it was cut off after using up search resources (nodes, time). The same check as walking's
     * {@code PathResult#budgetExhausted}; this is when re-solving on a finer grid is wasted effort.
     */
    public boolean budgetExhausted() {
        return termination == PathResult.Termination.NODE_BUDGET
                || termination == PathResult.Termination.TIME_LIMIT;
    }

    /**
     * A new route that attaches {@code extension}, which continues from this route's end.
     *
     * <p>The indices of the earlier points don't change, which is why {@code FlightProgress}'s mapping can be carried
     * over as-is. The first point of {@code extension} is the same as this route's end, so it's dropped.
     */
    public FlightRoute append(FlightRoute extension) {
        if (extension.points().size() < 2) {
            return this;
        }
        List<Vec3> combined = new java.util.ArrayList<>(points);
        combined.addAll(extension.points().subList(1, extension.points().size()));
        return new FlightRoute(List.copyOf(combined), extension.termination(),
                expandedNodes + extension.expandedNodes(), cellBlocks);
    }

    /** The end of the polyline. {@code null} if empty. */
    public Vec3 tail() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }
}
