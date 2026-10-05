package net.prason.xaeronav.pathfinding.coarse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Measures how the 3D coarse layer's ({@link VoxelCostToGo}) estimate deviates from the true value, broken down by cause.
 *
 * <p>The true value is the nav graph's value with a single window covering the whole terrain. Following the path down the 3D coarse layer's table cell by cell and
 * summing "difference in true value − difference in table" between each pair of adjacent points splits the start's "true − table" into per-segment contributions (the middle cancels out).
 * Segments are summed by category: "climb/descend/level from floor to floor" and "crossing cells with no floor".
 *
 * <p>{@code ./gradlew --offline :1.21.1-neoforge:bench --tests '*VoxelEstimateErrorBenchTest' -Pxaeronav.heap=8g} (about 2 minutes in total).
 * {@code -Pxaeronav.navGraphVerbose=true} prints segments with large differences, with coordinates.
 * {@code -Pxaeronav.alongPoints="trap:3,78,345 45,93,380"} lists ratios at points such as a walked path (multiple separated by {@code ;}).
 */
@Tag("bench")
class VoxelEstimateErrorBenchTest {

    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;
    private static final int SAMPLES = 400;

    private static FakeCells load(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(0).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30));
    }

    @Test
    void trap() throws IOException {
        FakeCells cells = load("/nether_trap.txt.gz");
        measure("trap", cells, new BlockPos(-12, 64, 349), new BlockPos(-53, 68, 716), List.of(
                new BlockPos(-65, 47, 521), new BlockPos(72, 69, 439), new BlockPos(-12, 44, 483),
                new BlockPos(-2, 59, 444), new BlockPos(-12, 64, 349), new BlockPos(-99, 90, 559),
                new BlockPos(-43, 66, 618)));
    }

    @Test
    void lavaSea() throws IOException {
        FakeCells cells = load("/nether_wide.txt.gz");
        measure("lava sea", cells, new BlockPos(-212, 48, 553), new BlockPos(-333, 59, 694), List.of(
                new BlockPos(-212, 48, 553), new BlockPos(-271, 64, 395), new BlockPos(-261, 66, 448)));
    }

    /** Same terrain as the lava sea, two other destinations (destinations of the 4 Nether routes). Checks we aren't overfitting to the two above. */
    @Test
    void wide() throws IOException {
        FakeCells cells = load("/nether_wide.txt.gz");
        measure("wide (north goal)", cells, new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379), List.of());
        measure("wide (west goal)", cells, new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496), List.of());
    }

    private static void measure(String name, FakeCells cells, BlockPos rawStart, BlockPos rawGoal, List<BlockPos> points) {
        BlockPos goal = StanceFinder.resolveGoal(cells, rawGoal);
        BlockPos start = StanceFinder.resolveStart(cells, rawStart);
        SearchBounds world = cells.bounds();
        int centerX = (world.minX() + world.maxX()) / 2;
        int centerZ = (world.minZ() + world.maxZ()) / 2;
        int radius = Math.max(world.maxX() - world.minX(), world.maxZ() - world.minZ()) / 2 + 40;
        long began = System.currentTimeMillis();
        WindowedCells window = new WindowedCells(cells, new BlockPos(centerX, 64, centerZ), radius);
        WindowField truth = new NavGraph(goal, world.minY(), world.maxY()).refresh(() -> window, centerX, centerZ,
                radius, LoadedArea.square(centerX, centerZ, radius), FarField.of((x, y, z) -> 0.0),
                ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false).field();
        System.out.printf(Locale.ROOT, "%s: truth window center %d,%d radius %d %dms%n", name, centerX, centerZ, radius,
                System.currentTimeMillis() - began);

        VoxelTerrain terrain = VoxelTerrain.of(XaeroMapModel.guideBox(start, goal, NETHER_MIN_Y, NETHER_MAX_Y), true);
        XaeroMapModel.fill(terrain, cells);
        VoxelCostToGo voxel = VoxelCostToGo.build(terrain, goal, () -> false);
        Probe probe = new Probe(terrain, voxel, truth);
        System.out.printf(Locale.ROOT, "  3D coarse layer cell %d %s reached %d/%d%n", terrain.cellBlocks(), terrain.breakdown(),
                voxel.reachableCells(), voxel.cellCount());

        for (BlockPos point : points) {
            BlockPos at = StanceFinder.resolveStart(cells, point);
            Breakdown one = new Breakdown();
            one.verbose = Boolean.getBoolean("xaeronav.navGraphVerbose");
            probe.decompose(at, one);
            System.out.printf(Locale.ROOT, "  %s true %.0f table %.0f ratio %.2f  %s%n", at.toShortString(), truth.exact(at.getX(), at.getY(), at.getZ()),
                    probe.value(at), probe.value(at) / truth.exact(at.getX(), at.getY(), at.getZ()), one);
        }

        // How the table/true ratio moves along the optimal path from the start. If the estimate outside the window gets
        // pulled toward a corridor that looks cheap partway along, there will be a segment where the ratio drops here
        List<BlockPos> optimal = new ArrayList<>();
        truth.descend(start.getX(), start.getY(), start.getZ(), (x, y, z) -> optimal.add(new BlockPos(x, y, z)));
        StringBuilder along = new StringBuilder();
        BlockPos last = null;
        for (BlockPos at : optimal) {
            if (last == null || Math.max(Math.abs(at.getX() - last.getX()), Math.abs(at.getZ() - last.getZ())) >= 24) {
                along.append(String.format(Locale.ROOT, " %d,%d,%d=%.2f", at.getX(), at.getY(), at.getZ(),
                        probe.value(at) / truth.exact(at.getX(), at.getY(), at.getZ())));
                last = at;
            }
        }
        System.out.printf(Locale.ROOT, "  ratio along the optimal path:%s%n", along);
        for (String spec : System.getProperty("xaeronav.alongPoints", "").split(";")) {
            if (spec.isBlank() || !spec.startsWith(name + ":")) {
                continue;
            }
            StringBuilder path = new StringBuilder();
            for (String token : spec.substring(name.length() + 1).split(" ")) {
                String[] xyz = token.split(",");
                BlockPos at = StanceFinder.resolveStart(cells,
                        new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
                double exact = truth.exact(at.getX(), at.getY(), at.getZ());
                path.append(String.format(Locale.ROOT, " %s true %.0f ratio %.2f", at.toShortString(), exact, probe.value(at) / exact));
            }
            System.out.printf(Locale.ROOT, "  ratio along the given path:%s%n", path);
        }

        List<BlockPos> nodes = new ArrayList<>();
        for (int x = world.minX(); x <= world.maxX(); x += 4) {
            for (int z = world.minZ(); z <= world.maxZ(); z += 4) {
                for (int y = world.minY(); y <= world.maxY(); y++) {
                    if (terrain.contains(x, y, z) && Double.isFinite(truth.exact(x, y, z))) {
                        nodes.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        Random random = new Random(1L);
        Breakdown all = new Breakdown();
        List<Double> ratios = new ArrayList<>();
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < SAMPLES && !nodes.isEmpty(); i++) {
            BlockPos at = nodes.get(random.nextInt(nodes.size()));
            double exact = truth.exact(at.getX(), at.getY(), at.getZ());
            if (exact < 200) {
                continue;
            }
            ratios.add(probe.value(at) / exact);
            pairs.add(new double[] {exact, probe.value(at)});
            probe.decompose(at, all);
        }
        ratios.sort(Double::compare);
        // Whether the table orders two points with the same true-value difference in the same order. Only this ordering matters at forks outside the window
        int agree = 0;
        int compared = 0;
        for (int a = 0; a < pairs.size(); a++) {
            for (int b = a + 1; b < pairs.size(); b++) {
                double trueGap = pairs.get(a)[0] - pairs.get(b)[0];
                if (Math.abs(trueGap) < 300) {
                    continue;
                }
                compared++;
                if (Math.signum(trueGap) == Math.signum(pairs.get(a)[1] - pairs.get(b)[1])) {
                    agree++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  %d random points table/true p10=%.2f p50=%.2f p90=%.2f spread p90/p10=%.2f order agreement (true diff >300)=%.3f%n",
                ratios.size(), ratios.get(ratios.size() / 10), ratios.get(ratios.size() / 2),
                ratios.get(ratios.size() * 9 / 10), ratios.get(ratios.size() * 9 / 10) / ratios.get(ratios.size() / 10),
                (double) agree / compared);
        System.out.printf(Locale.ROOT, "  breakdown (sum of true − table, positive = table too cheap)%s%n", all);
    }

    /** Per segment kind, the sum of "difference in true value − difference in table" and the number of segments. */
    private static final class Breakdown {
        private final Map<String, double[]> sums = new TreeMap<>();
        boolean verbose;

        void add(String kind, double delta) {
            double[] slot = sums.computeIfAbsent(kind, key -> new double[2]);
            slot[0] += delta;
            slot[1]++;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            sums.forEach((kind, slot) -> text.append(String.format(Locale.ROOT, " %s=%.0f(%d)", kind, slot[0], (int) slot[1])));
            return text.toString();
        }
    }

    private static final class Probe {
        private final VoxelTerrain terrain;
        private final VoxelCostToGo voxel;
        private final WindowField truth;
        private final double slack;
        private final double openRate;

        Probe(VoxelTerrain terrain, VoxelCostToGo voxel, WindowField truth) {
            this.terrain = terrain;
            this.voxel = voxel;
            this.truth = truth;
            this.slack = terrain.cellBlocks() * Math.sqrt(3.0) * ActionCosts.SPRINT_ONE_BLOCK;
            this.openRate = ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK;
        }

        /** Raw table value ({@link VoxelCostToGo#estimate} has {@code slack} subtracted). */
        double value(BlockPos at) {
            return voxel.estimate(at.getX(), at.getY(), at.getZ()) + slack;
        }

        private double cellCost(int index) {
            int x = terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks();
            int y = terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks();
            int z = terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks();
            double estimate = voxel.estimate(x, y, z);
            return estimate > 0 ? estimate + slack : Double.NaN;
        }

        /** The cheapest true value among nav graph nodes in the cell. NaN if there are none. */
        private double cellTruth(int index) {
            int x0 = terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks();
            int y0 = terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks();
            int z0 = terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks();
            double best = Double.NaN;
            for (int x = x0; x < x0 + terrain.cellBlocks(); x++) {
                for (int y = y0; y < y0 + terrain.cellBlocks(); y++) {
                    for (int z = z0; z < z0 + terrain.cellBlocks(); z++) {
                        double exact = truth.exact(x, y, z);
                        if (Double.isFinite(exact) && !(exact >= best)) {
                            best = exact;
                        }
                    }
                }
            }
            return best;
        }

        /** The cell that passed its value to this cell in the reverse Dijkstra. -1 if the table ran out (near the destination). */
        private int parent(int index) {
            double here = cellCost(index);
            if (Double.isNaN(here)) {
                return -1;
            }
            double rate = terrain.kindAt(index) == VoxelTerrain.STANDABLE ? ActionCosts.SPRINT_ONE_BLOCK : openRate;
            int i = terrain.cellX(index);
            int j = terrain.cellY(index);
            int k = terrain.cellZ(index);
            int best = -1;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int ni = i + di;
                        int nj = j + dj;
                        int nk = k + dk;
                        if ((di | dj | dk) == 0 || ni < 0 || nj < 0 || nk < 0
                                || ni >= terrain.nx() || nj >= terrain.ny() || nk >= terrain.nz()) {
                            continue;
                        }
                        int neighbor = terrain.index(ni, nj, nk);
                        double there = cellCost(neighbor);
                        if (Double.isNaN(there)) {
                            continue;
                        }
                        double edge = terrain.cellBlocks() * Math.sqrt(di * di + dj * dj + dk * dk) * rate;
                        double gap = Math.abs(there + edge - here);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = neighbor;
                        }
                    }
                }
            }
            return bestGap < 1e-6 ? best : -1;
        }

        private String corner(int index) {
            return (terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks()) + ","
                    + (terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks()) + ","
                    + (terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks());
        }

        void decompose(BlockPos at, Breakdown out) {
            int cell = terrain.indexOfBlock(at.getX(), at.getY(), at.getZ());
            double atTruth = truth.exact(at.getX(), at.getY(), at.getZ());
            double atValue = value(at);
            int anchor = cell;
            double anchorTruth = atTruth;
            double anchorValue = atValue;
            boolean crossedOpen = false;
            int guard = 0;
            for (int next = parent(cell); next >= 0 && guard < 10_000; next = parent(next), guard++) {
                crossedOpen |= terrain.kindAt(next) != VoxelTerrain.STANDABLE;
                double nextTruth = cellTruth(next);
                if (Double.isNaN(nextTruth)) {
                    continue;
                }
                double nextValue = cellCost(next);
                String kind;
                if (crossedOpen) {
                    kind = "cross no-floor";
                } else {
                    int dy = terrain.cellY(next) - terrain.cellY(anchor);
                    boolean adjacent = Math.abs(terrain.cellX(next) - terrain.cellX(anchor)) <= 1
                            && Math.abs(terrain.cellZ(next) - terrain.cellZ(anchor)) <= 1 && Math.abs(dy) <= 1;
                    kind = !adjacent ? "floor→floor (apart)" : dy > 0 ? "floor→floor (up)" : dy < 0 ? "floor→floor (down)" : "floor→floor (level)";
                }
                double delta = (anchorTruth - nextTruth) - (anchorValue - nextValue);
                if (out.verbose && Math.abs(delta) > 25) {
                    System.out.printf(Locale.ROOT, "      %s %s→%s true %.0f→%.0f table %.0f→%.0f diff %.0f%n", kind, corner(anchor),
                            corner(next), anchorTruth, nextTruth, anchorValue, nextValue, delta);
                }
                out.add(kind, delta);
                anchor = next;
                anchorTruth = nextTruth;
                anchorValue = nextValue;
                crossedOpen = false;
            }
            out.add("remaining at end", anchorTruth - anchorValue);
        }
    }
}
