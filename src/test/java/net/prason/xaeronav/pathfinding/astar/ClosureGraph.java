package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;
import java.util.BitSet;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>Experimental.</b> A graph holding every edge that layer 3's move generation builds inside a box.
 *
 * <p>Sweeps the box from the start with weight 0 (plain Dijkstra) and collects edges via {@link EdgeSink}.
 * Countermeasures for the pitfalls hit in the 2026-09-16 upper-bound measurement are built in from the start:
 * <ul>
 * <li><b>Don't narrow the box's Y</b> (the caller passes the world height). Narrowed, it doesn't know about routes
 *     that go under the void in the End and overestimates the remaining cost</li>
 * <li><b>Put the closure's goal in the same column as the real goal</b>. {@code BuildMoves#addBridge} only builds bridges
 *     over the void in the direction that reduces L1 distance to the goal, so placing a different point drops bridges
 *     toward the real goal from the graph. Only Y is shifted outside the world so it isn't cut off on arrival</li>
 * <li><b>Make the horizontal margin wider than the search window</b>. Where it overflows, the guide returns 0 and falls to the geometric lower bound</li>
 * </ul>
 */
final class ClosureGraph {

    final Long2IntOpenHashMap walking = new Long2IntOpenHashMap();
    final Long2IntOpenHashMap boating = new Long2IntOpenHashMap();
    final IntArrayList xs = new IntArrayList();
    final IntArrayList ys = new IntArrayList();
    final IntArrayList zs = new IntArrayList();
    final IntArrayList from = new IntArrayList();
    final IntArrayList to = new IntArrayList();
    final FloatArrayList cost = new FloatArrayList();
    /** Node kinds ({@link #NATURAL}, {@link #DIG}, {@link #AIR}). */
    final it.unimi.dsi.fastutil.bytes.ByteArrayList category = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
    long buildMillis;

    /** Standable without digging (floor, water, or something climbable underfoot). */
    static final byte NATURAL = 0;
    /** Can't be entered without digging one of the body's two cells. */
    static final byte DIG = 1;
    /** Enterable without digging, but with no footing (on a placed block, mid-bridge, falling). */
    static final byte AIR = 2;

    private CellSource source;

    private ClosureGraph() {
        walking.defaultReturnValue(-1);
        boating.defaultReturnValue(-1);
    }

    int nodes() {
        return xs.size();
    }

    long edges() {
        return from.size();
    }

    /** A box: the start/destination bounding rectangle widened horizontally by {@code margin}, with Y spanning {@code minY..maxY}. */
    static SearchBounds box(CellSource all, BlockPos start, BlockPos goal, int margin, int minY, int maxY) {
        SearchBounds world = all.bounds();
        return new SearchBounds(
                Math.max(world.minX(), Math.min(start.getX(), goal.getX()) - margin), minY,
                Math.max(world.minZ(), Math.min(start.getZ(), goal.getZ()) - margin),
                Math.min(world.maxX(), Math.max(start.getX(), goal.getX()) + margin), maxY,
                Math.min(world.maxZ(), Math.max(start.getZ(), goal.getZ()) + margin));
    }

    /**
     * @param start the start, snapped to a standable position
     * @param goal  the destination, snapped to a standable position (used only to match bridge direction with production)
     */
    static ClosureGraph build(CellSource all, BlockPos start, BlockPos goal, SearchBounds box) {
        return build(all, start, goal, box, false);
    }

    /** @param naturalOnly if true, expands only naturally standable points (a cheap closure that doesn't sweep the volume) */
    static ClosureGraph build(CellSource all, BlockPos start, BlockPos goal, SearchBounds box, boolean naturalOnly) {
        long began = System.currentTimeMillis();
        CellSource view = new WindowedCells(all, start, 1 << 28, box);
        ClosureGraph graph = new ClosureGraph();
        graph.source = view;
        AStarPathfinder closure = new AStarPathfinder(view, new SearchLimits(Integer.MAX_VALUE, 3_600_000L, 0.0));
        if (naturalOnly) {
            closure.expandFilter((x, y, z) -> classify(view, x, y, z) == NATURAL);
        }
        closure.edgeSink((fx, fy, fz, fb, tx, ty, tz, tb, edgeCost, kind) -> {
            int a = graph.id(fb, fx, fy, fz);
            int b = graph.id(tb, tx, ty, tz);
            boolean surfacing = ty > fy && Math.abs(tx - fx) + Math.abs(tz - fz) <= 1;
            boolean submerged = CellData.water(view.cell(tx, ty + 1, tz)) && !surfacing;
            graph.from.add(a);
            graph.to.add(b);
            graph.cost.add((float) (submerged ? edgeCost * ActionCosts.SUBMERGED_TRAVEL_PENALTY : edgeCost));
        });
        PathResult result = closure.search(start,
                new BlockPos(goal.getX(), box.maxY() + 10_000, goal.getZ()), () -> false);
        if (result.termination() != PathResult.Termination.EXHAUSTED) {
            throw new IllegalStateException("closure didn't finish sweeping: " + result.termination());
        }
        graph.buildMillis = System.currentTimeMillis() - began;
        return graph;
    }

