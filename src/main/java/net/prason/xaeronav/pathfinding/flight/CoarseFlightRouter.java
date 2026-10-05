package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * Long-distance aerial route (layer 1 equivalent) solved over {@link CoarseAirMap}. Reaches beyond the render distance.
 *
 * <p><b>Walking's {@code CoarseRouter} isn't reused.</b> It's a walking-only cost model that treats lava as impassable-to-expensive and water
 * with the boat multiplier, whereas in flight <b>flying over a lava sea is perfectly correct</b>;
 * the sign itself is wrong. Only the terrain reading ({@code CoarseMap}) is shared; costs are kept separately.
 *
 * <p>The state is {@code (chunkX, chunkZ, altitude band)}. <b>No moves are created that cross bands within the same cell</b>:
 * between bands lies a floor (rock), and whether there's a way to pass through it vertically can't be told at
 * chunk resolution. Crossing layers happens naturally by passing where bands overlap in a neighboring cell.
 * Rather than treating the unknown as connected, it's safer to defer to layer 3 ({@link AirGrid}).
 * This is the same trade-off as walking's layers 1/2 not representing layer crossings.
 */
public final class CoarseFlightRouter {

    private static final int CELL_BLOCKS = 16;

    /** Interval (cells) at which intermediate goals are dropped. Matched to walking's layer 1. */
    private static final int WAYPOINT_SPACING_CELLS = 4;

    /** If the height changes by this much, drop an intermediate goal without waiting for the interval (blocks). */
    private static final int WAYPOINT_VERTICAL_SPACING_BLOCKS = 24;

    /**
     * Height gap allowed when moving to a band in a neighboring cell (blocks). Even without overlap, a gap within this
     * is treated as a gentle climb or descent following the terrain. Making it larger starts to make separate levels that
     * are actually divided by rock look connected.
     */
    private static final int BAND_LINK_GAP_BLOCKS = 8;

    /**
     * Multiplier for passing through cells with no data. Same role as walking's {@code UNKNOWN_MULTIPLIER}, but in flight
     * the disadvantage of being unvisited is small (lava and water don't matter; all that's needed is open space), so it's modest.
     */
    private static final double UNKNOWN_MULTIPLIER = 1.3;

    private CoarseFlightRouter() {
    }

