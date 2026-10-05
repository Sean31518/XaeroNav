package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * Measures how close the nav graph guide, built from data obtainable in the real game, gets to the upper bound
 * (the perfect remaining cost of {@link ClosureGraph}). A measurement with no assertions ({@code bench}).
 *
 * <p>Scoring is the same as {@code LongRouteOptimalityTest}: a single solve with full visibility, weight 1.0, and no guide counts as 1.000.
 */
@Tag("bench")
class NavGraphGuideBenchTest {

    private static final int WINDOW = 160;

    /** Horizontal margin of the closure. Wider than the search window (pitfall 3 of {@link ClosureGraph}). */
    private static final int CLOSURE_MARGIN = 224;

    private static final long SEED = 20260917L;

    /** Conditions for one terrain. */
    private record Terrain(String name, FakeCells cells, List<BlockPos[]> routes, int minY, int maxY,
                           ProgressiveWalk.Mode mode) {
    }

    private static FakeCells overworld(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    private static Terrain random(String name, String resource, int count, int min, int max) throws IOException {
        FakeCells cells = overworld(resource);
        return new Terrain(name, cells, TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, count, min, max),
                cells.bounds().minY(), cells.bounds().maxY(), ProgressiveWalk.Mode.EXTEND);
    }

    private static Terrain terrain(String name) throws IOException {
        return switch (name) {
            case "mountains" -> random("surface/mountains", "/overworld_mountains.txt.gz", 5, 120, 200);
            case "coast" -> random("surface/coast", "/overworld_coast.txt.gz", 5, 120, 200);
            case "wide" -> random("surface/wide(short)", "/overworld_wide.txt.gz", 4, 120, 260);
            case "wideLong" -> random("surface/wide(long)", "/overworld_wide.txt.gz", 4, 200, 450);
            case "end" -> random("End", "/end_terrain_columns.txt.gz", 4, 120, 220);
            case "nether" -> new Terrain("Nether", NetherLiveWalkTest.terrain(), NetherLiveWalkTest.routes(),
                    NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, ProgressiveWalk.Mode.REPAIR);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private record Arm(String name, Function<ClosureGraph, Function<BlockPos, CostToGo>> guideAt, double weight) {

        Arm(String name, Function<ClosureGraph, CostToGo> guide, double weight, boolean fixed) {
            this(name, graph -> {
                CostToGo built = guide.apply(graph);
                return player -> built;
            }, weight);
        }
    }

    private static List<Arm> arms(Terrain terrain, BlockPos rawStart, BlockPos rawGoal, BlockPos start, BlockPos goal,
                                  double[] perfect, Map<String, String> notes) {
        List<Arm> arms = new ArrayList<>();
        boolean nether = terrain.mode() == ProgressiveWalk.Mode.REPAIR;
        arms.add(new Arm("A current", graph -> nether
                ? XaeroMapModel.guide(terrain.cells(), rawStart, rawGoal, terrain.minY(), terrain.maxY(), 1.0, 0L)
                : null, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, true));
        arms.add(new Arm("P perfect", graph -> graph.guide(perfect), 1.0, true));
        arms.add(new Arm("B window160+outside compressed(real)", graph -> {
            double[] compressed = graph.distancesTo(goal, SectionCompression.compress(graph, 4, false).kept());
            CostToGo far = graph.guide(compressed);
            return windowArm(graph, goal, far);
        }, 1.0));
        arms.add(new Arm("B window160+outside existing coarse layer", graph -> {
            CostToGo far = nether
                    ? XaeroMapModel.guide(terrain.cells(), rawStart, rawGoal, terrain.minY(), terrain.maxY(), 1.0, 0L)
                    : CoarseRouter.costToGo(LiveCoarseSampler.sample(terrain.cells(), terrain.cells().bounds(),
                            start.getY(), () -> false), goal, false, CoarseRouter.BridgePolicy.BRIDGE);
            return windowArm(graph, goal, far);
        }, 1.0));
        arms.add(new Arm("B window160+outside geometric", graph -> windowArm(graph, goal,
                (ClosureGraph.OutsideValue) (x, y, z) -> Double.POSITIVE_INFINITY), 1.0));
        CellSource synthCells = nether ? XaeroSynthCells.floors(terrain.cells()) : XaeroSynthCells.surface(terrain.cells());
        arms.add(new Arm("B window160+outside Xaero-synth natural only", graph -> {
            BlockPos synthGoal = StanceFinder.resolveGoal(synthCells, goal);
            BlockPos synthStart = StanceFinder.resolveStart(synthCells, start);
            ClosureGraph synthGraph = ClosureGraph.build(synthCells, synthStart, synthGoal, ClosureGraph.box(
                    synthCells, synthStart, synthGoal, CLOSURE_MARGIN, terrain.minY(), terrain.maxY()), true);
            if (synthGraph.idAt(synthGoal.getX(), synthGoal.getY(), synthGoal.getZ()) < 0) {
                return windowArm(graph, goal, (ClosureGraph.OutsideValue) (x, y, z) -> Double.POSITIVE_INFINITY);
            }
            double[] synthDistance = synthGraph.distancesTo(synthGoal, synthGraph.edgesBetween(1 << ClosureGraph.NATURAL));
            return windowArm(graph, goal,
                    (ClosureGraph.OutsideValue) (x, y, z) -> synthGraph.nearestValue(synthDistance, 3, x, y, z));
        }, 1.0));
        arms.add(new Arm("S shell 8/2 everywhere", graph -> graph.nearestGuide(graph.distancesTo(goal, graph.shellEdges(8, 2)), 3),
                1.0, true));
        return arms;
    }

    /** A guide that, per segment, re-solves the player's window exactly and puts {@code far}'s values on the boundary outside it. */
    private static Function<BlockPos, CostToGo> windowArm(ClosureGraph graph, BlockPos goal, CostToGo far) {
        return windowArm(graph, goal, (ClosureGraph.OutsideValue) far::estimate);
    }

    /** Variant that doesn't seed the boundary with points whose outside value is unknown ({@link Double#POSITIVE_INFINITY}). */
    private static Function<BlockPos, CostToGo> windowArm(ClosureGraph graph, BlockPos goal,
                                                          ClosureGraph.OutsideValue far) {
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (!player.equals(last[0])) {
                cached[0] = graph.windowGuide(goal, player, WINDOW, far);
                last[0] = player;
            }
            return cached[0];
        };
    }

    private static void measure(String name) throws IOException {
        Terrain terrain = terrain(name);
        FakeCells cells = terrain.cells();
        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (BlockPos[] route : terrain.routes()) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            ClosureGraph graph = ClosureGraph.build(cells, start, goal,
                    ClosureGraph.box(cells, start, goal, CLOSURE_MARGIN, terrain.minY(), terrain.maxY()));
            double[] perfect = graph.distancesTo(goal, null);
            PathResult check = new AStarPathfinder(cells, new SearchLimits(3_000_000, 120_000, 1.0),
                    graph.guide(perfect)).search(start, goal, () -> false);
            System.out.printf(Locale.ROOT, "%s->%s baseline%.0f closure%dnodes/%dedges/%dms check%+.2f%%(%dnodes)%n",
                    start.toShortString(), goal.toShortString(), best, graph.nodes(), graph.edges(),
                    graph.buildMillis, 100.0 * (ProgressiveWalk.cost(check.steps()) / best - 1.0),
                    check.expandedNodes());
            for (Arm arm : arms(terrain, route[0], route[1], start, goal, perfect, notes)) {
                long began = System.currentTimeMillis();
                Function<BlockPos, CostToGo> guide = arm.guideAt().apply(graph);
                long guideMillis = System.currentTimeMillis() - began;
                ProgressiveWalk.Aim aim = arm.name().startsWith("A ") && terrain.mode() != ProgressiveWalk.Mode.REPAIR
                        ? ProgressiveWalk.Aim.HORIZON : ProgressiveWalk.Aim.GOAL;
                ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, start, goal, WINDOW, terrain.mode(), aim,
                        guide, arm.weight());
                double ratio = trace.steps().isEmpty() ? Double.POSITIVE_INFINITY
                        : ProgressiveWalk.cost(trace.steps()) / best;
                System.out.printf(Locale.ROOT, "  %-18s %.3fx seams%d guide%dms %s%n", arm.name(), ratio,
                        trace.joints().size(), guideMillis, trace.stopped());
                ratios.computeIfAbsent(arm.name(), k -> new ArrayList<>()).add(ratio);
            }
        }
        System.out.println("== " + terrain.name());
        ratios.forEach((arm, list) -> {
            double mean = list.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).average().orElse(0);
            double worst = list.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            long missed = list.stream().filter(r -> !Double.isFinite(r)).count();
            System.out.printf(Locale.ROOT, "  %-18s mean%.3f worst%.3f unreached%d %s%n", arm, mean, worst, missed,
                    notes.getOrDefault(arm, ""));
        });
    }

    @Test
    void mountains() throws IOException {
        measure("mountains");
    }

    @Test
    void coast() throws IOException {
        measure("coast");
    }

    @Test
    void wide() throws IOException {
        measure("wide");
    }

    @Test
    void wideLong() throws IOException {
        measure("wideLong");
    }

    @Test
    void end() throws IOException {
        measure("end");
    }

    @Test
    void nether() throws IOException {
        measure("nether");
    }

    /** In the Nether, re-tests the upper limit of the endpoint-choice gamble against a guide that's perfect even in shape. */
    @Test
    void netherFallbackBudgetWithPerfectGuide() throws IOException {
        Terrain terrain = terrain("nether");
        FakeCells cells = terrain.cells();
        for (BlockPos[] route : terrain.routes()) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            ClosureGraph graph = ClosureGraph.build(cells, start, goal,
                    ClosureGraph.box(cells, start, goal, CLOSURE_MARGIN, terrain.minY(), terrain.maxY()));
            CostToGo perfect = graph.guide(graph.distancesTo(goal, null));
            for (double weight : new double[] {1.0, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT}) {
                for (boolean budget : new boolean[] {true, false}) {
                    AStarPathfinder.fallbackBudgetEnabled = budget;
                    try {
                        ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                                terrain.mode(), ProgressiveWalk.Aim.GOAL, perfect, weight);
                        System.out.printf(Locale.ROOT, "%s weight%.1f cap%s %.3fx seams%d %s%n",
                                start.toShortString(), weight, budget ? "on" : "off",
                                trace.steps().isEmpty() ? Double.POSITIVE_INFINITY
                                        : ProgressiveWalk.cost(trace.steps()) / best,
                                trace.joints().size(), trace.stopped());
                    } finally {
                        AStarPathfinder.fallbackBudgetEnabled = true;
                    }
                }
            }
        }
    }
}
