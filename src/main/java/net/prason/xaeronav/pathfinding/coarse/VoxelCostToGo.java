package net.prason.xaeronav.pathfinding.coarse;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * An estimate of the remaining cost to the destination, built from {@link VoxelTerrain}. The only guide passed to
 * {@code AStarPathfinder} for long-distance routes in dimensions with a ceiling.
 *
 * <p>There are only two differences from {@link CoarseRouter#costToGo}, but both were decisive in the Nether:
 *
 * <ul>
 *   <li><b>3D</b>. A representation with a few floors per column cannot distinguish vertically stacked tunnels</li>
 *   <li><b>Covers beyond the search box</b>. When the destination is outside the box, {@code CoarseRouter#costToGo}
 *       makes every cost infinite, giving a table that returns 0 everywhere = the same as no guide</li>
 * </ul>
 *
 * <p>Measured (the user's stalled route): the current 2.5D layer-1 guide gives 0 steps, no guide fails to arrive
 * after 3 million nodes even with the whole world visible, and the guide built here arrives (682 steps). "Being 3D"
 * and "covering beyond the box" are <b>both</b> required; with only one, both give 0 steps.
 *
 * <p><b>This is intentionally non-admissible.</b> {@code AStarPathfinder} takes the max with the geometric
 * {@code Heuristic}, so even if this breaks the lower bound, the geometric side rescues it. That is why it is not
 * covered by {@code GuideAdmissibilityTest}: that test guards the admissibility of layer 1's {@code costToGo}, a
 * different role.
 */
public final class VoxelCostToGo implements CostToGo {

    /**
     * Cost of crossing one block of a cell with no floor (as a multiple of sprinting) = the actual cost of bridging.
     *
     * <p><b>Use the actual cost as-is.</b> This table is taken via max with the geometric {@code Heuristic}, so its
     * multiple of straight-line distance becomes the search weight directly. At first glance it seems "a thin map
     * inflates it too much and makes it greedy", but <b>that is only how it looks; it is actually right</b>. Measured
     * (the user's stalled route, on a map as thin as the real one: one cave layer, 68% visited), the table inflated
     * 5.84 times walks all the way in 549 steps. Shrinking it (to 3.0 or 2.0 times) <b>made it unable to walk</b>:
     * the shrunken table is buried under the geometric heuristic and stops saying which way to detour.
     *
     * <p>Lowering the ratio was measured too: at 11.1→3.0→1.5 times, each search advanced 5→5→4 blocks, no improvement
     * either way. <b>The costs in this table are not the place to tweak.</b>
     */
    private static final double OPEN_PENALTY =
            (ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK)
                    / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * Cost of crossing one block of lava surface (as a multiple of sprinting). When bridging is allowed, the
     * <b>same</b> as a cell with no floor, because what you actually pay is the same "build a bridge".
     *
     * <p>One could argue "lava bridges are shorter than mid-air ones ({@code CellSource#maxLavaBridgeRunBlocks} defaults
     * to 30, mid-air to 96), so it should cost more", but <b>we have not measured that a surcharge helps</b>, so they
     * are kept equal. This table's job is to give a rough direction; avoiding lava cell by cell is layer 3's job.
     */
    private static final double LAVA_PENALTY = OPEN_PENALTY;

    /**
     * Cost of a lava surface when bridging over lava is disabled. It cannot be crossed, so it <b>must be more expensive
     * than a cell with no floor</b>. An implementation that set this to the actual dig cost (about 7 times sprinting)
     * estimated lava as <b>cheaper</b> than open space (about 11 times).
     *
     * <p>The multiplier itself has not been measured. All regression fixtures use settings with lava bridges, so they
     * do not go through this branch. The only basis is the ordering "what cannot be crossed costs more than what can".
     */
    private static final double BLOCKED_LAVA_PENALTY = OPEN_PENALTY * 8.0;

    /**
     * Range (blocks) for finding a substitute origin when no floor is found in the destination cell.
     *
     * <p><b>This is the crux of this design.</b> If not a single origin can be chosen, the whole cost table is empty and
     * {@link #estimate} returns 0 everywhere = the same as no guide = back to the "not a single route comes out"
     * problem. Measured on a map with 58% of floors dropped, restricting origins to floors made arrival a coin flip.
     * The vertical extent follows the same idea as the region goal's vertical tolerance
     * ({@code AStarPathfinder#goalVerticalRadius}).
     */
    private static final int ANCHOR_VERTICAL_BLOCKS = 24;

    private static final int ANCHOR_HORIZONTAL_BLOCKS = 16;

    // Priority queue keys must not change after insertion. Comparing through cost[] lets later relaxations
    // change only the priority while leaving the heap order broken
    private record Entry(int index, double cost) {
    }

    private final VoxelTerrain terrain;
    private final double[] cost;
    private final double slack;
    private final int reachableCells;

    private VoxelCostToGo(VoxelTerrain terrain, double[] cost, int reachableCells) {
        this.terrain = terrain;
        this.cost = cost;
        this.reachableCells = reachableCells;
        this.slack = terrain.cellBlocks() * Math.sqrt(3.0) * ActionCosts.SPRINT_ONE_BLOCK;
    }

    /**
     * Builds the cost table by running Dijkstra backward from the destination. <b>May be called on a worker thread</b>
     * (once {@link VoxelTerrain} is built, this touches neither Xaero nor the world).
     *
     * @param goal the destination already snapped to a standable coordinate (after {@code StanceFinder#resolveGoal})
     * @return {@code null} if no origin can be chosen. The caller must <b>not silently fall back to no guide</b>, and
     *         must log it: having no guide is the very failure of long-distance Nether routes, so without telling
     *         them apart the same investigation gets repeated
     */
    public static VoxelCostToGo build(VoxelTerrain terrain, BlockPos goal, BooleanSupplier cancelled) {
        int goalIndex = anchor(terrain, goal);
        if (goalIndex < 0) {
            return null;
        }
        double[] cost = new double[terrain.cellCount()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        cost[goalIndex] = 0.0;
        boolean[] closed = new boolean[cost.length];
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        open.add(new Entry(goalIndex, 0.0));
        int nx = terrain.nx();
        int ny = terrain.ny();
        int nz = terrain.nz();
        int reached = 0;
        boolean lavaPassable = terrain.lavaPassable();
        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            Entry entry = open.poll();
            int current = entry.index();
            if (closed[current] || entry.cost() != cost[current]) {
                continue;
            }
            closed[current] = true;
            reached++;
            int i = terrain.cellX(current);
            int j = terrain.cellY(current);
            int k = terrain.cellZ(current);
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        if (di == 0 && dj == 0 && dk == 0) {
                            continue;
                        }
                        int ni = i + di;
                        int nj = j + dj;
                        int nk = k + dk;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= ny || nk < 0 || nk >= nz) {
                            continue;
                        }
                        int neighbor = terrain.index(ni, nj, nk);
                        if (closed[neighbor]) {
                            continue;
                        }
                        double blocks = terrain.cellBlocks() * Math.sqrt(di * di + dj * dj + dk * dk);
                        double next = cost[current] + blocks * rate(terrain.kindAt(neighbor), lavaPassable);
                        if (next < cost[neighbor]) {
                            cost[neighbor] = next;
                            open.add(new Entry(neighbor, next));
                        }
                    }
                }
            }
        }
        return new VoxelCostToGo(terrain, cost, reached);
    }

    /**
     * Cost per block of an edge. <b>Must not be a flat "N times sprinting".</b> A prototype that lumped unstandable
     * cells at the same cost said "cutting through walls is cheaper" in the lava-filled Nether, and made routes worse
     * while reducing expanded nodes.
     *
     * <p>Conversely, seeing the estimate exceed 5 times the straight-line distance on a thin map <b>makes you want to
     * shrink it, but that was measured and ruled out too</b> ({@link #OPEN_PENALTY}).
     */
    private static double rate(byte kind, boolean lavaPassable) {
        double penalty = switch (kind) {
            case VoxelTerrain.STANDABLE -> 1.0;
            case VoxelTerrain.LAVA -> lavaPassable ? LAVA_PENALTY : BLOCKED_LAVA_PENALTY;
            default -> OPEN_PENALTY;
        };
        return ActionCosts.SPRINT_ONE_BLOCK * penalty;
    }

    /**
     * Finds the destination cell, or failing that a nearby cell, to use as the origin of the backward Dijkstra.
     * Prefers a floor; without a floor, open space; failing that, the destination cell itself.
     *
     * <p><b>Providing a last resort is the key point.</b> "No origin found" invalidates the whole table, so using even
     * a coordinate inside rock as the origin is far better than giving up the guide entirely.
     */
    private static int anchor(VoxelTerrain terrain, BlockPos goal) {
        if (!terrain.contains(goal.getX(), goal.getY(), goal.getZ())) {
            return -1;
        }
        int cell = terrain.cellBlocks();
        int verticalSpread = ANCHOR_VERTICAL_BLOCKS / cell;
        int horizontalSpread = ANCHOR_HORIZONTAL_BLOCKS / cell;
        int fallback = -1;
        for (int spread = 0; spread <= Math.max(verticalSpread, horizontalSpread); spread++) {
            int dyLimit = Math.min(spread, verticalSpread);
            int dxLimit = Math.min(spread, horizontalSpread);
            for (int dj = -dyLimit; dj <= dyLimit; dj++) {
                for (int di = -dxLimit; di <= dxLimit; di++) {
                    for (int dk = -dxLimit; dk <= dxLimit; dk++) {
                        if (Math.max(Math.abs(dj), Math.max(Math.abs(di), Math.abs(dk))) != spread) {
                            continue;
                        }
                        int x = goal.getX() + di * cell;
                        int y = goal.getY() + dj * cell;
                        int z = goal.getZ() + dk * cell;
                        if (!terrain.contains(x, y, z)) {
                            continue;
                        }
                        int index = terrain.indexOfBlock(x, y, z);
                        byte kind = terrain.kindAt(index);
                        if (kind == VoxelTerrain.STANDABLE) {
                            return index;
                        }
                        if (kind == VoxelTerrain.OPEN && fallback < 0) {
                            fallback = index;
                        }
                    }
                }
            }
        }
        return fallback >= 0 ? fallback : terrain.indexOfBlock(goal.getX(), goal.getY(), goal.getZ());
    }

    /** Number of cells Dijkstra reached (for diagnostics). */
    public int reachableCells() {
        return reachableCells;
    }

    public int cellCount() {
        return terrain.cellCount();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Outside the box, it answers with <b>the value of the nearest edge cell + the straight line to it</b>. Returning
     * 0 would turn the box edge into a cliff, and A* would read "outside the box is cheaper" and expand away from the route.
     */
    @Override
    public double estimate(int x, int y, int z) {
        double approach = 0.0;
        int index;
        if (terrain.contains(x, y, z)) {
            index = terrain.indexOfBlock(x, y, z);
        } else {
            index = terrain.clampedIndexOfBlock(x, y, z);
            approach = distanceToBox(x, y, z) * ActionCosts.SPRINT_ONE_BLOCK;
        }
        double value = cost[index];
        if (Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value + approach - slack);
    }

    private double distanceToBox(int x, int y, int z) {
        double dx = x - VoxelTerrain.clampBlock(x, terrain.box().minX(), terrain.box().maxX());
        double dy = y - VoxelTerrain.clampBlock(y, terrain.box().minY(), terrain.box().maxY());
        double dz = z - VoxelTerrain.clampBlock(z, terrain.box().minZ(), terrain.box().maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
