package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>Measurement only. Not a guard</b>, so it never asserts anything and fails (run explicitly via the `bench` task).
 *
 * <p>It measures three things: <b>cell reads per node and the share of vertical scans</b>,
 * <b>the quality/speed trade-off when varying the weight</b>, and <b>whether the layer 1 guide reduces expanded nodes</b>.
 *
 * <p>Results are also written to a file, because Gradle's tests swallow standard output.
 */
@Tag("bench")
class SearchProfileTest {

    private static final long SEED = 20260906L;

    /** Number of timing runs. The minimum is taken. */
    private static final int RUNS = 3;

    /** A watcher that only counts cell reads. Wrapped in a dynamic proxy so the implementation needn't be touched. */
    private static final class CellCounter implements InvocationHandler {

        private final CellSource delegate;
        private final LongOpenHashSet columns = new LongOpenHashSet();
        /** Number of scans by length. Index is floor(log2(length)). */
        private final long[] runsByLength = new long[16];
        private long calls;
        private long runs;
        private long runCalls;
        private int maxRun;
        private int prevX = Integer.MIN_VALUE;
        private int prevY;
        private int prevZ;
        private int runLength;

        CellCounter(CellSource delegate) {
            this.delegate = delegate;
        }

        CellSource view() {
            return (CellSource) Proxy.newProxyInstance(CellSource.class.getClassLoader(),
                    new Class<?>[] {CellSource.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("cell".equals(method.getName()) && args != null && args.length == 3) {
                int x = (Integer) args[0];
                int y = (Integer) args[1];
                int z = (Integer) args[2];
                calls++;
                columns.add(((long) x << 32) ^ (z & 0xffffffffL));
                if (x == prevX && z == prevZ && y == prevY - 1) {
                    runLength++;
                } else {
                    closeRun();
                    runLength = 1;
                }
                prevX = x;
                prevY = y;
                prevZ = z;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void closeRun() {
            if (runLength >= 2) {
                runs++;
                runCalls += runLength;
                maxRun = Math.max(maxRun, runLength);
                runsByLength[31 - Integer.numberOfLeadingZeros(runLength)]++;
            }
        }

        String report(int expandedNodes) {
            closeRun();
            runLength = 0;
            StringBuilder histogram = new StringBuilder();
            for (int i = 1; i < runsByLength.length; i++) {
                if (runsByLength[i] > 0) {
                    histogram.append(String.format(Locale.ROOT, " %d-%d:%d",
                            1 << i, (1 << (i + 1)) - 1, runsByLength[i]));
                }
            }
            // With a per-column index, one vertical scan takes a single query.
            // So (reads used for scans - number of scans) is the upper bound on reads that can be eliminated
            long removable = runCalls - runs;
            return String.format(Locale.ROOT,
                    "    cell reads %,d (%.0f/node)  vertical scans %,d scans %,d reads (%.0f%% of all reads) longest%d"
                            + "%n    upper bound of removable reads %,d (%.0f%% of total)  columns touched %,d"
                            + "%n    scan length distribution:%s",
                    calls, calls / (double) Math.max(1, expandedNodes),
                    runs, runCalls, 100.0 * runCalls / Math.max(1, calls), maxRun,
                    removable, 100.0 * removable / Math.max(1, calls), columns.size(),
                    histogram.isEmpty() ? " (no scans of length 2+)" : histogram.toString());
        }
    }

    private record Scenario(String name, FakeCellsFactory terrain, BlockPos start, BlockPos goal,
                            SearchLimits limits) {
    }

    @FunctionalInterface
    private interface FakeCellsFactory {
        FakeCells create() throws IOException;
    }

    private static FakeCells overworldWide() throws IOException {
        return TerrainFixture.load("/overworld_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    private static FakeCells mountains() throws IOException {
        return TerrainFixture.load("/overworld_mountains.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    /** Same conditions as {@code NetherLavaSeaTest}. */
    private static FakeCells netherLavaSea() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .bounds(new SearchBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), 128, bounds.maxZ()))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(6));
    }

    /** Same conditions as {@code RealEndTerrainTest}. */
    private static FakeCells endVoid() throws IOException {
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .placedBlockBudget(0)
                .maxFallDamagePoints(6));
    }

    private static PathResult run(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                  boolean guide) {
        try {
            return new PathfindingExecutor().submit(view, start, goal, limits, guide, 0).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Scenario> scenarios() throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        BlockPos[] wide = TerrainFixture.randomRoutes(overworldWide(), overworldWide().bounds(),
                SEED, 1, 140, 160).get(0);
        scenarios.add(new Scenario("Overworld, sea and land (" + (int) horizontal(wide[0], wide[1]) + " blocks)",
                SearchProfileTest::overworldWide, wide[0], wide[1],
                new SearchLimits(300_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        BlockPos[] mountain = TerrainFixture.randomRoutes(mountains(), mountains().bounds(),
                SEED, 1, 100, 130).get(0);
        scenarios.add(new Scenario("Overworld, mountains (" + (int) horizontal(mountain[0], mountain[1]) + " blocks)",
                SearchProfileTest::mountains, mountain[0], mountain[1],
                new SearchLimits(300_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        scenarios.add(new Scenario("Nether, lava sea (deep budget)", SearchProfileTest::netherLavaSea,
                new BlockPos(-289, 72, 525), new BlockPos(-296, 57, 584),
                new SearchLimits(800_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        scenarios.add(new Scenario("End, crossing the void", SearchProfileTest::endVoid,
                new BlockPos(1233, 57, 1142), new BlockPos(1288, 57, 1080),
                new SearchLimits(250_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        return scenarios;
    }

    @Test
    void whereTheTimeGoes() throws IOException {
        List<String> report = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            // The first run has a cold JIT and comes out 3-7x slower for the same search. Compare minimums
            PathResult result = null;
            double millis = Double.MAX_VALUE;
            for (int attempt = 0; attempt < RUNS; attempt++) {
                FakeCells timed = scenario.terrain().create();
                long began = System.nanoTime();
                result = run(timed, scenario.start(), scenario.goal(), scenario.limits(), true);
                millis = Math.min(millis, (System.nanoTime() - began) / 1e6);
            }

            FakeCells counted = scenario.terrain().create();
            CellCounter counter = new CellCounter(counted);
            PathResult countedResult = run(counter.view(), scenario.start(), scenario.goal(),
                    scenario.limits(), true);

            report.add(String.format(Locale.ROOT,
                    "%n■ %s%n    %s %d steps expanded %,d nodes %.0fms (%,.0f nodes/s)%s",
                    scenario.name(), result.termination(), result.steps().size(),
                    result.expandedNodes(), millis,
                    result.expandedNodes() / (millis / 1000.0),
                    countedResult.expandedNodes() == result.expandedNodes() ? ""
                            : String.format(Locale.ROOT, "  * counting run: %,d nodes (not comparable)",
                                    countedResult.expandedNodes())));
            report.add(counter.report(countedResult.expandedNodes()));
        }
        emit("profile-hotspots.txt", String.join("\n", report));
    }

    /** Visibility matching the real game's loaded window (equivalent to a render distance of 10 chunks). */
    private static final int WINDOW_RADIUS = 160;

    private static CellSource windowed(FakeCells all, BlockPos from, BlockPos to) {
        return new net.prason.xaeronav.pathfinding.world.WindowedCells(all, from, WINDOW_RADIUS,
                ProgressiveWalk.searchBox(all, from, to, WINDOW_RADIUS));
    }

    /**
     * Raw A* without the relaxation ladder. Going through {@code PathfindingExecutor}, changing the weight can return
     * the result of a different stage (the greedy retry), so the weight-to-quality relationship can't be measured.
     */
    private static PathResult raw(CellSource view, BlockPos start, BlockPos goal, double weight,
                                   CostToGo guide) {
        SearchLimits limits = new SearchLimits(2_000_000, 600_000, weight);
        AStarPathfinder finder = new AStarPathfinder(view, limits, guide);
        return finder.search(
                net.prason.xaeronav.pathfinding.world.StanceFinder.resolveStart(view, start),
                net.prason.xaeronav.pathfinding.world.StanceFinder.resolveGoal(view, goal),
                () -> false, Carryover.NONE, 0);
    }

    @Test
    void qualityAgainstSpeed() throws IOException {
        double[] weights = {1.0, 1.1, 1.25, 1.5, 1.75, 2.0};
        List<String> report = new ArrayList<>();
        FakeCells probe = overworldWide();
        List<BlockPos[]> routes = TerrainFixture.randomRoutes(probe, probe.bounds(), SEED, 4, 100, 160);
        for (BlockPos[] route : routes) {
            report.add(String.format(Locale.ROOT, "%n■ %s->%s (%.0f blocks)",
                    route[0].toShortString(), route[1].toShortString(),
                    horizontal(route[0], route[1])));
            double best = Double.NaN;
            for (double weight : weights) {
                CellSource view = windowed(overworldWide(), route[0], route[1]);
                long began = System.nanoTime();
                PathResult result = raw(view, route[0], route[1], weight, null);
                double millis = (System.nanoTime() - began) / 1e6;
                double cost = ProgressiveWalk.cost(result.steps());
                if (Double.isNaN(best)) {
                    best = cost;
                }
                report.add(String.format(Locale.ROOT,
                        "    weight%.2f no guide  %-12s cost%8.0f (%.3fx) expanded%,8d nodes %6.0fms",
                        weight, result.complete() ? "reached" : result.termination().toString(),
                        cost, cost / best, result.expandedNodes(), millis));
            }
            // Measure the layer 1 guide's effect and cost separately. Construction runs once before the search
            for (double weight : new double[] {1.0, 1.5}) {
                FakeCells cells = overworldWide();
                CellSource view = windowed(cells, route[0], route[1]);
                BlockPos resolvedStart =
                        net.prason.xaeronav.pathfinding.world.StanceFinder.resolveStart(view, route[0]);
                long guideBegan = System.nanoTime();
                net.prason.xaeronav.pathfinding.coarse.CoarseMap coarseMap =
                        net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler.sample(
                                view, view.bounds(), resolvedStart.getY(), () -> false);
                CostToGo guide = net.prason.xaeronav.pathfinding.coarse.CoarseRouter.costToGo(coarseMap,
                        route[1], false,
                        view.lavaBridgingEnabled()
                                ? net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.BRIDGE
                                : net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.ALLOW);
                double guideMillis = (System.nanoTime() - guideBegan) / 1e6;
                long began = System.nanoTime();
                PathResult result = raw(view, route[0], route[1], weight, guide);
                double millis = (System.nanoTime() - began) / 1e6;
                double cost = ProgressiveWalk.cost(result.steps());
                report.add(String.format(Locale.ROOT,
                        "    weight%.2f guide     %-12s cost%8.0f (%.3fx) expanded%,8d nodes %6.0fms"
                                + " (+guide build %.0fms)",
                        weight, result.complete() ? "reached" : result.termination().toString(),
                        cost, cost / best, result.expandedNodes(), millis, guideMillis));
            }
        }
        emit("profile-quality-speed.txt", String.join("\n", report));
    }

    /** Whether the layer 1 guide ({@code CoarseRouter#costToGo}) actually reduces expanded nodes. */
    @Test
    void guideValue() throws IOException {
        List<String> report = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            report.add(String.format(Locale.ROOT, "%n■ %s", scenario.name()));
            FakeCells guideCells = scenario.terrain().create();
            long sampleBegan = System.nanoTime();
            net.prason.xaeronav.pathfinding.coarse.CoarseMap coarseMap =
                    net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler.sample(guideCells,
                            guideCells.bounds(), scenario.start().getY(), () -> false);
            double sampleMillis = (System.nanoTime() - sampleBegan) / 1e6;
            long dijkstraBegan = System.nanoTime();
            net.prason.xaeronav.pathfinding.coarse.CoarseRouter.costToGo(coarseMap, scenario.goal(), false,
                    guideCells.lavaBridgingEnabled()
                            ? net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.BRIDGE
                            : net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.ALLOW);
            double dijkstraMillis = (System.nanoTime() - dijkstraBegan) / 1e6;
            report.add(String.format(Locale.ROOT, "    guide build breakdown: map scan %.0fms + Dijkstra %.0fms",
                    sampleMillis, dijkstraMillis));
            for (boolean guide : new boolean[] {false, true}) {
                FakeCells cells = scenario.terrain().create();
                long began = System.nanoTime();
                PathResult result = run(cells, scenario.start(), scenario.goal(), scenario.limits(), guide);
                double millis = (System.nanoTime() - began) / 1e6;
                report.add(String.format(Locale.ROOT,
                        "    guide %s %-12s cost%8.0f expanded%,9d nodes %7.0fms",
                        guide ? "on " : "off", result.complete() ? "reached" : result.termination().toString(),
                        ProgressiveWalk.cost(result.steps()), result.expandedNodes(), millis));
            }
        }
        emit("profile-guide.txt", String.join("\n", report));
    }

    /**
     * Total time to walk all the way with the same assembly as the real game (layer 1 guide + segment splitting + extension).
     * The cost of "rebuilding the same thing over and over", invisible in a single search, shows up here.
     */
    @Test
    void walkThrough() throws IOException {
        List<String> report = new ArrayList<>();
        FakeCells probe = overworldWide();
        for (BlockPos[] route : TerrainFixture.randomRoutes(probe, probe.bounds(), SEED, 3, 200, 320)) {
            double best = Double.MAX_VALUE;
            int steps = 0;
            for (int attempt = 0; attempt < RUNS; attempt++) {
                FakeCells cells = overworldWide();
                long began = System.nanoTime();
                List<PathStep> walked = ProgressiveWalk.walk(cells, route[0], route[1], WINDOW_RADIUS, true);
                best = Math.min(best, (System.nanoTime() - began) / 1e6);
                steps = walked.size();
            }
            report.add(String.format(Locale.ROOT, "    %s->%s (%.0f blocks) %d steps %.0fms",
                    route[0].toShortString(), route[1].toShortString(),
                    horizontal(route[0], route[1]), steps, best));
        }
        emit("profile-walk.txt", String.join("\n", report));
    }

    /** Gradle swallows the results, so output to both standard output and a file. */
    private static void emit(String name, String text) {
        System.out.println(text);
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("xaeronav.profileOut", System.getProperty("java.io.tmpdir")));
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve(name), text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