    private int id(boolean boat, int x, int y, int z) {
        Long2IntOpenHashMap ids = boat ? boating : walking;
        long key = BlockPos.asLong(x, y, z);
        int id = ids.get(key);
        if (id < 0) {
            id = xs.size();
            ids.put(key, id);
            xs.add(x);
            ys.add(y);
            zs.add(z);
            category.add(classify(source, x, y, z));
        }
        return id;
    }

    static byte classify(CellSource cells, int x, int y, int z) {
        long feet = cells.cell(x, y, z);
        if (!CellData.occupiableWithoutDigging(feet) || !CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
            return DIG;
        }
        boolean surfaceWater = CellData.water(feet) && !CellData.water(cells.cell(x, y + 1, z));
        if (CellData.standable(cells.cell(x, y - 1, z)) && !CellData.water(feet) || surfaceWater
                || CellData.climbable(feet)) {
            return NATURAL;
        }
        return AIR;
    }

    /** Node count per kind. */
    int[] categoryCounts() {
        int[] counts = new int[3];
        for (int i = 0; i < category.size(); i++) {
            counts[category.getByte(i)]++;
        }
        return counts;
    }

    /** Keeps only edges whose both ends are kinds included in {@code allowed}. {@code allowed} is a bit per kind. */
    BitSet edgesBetween(int allowed) {
        BitSet kept = new BitSet(from.size());
        for (int e = 0; e < from.size(); e++) {
            if ((allowed >> category.getByte(from.getInt(e)) & 1) != 0
                    && (allowed >> category.getByte(to.getInt(e)) & 1) != 0) {
                kept.set(e);
            }
        }
        return kept;
    }

