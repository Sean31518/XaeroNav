package net.prason.xaeronav.pathfinding.navgraph;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Remaining cost to the goal within the window. Built by running Dijkstra backwards over the {@link NavGraph} edges.
 *
 * <p>Edges leaving the window, or into sections not yet built, are seeded with the {@link FarField} value at their far end.
 * Points whose outside value is unknown are not seeded (the {@link FarField} contract).
 *
 * <p>Read-only after construction, so it may be queried from multiple threads.
 */
public final class WindowField implements CostToGo {

    /**
     * Range (blocks) searched when extending a value from nearby nodes to a point not in the graph.
     *
     * <p><b>The guide must not return 0.</b> Standing spots created by mined or placed blocks are not in the graph,
     * and returning 0 there pulls the search and partial-path endpoint selection toward them (measured: even a perfect guide dropped to 1.30x).
     */
    private static final int NEAREST_REACH = 3;

    /**
     * Points within this width (blocks) of the window edge get the outside value directly.
     *
     * <p>The search never creates moves into unloaded cells, so edge sections have no edges leaving the window at all.
     * Seeding only the far ends of edges would leave no seeds, and the guide inside the window would fall back entirely to outside values
     * (measured: 2.651x on long wide-area routes).
     */
    private static final int EDGE_SEED_BAND = 2;

    /**
     * Values within this width (blocks) of the window edge are considered to come from the {@link FarField} estimate
     * ({@link #measuredInWindow}). Edge sections are seeded with outside values at the far end of edges leaving them
     * ({@link #EDGE_SEED_BAND}), so the estimate persists in the values around them.
     */
    private static final int EDGE_MARGIN_BLOCKS = 32;

    /** Size of the chunks counted in parallel (in sections). */
    private static final int SLOTS_PER_TASK = 32;

    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle DOUBLES = MethodHandles.arrayElementVarHandle(double[].class);

    /** Minimum number of incoming edges for processing Dial buckets in parallel. Below this, the parallelization overhead costs more. */
    private static final long PARALLEL_BUCKET_EDGES = 1024;

    /** Number of nodes handled by one chunk in a parallel bucket. */
    private static final int NODES_PER_TASK = 128;

    /** Outside the window, or a section not yet built. */
    private static final int OUTSIDE = -1;
    /** Inside a built section, but not a node (outside the shell). */
    private static final int NOT_A_NODE = -2;

    private final BlockPos goal;
    private final FarField far;
    private final Index index;
    private final MoveTable.View moves;
    private final double[] distance;
    private final int edges;
    private final long buildMillis;
    private final boolean goalCut;
    private final int centerX;
    private final int centerZ;
    private final int radius;

    private WindowField(BlockPos goal, FarField far, Index index, MoveTable.View moves, double[] distance, int edges,
                        long buildMillis, boolean goalCut, int centerX, int centerZ, int radius) {
        this.moves = moves;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.goal = goal;
        this.far = far;
        this.index = index;
        this.distance = distance;
        this.edges = edges;
        this.buildMillis = buildMillis;
        this.goalCut = goalCut;
    }

    public BlockPos goal() {
        return goal;
    }

    /** Window center and radius (blocks). */
    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public int nodes() {
        return distance.length;
    }

    public int edges() {
        return edges;
    }

    public long buildMillis() {
        return buildMillis;
    }

    /** Approximate byte size of the arrays kept after construction. */
    public long bytes() {
        return 8L * distance.length + index.bytes();
    }

    /**
     * Large arrays used only during construction. Recreating tens of millions of elements per segment would produce tens of MB of garbage each time.
     * The finished guide does not reference these, so the next construction may overwrite them.
     */
    static final class Buffers {
        private int[] start = new int[0];
        private int[] position = new int[0];
        private char[] inMove = new char[0];
        private final BucketQueue queue = new BucketQueue();

        int[] start(int size) {
            if (start.length < size) {
                start = new int[size + size / 4];
            }
            Arrays.fill(start, 0, size, 0);
            return start;
        }

