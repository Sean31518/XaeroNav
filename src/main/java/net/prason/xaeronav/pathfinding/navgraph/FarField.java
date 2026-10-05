package net.prason.xaeronav.pathfinding.navgraph;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;

/**
 * Estimate of the remaining cost to the goal outside the window (the loaded range). Used only to seed the window boundary.
 *
 * <p><b>Return {@link Double#POSITIVE_INFINITY} for points that are unknown.</b> A bad value is worse than nothing
 * (measured: in the End, outside values on layer 1 give 1.197x, nothing at all gives 1.009x), and returning 0 pulls the search toward it.
 */
@FunctionalInterface
public interface FarField {

    FarField UNKNOWN = (x, y, z) -> Double.POSITIVE_INFINITY;

    /**
     * Geometric lower bound to the goal. The outside value when there is nothing to estimate from.
     *
     * <p>Putting {@link #UNKNOWN} on the window edge leaves no seeds at all while the goal is outside the window, and the guide is unusable
     * (measured: in the End, routes whose goal leaves the window went from 1.022 to 1.235x, falling back to the old leg search).
     */
    static FarField straightLineTo(BlockPos goal) {
        return straightLineTo(goal, 1.0);
    }

    /** {@code scale} times the geometric lower bound to the goal. */
    static FarField straightLineTo(BlockPos goal, double scale) {
        return new FarField() {
            @Override
            public double at(int x, int y, int z) {
                return scale * Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return true;
            }
        };
    }

    /**
     * Whether to stay unused (treated as {@link #UNKNOWN}) while the goal is inside the window.
     *
     * <p>The geometric lower bound knows nothing about terrain outside the window, so even with the goal inside the window it puts an
     * underestimate of "straight there from here" on edge points and pulls the search toward the edge.
     */
    default boolean onlyWhenGoalOutside() {
        return false;
    }

    /** The estimate placed on the edge while the goal is inside the window. */
    default FarField whenGoalInside() {
        return onlyWhenGoalOutside() ? UNKNOWN : this;
    }

    /** Places {@code outside} on the edge if the goal is outside the window, {@code inside} if it is inside. */
    static FarField byGoal(FarField outside, FarField inside) {
        return new FarField() {
            @Override
            public double at(int x, int y, int z) {
                return outside.at(x, y, z);
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return outside.onlyWhenGoalOutside();
            }

            @Override
            public FarField whenGoalInside() {
                return inside;
            }
        };
    }

    double at(int x, int y, int z);

    /**
     * Sets points farther from the goal than {@code (x, y, z)} (the player at the window center), by this estimate, to {@link Double#POSITIVE_INFINITY}.
     *
     * <p>Inside the window the cost is real and outside it is estimated, so in terrain where the estimate is cheaper than reality (crossing End voids),
     * "leave the window by the back edge and re-cross at the cheap estimated price" looks cheaper than crossing inside the window. In the real game, every time the player reached
     * the tip of an island the guidance turned back to the edge they came from (on layer 1 values: west edge 913+18265 vs. actually crossing east 4366+15874). An edge that moves away
     * on the estimate itself can't be a correct exit: if the estimated shortest path goes around, the edges beyond it keep decreasing in value and remain.
     */
    static FarField forwardOf(FarField far, int x, int y, int z) {
        double limit = far.at(x, y, z);
        if (!Double.isFinite(limit)) {
            return far;
        }
        return new FarField() {
            @Override
            public double at(int px, int py, int pz) {
                double value = far.at(px, py, pz);
                return value < limit ? value : Double.POSITIVE_INFINITY;
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return far.onlyWhenGoalOutside();
            }

            @Override
            public FarField whenGoalInside() {
                return forwardOf(far.whenGoalInside(), x, y, z);
            }
        };
    }

    /**
     * Wraps a guide built on the "0 if no information" convention ({@code CoarseRouter#costToGo} etc.).
     * 0 or less is treated as unknown; the goal itself is seeded separately inside the window, so losing 0 here does no harm.
     */
    static FarField of(CostToGo guide) {
        return (x, y, z) -> {
            double value = guide.estimate(x, y, z);
            return value > 0.0 ? value : Double.POSITIVE_INFINITY;
        };
    }
}
