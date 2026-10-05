package net.prason.xaeronav.util;

/**
 * Equivalent of {@link Math#clamp}. That overload was added in JDK 21, so it's provided here for Java 17 nodes
 * (1.20.1). It gives the same result regardless of loader/version, so it can be called from {@code pathfinding/}
 * without gating.
 */
public final class MathSupport {

    private MathSupport() {
    }

    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
