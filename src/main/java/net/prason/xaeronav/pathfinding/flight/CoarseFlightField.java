package net.prason.xaeronav.pathfinding.flight;

import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.ToDoubleFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * A field of the remaining cost to fly to the goal, computed for every (chunk, band) on the coarse aerial map
 * ({@link CoarseAirMap}). Used for the aerial path search's estimates.
 *
 * <p>Choosing an exit from the edge of the loaded range ({@link FlightHorizon}) with straight-line estimates alone exits on
 * the side closest to the goal in a straight line even if it's a dead end beyond the edge. The next extension discovers that and turns back; in the Nether
 * this produced paths that went up to 200 blocks away from the goal before coming back. It's the same fix as walking using layer 1's
 * remaining-cost field to estimate outside the window; here the estimate includes the detours on the map.
 *
 * <p>Edge costs are the same as {@link CoarseFlightRouter} (same map, same route choice). Edges are directed, so
 * when solving backward from the goal, relaxation uses the cost of "entering this cell from the neighbor".
 */
public final class CoarseFlightField {

    private static final int CELL_BLOCKS = 16;
    private static final int BAND_LINK_GAP_BLOCKS = 8;
    private static final double UNKNOWN_MULTIPLIER = 1.3;

    /** Whether to exclude a chunk from the field. */
    @FunctionalInterface
    public interface ChunkFilter {
        boolean test(int chunkX, int chunkZ);
    }

    private static final ChunkFilter NONE_EXCLUDED = (chunkX, chunkZ) -> false;

    private final CoarseAirMap map;
    private final boolean rockets;
    private final double[] cost;

    private CoarseFlightField(CoarseAirMap map, boolean rockets, double[] cost) {
        this.map = map;
        this.rockets = rockets;
        this.cost = cost;
    }

    /** The field toward {@code goal}. {@code null} if the goal is outside the map or inside a wall. */
    public static CoarseFlightField toward(CoarseAirMap map, BlockPos goal, boolean rockets) {
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(goalX, goalZ) || map.blocked(goalX, goalZ)) {
            return null;
        }
        int goalState = state(map, goalX, goalZ, map.bandAt(goalX, goalZ, goal.getY()));
        return new CoarseFlightField(map, rockets, solve(map, new int[] {goalState}, new double[] {0.0}, rockets,
                NONE_EXCLUDED));
    }

    /**
     * On the same map, a field of the remaining cost to reach any of {@code seeds} without passing through any {@code excluded} chunk.
     * The remainder beyond each seed is given by {@code seedCost}. Points outside the map, inside walls, or in excluded chunks aren't used.
     */
    public CoarseFlightField avoiding(List<Vec3> seeds, ToDoubleFunction<Vec3> seedCost, ChunkFilter excluded) {
        int[] states = new int[seeds.size()];
        double[] costs = new double[seeds.size()];
        int count = 0;
        for (Vec3 seed : seeds) {
            int chunkX = (int) Math.floor(seed.x) >> 4;
            int chunkZ = (int) Math.floor(seed.z) >> 4;
            if (!map.containsChunk(chunkX, chunkZ) || map.blocked(chunkX, chunkZ) || excluded.test(chunkX, chunkZ)) {
                continue;
            }
            states[count] = state(map, chunkX, chunkZ, map.bandAt(chunkX, chunkZ, (int) Math.floor(seed.y)));
            costs[count] = seedCost.applyAsDouble(seed);
            count++;
        }
        return new CoarseFlightField(map, rockets, solve(map, Arrays.copyOf(states, count),
                Arrays.copyOf(costs, count), rockets, excluded));
    }

    private static double[] solve(CoarseAirMap map, int[] seedStates, double[] seedCosts, boolean rockets,
                                  ChunkFilter excluded) {
        double[] cost = new double[map.chunksX() * map.chunksZ() * CoarseAirMap.MAX_BANDS];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        for (int i = 0; i < seedStates.length; i++) {
            if (seedCosts[i] < cost[seedStates[i]]) {
                cost[seedStates[i]] = seedCosts[i];
                open.add(new long[] {Double.doubleToRawLongBits(seedCosts[i]), seedStates[i]});
            }
        }
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int current = (int) top[1];
            double known = Double.longBitsToDouble(top[0]);
            if (known > cost[current]) {
                continue;
            }
            int chunkX = chunkX(map, current);
            int chunkZ = chunkZ(map, current);
            int band = current % CoarseAirMap.MAX_BANDS;
            int bottom = map.bandBottom(chunkX, chunkZ, band);
            int topY = map.bandTop(chunkX, chunkZ, band);
            double enterMultiplier = map.unknown(chunkX, chunkZ) ? UNKNOWN_MULTIPLIER : 1.0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int fromX = chunkX + dx;
                    int fromZ = chunkZ + dz;
                    if (!map.containsChunk(fromX, fromZ) || map.blocked(fromX, fromZ)
                            || excluded.test(fromX, fromZ)) {
                        continue;
                    }
                    double horizontal = Math.sqrt(dx * dx + dz * dz) * CELL_BLOCKS;
                    for (int fromBand = 0; fromBand < map.stateBands(fromX, fromZ); fromBand++) {
                        int vertical = gap(map.bandBottom(fromX, fromZ, fromBand), map.bandTop(fromX, fromZ, fromBand),
                                bottom, topY);
                        if (Math.abs(vertical) > BAND_LINK_GAP_BLOCKS) {
                            continue;
                        }
                        double step = FlightCosts.segmentTicks(horizontal, vertical, rockets) * enterMultiplier;
                        int from = state(map, fromX, fromZ, fromBand);
                        double tentative = known + step;
                        if (tentative < cost[from]) {
                            cost[from] = tentative;
                            open.add(new long[] {Double.doubleToRawLongBits(tentative), from});
                        }
                    }
                }
            }
        }
        return cost;
    }

    /**
     * Remaining cost (ticks) from that position to the goal. Outside the map, or where the map doesn't connect to the goal,
     * it's {@link Double#NaN} (unknown); the coarse map's "doesn't connect" is only a chunk-resolution estimate.
     */
    public double estimate(double x, double y, double z) {
        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;
        if (!map.containsChunk(chunkX, chunkZ) || map.blocked(chunkX, chunkZ)) {
            return Double.NaN;
        }
        double value = cost[state(map, chunkX, chunkZ, map.bandAt(chunkX, chunkZ, (int) Math.floor(y)))];
        return value < Double.POSITIVE_INFINITY ? value : Double.NaN;
    }

    /** Climb or descent needed to move from band {@code [bottom, top]} to band {@code [toBottom, toTop]} (up is positive). */
    private static int gap(int bottom, int top, int toBottom, int toTop) {
        if (toBottom > top) {
            return toBottom - top;
        }
        if (toTop < bottom) {
            return toTop - bottom;
        }
        return 0;
    }

    private static int state(CoarseAirMap map, int chunkX, int chunkZ, int band) {
        return ((chunkZ - map.minChunkZ()) * map.chunksX() + (chunkX - map.minChunkX())) * CoarseAirMap.MAX_BANDS
                + band;
    }

    private static int chunkX(CoarseAirMap map, int state) {
        return map.minChunkX() + (state / CoarseAirMap.MAX_BANDS) % map.chunksX();
    }

    private static int chunkZ(CoarseAirMap map, int state) {
        return map.minChunkZ() + (state / CoarseAirMap.MAX_BANDS) / map.chunksX();
    }
}