        int[] position(int size) {
            if (position.length < size) {
                position = new int[size + size / 4];
            }
            return position;
        }

        char[] inMove(int size) {
            if (inMove.length < size) {
                inMove = new char[size + size / 4];
            }
            return inMove;
        }

        BucketQueue queue(int buckets, int items) {
            queue.clear(buckets, items);
            return queue;
        }

        long bytes() {
            return 4L * start.length + 4L * position.length + 2L * inMove.length + queue.bytes();
        }
    }

    /**
     * The window's sections and the sequential numbering of their nodes. The nodes of section {@code s} are
     * {@code offsets[s]..offsets[s+1]}.
     */
    private static final class Index {
        final long[] keys;
        final SectionEdges[] sections;
        final Long2IntOpenHashMap slotOf;
        final int[] offsets;
        /** Index of the neighboring section (-1 to 1 on each axis) of section {@code s}, or -1 if none. */
        final int[] neighbor;
        /**
         * Sections below this are not in the graph ({@link NavGraph#floorBelow}). Treating them as outside the window ({@link #OUTSIDE}) would
         * create a false shortcut that drops down in the middle of the window and reads the outside estimate, so they are treated as non-nodes.
         */
        final int lowSectionY;

        Index(long[] keys, SectionEdges[] sections, Long2IntOpenHashMap slotOf, int[] offsets, int[] neighbor,
              int lowSectionY) {
            this.keys = keys;
            this.sections = sections;
            this.slotOf = slotOf;
            this.offsets = offsets;
            this.neighbor = neighbor;
            this.lowSectionY = lowSectionY;
        }

        /** Node index of the point ({@code x},{@code y},{@code z}) relative to the origin of section {@code slot}. */
        int resolve(int slot, int x, int y, int z) {
            if (((x | y | z) & ~15) == 0) {
                // Most edges stay within the same section
                int node = sections[slot].nodeOf(x | z << 4 | y << 8);
                return node < 0 ? NOT_A_NODE : offsets[slot] + node;
            }
            int sdx = x >> 4;
            int sdy = y >> 4;
            int sdz = z >> 4;
            int target;
            if (isNear(sdx) && isNear(sdy) && isNear(sdz)) {
                target = neighbor[slot * 27 + (sdx + 1) * 9 + (sdy + 1) * 3 + sdz + 1];
            } else {
                long key = keys[slot];
                target = slotOf.get(NavGraph.key(BlockPos.getX(key) + sdx, BlockPos.getY(key) + sdy,
                        BlockPos.getZ(key) + sdz));
            }
            if (target < 0) {
                return BlockPos.getY(keys[slot]) + sdy < lowSectionY ? NOT_A_NODE : OUTSIDE;
            }
            int node = sections[target].nodeOf(x & 15 | (z & 15) << 4 | (y & 15) << 8);
            return node < 0 ? NOT_A_NODE : offsets[target] + node;
        }

        private static boolean isNear(int d) {
            return d >= -1 && d <= 1;
        }

        int resolveAbsolute(int x, int y, int z) {
            int slot = slotOf.get(NavGraph.key(Math.floorDiv(x, SectionMoves.SIZE), Math.floorDiv(y, SectionMoves.SIZE),
                    Math.floorDiv(z, SectionMoves.SIZE)));
            if (slot < 0) {
                return Math.floorDiv(y, SectionMoves.SIZE) < lowSectionY ? NOT_A_NODE : OUTSIDE;
            }
            int node = sections[slot].nodeOf(SectionEdges.local(x, y, z));
            return node < 0 ? NOT_A_NODE : offsets[slot] + node;
        }

        long bytes() {
            return 8L * keys.length + 12L * sections.length + 4L * offsets.length + 4L * neighbor.length;
        }
    }