    /**
     * Edges keeping only nodes within horizontal {@code r} and vertical {@code k} of a naturally standable point. Since
     * this only thins out edges, the remaining cost stays at or above the real one (on the overestimating side).
     */
    BitSet shellEdges(int r, int k) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (int i = 0; i < nodes(); i++) {
            minX = Math.min(minX, xs.getInt(i));
            maxX = Math.max(maxX, xs.getInt(i));
            minZ = Math.min(minZ, zs.getInt(i));
            maxZ = Math.max(maxZ, zs.getInt(i));
            minY = Math.min(minY, ys.getInt(i));
        }
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        // Per column, bits for the heights that may be kept (Y counted from the minimum, up to 512)
        long[] allowed = new long[sizeX * sizeZ * 8];
        for (int i = 0; i < nodes(); i++) {
            if (category.getByte(i) != NATURAL) {
                continue;
            }
            int x = xs.getInt(i);
            int z = zs.getInt(i);
            int y = ys.getInt(i) - minY;
            for (int cx = Math.max(minX, x - r); cx <= Math.min(maxX, x + r); cx++) {
                for (int cz = Math.max(minZ, z - r); cz <= Math.min(maxZ, z + r); cz++) {
                    int base = ((cx - minX) + (cz - minZ) * sizeX) * 8;
                    for (int dy = Math.max(0, y - k); dy <= Math.min(511, y + k); dy++) {
                        allowed[base + (dy >> 6)] |= 1L << dy;
                    }
                }
            }
        }
        BitSet keptNodes = new BitSet(nodes());
        for (int i = 0; i < nodes(); i++) {
            int y = ys.getInt(i) - minY;
            int base = ((xs.getInt(i) - minX) + (zs.getInt(i) - minZ) * sizeX) * 8;
            if (category.getByte(i) == NATURAL || y < 512 && (allowed[base + (y >> 6)] >> y & 1) != 0) {
                keptNodes.set(i);
            }
        }
        BitSet kept = new BitSet(from.size());
        for (int e = 0; e < from.size(); e++) {
            if (keptNodes.get(from.getInt(e)) && keptNodes.get(to.getInt(e))) {
                kept.set(e);
            }
        }
        return kept;
    }

    private int[] reverseStart;
    private int[] reversePredecessor;
    private float[] reverseWeight;

    private void ensureReverse() {
        if (reverseStart != null) {
            return;
        }
        int n = nodes();
        int m = from.size();
        int[] start = new int[n + 1];
        for (int e = 0; e < m; e++) {
            start[to.getInt(e) + 1]++;
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int[] fill = Arrays.copyOf(start, n);
        int[] predecessor = new int[m];
        float[] weight = new float[m];
        for (int e = 0; e < m; e++) {
            int slot = fill[to.getInt(e)]++;
            predecessor[slot] = from.getInt(e);
            weight[slot] = cost.getFloat(e);
        }
        reverseStart = start;
        reversePredecessor = predecessor;
        reverseWeight = weight;
    }

    /** Value for points outside the window. */
    @FunctionalInterface
    interface OutsideValue {
        double at(int x, int y, int z);
    }

    /**
     * A guide that runs reverse Dijkstra with real edges only inside the window (a square of horizontal {@code radius}
     * around the player), puts {@code outside}'s values at the far ends of edges leaving the window, and returns
     * {@code outside} directly for points outside the window.
     */
    CostToGo windowGuide(BlockPos goal, BlockPos player, int radius, OutsideValue outside) {
        ensureReverse();
        int n = nodes();
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        MinHeap heap = new MinHeap();
        int goalId = idAt(goal.getX(), goal.getY(), goal.getZ());
        if (goalId >= 0 && inWindow(goalId, player, radius)) {
            distance[goalId] = 0.0;
            heap.push(0.0, goalId);
        }
        // Seed with points outside the window that have edges coming in from points inside the window
        for (int v = 0; v < n; v++) {
            if (inWindow(v, player, radius)) {
                continue;
            }
            boolean entered = false;
            for (int slot = reverseStart[v]; slot < reverseStart[v + 1]; slot++) {
                if (inWindow(reversePredecessor[slot], player, radius)) {
                    entered = true;
                    break;
                }
            }
            if (!entered) {
                continue;
            }
            double value = outside.at(xs.getInt(v), ys.getInt(v), zs.getInt(v));
            if (Double.isFinite(value) && value < distance[v]) {
                distance[v] = value;
                heap.push(value, v);
            }
        }
        while (!heap.isEmpty()) {
            double d = heap.topKey();
            int node = heap.pop();
            if (d > distance[node]) {
                continue;
            }
            for (int slot = reverseStart[node]; slot < reverseStart[node + 1]; slot++) {
                int p = reversePredecessor[slot];
                if (!inWindow(p, player, radius)) {
                    continue;
                }
                double candidate = d + reverseWeight[slot];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        OutsideValue finiteOutside = (x, y, z) -> {
            double value = outside.at(x, y, z);
            return Double.isFinite(value) ? value
                    : Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        };
        return (x, y, z) -> {
            if (Math.abs(x - player.getX()) > radius || Math.abs(z - player.getZ()) > radius) {
                return finiteOutside.at(x, y, z);
            }
            int id = idAt(x, y, z);
            if (id >= 0 && Double.isFinite(distance[id])) {
                return distance[id];
            }
            double near = nearestValue(distance, 3, x, y, z);
            return Double.isFinite(near) ? near : finiteOutside.at(x, y, z);
        };
    }

    private boolean inWindow(int id, BlockPos player, int radius) {
        return Math.abs(xs.getInt(id) - player.getX()) <= radius && Math.abs(zs.getInt(id) - player.getZ()) <= radius;
    }

    /** The id of the walking state, preferred, or else the boat state. -1 if neither. */
    int idAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int id = walking.get(key);
        return id >= 0 ? id : boating.get(key);
    }

    /**
     * Computes the remaining cost to the destination for every node. If {@code kept} isn't {@code null},
     * only the edges set there are used.
     */
    double[] distancesTo(BlockPos goal, BitSet kept) {
        return distancesTo(goal, kept, new IntArrayList(), new IntArrayList(), new FloatArrayList());
    }

    /** Uses the edge {@code extraFrom->extraTo} (cost {@code extraCost}) in addition to the edges in {@code kept}. */
    double[] distancesTo(BlockPos goal, BitSet kept, IntArrayList extraFrom, IntArrayList extraTo,
                         FloatArrayList extraCost) {
        int n = nodes();
        int m = from.size();
        int[] start = new int[n + 1];
        for (int e = 0; e < m; e++) {
            if (kept == null || kept.get(e)) {
                start[to.getInt(e) + 1]++;
            }
        }
        for (int e = 0; e < extraTo.size(); e++) {
            start[extraTo.getInt(e) + 1]++;
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int[] fill = Arrays.copyOf(start, n);
        int[] predecessor = new int[start[n]];
        float[] weight = new float[start[n]];
        for (int e = 0; e < m; e++) {
            if (kept == null || kept.get(e)) {
                int slot = fill[to.getInt(e)]++;
                predecessor[slot] = from.getInt(e);
                weight[slot] = cost.getFloat(e);
            }
        }
        for (int e = 0; e < extraTo.size(); e++) {
            int slot = fill[extraTo.getInt(e)]++;
            predecessor[slot] = extraFrom.getInt(e);
            weight[slot] = extraCost.getFloat(e);
        }
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        MinHeap heap = new MinHeap();
        long key = BlockPos.asLong(goal.getX(), goal.getY(), goal.getZ());
        for (int seed : new int[] {walking.get(key), boating.get(key)}) {
            if (seed >= 0) {
                distance[seed] = 0.0;
                heap.push(0.0, seed);
            }
        }
        if (heap.isEmpty()) {
            throw new IllegalStateException("closure doesn't reach the destination: " + goal.toShortString());
        }
        while (!heap.isEmpty()) {
            double d = heap.topKey();
            int node = heap.pop();
            if (d > distance[node]) {
                continue;
            }
            for (int slot = start[node]; slot < start[node + 1]; slot++) {
                int p = predecessor[slot];
                double candidate = d + weight[slot];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        return distance;
    }

    /** Looks up the remaining-cost table as a guide. */
    CostToGo guide(double[] distance) {
        return new Table(this, distance);
    }

    /**
     * A guide that looks up, by real coordinates, a table built in another world (e.g. a world rebuilt from Xaero's map).
     * If the looked-up coordinate isn't in the graph, the minimum of values extended by the geometric lower bound from nodes within {@code reach} blocks.
     */
    CostToGo nearestGuide(double[] distance, int reach) {
        return (x, y, z) -> {
            if (idAt(x, y, z) < 0 && !Double.isFinite(nearestValue(distance, reach, x, y, z))) {
                // Outside the graph (outside the box, or where the closure doesn't reach) is left to the geometric lower bound
                return 0.0;
            }
            double value = nearestValue(distance, reach, x, y, z);
            // Avoid points that are in the graph but have no value (thinned-out kinds, dead ends). Returning 0 would attract the search
            return Double.isFinite(value) ? value : 1.0e7;
        };
    }

    /** Same lookup as {@link #nearestGuide}, but {@link Double#POSITIVE_INFINITY} if nothing is found. Used to seed the window boundary. */
    double nearestValue(double[] distance, int reach, int x, int y, int z) {
        double value = exact(this, distance, x, y, z);
        if (Double.isFinite(value)) {
            return value;
        }
        double bestValue = Double.POSITIVE_INFINITY;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    int id = idAt(x + dx, y + dy, z + dz);
                    if (id >= 0 && Double.isFinite(distance[id])) {
                        bestValue = Math.min(bestValue, distance[id] + Heuristic.estimate(x, y, z,
                                x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return bestValue;
    }

    /** This cell's value within the closure. {@code NaN} if outside the closure. */
    static double exact(ClosureGraph graph, double[] distance, int x, int y, int z) {
        int id = graph.idAt(x, y, z);
        return id < 0 ? Double.NaN : distance[id];
    }

    private record Table(ClosureGraph graph, double[] distance) implements CostToGo {

        /** A coordinate not in the closure. Left to the geometric lower bound. */
        private static final double UNKNOWN = 0.0;

        /**
         * A coordinate in the closure that can't reach the destination. Infinity would make {@code selectFallback}'s scoring
         * NaN, so it's a finite value higher than any path.
         */
        private static final double DEAD_END = 1.0e7;

        @Override
        public double estimate(int x, int y, int z) {
            double value = exact(graph, distance, x, y, z);
            if (Double.isNaN(value)) {
                // A standing spot not in the closure, created by dug or placed blocks. Returning 0 would attract the search and
                // endpoint selection there, so extend from nearby points' values
                double near = graph.nearestValue(distance, 3, x, y, z);
                return Double.isFinite(near) ? near : UNKNOWN;
            }
            return Double.isInfinite(value) ? DEAD_END : value;
        }
    }

    /** A binary heap that allows duplicates (lazy deletion). */
    static final class MinHeap {
        private double[] keys = new double[1024];
        private int[] values = new int[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        double topKey() {
            return keys[0];
        }

        void push(double key, int value) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (keys[parent] <= key) {
                    break;
                }
                keys[i] = keys[parent];
                values[i] = values[parent];
                i = parent;
            }
            keys[i] = key;
            values[i] = value;
        }

        int pop() {
            int top = values[0];
            size--;
            if (size > 0) {
                double key = keys[size];
                int value = values[size];
                int i = 0;
                while (true) {
                    int child = 2 * i + 1;
                    if (child >= size) {
                        break;
                    }
                    if (child + 1 < size && keys[child + 1] < keys[child]) {
                        child++;
                    }
                    if (keys[child] >= key) {
                        break;
                    }
                    keys[i] = keys[child];
                    values[i] = values[child];
                    i = child;
                }
                keys[i] = key;
                values[i] = value;
            }
            return top;
        }
    }
}
