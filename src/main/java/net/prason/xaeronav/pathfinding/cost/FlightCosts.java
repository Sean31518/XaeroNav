package net.prason.xaeronav.pathfinding.cost;

import net.prason.xaeronav.pathfinding.cost.ElytraPhysics.Velocity;

/**
 * Cost model for aerial routes (unit: ticks, consistent with the other costs).
 *
 * <p>Every constant is derived by {@link ElytraPhysics} running vanilla's recurrence. The only numbers written here
 * directly are <b>the sweep range and step</b>; not a single speed, sink rate, glide ratio or climb rate is copied in.
 *
 * <h2>Key point: level flight is already "climbing"</h2>
 *
 * The elytra loses altitude in steady state at any pitch (see the comment on {@link ElytraPhysics#zoomClimb}).
 * In other words, holding altitude itself takes active input, i.e. rockets. Without this asymmetry in the cost,
 * "flying straight and level" looks cheapest, and gentle downward slopes reachable by gliding alone stop being chosen.
 *
 * <p>So a segment's cost first subtracts the descent that natural gliding earns (horizontal distance ÷ glide ratio),
 * then charges only the remainder as "altitude to be gained actively":
 *
 * <pre>
 *   requiredClimb = dv + dh / GLIDE_RATIO
 *   cost = dh * HORIZONTAL + max(0, -dv) * DESCENT + max(0, requiredClimb) * ASCENT
 * </pre>
 *
 * <p>As a side effect, descents steeper than the glide ratio also stop paying off ({@code -dv} is charged, and requiredClimb
 * bottoms out at 0 so there's no gain). The real trade-off, that spent altitude can only be regained by climbing, shows up as-is.
 *
 * <h2>The estimate is measured with the same formula</h2>
 *
 * The cost of the straight segment itself never exceeds the cost of any polyline (see {@link #lowerBoundTicks}).
 * Dropping the level-flight climb share (about 30% of the horizontal cost without rockets) from the estimate would move the
 * estimate that much further from the real cost and make A* spread sideways.
 */
public final class FlightCosts {

    /** Sweep step (degrees). 0.5 degrees captures the polar's peak well enough (it's flat near the peak). */
    private static final double PITCH_STEP_DEGREES = 0.5;

    /** The attitude that glides the farthest. The horizontal cost and glide ratio come from this. */
    private static final Velocity BEST_GLIDE =
            ElytraPhysics.bestSteadyState(-30.0, 80.0, PITCH_STEP_DEGREES, false, Velocity::glideRatio);

    /** The attitude that climbs fastest while continuously firing rockets. */
    private static final Velocity BEST_ROCKET_CLIMB =
            ElytraPhysics.bestSteadyState(-90.0, 0.0, PITCH_STEP_DEGREES, true, Velocity::vertical);

    /** Climbing without rockets can only trade the speed built up while cruising for altitude, once. */
    private static final ElytraPhysics.ZoomClimb BEST_ZOOM_CLIMB =
            ElytraPhysics.bestZoomClimb(BEST_GLIDE, -5.0, -90.0, PITCH_STEP_DEGREES);

    /** Ticks to travel one block horizontally. From the cruise speed at the best glide attitude. */
    public static final double HORIZONTAL_TICKS_PER_BLOCK = 1.0 / BEST_GLIDE.horizontal();

    /** How many blocks of horizontal travel per block of sinking. The slope of descent available for free. */
    public static final double GLIDE_RATIO = BEST_GLIDE.glideRatio();

    /**
     * Ticks to descend one block. From the terminal velocity when pointing straight down.
     *
     * <p><b>Must not be 0</b>. If the price of giving back climbed altitude looks free, weighted A* systematically picks
     * the climbing branches and finalizes them as {@code closed} (the same shape as the oscillation actually hit on the walking side).
     */
    public static final double DESCENT_TICKS_PER_BLOCK =
            1.0 / -ElytraPhysics.steadyState(90.0, false).vertical();

    /** Ticks to climb one block with rockets. */
    public static final double ROCKET_ASCENT_TICKS_PER_BLOCK = 1.0 / BEST_ROCKET_CLIMB.vertical();

    /** Ticks to climb one block without rockets. It's a trade of speed for altitude, so orders of magnitude more expensive. */
    public static final double GLIDING_ASCENT_TICKS_PER_BLOCK = BEST_ZOOM_CLIMB.ticksPerBlock();

    private FlightCosts() {
    }

    /** Ticks to climb one block. Switches on whether rockets are carried (the same shape as water costs changing with having a boat). */
    public static double ascentTicksPerBlock(boolean rockets) {
        return rockets ? ROCKET_ASCENT_TICKS_PER_BLOCK : GLIDING_ASCENT_TICKS_PER_BLOCK;
    }

    /**
     * Ticks to fly a segment. {@code verticalBlocks} is positive upward.
     * The descent covered by natural gliding is subtracted before the climb is charged (see the class comment).
     */
    public static double segmentTicks(double horizontalBlocks, double verticalBlocks, boolean rockets) {
        double requiredClimb = verticalBlocks + horizontalBlocks / GLIDE_RATIO;
        return horizontalBlocks * HORIZONTAL_TICKS_PER_BLOCK
                + Math.max(0.0, -verticalBlocks) * DESCENT_TICKS_PER_BLOCK
                + Math.max(0.0, requiredClimb) * ascentTicksPerBlock(rockets);
    }

    /**
     * Lower bound on the cost of a route reaching anywhere at horizontal {@code horizontalBlocks} and vertical
     * {@code verticalLow} to {@code verticalHigh} (positive upward). <b>No route, however bent, goes below this</b>.
     *
     * <p>All three terms of {@link #segmentTicks} are subadditive over segments (the horizontal term is linear, the other two are
     * {@code max(0, ·)} of linear quantities), so the total of a polyline never goes below the cost of the single segment joining
     * its start and end in a straight line. A detour only adds horizontal distance and, if anything, increases {@code requiredClimb}.
     * So the straight segment cost is itself an admissible estimate, and the triangle inequality holds between neighbors too (it's consistent).
     *
     * <p>Within the vertical range, it's measured at the height closest to the best glide slope ({@code -horizontal/GLIDE_RATIO}).
     * The segment cost is minimal there (below it descent is charged, above it climbing is).
     */
    public static double lowerBoundTicks(double horizontalBlocks, double verticalLow, double verticalHigh,
                                         boolean rockets) {
        double glide = -horizontalBlocks / GLIDE_RATIO;
        double vertical = Math.max(verticalLow, Math.min(verticalHigh, glide));
        return segmentTicks(horizontalBlocks, vertical, rockets);
    }

    /**
     * A coarse estimate that doesn't discount climbs covered by gliding. Always smaller than {@link #lowerBoundTicks}.
     * Used by {@code CoarseFlightRouter}, which measures from coarse-layer states that span a band.
     */
    public static double heuristicTicks(double horizontalBlocks, double verticalBlocks, boolean rockets) {
        return horizontalBlocks * HORIZONTAL_TICKS_PER_BLOCK
                + Math.max(0.0, -verticalBlocks) * DESCENT_TICKS_PER_BLOCK
                + Math.max(0.0, verticalBlocks) * ascentTicksPerBlock(rockets);
    }
}
