package net.prason.xaeronav.pathfinding.astar;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * <b>Experimental.</b> Measures what HPA* with 16³ sections as clusters loses compared to the perfect graph.
 *
 * <p>An HPA* route "moves freely inside a cluster and crosses between clusters only through representative entrances",
 * so its distance equals <b>the shortest distance on the graph that keeps only the representatives among crossing edges</b>.
 * This only builds that edge selection; the values match the production form, which precomputes distances between entrances
 * within a cluster, and only the computation differs.
 *
 * <p>Crossing edges are grouped by (section exited, section entered, whether it's a cheap move), and within each group,
 * a cluster of start points connected in the 26-neighborhood is treated as one entrance. Representatives are start points
 * whose in-section coordinates on the axes other than the crossing direction lie on a {@code spacing} grid. For entrances
 * with no point on the grid, the start point closest to the centroid is kept (so narrow passage openings aren't dropped).
 */
final class SectionCompression {

    /** Edges priced above this multiple of the movement amount are treated separately as "expensive" entrances involving digging or placement. */
    private static final double CHEAP_FACTOR = 3.0;

    private record Group(long from, long to, boolean cheap) {
    }

    /** The selected edges, and the number of representatives per section. */
    record Result(BitSet kept, double representativesPerSection, int crossingEdges, int keptCrossing,
                  IntOpenHashSet representatives) {
    }

    private SectionCompression() {
    }

    static long section(ClosureGraph graph, int id) {
        return BlockPos.asLong(graph.xs.getInt(id) >> 4, graph.ys.getInt(id) >> 4, graph.zs.getInt(id) >> 4);
    }

    /**
     * @param landingMustBeRepresentative if true, an edge is kept only if the point it lands on is also <b>a representative of
     *                                    the section it enters</b>. In production, where sections are built independently, there's no
     *                                    guarantee the landing point's distances are precomputed, so this is the production constraint
     */
    static Result compress(ClosureGraph graph, int spacing, boolean landingMustBeRepresentative) {
        int m = (int) graph.edges();
        BitSet kept = new BitSet(m);
        Map<Group, IntOpenHashSet> sources = new HashMap<>();
        LongOpenHashSet sections = new LongOpenHashSet();
        int crossing = 0;
        for (int e = 0; e < m; e++) {
            int u = graph.from.getInt(e);
            int v = graph.to.getInt(e);
            long cu = section(graph, u);
            long cv = section(graph, v);
            sections.add(cu);
            if (cu == cv) {
                kept.set(e);
                continue;
            }
            crossing++;
            sources.computeIfAbsent(new Group(cu, cv, cheap(graph, e)), k -> new IntOpenHashSet()).add(u);
        }
        Map<Group, IntOpenHashSet> representatives = new HashMap<>();
        long total = 0;
        for (Map.Entry<Group, IntOpenHashSet> entry : sources.entrySet()) {
            IntOpenHashSet chosen = choose(graph, entry.getKey(), entry.getValue(), spacing);
            representatives.put(entry.getKey(), chosen);
            total += chosen.size();
        }
        IntOpenHashSet everyRepresentative = new IntOpenHashSet();
        representatives.values().forEach(everyRepresentative::addAll);
        int keptCrossing = 0;
        for (int e = 0; e < m; e++) {
            if (kept.get(e)) {
                continue;
            }
            int u = graph.from.getInt(e);
            Group group = new Group(section(graph, u), section(graph, graph.to.getInt(e)), cheap(graph, e));
            if (representatives.get(group).contains(u)
                    && (!landingMustBeRepresentative || everyRepresentative.contains(graph.to.getInt(e)))) {
                kept.set(e);
                keptCrossing++;
            }
        }
        return new Result(kept, sections.isEmpty() ? 0 : (double) total / sections.size(), crossing, keptCrossing,
                everyRepresentative);
    }

    private static boolean cheap(ClosureGraph graph, int e) {
        int u = graph.from.getInt(e);
        int v = graph.to.getInt(e);
        double dx = graph.xs.getInt(v) - graph.xs.getInt(u);
        double dy = graph.ys.getInt(v) - graph.ys.getInt(u);
        double dz = graph.zs.getInt(v) - graph.zs.getInt(u);
        double moved = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        return graph.cost.getFloat(e) <= CHEAP_FACTOR * ActionCosts.SPRINT_ONE_BLOCK * moved;
    }

    private static IntOpenHashSet choose(ClosureGraph graph, Group group, IntOpenHashSet members, int spacing) {
        int[] ids = members.toIntArray();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap(ids.length);
        index.defaultReturnValue(-1);
        for (int i = 0; i < ids.length; i++) {
            index.put(BlockPos.asLong(graph.xs.getInt(ids[i]), graph.ys.getInt(ids[i]), graph.zs.getInt(ids[i])), i);
        }
        int[] parent = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < ids.length; i++) {
            int x = graph.xs.getInt(ids[i]);
            int y = graph.ys.getInt(ids[i]);
            int z = graph.zs.getInt(ids[i]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int j = index.get(BlockPos.asLong(x + dx, y + dy, z + dz));
                        if (j >= 0) {
                            union(parent, i, j);
                        }
                    }
                }
            }
        }
        boolean crossX = BlockPos.getX(group.from()) != BlockPos.getX(group.to());
        boolean crossY = BlockPos.getY(group.from()) != BlockPos.getY(group.to());
        boolean crossZ = BlockPos.getZ(group.from()) != BlockPos.getZ(group.to());
        Map<Integer, IntArrayList> components = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            components.computeIfAbsent(find(parent, i), k -> new IntArrayList()).add(i);
        }
        IntOpenHashSet chosen = new IntOpenHashSet();
        for (IntArrayList component : components.values()) {
            boolean any = false;
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int k = 0; k < component.size(); k++) {
                int id = ids[component.getInt(k)];
                int x = graph.xs.getInt(id);
                int y = graph.ys.getInt(id);
                int z = graph.zs.getInt(id);
                cx += x;
                cy += y;
                cz += z;
                if ((crossX || onLattice(x, spacing)) && (crossY || onLattice(y, spacing))
                        && (crossZ || onLattice(z, spacing))) {
                    chosen.add(id);
                    any = true;
                }
            }
            if (any) {
                continue;
            }
            cx /= component.size();
            cy /= component.size();
            cz /= component.size();
            int best = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int k = 0; k < component.size(); k++) {
                int id = ids[component.getInt(k)];
                double dx = graph.xs.getInt(id) - cx;
                double dy = graph.ys.getInt(id) - cy;
                double dz = graph.zs.getInt(id) - cz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = id;
                }
            }
            chosen.add(best);
        }
        return chosen;
    }

    private static boolean onLattice(int coordinate, int spacing) {
        return (coordinate & 15) % spacing == spacing / 2 % spacing;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }
}
