package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * Re-measures the estimates for exits leaving the edge of the readable range ({@link FlightHorizon}) along detours
 * that <b>don't pass through the inside of the readable range</b>.
 *
 * <p>The coarse map's remaining-cost field ({@link CoarseFlightField}) is at chunk resolution, so it misses walls thinner than
 * a chunk. Using it directly for exit estimates picks an exit estimated as "can go straight back from the exit to the goal",
 * even though the inside that the way back passes through is already known to be blocked on the fine grid. In the Nether,
 * facing a wall just before the goal, it kept circling the goal from edge to edge on the opposite side, producing lines 3-6x the optimum.
 *
 * <p>So exit estimates are measured on a field that excludes chunks fully inside. When the goal is inside,
 * the space containing the goal is flood-filled on the fine grid, and where it touches the edge becomes the field's seed (the entrance
 * for going around outside and coming back). If the filled space reaches the player, it's connected through the inside alone, so no exit is used. If it reaches
 * neither the edge nor the player, the goal is inside a closed small room at this grid's coarseness. Allowing exits would keep circling
 * an unreachable goal, so again no exit is used, and the route is drawn to as close as can be approached from the air ({@code FlightRouter#approach}).
 * Since it only looks for an approachable spot, the budget is capped at {@link #ENCLOSED_MAX_EXPANDED_NODES} (with the full budget it burns
 * 2 seconds every time just to confirm it can't reach).
 */
final class HorizonGuide {

    /**
     * Max number of cells flood-filled from the goal. In open areas it would fill most of the readable range, so it's capped
     * (same reason {@link AirGrid} doesn't prebuild). A space that can be filled up to the cap usually touches the edge too.
     */
    private static final int MAX_FLOOD_CELLS = 40_000;

    /** Expansion cap when the goal is inside a closed small room. */
    static final int ENCLOSED_MAX_EXPANDED_NODES = 15_000;

    private static final int[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Exits and estimates passed to the search.
     *
     * @param enclosed the goal is inside a closed small room (search with {@link #ENCLOSED_MAX_EXPANDED_NODES})
     */
    record Plan(FlightHorizon horizon, FlightGuide guide, boolean enclosed) {
    }

    private HorizonGuide() {
    }

    static Plan plan(AirGrid grid, Vec3 start, Vec3 goal, FlightHorizon horizon, CoarseFlightField field,
                     boolean rockets) {
        FlightGuide inside = field::estimate;
        if (horizon.radius() == Double.POSITIVE_INFINITY) {
            return new Plan(horizon, inside, false);
        }
        CoarseFlightField.ChunkFilter interior = (chunkX, chunkZ) -> chunkInside(horizon, chunkX, chunkZ);
        CoarseFlightField outside;
        if (horizon.outside(goal.x, goal.z)) {
            outside = field.avoiding(List.of(goal), seed -> 0.0, interior);
        } else {
            Flood flood = flood(grid, start, goal, horizon);
            if (flood.reachedStart()) {
                return new Plan(FlightHorizon.NONE, inside, false);
            }
            if (flood.entrances().isEmpty() && !flood.capped()) {
                return new Plan(FlightHorizon.NONE, inside, true);
            }
            outside = field.avoiding(flood.entrances(), seed -> lowerBound(seed, goal, grid, rockets), interior);
        }
        return new Plan(horizon, (x, y, z) -> horizon.outside(x, z) ? outside.estimate(x, y, z)
                : inside.estimate(x, y, z), false);
    }

    /** Whether all four corners of the chunk are inside the edge. */
    private static boolean chunkInside(FlightHorizon horizon, int chunkX, int chunkZ) {
        for (int corner = 0; corner < 4; corner++) {
            double x = (chunkX + (corner & 1)) * 16.0;
            double z = (chunkZ + (corner >> 1)) * 16.0;
            if (horizon.outside(x, z)) {
                return false;
            }
        }
        return true;
    }

    private static double lowerBound(Vec3 from, Vec3 goal, AirGrid grid, boolean rockets) {
        double radius = grid.cellBlocks() * FlightRouter.GOAL_RADIUS_CELLS;
        double horizontal = Math.max(0.0, Math.hypot(goal.x - from.x, goal.z - from.z) - radius);
        double tolerance = Math.max(radius, FlightPathfinder.GOAL_VERTICAL_TOLERANCE_BLOCKS);
        double dy = goal.y - from.y;
        return FlightCosts.lowerBoundTicks(horizontal, dy - tolerance, dy + tolerance, rockets);
    }

    /**
     * @param entrances centers of flyable cells outside the edge, adjacent to the filled space
     */
    private static long distanceSq(long key, int x, int y, int z) {
        long dx = BlockPos.getX(key) - x;
        long dy = BlockPos.getY(key) - y;
        long dz = BlockPos.getZ(key) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Flood(boolean reachedStart, boolean capped, List<Vec3> entrances) {
    }

    /**
     * Flood-fills the inside of the edge with 6-neighborhood from the goal region (the cells {@link FlightPathfinder} treats as the goal).
     * Diagonal moves in the 26-neighborhood are allowed only when every box they straddle is flyable, so the result equals the 6-connected range.
     *
     * <p>Fill order is <b>cells closest to the player first</b>. When connected, it reaches the player directly and finishes
     * early (breadth-first used to fill most of the readable range before reaching it). When not connected,
     * any order fills the same space, so the entrances to the edge don't change either.
     */
    private static Flood flood(AirGrid grid, Vec3 start, Vec3 goal, FlightHorizon horizon) {
        long startCell = grid.nearestFlyable(start, FlightPathfinder.SNAP_CELL_RADIUS);
        Vec3 snapped = FlightPathfinder.snappedGoal(grid, goal);
        double radius = grid.cellBlocks() * FlightRouter.GOAL_RADIUS_CELLS;
        double tolerance = Math.max(radius, FlightPathfinder.GOAL_VERTICAL_TOLERANCE_BLOCKS);
        LongOpenHashSet seen = new LongOpenHashSet();
        int towardX = grid.toCell(start.x);
        int towardY = grid.toCell(start.y);
        int towardZ = grid.toCell(start.z);
        LongHeapPriorityQueue queue = new LongHeapPriorityQueue((a, b) -> Long.compare(
                distanceSq(a, towardX, towardY, towardZ), distanceSq(b, towardX, towardY, towardZ)));
        int reachX = (int) Math.ceil(radius / grid.cellBlocks());
        int reachY = (int) Math.ceil(tolerance / grid.cellBlocks());
        int goalX = grid.toCell(snapped.x);
        int goalY = grid.toCell(snapped.y);
        int goalZ = grid.toCell(snapped.z);
        for (int dx = -reachX; dx <= reachX; dx++) {
            for (int dy = -reachY; dy <= reachY; dy++) {
                for (int dz = -reachX; dz <= reachX; dz++) {
                    Vec3 center = grid.center(goalX + dx, goalY + dy, goalZ + dz);
                    if (Math.hypot(center.x - snapped.x, center.z - snapped.z) > radius
                            || Math.abs(center.y - snapped.y) > tolerance || horizon.outside(center.x, center.z)
                            || !grid.flyable(goalX + dx, goalY + dy, goalZ + dz)) {
                        continue;
                    }
                    long key = BlockPos.asLong(goalX + dx, goalY + dy, goalZ + dz);
                    seen.add(key);
                    queue.enqueue(key);
                }
            }
        }
        List<Vec3> entrances = new ArrayList<>();
        int visited = 0;
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            if (key == startCell) {
                return new Flood(true, false, entrances);
            }
            if (++visited > MAX_FLOOD_CELLS) {
                return new Flood(false, true, entrances);
            }
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            for (int[] axis : AXES) {
                int nx = x + axis[0];
                int ny = y + axis[1];
                int nz = z + axis[2];
                long next = BlockPos.asLong(nx, ny, nz);
                if (!seen.add(next) || !grid.flyable(nx, ny, nz)) {
                    continue;
                }
                Vec3 center = grid.center(nx, ny, nz);
                if (horizon.outside(center.x, center.z)) {
                    entrances.add(center);
                } else {
                    queue.enqueue(next);
                }
            }
        }
        return new Flood(false, false, entrances);
    }
}
