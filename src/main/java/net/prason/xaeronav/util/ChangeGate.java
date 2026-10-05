package net.prason.xaeronav.util;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Bundles "stay quiet if the value is the same as last time, report if it differs" into one. The same kind of guard, keeping diagnostic logs from
 * emitting the same reason every tick, was written in parallel in 3 places in {@code PathfindingState}
 * (merge refusal, skipped seam re-solve, unstandable goal), so it's factored out here.
 *
 * <p>It's volatile to preserve the assumption of the existing code (the original {@code lastSeamRepairRefusal} field) that reset
 * (on success) and update (recording the failure reason) are called from different execution paths.
 */
public final class ChangeGate<T> {

    private volatile @Nullable T last;
    private volatile long lastAtMillis = Long.MIN_VALUE;

    /** {@code false} (suppress) if the value is the same as last time. Otherwise updates internal state and returns {@code true}. */
    public boolean changed(T value) {
        if (Objects.equals(last, value)) {
            return false;
        }
        last = value;
        return true;
    }

    /**
     * {@code true} if the value changed, or at least {@code minIntervalMillis} has passed since the last report.
     * For uses that want periodic reports even while the same state persists ({@link #changed(Object)}
     * stays silent as long as the state doesn't change). Pass time from {@link MonotonicTime}.
     */
    public boolean changed(T value, long nowMillis, long minIntervalMillis) {
        if (Objects.equals(last, value) && nowMillis - lastAtMillis < minIntervalMillis) {
            return false;
        }
        last = value;
        lastAtMillis = nowMillis;
        return true;
    }

    /** Forces the next {@link #changed} to report (call when returning to a "no longer a problem" state). */
    public void reset() {
        last = null;
    }

    /** The most recently reported value. {@code null} if nothing has been reported yet, or after {@link #reset}. */
    public @Nullable T current() {
        return last;
    }
}
