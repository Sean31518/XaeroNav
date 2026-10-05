package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * A* over {@link AirGrid} with a 26-neighborhood. Returns a polyline that passes only through open space.
 *
 * <p>Nodes are <b>integer IDs over parallel arrays</b> rather than objects holding coordinates. None of the information
 * that the walking {@code PathNode} holds (move kind, bridge run length, digging) exists in the air; all that remains is
 * cost and parent. A 3D grid touches many times more nodes than walking for the same distance, so eliminating
 * per-node object allocation matters a lot.
 *
 * <p>Follows the same discipline as {@code PathNode.closed}: weighted A* breaks consistency, so returning settled nodes
 * to open re-expands the same cell over and over (6.3 times per cell measured on the walking side). In exchange for not returning them,
 * the path cost stays within {@code heuristicWeight} times the optimum.
 */
public final class FlightPathfinder {

    /**
     * Coefficients for choosing "how far we got" when the search ends without reaching the goal. Smaller values weigh
     * the distance actually traveled more heavily. Same idea as the walking side ({@code AStarPathfinder.COEFFICIENTS}), following Baritone.
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /** Interval for checking cancellation/timeout (a mask on the expansion count). */
    private static final int CHECK_INTERVAL_MASK = 0x3F;

    /** Shortest length (blocks) worth offering as a partial path. Anything shorter is treated as no path. */
    private static final double MIN_USEFUL_PATH_BLOCKS = 8.0;

    /** Radius (in cells) to search for a flyable cell when the start or destination doesn't land on the grid. */
    static final int SNAP_CELL_RADIUS = 3;

    /**
     * Vertical tolerance (blocks) of the goal region. <b>Fixed wider than the horizontal radius</b>.
     *
     * <p>The flight goal is usually an intermediate target placed by {@code CoarseFlightRouter}, and its Y is
     * only an estimate: "an altitude band derived from the chunk-average floor height", further clamped. Constraining Y to the same
     * width as horizontal makes the goal <b>unreachable in principle</b> when the estimate is off by just a few blocks, and discovering that
     * burns the node limit every time; the more complex the terrain, the more often this hits, and the path stops extending.
     * Exactly the same reasoning as walking's {@code AStarPathfinder.GOAL_VERTICAL_TOLERANCE_BLOCKS}.
     */
    static final int GOAL_VERTICAL_TOLERANCE_BLOCKS = 24;

    private static final double MIN_IMPROVEMENT = 0.01;

    /**
     * Extra time (milliseconds) allowed for smoothing after the search deadline has passed. Without this, smoothing after a search
     * that ran to the limit is unbounded (in practice: 2 s search + 6.5 s smoothing).
     */
    private static final long SMOOTHING_ALLOWANCE_MILLIS = 400L;

    private final AirGrid grid;
    private final boolean rockets;
    private final SearchLimits limits;
    private final double clearancePenaltyTicks;

    // Node table (IDs are sequential from 0). Only one cellKey -> ID lookup is kept
    private final Long2IntOpenHashMap ids = new Long2IntOpenHashMap();
    private long[] cellKey = new long[1024];
    private double[] cost = new double[1024];
    private double[] combined = new double[1024];
    private double[] estimate = new double[1024];
    private int[] previous = new int[1024];
    private int[] heapPosition = new int[1024];
    private boolean[] closed = new boolean[1024];
    private int nodeCount;

    private int[] heap = new int[1024];
    private int heapSize;

