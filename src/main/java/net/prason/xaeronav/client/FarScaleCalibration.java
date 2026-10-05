package net.prason.xaeronav.client;

import java.util.ArrayDeque;
import java.util.Iterator;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.util.MathSupport;

/**
 * For the arrival time display, learns the multiplier applied to the guide's "estimate outside the window" from values
 * actually observed along this route.
 *
 * <p>The outside-window estimate is off in a different way at each place, and in the Nether it is only 0.45 to 0.61 times
 * the true value. Adding it as-is means that each time the window advances, the estimated stretch gets replaced by the
 * actual cost, and the arrival time keeps growing while you walk.
 *
 * <p>How it learns: each time the window is built, remember the outside estimate {@code O} read at the window edge (point
 * {@code X}). When {@code X} later lies inside a window, the value from there becomes "the actual cost {@code I} through
 * the window + the estimate {@code O'} at the new edge". Taking the true value as {@code k} times the estimate gives
 * {@code k·O = I + k·O'}, so {@code k = I / (O - O')}. Individual ratios fluctuate, so we take the ratio of the sums.
 *
 * <p><b>Not applied to the pathfinding guide.</b> Applying it to the search changes its quality (self-calibration was
 * tried on the model and rejected). Used for display only.
 * <b>Only the single setup thread calls {@link #observe} and {@link #reset}.</b>
 */
final class FarScaleCalibration {

    /** Multiplier while there are no samples. Trusts the estimate. */
    private static final double PRIOR_SCALE = 1.0;
    /**
     * Seeds the prior multiplier as a sample worth this cost (ticks), so the first observation does not swing the
     * multiplier too far. It decays together with the real samples, so it stops mattering as you walk.
     */
    private static final double PRIOR_TICKS = 200.0;
    /** Fraction by which old samples are reduced each time. If the terrain changes, so does the error, so recent samples weigh more. */
    private static final double DECAY = 0.8;
    /** Samples with a smaller denominator are discarded. If the window has barely moved, the ratio is pure noise. */
    private static final double MIN_SAMPLE_TICKS = 40.0;
    /** Number of points to remember. Walking away from the destination piles up remembered points that never enter a window. */
    private static final int MAX_PENDING = 16;
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 4.0;

    private record Pending(BlockPos exit, double outside) {
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private double inside = PRIOR_SCALE * PRIOR_TICKS;
    private double predicted = PRIOR_TICKS;
    private volatile double scale = PRIOR_SCALE;

    /** The current multiplier. May be read from any thread. */
    double scale() {
        return scale;
    }

    /** For a newly built window, checks the remembered points against it and remembers a new point from {@code center}. */
    void observe(WindowField field, BlockPos center) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending point = it.next();
            BlockPos exit = point.exit();
            if (!field.measuredInWindow(exit.getX(), exit.getZ())) {
                continue;
            }
            it.remove();
            WindowField.Descent descent = field.descend(exit.getX(), exit.getY(), exit.getZ());
            if (descent == null) {
                continue;
            }
            double denominator = point.outside() - descent.outside();
            if (denominator < MIN_SAMPLE_TICKS) {
                continue;
            }
            inside = inside * DECAY + descent.inside();
            predicted = predicted * DECAY + denominator;
            scale = MathSupport.clamp(inside / predicted, MIN_SCALE, MAX_SCALE);
        }
        WindowField.Descent descent = field.descend(center.getX(), center.getY(), center.getZ());
        if (descent == null || descent.reachedGoal()) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            pending.removeFirst();
        }
        pending.addLast(new Pending(descent.exit(), descent.outside()));
    }

    void reset() {
        pending.clear();
        inside = PRIOR_SCALE * PRIOR_TICKS;
        predicted = PRIOR_TICKS;
        scale = PRIOR_SCALE;
    }
}
