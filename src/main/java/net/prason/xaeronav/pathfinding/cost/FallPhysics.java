package net.prason.xaeronav.pathfinding.cost;

/**
 * Simulates Minecraft's fall physics (each tick velocity = (velocity - 0.08) * 0.98, terminal velocity 3.92 blocks/tick)
 * to find the ticks needed to fall N blocks.
 */
public final class FallPhysics {

    private static final double TERMINAL_VELOCITY = 3.92;

    private FallPhysics() {
    }

    private static double velocityAfterTicks(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -TERMINAL_VELOCITY;
    }

    /**
     * Ticks needed to fall distance blocks. The last tick linearly interpolates the fraction until landing
     * (same idea as Baritone's distance-to-ticks method).
     */
    public static double ticksToFall(double distance) {
        if (distance <= 0) {
            return 0.0;
        }
        double remaining = distance;
        int tick = 0;
        while (true) {
            double v = velocityAfterTicks(tick);
            if (remaining <= v) {
                return tick + remaining / v;
            }
            remaining -= v;
            tick++;
        }
    }
}
