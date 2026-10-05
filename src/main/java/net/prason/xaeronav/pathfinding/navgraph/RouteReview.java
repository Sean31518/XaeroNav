package net.prason.xaeronav.pathfinding.navgraph;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Reviews the drawn path using the guide of the rebuilt nav graph.
 *
 * <p>The path is drawn aiming outside the window by estimates (3D coarse layer, layer 1, straight-line distance), and afterwards only extended from the end without reviewing what's behind.
 * As you walk and the window advances, what was an estimate becomes exact and it may turn out "going north was shorter", but the line at that point keeps stretching west
 * (real Nether). The guide's value is the minimum cost from there to the destination, so its difference from the price along the line is exactly the amount of detour.
 *
 * <p>Reviews only when the destination is inside the window and the guide includes no outside estimate.
 */
public final class RouteReview {

    private RouteReview() {
    }

    /**
     * Amount of detour (ticks) relative to the shortest the guide knows, when following {@code steps[from..]}. 0 if there are no comparable points.
     *
     * <p>Looks only at points whose value comes directly from a graph node ({@link WindowField#exact}). Points not in the graph, such as on placed or dug blocks,
     * are estimates extended from nearby values, and using them as the reference invents detours that aren't on the line's side.
     *
     * @param start The origin to compare from (the player's feet)
     * @param from  Index of the step stepped on after the origin
     */
    public static Detour detour(WindowField field, BlockPos start, List<PathStep> steps, int from) {
        BlockPos goal = field.goal();
        if (!field.measuredInWindow(goal.getX(), goal.getZ())) {
            // If the destination is outside the window, values come from outside estimates placed at the window edge. Estimate errors vary by place, so taking the difference
            // calls non-detouring lines detours (measured: using the 3D coarse layer as the outside estimate in the Nether, the start's value exceeded the true overall shortest, and redrawing went 1.003 → 1.187x)
            return Detour.NONE;
        }
        double startValue = field.exact(start.getX(), start.getY(), start.getZ());
        if (!Double.isFinite(startValue)) {
            return Detour.NONE;
        }
        double walked = 0.0;
        Detour worst = Detour.NONE;
        for (int i = from; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            BlockPos pos = step.pos();
            if (!field.measuredInWindow(pos.getX(), pos.getZ())) {
                break;
            }
            walked += step.cost();
            double value = field.exact(pos.getX(), pos.getY(), pos.getZ());
            if (Double.isFinite(value) && walked + value - startValue > worst.extraTicks()) {
                worst = new Detour(walked + value - startValue, walked);
            }
        }
        return worst;
    }

    /**
     * Amount of detour.
     *
     * @param extraTicks  Extra cost over the shortest for advancing {@code walkedTicks} along the line and then taking the shortest
     * @param walkedTicks Price of the line up to the compared point
     */
    public record Detour(double extraTicks, double walkedTicks) {

        static final Detour NONE = new Detour(0.0, 0.0);

        /**
         * Whether it's worth redrawing. Redrawing on small differences makes the line redraw with every step due to minor
         * disagreements between the guide and the search (estimates of the underwater surcharge, etc.).
         */
        public boolean worthReplanning(double minExtraTicks) {
            return extraTicks > minExtraTicks;
        }
    }
}
