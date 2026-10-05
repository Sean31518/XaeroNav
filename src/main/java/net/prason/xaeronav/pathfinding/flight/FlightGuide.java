package net.prason.xaeronav.pathfinding.flight;

/**
 * Estimate (ticks) of the remaining cost to the destination added to the air path search. Takes effect only when larger than the straight-line lower bound.
 * Returns {@link Double#NaN} where unknown (estimated with the straight-line lower bound), and {@link Double#POSITIVE_INFINITY}
 * where known not to connect to the destination.
 */
@FunctionalInterface
public interface FlightGuide {

    FlightGuide NONE = (x, y, z) -> Double.NaN;

    double estimate(double x, double y, double z);
}
