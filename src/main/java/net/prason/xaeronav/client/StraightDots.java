package net.prason.xaeronav.client;

/**
 * A dotted line for the map that joins the stretch with no known route straight to the destination.
 *
 * <p>Beyond unloaded chunks no search is possible at all, so the route on the map ends partway too.
 * So that at least the direction to head is clear, the rest is drawn as a straight connection. It's not the same thing
 * as a route that follows terrain, so it's drawn dotted rather than solid to tell them apart.
 */
final class StraightDots {

    /** Period of the dotted line (blocks). Only the first half of each period is drawn. */
    private static final int PERIOD = 4;
    private static final int DASH = 2;

    /**
     * Cap on the number of dots. Even if the destination is thousands of blocks away, there's no point counting beyond what fits on the map.
     */
    private static final int MAX_DOTS = 4096;

    @FunctionalInterface
    interface DotConsumer {
        void accept(int blockX, int blockZ);
    }

    private StraightDots() {
    }

    static void forEach(int fromX, int fromZ, int toX, int toZ, DotConsumer consumer) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < PERIOD) {
            return;
        }
        int count = (int) Math.min(length, MAX_DOTS);
        double stepX = dx / length;
        double stepZ = dz / length;
        for (int i = 0; i < count; i++) {
            if (i % PERIOD >= DASH) {
                continue;
            }
            consumer.accept((int) Math.floor(fromX + stepX * i), (int) Math.floor(fromZ + stepZ * i));
        }
    }
}