    private final int[] bestSoFar = new int[COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[COEFFICIENTS.length];

    private Vec3 goal;
    private double goalRadius;
    private FlightHorizon horizon = FlightHorizon.NONE;
    private FlightGuide guide = FlightGuide.NONE;
    private long deadline;

    /**
     * @param clearancePenaltyTicks surcharge (ticks) for entering a cell whose 26-neighborhood is fully blocked. 0 disables it.
     *                              Expresses the requirement to avoid narrow spots even on the shortest path
     */
    public FlightPathfinder(AirGrid grid, boolean rockets, SearchLimits limits, double clearancePenaltyTicks) {
        this.grid = grid;
        this.rockets = rockets;
        this.limits = limits;
        this.clearancePenaltyTicks = clearancePenaltyTicks;
        this.ids.defaultReturnValue(-1);
    }

    public FlightRoute search(Vec3 start, Vec3 target, double goalRadiusBlocks) {
        return search(start, target, goalRadiusBlocks, () -> false);
    }

    /**
     * Find a polyline from {@code start} that reaches within radius {@code goalRadiusBlocks} of {@code target}.
     *
     * <p>The goal is a <b>region</b> rather than a point for the same reason as walking: the destination is usually the landing ground
     * itself (i.e. a non-flyable cell), so requiring a point makes it unreachable in principle. What we actually want to know is how close
     * we need to get in the air before the player can descend on their own.
     */
    public FlightRoute search(Vec3 start, Vec3 target, double goalRadiusBlocks, BooleanSupplier cancelled) {
        return search(start, target, goalRadiusBlocks, FlightHorizon.NONE, FlightGuide.NONE, cancelled);
    }

    /** Cells that leave {@code horizon} also count as arrived (see {@link FlightHorizon}). */
    public FlightRoute search(Vec3 start, Vec3 target, double goalRadiusBlocks, FlightHorizon horizon,
                              FlightGuide guide, BooleanSupplier cancelled) {
        this.horizon = horizon;
        this.guide = guide;
        // A node's estimate can only be computed once the goal is fixed. Even if the goal changes on a second search,
        // the previous nodes keep their stale estimates, so discard the whole table
        ids.clear();
        nodeCount = 0;
        heapSize = 0;
        this.goal = target;
        this.goalRadius = goalRadiusBlocks;

        this.goal = snappedGoal(grid, target);

        long startCell = grid.nearestFlyable(start, SNAP_CELL_RADIUS);
        if (startCell == AirGrid.NONE) {
            // The surroundings are blocked (inside rock, unloaded). No path can be drawn from here
            return FlightRoute.NONE;
        }

        int startNode = node(startCell);
        cost[startNode] = 0.0;
        combined[startNode] = limits.heuristicWeight() * estimate[startNode];
        insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, estimate[startNode]);

        this.deadline = MonotonicTime.millis() + limits.timeLimitMillis();
        int expanded = 0;
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;

        while (heapSize > 0) {
            if (expanded >= limits.maxExpandedNodes()) {
                termination = PathResult.Termination.NODE_BUDGET;
                break;
            }
            if ((expanded & CHECK_INTERVAL_MASK) == 0) {
                if (cancelled.getAsBoolean()) {
                    termination = PathResult.Termination.CANCELLED;
                    break;
                }
                if (MonotonicTime.millis() >= deadline) {
                    termination = PathResult.Termination.TIME_LIMIT;
                    break;
                }
            }

            int current = removeLowest();
            closed[current] = true;
            expanded++;
            if (reachedGoal(current)) {
                return build(startNode, current, start, PathResult.Termination.REACHED_GOAL, expanded);
            }
            expand(current);
        }

        return build(startNode, fallback(startNode), start, termination, expanded);
    }

    /**
     * The goal snapped to a flyable cell. Intermediate targets are estimates (chunk center + band Y), so
     * at block resolution they are often inside rock; left as-is, even a region goal wouldn't be reached,
     * and every search would burn the node limit before returning a partial path.
     */
    static Vec3 snappedGoal(AirGrid grid, Vec3 target) {
        long goalCell = grid.nearestFlyable(target, SNAP_CELL_RADIUS);
        return goalCell == AirGrid.NONE ? target
                : grid.center(BlockPos.getX(goalCell), BlockPos.getY(goalCell), BlockPos.getZ(goalCell));
    }

    /**
     * Checked as a <b>horizontal cylinder</b>, not a sphere (vertically, up to {@link #GOAL_VERTICAL_TOLERANCE_BLOCKS} is allowed).
     */
    private boolean reachedGoal(int node) {
        Vec3 center = centerOf(node);
        if (horizon.outside(center.x, center.z)) {
            return true;
        }
        double dx = center.x - goal.x;
        double dz = center.z - goal.z;
        return dx * dx + dz * dz <= goalRadius * goalRadius
                && Math.abs(center.y - goal.y) <= Math.max(goalRadius, GOAL_VERTICAL_TOLERANCE_BLOCKS);
    }

