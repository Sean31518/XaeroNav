package net.prason.xaeronav.pathfinding.flight;

/**
 * A horizontal circle at which the aerial route search may stop. Cells that leave the circle are treated as reaching the destination.
 *
 * <p>Aiming at a destination outside the loaded chunks as a point is <b>unreachable in principle</b>. Every search used up the
 * node cap just to confirm that (150k nodes and about 0.6 s for the first route in the Overworld and End).
 * Placing an intermediate point closer has the same problem when that point lands inside a mountain or rock.
 *
 * <p>So the destination stays the real one, the estimate keeps pointing at the real destination, and the edge of the readable
 * range becomes an <b>exit</b>. The estimate pulls toward the destination, so the search exits from the edge in the destination's
 * direction. The search also picks the exit altitude.
 *
 * @param radius horizontal distance from the center (blocks). {@link Double#POSITIVE_INFINITY} means no exit
 */
public record FlightHorizon(double centerX, double centerZ, double radius) {

    public static final FlightHorizon NONE = new FlightHorizon(0.0, 0.0, Double.POSITIVE_INFINITY);

    boolean outside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz >= radius * radius;
    }
}
