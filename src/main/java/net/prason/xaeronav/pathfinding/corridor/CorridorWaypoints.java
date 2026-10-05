package net.prason.xaeronav.pathfinding.corridor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Assembles the per-leg point lists solved by layer 2 into a detailed waypoint list. Pure logic with no
 * dependency on Minecraft or Xaero ({@link CorridorLegSolver} reads the Xaero map; this class only
 * post-processes its results).
 */
public final class CorridorWaypoints {

    private CorridorWaypoints() {
    }

    /**
     * Concatenates each leg's point list in order. For each leg the caller passes either the positions of
     * {@code PathResult.steps()} if layer-2 A* solved it (even if unreached, the part that was reached is used as-is,
     * the same idea as the existing "provisional route"), or just the raw single waypoint if there is no surface data.
     */
    public static List<BlockPos> stitch(List<List<BlockPos>> legPoints) {
        List<BlockPos> waypoints = new ArrayList<>();
        for (List<BlockPos> leg : legPoints) {
            waypoints.addAll(leg);
        }
        return waypoints;
    }

    /**
     * Thins out points closer than {@code minSpacingBlocks} (Euclidean) to the last kept point. Layer 2
     * returns a fine per-block point list, so without thinning the HUD's "long-distance route N/M" and the waypoint
     * count grow by orders of magnitude compared to layer 1, making the guidance hard to read.
     *
     * <p>The last point (the end of the leg = arrival point for the next waypoint) is always kept even if it would be
     * thinned: if a waypoint's arrival position shifts, it no longer lines up with the start of the following leg.
     */
    public static List<BlockPos> downsample(List<BlockPos> points, int minSpacingBlocks) {
        if (points.isEmpty()) {
            return points;
        }
        List<BlockPos> kept = new ArrayList<>();
        BlockPos last = points.get(0);
        kept.add(last);
        double minSpacingSq = (double) minSpacingBlocks * minSpacingBlocks;
        for (int i = 1; i < points.size() - 1; i++) {
            BlockPos candidate = points.get(i);
            if (distanceSq(last, candidate) >= minSpacingSq) {
                kept.add(candidate);
                last = candidate;
            }
        }
        BlockPos finalPoint = points.get(points.size() - 1);
        if (points.size() > 1 && !finalPoint.equals(last)) {
            kept.add(finalPoint);
        }
        return kept;
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