    static @Nullable WindowField build(NavGraph graph, Buffers buffers, int centerX, int centerZ, int radius,
                                       FarField givenFar, Parallel parallel, BooleanSupplier cancelled) {
        long began = MonotonicTime.millis();
        BlockPos goal = graph.goal();
        // Treat goals near the edge as outside the window. Edge sections are built provisionally without their surroundings loaded, so no edges into the goal get generated,
        // and despite being inside the window it is judged unreachable and falls back to the legacy search (measured: two End segments with the goal 5 blocks from the edge, 1.022→1.050x)
        boolean goalInWindow = Math.abs(goal.getX() - centerX) <= radius - NavGraph.READ_MARGIN
                && Math.abs(goal.getZ() - centerZ) <= radius - NavGraph.READ_MARGIN;
        FarField far = goalInWindow ? givenFar.whenGoalInside() : givenFar;
        LongArrayList keyList = new LongArrayList();
        graph.forEachWindowSection(centerX, centerZ, radius, (sx, sy, sz) -> {
            long key = NavGraph.key(sx, sy, sz);
            if (graph.section(key) != null) {
                keyList.add(key);
            }
        });
        long[] keys = keyList.toLongArray();
        int slots = keys.length;
        SectionEdges[] sections = new SectionEdges[slots];
        Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap(slots);
        slotOf.defaultReturnValue(-1);
        int[] offsets = new int[slots + 1];
        int insideEdges = 0;
        for (int s = 0; s < slots; s++) {
            SectionEdges edges = graph.section(keys[s]);
            // If it was discarded mid-construction (chunk update), treat it the same as not yet built
            sections[s] = edges == null ? SectionEdges.EMPTY : edges;
            slotOf.put(keys[s], s);
            offsets[s + 1] = offsets[s] + sections[s].nodes;
            insideEdges += sections[s].inSize();
        }
        // Edge move indices are registered in the table before the section is recorded. Taking the table after collecting all sections resolves them all
        MoveTable.View moves = graph.moves().view();
        int[] neighbor = new int[slots * 27];
        for (int s = 0; s < slots; s++) {
            int sx = BlockPos.getX(keys[s]);
            int sy = BlockPos.getY(keys[s]);
            int sz = BlockPos.getZ(keys[s]);
            for (int k = 0; k < 27; k++) {
                neighbor[s * 27 + k] = slotOf.get(NavGraph.key(sx + k / 9 - 1, sy + k / 3 % 3 - 1, sz + k % 3 - 1));
            }
        }
        Index index = new Index(keys, sections, slotOf, offsets, neighbor, graph.lowSectionY());

        int n = offsets[slots];
        int[] position = buffers.position(n);
        for (int s = 0; s < slots; s++) {
            sections[s].positions(position, offsets[s]);
        }
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int goalId = index.resolveAbsolute(goal.getX(), goal.getY(), goal.getZ());
        if (goalId >= 0) {
            distance[goalId] = 0.0;
        }
        AtomicBoolean goalEntered = new AtomicBoolean();

        // Pass 1: count incoming edges that cross sections inside the window per destination (start[dest+2]), and seed edges leaving the window.
        // Incoming edges closed within a section are recorded by the section (SectionEdges#inFirst).
        // Seeds are only written to the section's own nodes, so only the counting needs atomic adds when parallelized
        int[] start = buffers.start(n + 2);
        boolean concurrent = parallel.workers() > 1;
        boolean counted = parallel.forEach(slots, SLOTS_PER_TASK, cancelled, (fromSlot, toSlot) -> {
            for (int s = fromSlot; s < toSlot; s++) {
                SectionEdges section = sections[s];
                long key = keys[s];
                int baseX = BlockPos.getX(key) * SectionMoves.SIZE;
                int baseY = BlockPos.getY(key) * SectionMoves.SIZE;
                int baseZ = BlockPos.getZ(key) * SectionMoves.SIZE;
                for (int i = 0; i < section.nodes; i++) {
                    int from = offsets[s] + i;
                    int local = position[from];
                    int lx = local & 15;
                    int ly = local >> 8 & 15;
                    int lz = local >> 4 & 15;
                    if (Math.abs(baseX + lx - centerX) > radius - EDGE_SEED_BAND
                            || Math.abs(baseZ + lz - centerZ) > radius - EDGE_SEED_BAND) {
                        double value = far.at(baseX + lx, baseY + ly, baseZ + lz);
                        if (Double.isFinite(value)) {
                            distance[from] = Math.min(distance[from], value);
                        }
                    }
                    for (int e = section.first(i), end = section.end(i); e < end; e++) {
                        int m = section.move[e];
                        int tx = lx + moves.dx[m];
                        int ty = ly + moves.dy[m];
                        int tz = lz + moves.dz[m];
                        if (baseX + tx == goal.getX() && baseY + ty == goal.getY() && baseZ + tz == goal.getZ()) {
                            // The goal itself may lie outside the shell (in a cell that is not expanded). The edge into it is the cost to the goal itself
                            distance[from] = Math.min(distance[from], moves.cost[m]);
                            goalEntered.set(true);
                        }
                        if (((tx | ty | tz) & ~15) == 0) {
                            continue;
                        }
                        int target = index.resolve(s, tx, ty, tz);
                        if (target >= 0) {
                            if (concurrent) {
                                INTS.getAndAdd(start, target + 2, 1);
                            } else {
                                start[target + 2]++;
                            }
                        } else if (target == OUTSIDE) {
                            double value = far.at(baseX + tx, baseY + ty, baseZ + tz);
                            if (Double.isFinite(value)) {
                                distance[from] = Math.min(distance[from], moves.cost[m] + value);
                            }
                        }
                    }
                }
            }
            return true;
        });
        if (!counted) {
            return null;
        }
        for (int i = 2; i <= n + 1; i++) {
            start[i] += start[i - 1];
        }
        int m = start[n + 1];
        char[] inMove = buffers.inMove(m);
        // Pass 2: per destination, fill in the moves of incoming edges that cross sections. The origin is found by undoing the move from the destination.
        // Parallelizing changes the order of incoming edges, but not the distances
        boolean filled = parallel.forEach(slots, SLOTS_PER_TASK, cancelled, (fromSlot, toSlot) -> {
            for (int s = fromSlot; s < toSlot; s++) {
                SectionEdges section = sections[s];
                for (int i = 0; i < section.nodes; i++) {
                    int local = position[offsets[s] + i];
                    for (int e = section.first(i), end = section.end(i); e < end; e++) {
                        int move = section.move[e];
                        int tx = (local & 15) + moves.dx[move];
                        int ty = (local >> 8 & 15) + moves.dy[move];
                        int tz = (local >> 4 & 15) + moves.dz[move];
                        if (((tx | ty | tz) & ~15) == 0) {
                            continue;
                        }
                        int target = index.resolve(s, tx, ty, tz);
                        if (target >= 0) {
                            int slot = concurrent ? (int) INTS.getAndAdd(start, target + 1, 1) : start[target + 1]++;
                            inMove[slot] = (char) move;
                        }
                    }
                }
            }
            return true;
        });
        if (!filled) {
            return null;
        }
        // Once filled, start[t]..start[t+1] are the edges into destination t
        for (int s = 0; s < slots; s++) {
            for (int i = offsets[s]; i < offsets[s + 1]; i++) {
                position[i] |= s << 12;
            }
        }

        double base = Double.POSITIVE_INFINITY;
        double top = Double.NEGATIVE_INFINITY;
        int seeds = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(distance[i])) {
                base = Math.min(base, distance[i]);
                top = Math.max(top, distance[i]);
                seeds++;
            }
        }
        BitSet settled = new BitSet(n);
        if (Double.isFinite(base)) {
            // Every assigned move costs at least this width, so simply emptying the buckets from the front yields the settle order.
            // This minimum includes moves of sections outside the window, but a narrower width does not affect correctness
            double width = moves.minCost;
            BucketQueue queue = buffers.queue((int) ((top - base) / width) + 1, seeds);
            for (int i = 0; i < n; i++) {
                if (Double.isFinite(distance[i])) {
                    queue.push((int) ((distance[i] - base) / width), i);
                }
            }
            if (!settle(queue, settled, distance, position, start, inMove, index, moves, base, width, parallel,
                    cancelled)) {
                return null;
            }
        }
        return new WindowField(goal, far, index, moves, distance, m + insideEdges, MonotonicTime.millis() - began,
                goalInWindow && !goalEntered.get(), centerX, centerZ, radius);
    }

    /**
     * Settle distances by emptying the buckets from the front (Dial's algorithm).
     *
     * <p>Large buckets relax their nodes in parallel. Every edge costs at least the bucket width, so relaxing from a node in a bucket
     * always lands in a later bucket, and nodes in the same bucket do not use each other's values (improvements that round back into
     * the same bucket are picked up by processing that bucket again). Distances are only written when they decrease, so parallel runs
     * converge to the same shortest distances as a single-threaded run; each path's value is summed in the same order from the goal side, so they match bit for bit.
     *
     * @return {@code false} if aborted
     */
    private static boolean settle(BucketQueue queue, BitSet settled, double[] distance, int[] position, int[] start,
                                  char[] inMove, Index index, MoveTable.View moves, double base, double width,
                                  Parallel parallel, BooleanSupplier cancelled) {
        boolean concurrent = parallel.workers() > 1;
        int[] frontier = new int[1024];
        int cursor = 0;
        while (cursor >= 0) {
            int size = 0;
            long edges = 0;
            for (int node = queue.pop(cursor); node >= 0; node = queue.pop(cursor)) {
                if (settled.get(node)) {
                    continue;
                }
                settled.set(node);
                if (size == frontier.length) {
                    frontier = Arrays.copyOf(frontier, size * 2);
                }
                frontier[size++] = node;
                int slot = position[node] >>> 12;
                int own = node - index.offsets[slot];
                edges += start[node + 1] - start[node] + index.sections[slot].inEnd(own)
                        - index.sections[slot].inFirst(own);
            }
            if (size == 0) {
                cursor = queue.nextNonEmpty(cursor + 1);
                continue;
            }
            if (cancelled.getAsBoolean()) {
                return false;
            }
            int bucket = cursor;
            if (!concurrent || edges < PARALLEL_BUCKET_EDGES) {
                for (int i = 0; i < size; i++) {
                    int node = frontier[i];
                    double d = distance[node];
                    int packed = position[node];
                    int slot = packed >>> 12;
                    int lx = packed & 15;
                    int ly = packed >> 8 & 15;
                    int lz = packed >> 4 & 15;
                    SectionEdges section = index.sections[slot];
                    int offset = index.offsets[slot];
                    int own = node - offset;
                    for (int k = section.inFirst(own), end = section.inEnd(own); k < end; k++) {
                        int move = section.inMove[k];
                        int p = offset + section.nodeOf(lx - moves.dx[move] | lz - moves.dz[move] << 4
                                | ly - moves.dy[move] << 8);
                        double candidate = d + moves.cost[move];
                        if (candidate < distance[p]) {
                            distance[p] = candidate;
                            // An improvement that rounded back into the same bucket: unsettle it and solve again
                            settled.clear(p);
                            queue.push(Math.max(bucket, (int) ((candidate - base) / width)), p);
                        }
                    }
                    for (int k = start[node]; k < start[node + 1]; k++) {
                        int move = inMove[k];
                        int p = index.resolve(slot, lx - moves.dx[move], ly - moves.dy[move], lz - moves.dz[move]);
                        double candidate = d + moves.cost[move];
                        if (candidate < distance[p]) {
                            distance[p] = candidate;
                            settled.clear(p);
                            queue.push(Math.max(bucket, (int) ((candidate - base) / width)), p);
                        }
                    }
                }
                continue;
            }
            int[] nodes = frontier;
            ConcurrentLinkedQueue<int[]> improved = new ConcurrentLinkedQueue<>();
            boolean relaxed = parallel.forEach(size, NODES_PER_TASK, cancelled, (from, to) -> {
                IntArrayList lowered = new IntArrayList();
                for (int i = from; i < to; i++) {
                    int node = nodes[i];
                    double d = (double) DOUBLES.getOpaque(distance, node);
                    int packed = position[node];
                    int slot = packed >>> 12;
                    int lx = packed & 15;
                    int ly = packed >> 8 & 15;
                    int lz = packed >> 4 & 15;
                    SectionEdges section = index.sections[slot];
                    int offset = index.offsets[slot];
                    int own = node - offset;
                    for (int k = section.inFirst(own), end = section.inEnd(own); k < end; k++) {
                        int move = section.inMove[k];
                        int p = offset + section.nodeOf(lx - moves.dx[move] | lz - moves.dz[move] << 4
                                | ly - moves.dy[move] << 8);
                        if (lower(distance, p, d + moves.cost[move])) {
                            lowered.add(p);
                        }
                    }
                    for (int k = start[node]; k < start[node + 1]; k++) {
                        int move = inMove[k];
                        int p = index.resolve(slot, lx - moves.dx[move], ly - moves.dy[move], lz - moves.dz[move]);
                        if (lower(distance, p, d + moves.cost[move])) {
                            lowered.add(p);
                        }
                    }
                }
                improved.add(lowered.toIntArray());
                return true;
            });
            if (!relaxed) {
                return false;
            }
            for (int[] lowered : improved) {
                for (int p : lowered) {
                    settled.clear(p);
                    queue.push(Math.max(bucket, (int) ((distance[p] - base) / width)), p);
                }
            }
        }
        return true;
    }

    /** Lowers {@code distance[p]} to {@code candidate}. {@code true} if it was lowered. */
    private static boolean lower(double[] distance, int p, double candidate) {
        double current = (double) DOUBLES.getOpaque(distance, p);
        while (candidate < current) {
            if (DOUBLES.compareAndSet(distance, p, current, candidate)) {
                return true;
            }
            current = (double) DOUBLES.getOpaque(distance, p);
        }
        return false;
    }

    /**
     * Whether the goal inside the window can be entered from somewhere in the shell. If not, every value in the window comes only from the
     * estimate outside the edge, so this guide must not be used for searching. Always {@code true} if the goal is outside the window.
     */
    public boolean reachesGoal() {
        return !goalCut;
    }

    /**
     * Whether ({@code x},{@code y},{@code z}) lies inside the shell connected to the goal.
     *
     * <p>The shell ({@link SectionShell}) only holds the volume around naturally standable points, so closed caves and islands separated
     * by voids wider than 16 blocks are not connected. There, no nearby node has a value, and the value falls back to the outside estimate or the geometric lower bound.
     *
     * <p><b>Points with no nearby node at all are treated as connected.</b> Placed blocks, such as the far end of a bridge over a void, normally lie
     * outside the shell, and there the nearby values are extended ({@link #estimate}).
     */
    public boolean connects(int x, int y, int z) {
        boolean nodeNearby = false;
        for (int dx = -NEAREST_REACH; dx <= NEAREST_REACH; dx++) {
            for (int dy = -NEAREST_REACH; dy <= NEAREST_REACH; dy++) {
                for (int dz = -NEAREST_REACH; dz <= NEAREST_REACH; dz++) {
                    int near = index.resolveAbsolute(x + dx, y + dy, z + dz);
                    if (near >= 0) {
                        if (Double.isFinite(distance[near])) {
                            return true;
                        }
                        nodeNearby = true;
                    }
                }
            }
        }
        return !nodeNearby;
    }

    /**
     * Where a guide value came from. {@code exit} is the value's source: the goal itself, or the point where the outside-window estimate was read.
     *
     * @param inside cost of the path traced inside the window from {@code from} up to just before {@code exit}
     * @param outside the outside-window estimate read at {@code exit} (0 if it is the goal)
     */
    public record Descent(BlockPos exit, double inside, double outside, boolean reachedGoal) {
    }

    /**
     * Follows the edges that produced the guide value downhill from {@code (x, y, z)} to find the value's source. {@code null} if not a node or no value.
     *
     * <p>Used to see in in-game logs which guide estimate pulled the path direction. Values are exact sums of edge costs, so following
     * at each node the edge whose "cost + destination value" equals its own value reaches the source.
     */
    public @Nullable Descent descend(int x, int y, int z) {
        return descend(x, y, z, null);
    }

    /** A point visited during the descent. */
    @FunctionalInterface
    public interface Trail {
        void visit(int x, int y, int z);
    }

    /** Same as {@link #descend(int, int, int)}. Passes visited points to {@code trail} in order from the start (the outside-window exit is not passed). */
    public @Nullable Descent descend(int x, int y, int z, @Nullable Trail trail) {
        int id = index.resolveAbsolute(x, y, z);
        if (id < 0 || !Double.isFinite(distance[id])) {
            return null;
        }
        double inside = 0;
        for (int guard = 0; guard < distance.length; guard++) {
            if (trail != null) {
                trail.visit(x, y, z);
            }
            if (x == goal.getX() && y == goal.getY() && z == goal.getZ()) {
                return new Descent(goal, inside, 0, true);
            }
            int slot = index.slotOf.get(NavGraph.key(x >> 4, y >> 4, z >> 4));
            int lx = x & 15;
            int ly = y & 15;
            int lz = z & 15;
            SectionEdges section = index.sections[slot];
            int node = section.nodeOf(lx | lz << 4 | ly << 8);
            double best = Double.POSITIVE_INFINITY;
            int bestMove = -1;
            int bestTarget = OUTSIDE;
            if (Math.abs(x - centerX) > radius - EDGE_SEED_BAND || Math.abs(z - centerZ) > radius - EDGE_SEED_BAND) {
                best = far.at(x, y, z);
            }
            for (int e = section.first(node), end = section.end(node); e < end; e++) {
                int m = section.move[e];
                int tx = x + moves.dx[m];
                int ty = y + moves.dy[m];
                int tz = z + moves.dz[m];
                double candidate;
                int target = index.resolve(slot, lx + moves.dx[m], ly + moves.dy[m], lz + moves.dz[m]);
                if (tx == goal.getX() && ty == goal.getY() && tz == goal.getZ()) {
                    candidate = moves.cost[m];
                } else if (target >= 0) {
                    candidate = moves.cost[m] + distance[target];
                } else if (target == OUTSIDE) {
                    candidate = moves.cost[m] + far.at(tx, ty, tz);
                } else {
                    continue;
                }
                if (candidate < best) {
                    best = candidate;
                    bestMove = m;
                    bestTarget = target;
                }
            }
            if (bestMove < 0) {
                return new Descent(new BlockPos(x, y, z), inside, best, false);
            }
            int tx = x + moves.dx[bestMove];
            int ty = y + moves.dy[bestMove];
            int tz = z + moves.dz[bestMove];
            if (tx == goal.getX() && ty == goal.getY() && tz == goal.getZ()) {
                return new Descent(goal, inside + moves.cost[bestMove], 0, true);
            }
            inside += moves.cost[bestMove];
            if (bestTarget < 0) {
                return new Descent(new BlockPos(tx, ty, tz), inside, best - moves.cost[bestMove], false);
            }
            x = tx;
            y = ty;
            z = tz;
        }
        throw new IllegalStateException("Cannot descend the guide: " + x + ", " + y + ", " + z);
    }

    /**
     * Horizontal bounding box of the path descended along the guide from {@code (x, y, z)}, padded by {@code pad} ({@code {minX, minZ, maxX, maxZ}}).
     * {@code null} if it cannot be descended, or if the path does not reach the goal and exits to the geometric lower bound estimate.
     *
     * <p>Cutting the search box to just the bounding box of start and target makes optimal detours fall outside it even with an exact guide (measured: in the Nether, a
     * 14-block bridge was built along the box wall, 1.246x). The edge the geometric lower bound points to ignores terrain, so paths heading there do not widen the box
     * (outer End islands: 11113→15613 ticks).
     */
    public int @Nullable [] descentBox(int x, int y, int z, int pad) {
        int[] box = {x, z, x, z};
        Descent descent = descend(x, y, z, (px, py, pz) -> {
            box[0] = Math.min(box[0], px);
            box[1] = Math.min(box[1], pz);
            box[2] = Math.max(box[2], px);
            box[3] = Math.max(box[3], pz);
        });
        if (descent == null || !descent.reachedGoal() && far.onlyWhenGoalOutside()) {
            return null;
        }
        return new int[] {box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad};
    }

    /**
     * Whether this point's value comes from actually tracing inside the window. <b>When subtracting two points' values, both must satisfy this</b>:
     * values near the edge and outside the window are {@link FarField} estimates, not on the same scale as inside the window (the Nether 3D coarse layer
     * is stored multiplied by {@code NavGraphGuide.VOXEL_FAR_SCALE}). Taking the difference makes the estimate's error the conclusion.
     */
    public boolean measuredInWindow(int x, int z) {
        int limit = radius - EDGE_MARGIN_BLOCKS;
        return Math.abs(x - centerX) <= limit && Math.abs(z - centerZ) <= limit;
    }

    /** Value read directly from a graph node. {@link Double#NaN} if not a node or not connected to the goal. */
    public double exact(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        return id >= 0 && Double.isFinite(distance[id]) ? distance[id] : Double.NaN;
    }

    @Override
    public double estimate(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        if (id >= 0 && Double.isFinite(distance[id])) {
            return distance[id];
        }
        if (id == OUTSIDE) {
            return outside(x, y, z);
        }
        double nearest = nearest(x, y, z);
        return Double.isFinite(nearest) ? nearest : outside(x, y, z);
    }

    /**
     * {@link Double#NaN} for points inside a built section that hold no value (outside the shell, or nodes not connected to the goal) and have no value nearby either.
     *
     * <p>Using {@link #outside} (outside-window estimate or geometric lower bound) as the search value there creates a hole that looks thousands of ticks cheaper than nodes on the neighboring island.
     * Excluding those points would prevent the search from building bridges the graph lacks (such as L-shaped bridges not heading toward the goal), so the search side inherits the value from the parent.
     */
    @Override
    public double searchEstimate(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        if (id >= 0 && Double.isFinite(distance[id])) {
            return distance[id];
        }
        if (id == OUTSIDE) {
            return outside(x, y, z);
        }
        double nearest = nearest(x, y, z);
        return Double.isFinite(nearest) ? nearest : Double.NaN;
    }

    /** Value extended from node values within {@link #NEAREST_REACH}. {@link Double#POSITIVE_INFINITY} if none. */
    private double nearest(int x, int y, int z) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int dx = -NEAREST_REACH; dx <= NEAREST_REACH; dx++) {
            for (int dy = -NEAREST_REACH; dy <= NEAREST_REACH; dy++) {
                for (int dz = -NEAREST_REACH; dz <= NEAREST_REACH; dz++) {
                    int near = index.resolveAbsolute(x + dx, y + dy, z + dz);
                    if (near >= 0 && Double.isFinite(distance[near])) {
                        nearest = Math.min(nearest,
                                distance[near] + Heuristic.estimate(x, y, z, x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return nearest;
    }

    /** A point whose value cannot be read from the graph. The outside value if present, otherwise the geometric lower bound to the goal. */
    private double outside(int x, int y, int z) {
        double value = far.at(x, y, z);
        return Double.isFinite(value) ? value : Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
    }
}