    /**
     * Intermediate-goal sequence from {@code start} to {@code goal}. If unreachable, returns up to the point that got
     * closest to the goal at that time ({@link CoarseRouter.Route#reachedGoal()} is false).
     */
    public static CoarseRouter.Route findRoute(CoarseAirMap map, BlockPos start, BlockPos goal,
                                                boolean rockets) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new CoarseRouter.Route(List.of(), false);
        }

        int states = map.chunksX() * map.chunksZ() * CoarseAirMap.MAX_BANDS;
        double[] cost = new double[states];
        int[] previous = new int[states];
        boolean[] closed = new boolean[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        if (map.blocked(startX, startZ) || map.blocked(goalX, goalZ)) {
            // The start or goal column is a wall on the coarse map. Forcing a route here is pointless, so
            // give up on the coarse layer and defer to layer 3 (the side that looks at loaded chunks)
            return new CoarseRouter.Route(List.of(), false);
        }
        int startState = stateIndex(map, startX, startZ, map.bandAt(startX, startZ, start.getY()));
        int goalState = stateIndex(map, goalX, goalZ, map.bandAt(goalX, goalZ, goal.getY()));
        cost[startState] = 0.0;

        PriorityQueue<Candidate> open = new PriorityQueue<>();
        open.add(new Candidate(startState, heuristic(map, startState, goal, rockets)));
        int bestState = startState;
        double bestHeuristic = heuristic(map, startState, goal, rockets);
        boolean reachedGoal = false;

        while (!open.isEmpty()) {
            Candidate candidate = open.poll();
            int state = candidate.state();
            if (closed[state]) {
                continue;
            }
            closed[state] = true;
            if (state == goalState) {
                reachedGoal = true;
                break;
            }
            double estimate = heuristic(map, state, goal, rockets);
            if (estimate < bestHeuristic) {
                bestHeuristic = estimate;
                bestState = state;
            }
            expand(map, state, goal, rockets, cost, previous, closed, open);
        }

        return buildRoute(map, reachedGoal ? goalState : bestState, startState, previous, start.getY(),
                reachedGoal);
    }

    private static void expand(CoarseAirMap map, int state, BlockPos goal, boolean rockets,
                                double[] cost, int[] previous, boolean[] closed,
                                PriorityQueue<Candidate> open) {
        int chunkX = stateChunkX(map, state);
        int chunkZ = stateChunkZ(map, state);
        int band = stateBand(state);
        int bottom = map.bandBottom(chunkX, chunkZ, band);
        int top = map.bandTop(chunkX, chunkZ, band);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nextX = chunkX + dx;
                int nextZ = chunkZ + dz;
                if (!map.containsChunk(nextX, nextZ) || map.blocked(nextX, nextZ)) {
                    continue;
                }
                double horizontal = Math.sqrt(dx * dx + dz * dz) * CELL_BLOCKS;
                for (int nextBand = 0; nextBand < map.stateBands(nextX, nextZ); nextBand++) {
                    int nextBottom = map.bandBottom(nextX, nextZ, nextBand);
                    int nextTop = map.bandTop(nextX, nextZ, nextBand);
                    // If the bands overlap, the move needs no height change. If they're apart, it needs a climb or descent
                    // of that gap; bands too far apart have no evidence of being connected, so no edge is created
                    int vertical = verticalGap(bottom, top, nextBottom, nextTop);
                    if (Math.abs(vertical) > BAND_LINK_GAP_BLOCKS) {
                        continue;
                    }
                    double step = FlightCosts.segmentTicks(horizontal, vertical, rockets);
                    if (map.unknown(nextX, nextZ)) {
                        step *= UNKNOWN_MULTIPLIER;
                    }
                    int nextState = stateIndex(map, nextX, nextZ, nextBand);
                    if (closed[nextState]) {
                        continue;
                    }
                    double tentative = cost[state] + step;
                    if (tentative >= cost[nextState]) {
                        continue;
                    }
                    cost[nextState] = tentative;
                    previous[nextState] = state;
                    open.add(new Candidate(nextState,
                            tentative + heuristic(map, nextState, goal, rockets)));
                }
            }
        }
    }

    /**
     * Signed minimum movement between two altitude bands. 0 if they overlap (move without changing height).
     * Positive means climbing, negative means descending.
     */
    private static int verticalGap(int bottom, int top, int nextBottom, int nextTop) {
        if (nextBottom > top) {
            return nextBottom - top;
        }
        if (nextTop < bottom) {
            return nextTop - bottom;
        }
        return 0;
    }

    private static double heuristic(CoarseAirMap map, int state, BlockPos goal, boolean rockets) {
        int chunkX = stateChunkX(map, state);
        int chunkZ = stateChunkZ(map, state);
        double dx = goal.getX() - (chunkX * CELL_BLOCKS + CELL_BLOCKS / 2.0);
        double dz = goal.getZ() - (chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2.0);
        // Measure from the height within the band closest to the goal's Y. Bands have thickness, so measuring from the center overestimates
        int from = map.clampToBand(chunkX, chunkZ, stateBand(state), goal.getY());
        return FlightCosts.heuristicTicks(Math.sqrt(dx * dx + dz * dz), goal.getY() - from, rockets);
    }

    private static CoarseRouter.Route buildRoute(CoarseAirMap map, int endState, int startState,
                                                  int[] previous, int startY, boolean reachedGoal) {
        List<Integer> states = new ArrayList<>();
        for (int state = endState; state != -1; state = previous[state]) {
            states.add(state);
            if (state == startState) {
                break;
            }
        }
        Collections.reverse(states);
        if (states.size() <= 1) {
            return new CoarseRouter.Route(List.of(), reachedGoal);
        }

        List<BlockPos> waypoints = new ArrayList<>();
        int lastX = stateChunkX(map, states.get(0));
        int lastZ = stateChunkZ(map, states.get(0));
        // The height is the previous intermediate goal's height clamped into the band. Using the band's center makes the altitude
        // jump for no reason in thick bands, and the guidance appears to swing up and down
        int lastY = startY;
        for (int i = 1; i < states.size(); i++) {
            int state = states.get(i);
            int chunkX = stateChunkX(map, state);
            int chunkZ = stateChunkZ(map, state);
            int y = map.clampToBand(chunkX, chunkZ, stateBand(state), lastY);
            boolean last = i == states.size() - 1;
            int spanX = Math.abs(chunkX - lastX);
            int spanZ = Math.abs(chunkZ - lastZ);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS
                    || Math.abs(y - lastY) >= WAYPOINT_VERTICAL_SPACING_BLOCKS) {
                waypoints.add(new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2, y,
                        chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2));
                lastX = chunkX;
                lastZ = chunkZ;
            }
            lastY = y;
        }
        return new CoarseRouter.Route(List.copyOf(waypoints), reachedGoal);
    }

    private static int stateIndex(CoarseAirMap map, int chunkX, int chunkZ, int band) {
        int localX = chunkX - map.minChunkX();
        int localZ = chunkZ - map.minChunkZ();
        return (localZ * map.chunksX() + localX) * CoarseAirMap.MAX_BANDS + band;
    }

    private static int stateChunkX(CoarseAirMap map, int state) {
        return map.minChunkX() + (state / CoarseAirMap.MAX_BANDS) % map.chunksX();
    }

    private static int stateChunkZ(CoarseAirMap map, int state) {
        return map.minChunkZ() + (state / CoarseAirMap.MAX_BANDS) / map.chunksX();
    }

    private static int stateBand(int state) {
        return state % CoarseAirMap.MAX_BANDS;
    }

    private record Candidate(int state, double estimatedTotal) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate other) {
            return Double.compare(estimatedTotal, other.estimatedTotal);
        }
    }
}
