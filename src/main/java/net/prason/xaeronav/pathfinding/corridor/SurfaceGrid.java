package net.prason.xaeronav.pathfinding.corridor;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/**
 * Per-block-column (x,z) surface data used by long-range route layer 2 (corridor-only, block-resolution surface graph).
 *
 * <p>The block-resolution version of {@link CoarseMap}. Instead of 1 chunk = 1 cell it holds 1 block = 1 cell, and in
 * exchange keeps the array size down by narrowing the target area to the corridor (roughly the segments between layer 1's waypoints ± a margin).
 *
 * <p>Immutable after creation. Built by {@link SurfaceGridBuilder}.
 */
public final class SurfaceGrid {

    public static final short UNKNOWN_HEIGHT = CoarseMap.UNKNOWN_HEIGHT;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final byte[] kind;
    private final short[] groundHeight;
    private final short[] surfaceHeight;

    SurfaceGrid(int minX, int minZ, int sizeX, int sizeZ,
                byte[] kind, short[] groundHeight, short[] surfaceHeight) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.kind = kind;
        this.groundHeight = groundHeight;
        this.surfaceHeight = surfaceHeight;
    }

    public boolean containsColumn(int x, int z) {
        int localX = x - minX;
        int localZ = z - minZ;
        return localX >= 0 && localX < sizeX && localZ >= 0 && localZ < sizeZ;
    }

    public byte kindAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return CoarseMap.NO_DATA;
        }
        return kind[index(x, z)];
    }

    /** Surface height. The bottom for water, the surface for land and lava. {@link #UNKNOWN_HEIGHT} if there's no data. */
    public short groundHeightAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return UNKNOWN_HEIGHT;
        }
        return groundHeight[index(x, z)];
    }

    /** Water surface height. Same value as {@link #groundHeightAt} for anything but water. */
    public short surfaceHeightAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return UNKNOWN_HEIGHT;
        }
        return surfaceHeight[index(x, z)];
    }

    private int index(int x, int z) {
        return (z - minZ) * sizeX + (x - minX);
    }

    /**
     * Resolves to the height actually standable in this column (x,z). Land is one above the ground, water is the
     * surface itself ({@code SurfaceCellSource#cell} treats the surface as a WATER cell, so +1 would resolve to air).
     * Lava has nowhere to stand, so {@code null} ({@code groundHeightAt} returns the height of the lava surface, and
     * one above that is either inside lava or submerged air). Also {@code null} if there's no data.
     */
    public BlockPos resolveStandable(int x, int z) {
        byte kind = kindAt(x, z);
        if (kind == CoarseMap.WATER) {
            short surface = surfaceHeightAt(x, z);
            return surface == UNKNOWN_HEIGHT ? null : new BlockPos(x, surface, z);
        }
        if (kind == CoarseMap.LAVA) {
            return null;
        }
        short ground = groundHeightAt(x, z);
        return ground == UNKNOWN_HEIGHT ? null : new BlockPos(x, ground + 1, z);
    }

    /**
     * Resolves to the standable height closer to the requested Y. Only water columns differ from {@link #resolveStandable},
     * as <b>a choice between surface and bottom</b>.
     *
     * <p>Only destination resolution needs this. Intermediate targets indicate "which way to go", so the surface is
     * fine, but the destination is <b>the very point the user pointed at</b>: if they pointed at the seabed, it isn't
     * arrival until you reach the seabed. Going through {@link #resolveStandable} rounded it to the surface, and it
     * "arrived" on top of the sea.
     *
     * <p>The bottom side is {@code groundHeight + 1} (one above the bottom = feet on sand, body in water). The surface
     * side is the surface itself (one above is out of the water and not standable). This difference is where the asymmetry of {@link #resolveStandable} comes from.
     */
    public BlockPos resolveStandableNear(int x, int z, int preferredY) {
        if (kindAt(x, z) != CoarseMap.WATER) {
            return resolveStandable(x, z);
        }
        short surface = surfaceHeightAt(x, z);
        short ground = groundHeightAt(x, z);
        if (surface == UNKNOWN_HEIGHT) {
            return null;
        }
        if (ground == UNKNOWN_HEIGHT) {
            return new BlockPos(x, surface, z);
        }
        int seabed = ground + 1;
        return Math.abs(preferredY - seabed) < Math.abs(preferredY - surface)
                ? new BlockPos(x, seabed, z) : new BlockPos(x, surface, z);
    }

    /**
     * Resolves an endpoint for which {@link #resolveStandable} was {@code null} by moving it to the nearest standable
     * column in the corridor. At the edge of Nether lava seas it's not unusual for a waypoint to land right on a lava
     * column, and giving up the whole layer 2 corridor refinement there is wasteful; often there's land just a few blocks away.
     *
     * <p>Returns the closest column within {@code maxRadius} (ties go to the first in scan order = smaller Z, then
     * smaller X, so the result doesn't vary between calls). {@code null} if none is found.
     */
    public BlockPos resolveNearestStandable(int x, int z, int maxRadius) {
        BlockPos direct = resolveStandable(x, z);
        if (direct != null) {
            return direct;
        }
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int minSearchX = Math.max(minX, x - maxRadius);
        int maxSearchX = Math.min(minX + sizeX - 1, x + maxRadius);
        int minSearchZ = Math.max(minZ, z - maxRadius);
        int maxSearchZ = Math.min(minZ + sizeZ - 1, z + maxRadius);
        for (int candidateZ = minSearchZ; candidateZ <= maxSearchZ; candidateZ++) {
            for (int candidateX = minSearchX; candidateX <= maxSearchX; candidateX++) {
                long dx = candidateX - x;
                long dz = candidateZ - z;
                long distanceSq = dx * dx + dz * dz;
                if (distanceSq > (long) maxRadius * maxRadius || distanceSq >= bestDistanceSq) {
                    continue;
                }
                BlockPos resolved = resolveStandable(candidateX, candidateZ);
                if (resolved != null) {
                    best = resolved;
                    bestDistanceSq = distanceSq;
                }
            }
        }
        return best;
    }
}
