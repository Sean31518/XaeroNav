package net.prason.xaeronav.pathfinding.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Surcharge (ticks) for passing through tight spaces.
 *
 * <p>The key point is that <b>both the search and smoothing go through this</b>. Putting it in only one makes
 * smoothing judge a path A* correctly detoured into open space as "not touching terrain and cheap" and pull it
 * back into the tight space; this actually happened (the line kept going through a tunnel even with the
 * surcharge raised to 120 ticks/cell). Unless smoothing decides acceptance with the same cost function as the search, it breaks the search's intent outright.
 */
final class Clearance {

    /**
     * Number of blocked cells among the 26 neighbours that still doesn't count as "tight".
     *
     * <p>9 is one plane's worth: just flying comfortably above the ground or below a ceiling, which isn't tight.
     * Setting this to 0 adds a surcharge even in open areas merely for a nearby floor, and paths go high for no
     * reason (gliding gets descent for free, so the two collide head-on).
     */
    private static final int FREE_BLOCKED_NEIGHBOURS = 9;

    private static final int NEIGHBOUR_COUNT = 26;

    private Clearance() {
    }

    /** Surcharge for entering that cell. Higher the more blocked its surroundings. */
    static double cell(AirGrid grid, int cellX, int cellY, int cellZ, double penaltyTicks) {
        if (penaltyTicks <= 0.0) {
            return 0.0;
        }
        int excess = grid.blockedNeighbours(cellX, cellY, cellZ) - FREE_BLOCKED_NEIGHBOURS;
        return excess <= 0 ? 0.0
                : penaltyTicks * excess / (NEIGHBOUR_COUNT - FREE_BLOCKED_NEIGHBOURS);
    }

    /** Sum of the surcharge over every cell the segment crosses. */
    static double alongLine(AirGrid grid, Vec3 from, Vec3 to, double penaltyTicks) {
        if (penaltyTicks <= 0.0) {
            return 0.0;
        }
        double scale = 1.0 / grid.cellBlocks();
        double[] total = {0.0};
        VoxelRay.traverse(from.scale(scale), to.scale(scale), (x, y, z) -> {
            total[0] += cell(grid, x, y, z, penaltyTicks);
            return true;
        });
        return total[0];
    }
}
