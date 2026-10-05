package net.prason.xaeronav.util;

/** Time for deadlines, retries and durations, unaffected by wall clock adjustments. */
public final class MonotonicTime {

    private MonotonicTime() {
    }

    /** Returns the same base as {@link System#nanoTime()} in milliseconds. Must not be used as an absolute date/time. */
    public static long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