    /**
     * The reached point when the goal wasn't reached. In order of smallest coefficient (= weighing distance traveled more),
     * take the first candidate at least {@link #MIN_USEFUL_PATH_BLOCKS} away from the start.
     */
    private int fallback(int startNode) {
        double threshold = MIN_USEFUL_PATH_BLOCKS * MIN_USEFUL_PATH_BLOCKS;
        Vec3 origin = centerOf(startNode);
        for (int candidate : bestSoFar) {
            if (centerOf(candidate).distanceToSqr(origin) > threshold) {
                return candidate;
            }
        }
        return startNode;
    }

    /**
     * Expand to the 26-neighborhood. A diagonal move is allowed <b>only when the whole 2×2×2 box it spans is flyable</b>.
     * Checking only the 2 end cells produces paths that slip diagonally through rock corners.
     */
    private void expand(int current) {
        long key = cellKey[current];
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!boxClear(x, y, z, dx, dy, dz)) {
                        continue;
                    }
                    relax(current, x + dx, y + dy, z + dz, dx, dy, dz);
                }
            }
        }
    }

    /** Whether all cells a move spans (2 for an axis move, 4 for a face diagonal, 8 for a 3D diagonal) are flyable. */
    private boolean boxClear(int x, int y, int z, int dx, int dy, int dz) {
        for (int stepX = 0; stepX <= Math.abs(dx); stepX++) {
            for (int stepY = 0; stepY <= Math.abs(dy); stepY++) {
                for (int stepZ = 0; stepZ <= Math.abs(dz); stepZ++) {
                    if (!grid.flyable(x + stepX * dx, y + stepY * dy, z + stepZ * dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void relax(int current, int x, int y, int z, int dx, int dy, int dz) {
        int neighbor = node(BlockPos.asLong(x, y, z));
        if (closed[neighbor]) {
            return;
        }
        int cells = grid.cellBlocks();
        double horizontal = Math.sqrt(dx * dx + dz * dz) * cells;
        double vertical = dy * (double) cells;
        double tentative = cost[current] + FlightCosts.segmentTicks(horizontal, vertical, rockets)
                + Clearance.cell(grid, x, y, z, clearancePenaltyTicks);
        if (tentative >= cost[neighbor]) {
            return;
        }

        previous[neighbor] = current;
        cost[neighbor] = tentative;
        combined[neighbor] = tentative + limits.heuristicWeight() * estimate[neighbor];
        if (heapPosition[neighbor] >= 0) {
            siftUp(neighbor);
        } else {
            insert(neighbor);
        }

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = estimate[neighbor] + cost[neighbor] / COEFFICIENTS[i];
            if (bestHeuristic[i] - heuristic > MIN_IMPROVEMENT) {
                bestHeuristic[i] = heuristic;
                bestSoFar[i] = neighbor;
            }
        }
    }

    /**
     * Estimate to the goal. <b>Measures to the edge of the goal region</b>: measuring to the center leaves an estimate on
     * nodes already inside the region (= remaining cost 0), making it inadmissible.
     *
     * <p>The key is clamping to the edge per axis. Previously a flat discount was subtracted from the estimate to the center, but
     * widening the vertical tolerance to {@link #GOAL_VERTICAL_TOLERANCE_BLOCKS} made the discount insufficient, so nodes directly below the goal
     * looked like they "still take 54 ticks", and A* kept digging in another direction.
     *
     * <p>The narrowness surcharge ({@link Clearance}) is not included here. The surcharge is always >= 0, so as long as it's left out
     * the estimate stays a lower bound and A*'s properties don't change.
     */
    private double estimateToGoal(int x, int y, int z) {
        Vec3 center = grid.center(x, y, z);
        double dx = goal.x - center.x;
        double dz = goal.z - center.z;
        double horizontal = Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - goalRadius);
        double verticalTolerance = Math.max(goalRadius, GOAL_VERTICAL_TOLERANCE_BLOCKS);
        double dy = goal.y - center.y;
        double lowerBound = FlightCosts.lowerBoundTicks(horizontal, dy - verticalTolerance, dy + verticalTolerance,
                rockets);
        double guided = guide.estimate(center.x, center.y, center.z);
        return Double.isNaN(guided) ? lowerBound : Math.max(lowerBound, guided);
    }

    private FlightRoute build(int startNode, int endNode, Vec3 start, PathResult.Termination termination,
                              int expanded) {
        if (endNode == startNode) {
            return new FlightRoute(List.of(), termination, expanded, grid.cellBlocks());
        }
        List<Vec3> reversed = new ArrayList<>();
        for (int node = endNode; node != -1; node = previous[node]) {
            reversed.add(centerOf(node));
            if (node == startNode) {
                break;
            }
        }
        Collections.reverse(reversed);
        // The first point is the center of the player's cell, so replace it with the actual position. Leaving it at the center
        // makes the line appear to sprout from beside the player
        reversed.set(0, start);
        List<Vec3> smoothed = FlightSmoother.smooth(reversed, grid, rockets, clearancePenaltyTicks,
                deadline + SMOOTHING_ALLOWANCE_MILLIS);
        return new FlightRoute(List.copyOf(smoothed), termination, expanded, grid.cellBlocks());
    }

    private Vec3 centerOf(int node) {
        long key = cellKey[node];
        return grid.center(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
    }

    /** The node ID for that cell. Created if absent. */
    private int node(long key) {
        int existing = ids.get(key);
        if (existing >= 0) {
            return existing;
        }
        if (nodeCount == cellKey.length) {
            growNodes();
        }
        int id = nodeCount++;
        ids.put(key, id);
        cellKey[id] = key;
        cost[id] = Double.POSITIVE_INFINITY;
        combined[id] = Double.POSITIVE_INFINITY;
        estimate[id] = estimateToGoal(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
        previous[id] = -1;
        heapPosition[id] = -1;
        closed[id] = false;
        return id;
    }

    private void growNodes() {
        int size = cellKey.length << 1;
        cellKey = Arrays.copyOf(cellKey, size);
        cost = Arrays.copyOf(cost, size);
        combined = Arrays.copyOf(combined, size);
        estimate = Arrays.copyOf(estimate, size);
        previous = Arrays.copyOf(previous, size);
        heapPosition = Arrays.copyOf(heapPosition, size);
        closed = Arrays.copyOf(closed, size);
    }

    // --- Open set (a binary heap of IDs; nodes store their position for decrease-key) ---

    private void insert(int node) {
        if (heapSize + 1 == heap.length) {
            heap = Arrays.copyOf(heap, heap.length << 1);
        }
        heapSize++;
        heap[heapSize] = node;
        heapPosition[node] = heapSize;
        siftUp(node);
    }

    private int removeLowest() {
        int result = heap[1];
        heapPosition[result] = -1;
        int last = heap[heapSize];
        heap[heapSize] = 0;
        heapSize--;
        if (heapSize > 0) {
            heap[1] = last;
            heapPosition[last] = 1;
            siftDown(last);
        }
        return result;
    }

    private void siftUp(int node) {
        int index = heapPosition[node];
        double key = combined[node];
        while (index > 1) {
            int parentIndex = index >>> 1;
            int parent = heap[parentIndex];
            if (combined[parent] <= key) {
                break;
            }
            heap[parentIndex] = node;
            heap[index] = parent;
            heapPosition[parent] = index;
            index = parentIndex;
        }
        heap[index] = node;
        heapPosition[node] = index;
    }

    private void siftDown(int node) {
        int index = heapPosition[node];
        double key = combined[node];
        while (true) {
            int childIndex = index << 1;
            if (childIndex > heapSize) {
                break;
            }
            int child = heap[childIndex];
            if (childIndex < heapSize && combined[heap[childIndex + 1]] < combined[child]) {
                childIndex++;
                child = heap[childIndex];
            }
            if (key <= combined[child]) {
                break;
            }
            heap[index] = child;
            heap[childIndex] = node;
            heapPosition[child] = index;
            index = childIndex;
        }
        heap[index] = node;
        heapPosition[node] = index;
    }
}
