package net.prason.xaeronav.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;

/**
 * Estimated travel time (ticks) for the stretch whose path isn't known yet: from the end of the solid line, or from the current position to the destination if there's no path yet.
 *
 * <p>Uses the nav graph guide's value if there is one. That is the very value the search uses to choose its direction: inside the window it's the cost of a road that can actually be walked,
 * outside the window it's an estimate. The outside estimate comes out low, so it's multiplied by the scale learned on this trip ({@link FarScaleCalibration}).
 * While there's no guide yet (the first few seconds after setting a destination, or with the guide turned off), the length of the map's dotted line at sprint speed is used.
 *
 * <p>Descending the guide ({@link WindowField#descend}) walks hundreds of nodes, and the HUD is drawn every frame,
 * so the previous value is returned as long as the inputs haven't changed.
 */
final class GoalEta {

    private @Nullable BlockPos cachedFrom;
    private @Nullable BlockPos cachedGoal;
    private @Nullable WindowField cachedField;
    private double cachedScale;
    private @Nullable List<BlockPos> cachedWaypoints;
    private double cachedTicks;

    /**
     * @param waypoints intermediate targets of the long-distance route not yet passed ({@link PathfindingState.NavigationView#coarseRouteWaypoints})
     */
    double ticks(BlockPos from, BlockPos goal, List<BlockPos> waypoints) {
        // A whole route planned on real blocks beats every estimate: it's the same search the live route uses
        RoutePreview full = FullRoutePlanner.INSTANCE.preview();
        if (full.complete() && full.points().get(full.points().size() - 1).distSqr(goal) <= 9.0) {
            return full.ticksFrom(from.getX(), from.getZ());
        }
        WindowField field = PathfindingState.INSTANCE.guideForDisplay(goal);
        double scale = PathfindingState.INSTANCE.guideFarScaleForDisplay();
        if (from.equals(cachedFrom) && goal.equals(cachedGoal) && field == cachedField && scale == cachedScale
                && (field != null || waypoints == cachedWaypoints)) {
            return cachedTicks;
        }
        double guided = field == null ? Double.NaN : fromGuide(field, scale, from);
        cachedTicks = Double.isFinite(guided) ? guided
                : alongDots(from, goal, waypoints) * ActionCosts.SPRINT_ONE_BLOCK;
        cachedFrom = from;
        cachedGoal = goal;
        cachedField = field;
        cachedScale = scale;
        cachedWaypoints = waypoints;
        return cachedTicks;
    }

    private static double fromGuide(WindowField field, double scale, BlockPos from) {
        WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ());
        if (descent != null) {
            return descent.inside() + scale * descent.outside();
        }
        // A point that isn't a node (e.g. the path ends on collapsed footing). Gets a nearby node's value or the outside estimate
        double value = field.estimate(from.getX(), from.getY(), from.getZ());
        return field.measuredInWindow(from.getX(), from.getZ()) ? value : scale * value;
    }

    /**
     * Length (horizontal, in blocks) of the same polyline as the dotted line drawn on the map ({@link MapPathOverlay}). Intermediate
     * targets already passed are skipped by the same rule as the dotted line.
     */
    static double alongDots(BlockPos from, BlockPos goal, List<BlockPos> waypoints) {
        double length = 0.0;
        int x = from.getX();
        int z = from.getZ();
        if (!waypoints.isEmpty()) {
            for (int i = MapPathOverlay.firstAheadWaypoint(waypoints, x, z); i < waypoints.size(); i++) {
                BlockPos next = waypoints.get(i);
                length += Math.hypot(next.getX() - x, next.getZ() - z);
                x = next.getX();
                z = next.getZ();
            }
        }
        return length + Math.hypot(goal.getX() - x, goal.getZ() - z);
    }
}
