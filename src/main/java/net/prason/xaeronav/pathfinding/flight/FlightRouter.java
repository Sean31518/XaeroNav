package net.prason.xaeronav.pathfinding.flight;

import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Entry point for finding a flight route. Only holds the sequencing of a few attempts at decreasing granularity.
 *
 * <p>The same shape of escalation as {@code CoarseRouter.BridgePolicy}. Failing at the default granularity is
 * usually the case of "only gaps that can't be passed at this coarseness", so it is solved once more at half the
 * granularity. Finer means less clearance per cell, but a narrow route beats one that can't be flown; this matches
 * the existing priority that guidance disappearing is the worst outcome.
 */
public final class FlightRouter {

    /**
     * Radius of the goal region as a multiple of the cell width. The destination is usually the landing ground
     * itself = not flyable, so the check is "did it get close enough to descend on its own from there".
     */
    static final double GOAL_RADIUS_CELLS = 1.5;

    /**
     * Horizontal radii (blocks) treated as the destination when it is inside a closed small room. The first is the
     * distance at which walking takes over from the sky ({@code PathfindingState.LANDING_APPROACH_ENTER_BLOCKS}), the
     * second is the distance walking can solve in one search (the default of {@code detailHorizonBlocks}).
     */
    private static final double[] ENCLOSED_APPROACH_RADII = {48.0, 96.0};

    /** Lower limit (blocks) for reducing granularity. Finer than this, the grid's meaning (clearance) is lost. */
    private static final int MIN_CELL_BLOCKS = 2;

    /**
     * Time (milliseconds) that should remain in order to commit to a second attempt.
     *
     * <p>Resetting the deadline per stage inflates the total time of one call by the number of stages. This is
     * guidance for someone flying, so it's right to <b>fit the whole thing in one attempt's time</b>: a rough
     * line now is more useful than a perfect line that comes late.
     */
    private static final long MIN_RETRY_BUDGET_MILLIS = 500L;

    private FlightRouter() {
    }

    /**
     * Flight route from {@code start} to {@code goal}. Returns {@link FlightRoute#NONE} if none can be drawn
     * (the caller should fall back to the dotted line to the destination as before).
     */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning, BooleanSupplier cancelled) {
        return route(view, start, goal, rockets, tuning, FlightHorizon.NONE, FlightGuide.NONE, cancelled);
    }

    /**
     * Version that picks the exit using the remaining-cost field of the coarse map. Exit estimates are re-measured
     * with detours that don't pass inside the readable area (see {@link HorizonGuide}). If {@code field} is {@code null}, picks by straight-line estimate only.
     */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning, FlightHorizon horizon, @Nullable CoarseFlightField field,
                                     BooleanSupplier cancelled) {
        if (field == null) {
            return route(view, start, goal, rockets, tuning, horizon, FlightGuide.NONE, cancelled);
        }
        // Cells judged by the flood fill are touched again in nearly the same places by subsequent searches, so pass the same grid and reuse the memo
        AirGrid grid = new AirGrid(view, tuning.cellBlocks());
        HorizonGuide.Plan plan = HorizonGuide.plan(grid, start, goal, horizon, field, rockets);
        if (plan.enclosed()) {
            return approach(grid, start, goal, rockets, tuning, plan.guide(), cancelled);
        }
        return route(view, grid, start, goal, rockets, tuning, plan.horizon(), plan.guide(), cancelled);
    }

    /**
     * Route to where the destination can be approached from the sky, when it is inside a closed small room
     * ({@link HorizonGuide}). It doesn't aim at the destination itself; going from the nearer of {@link #ENCLOSED_APPROACH_RADII}, airborne cells within that radius are treated as the destination.
     *
     * <p>Aiming at the destination and cutting off at the closest approach chooses within the area filled on a
     * limited budget, so it stops in front of the near wall even when going around to the far side of the room
     * would get closer. With the radius as the destination, A* pulls all the way there if any reachable spot exists.
     */
    private static FlightRoute approach(AirGrid grid, Vec3 start, Vec3 goal, boolean rockets, FlightTuning tuning,
                                        FlightGuide guide, BooleanSupplier cancelled) {
        SearchLimits limits = new SearchLimits(
                Math.min(tuning.limits().maxExpandedNodes(), HorizonGuide.ENCLOSED_MAX_EXPANDED_NODES),
                tuning.limits().timeLimitMillis(), tuning.limits().heuristicWeight());
        FlightRoute best = FlightRoute.NONE;
        for (double radius : ENCLOSED_APPROACH_RADII) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            FlightRoute route = new FlightPathfinder(grid, rockets, limits, tuning.clearancePenaltyTicks())
                    .search(start, goal, radius, FlightHorizon.NONE, guide, cancelled);
            if (route.complete()) {
                return route;
            }
            if (best.isEmpty()) {
                best = route;
            }
        }
        return best;
    }

    /** Version that may cut off once outside {@code horizon} (see {@link FlightHorizon}). */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning, FlightHorizon horizon, FlightGuide guide,
                                     BooleanSupplier cancelled) {
        return route(view, null, start, goal, rockets, tuning, horizon, guide, cancelled);
    }

    /** {@code firstGrid} is the grid used at the first granularity (created if {@code null}). */
    private static FlightRoute route(CellSource view, @Nullable AirGrid firstGrid, Vec3 start, Vec3 goal,
                                     boolean rockets, FlightTuning tuning, FlightHorizon horizon, FlightGuide guide,
                                     BooleanSupplier cancelled) {
        FlightRoute best = FlightRoute.NONE;
        long deadline = MonotonicTime.millis() + tuning.limits().timeLimitMillis();
        for (int cells = tuning.cellBlocks(); cells >= MIN_CELL_BLOCKS; cells /= 2) {
            if (cancelled.getAsBoolean()) {
                return best;
            }
            long remaining = deadline - MonotonicTime.millis();
            if (best != FlightRoute.NONE && remaining < MIN_RETRY_BUDGET_MILLIS) {
                // Something has already been produced and there's no time left. Return the current line rather than persisting here
                break;
            }
            SearchLimits limits = new SearchLimits(tuning.limits().maxExpandedNodes(),
                    Math.max(MIN_RETRY_BUDGET_MILLIS, remaining), tuning.limits().heuristicWeight());
            AirGrid grid = firstGrid != null && firstGrid.cellBlocks() == cells ? firstGrid : new AirGrid(view, cells);
            FlightRoute route = new FlightPathfinder(grid, rockets, limits,
                    tuning.clearancePenaltyTicks()).search(start, goal, cells * GOAL_RADIUS_CELLS, horizon,
                    guide, cancelled);
            if (route.complete()) {
                return route;
            }
            if (best.isEmpty() && !route.isEmpty()) {
                // Partial routes that didn't reach can still be used for guidance. Keep the one from the coarser side (= wider clearance)
                best = route;
            }
            if (route.budgetExhausted()) {
                // If the budget was burned through, re-solving on a finer grid only hits <b>the same limit, sooner</b>:
                // the same volume has 8x as many cells, so the reachable distance actually shrinks. Going finer only
                // makes sense when it has been proven (EXHAUSTED) that "there's no passable gap at this coarseness".
                // In-game log: in the Nether, a 4-block grid burned 100k nodes in 2.1 s, and then a 2-block grid
                // burned the same again, so one recompute took 4 seconds
                break;
            }
        }
        return best;
    }
}
