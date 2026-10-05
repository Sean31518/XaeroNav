package net.prason.xaeronav.pathfinding.cost;

import java.util.function.ToDoubleFunction;

/**
 * Runs elytra gliding physics with the recurrence of vanilla {@code LivingEntity#travel}'s fall-flying branch, as-is.
 *
 * <p>Numbers for glide speed, sink rate and glide ratio are known ("about 30 blocks/s", "glide ratio 10:1"),
 * but <b>none are copied here</b>. Only values produced by running vanilla's formula are passed to {@link FlightCosts}.
 * Lift, thrust, steering and drag all interact in the formula, so dropping a single intermediate term shifts
 * things by a few percent, which quietly skews the slope of the whole cost model (the balance of climbing vs horizontal).
 *
 * <p>Yaw is always 0, and it runs in 2D with the forward component {@code horizontal} (+Z) and the vertical
 * component {@code vertical}. Yaw only rotates the heading without affecting speed magnitude, so 3D isn't needed to find the glide polar.
 */
public final class ElytraPhysics {

    /**
     * Gravity. Exactly the {@code RangedAttribute} default of {@code Attributes.GRAVITY}.
     * Appears as {@code d0} in vanilla's formula.
     */
    private static final double GRAVITY = 0.08;

    /** Ticks to run for convergence. Time constants from drag 0.98/0.99 are a few dozen ticks, so this settles enough. */
    private static final int STEADY_STATE_TICKS = 4000;

    /** How long to follow a single climb. The apex comes within 100 ticks, so looking past this doesn't move it. */
    private static final int ZOOM_CLIMB_TICKS = 200;

    private ElytraPhysics() {
    }

    /** Velocity (blocks/tick). Vertical is positive upward. */
    public record Velocity(double horizontal, double vertical) {

        static final Velocity ZERO = new Velocity(0.0, 0.0);

        /** How many blocks travelled horizontally per block of sink. {@link Double#NaN} if not sinking. */
        public double glideRatio() {
            return vertical < 0.0 ? horizontal / -vertical : Double.NaN;
        }
    }

    /**
     * Altitude gained by a single pull-up, and the ticks to the apex.
     *
     * @param blocks Altitude gain (highest point, with the pull-up point as 0)
     * @param ticks  Ticks to reach the apex
     */
    public record ZoomClimb(double blocks, int ticks) {

        /** Ticks needed to gain one block. */
        public double ticksPerBlock() {
            return blocks > 0.0 ? ticks / blocks : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * Advances one tick. {@code pitchRadians} is <b>positive downward</b> (same direction as vanilla's {@code xRot}).
     *
     * <p>Correspondence with vanilla's formula: {@code lookY = vec31.y}, {@code lookZ = vec31.z}, {@code horizontalLook} is
     * the horizontal component of the look ({@code d1}), {@code entrySpeed} is the horizontal speed entering this tick ({@code d3}),
     * {@code lift} is the lift coefficient from the angle of attack ({@code d5}).
     */
    public static Velocity step(Velocity velocity, double pitchRadians, boolean rocket) {
        double horizontal = velocity.horizontal();
        double vertical = velocity.vertical();
        double lookY = -Math.sin(pitchRadians);
        double lookZ = Math.cos(pitchRadians);
        double horizontalLook = Math.abs(lookZ);
        double entrySpeed = Math.abs(horizontal);

        // cos^2(pitch) * min(1, |look|/0.4). The look is a unit vector, so the latter is always 1
        double lift = lookZ * lookZ * Math.min(1.0, 1.0 / 0.4);
        vertical += GRAVITY * (-1.0 + lift * 0.75);

        if (vertical < 0.0 && horizontalLook > 0.0) {
            // Converts part of the sink into lift and thrust. What makes gliding gliding is concentrated in this one spot
            double recovered = vertical * -0.1 * lift;
            horizontal += lookZ * recovered / horizontalLook;
            vertical += recovered;
        }
        if (pitchRadians < 0.0 && horizontalLook > 0.0) {
            // Pull-up. Trades horizontal speed for altitude (enters vertical at 3.2x)
            double zoom = entrySpeed * -Math.sin(pitchRadians) * 0.04;
            horizontal += -lookZ * zoom / horizontalLook;
            vertical += zoom * 3.2;
        }
        if (horizontalLook > 0.0) {
            // Pulls the horizontal velocity's direction toward the look (magnitude unchanged)
            horizontal += (lookZ / horizontalLook * entrySpeed - horizontal) * 0.1;
        }
        if (rocket) {
            // FireworkRocketEntity#tick: pulls the wearer's glide velocity toward look*1.5 by 0.5 each tick, then adds look*0.1
            horizontal += lookZ * 0.1 + (lookZ * 1.5 - horizontal) * 0.5;
            vertical += lookY * 0.1 + (lookY * 1.5 - vertical) * 0.5;
        }
        return new Velocity(horizontal * 0.99, vertical * 0.98);
    }

    /** Steady-state velocity when holding that attitude from rest. */
    public static Velocity steadyState(double pitchDegrees, boolean rocket) {
        double pitch = Math.toRadians(pitchDegrees);
        Velocity velocity = Velocity.ZERO;
        for (int tick = 0; tick < STEADY_STATE_TICKS; tick++) {
            velocity = step(velocity, pitch, rocket);
        }
        return velocity;
    }

    /**
     * Sweeps pitch from {@code fromDegrees} to {@code toDegrees} in {@code stepDegrees} steps and returns the
     * steady-state velocity of the attitude maximizing {@code score}. "Fastest horizontal cruise", "best glide
     * ratio" and "maximum climb rate" are all the same sweep with a different scoring function, so they're unified.
     */
    public static Velocity bestSteadyState(double fromDegrees, double toDegrees, double stepDegrees,
                                            boolean rocket, ToDoubleFunction<Velocity> score) {
        Velocity best = Velocity.ZERO;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double pitch = fromDegrees; pitch <= toDegrees + 1.0e-9; pitch += stepDegrees) {
            Velocity candidate = steadyState(pitch, rocket);
            double value = score.applyAsDouble(candidate);
            if (Double.isFinite(value) && value > bestScore) {
                bestScore = value;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * From velocity {@code entry}, pulls the nose up to {@code pitchDegrees} and climbs to the apex.
     *
     * <p>An elytra without rockets <b>can't climb in steady state</b> (the steady vertical component is negative at every pitch).
     * The only way to gain altitude is this move, trading stored horizontal speed for altitude once.
     */
    public static ZoomClimb zoomClimb(Velocity entry, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        Velocity velocity = entry;
        double height = 0.0;
        double peak = 0.0;
        int peakTick = 0;
        for (int tick = 1; tick <= ZOOM_CLIMB_TICKS; tick++) {
            velocity = step(velocity, pitch, false);
            height += velocity.vertical();
            if (height > peak) {
                peak = height;
                peakTick = tick;
            }
        }
        return new ZoomClimb(peak, peakTick);
    }

    /** The pull-up that gains altitude most cheaply (fewest ticks per block) from {@code entry}. */
    public static ZoomClimb bestZoomClimb(Velocity entry, double fromDegrees, double toDegrees,
                                           double stepDegrees) {
        ZoomClimb best = new ZoomClimb(0.0, 0);
        for (double pitch = fromDegrees; pitch >= toDegrees - 1.0e-9; pitch -= stepDegrees) {
            ZoomClimb candidate = zoomClimb(entry, pitch);
            if (candidate.ticksPerBlock() < best.ticksPerBlock()) {
                best = candidate;
            }
        }
        return best;
    }
}
