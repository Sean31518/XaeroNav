package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>Measures how far guided paths on real terrain are from the best.</b>
 * The terrain was exported from save data with {@code tools/dump_terrain_columns.py}.
 *
 * <p>Unlike other tests that state "this path should come out" for individual terrain, this looks only
 * at <b>the ratio to the baseline</b>. It's for catching regressions at a scale where humans can't write
 * expected paths: 12 terrains, with start and end points drawn by a fixed-seed random generator.
 *
 * <p><b>Terrain differs in character by biome</b>, so measuring on one terrain isn't enough. Mountains
 * (relief 168) probe the pricing of climbing and descending, the coast (65% water) how water is crossed,
 * the basalt deltas lava and fine relief, the jungle the canopy (layer 1's representative height lands on
 * the leaves), and the swamp shallow water ({@code WALK_ONE_IN_WATER}), each in its own way. Rather than
 * writing "on this terrain..." one by one, the aim of this form is to look only at the ratios together.
 *
 * <p><b>What's measured here is 40-90-block paths solved in a single search.</b> The real game solves that
 * distance in one go only up to the search horizon (96 blocks); the quality of longer paths is measured by
 * {@code LongRouteOptimalityTest}, including layer 1 and segmentation.
 *
 * <p><b>The baseline is a search on the same {@code CellSource} with weight 1.0, no layer 1 guide and an
 * effectively unlimited budget.</b> The difference from the production configuration (weight
 * {@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}, guide on, default budget) is entirely
 * <b>non-optimality introduced by the implementation</b>. The baseline uses the same search engine, so it
 * isn't strictly optimal either (by the quantization of {@code orderingCost} and not reopening closed nodes).
 *
 * <p><b>What it can't catch: errors in the cost model itself.</b> Both sides share the same model, so the
 * ratio stays at 1.00. Whether values like the "dig or detour" exchange rate are sensible can only be
 * judged by a human ({@code TerrainEditVersusDetourTest}).
 */
@Tag("slow")
class PathOptimalityTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** The real game's default budget ({@code PathfindingState}). */
    private static final int PRODUCTION_NODE_BUDGET = 100_000;

    /**
     * The weight the real game <b>actually uses for paths of this distance</b>. {@code PathfindingState}
     * runs the normal budget and the deep budget in parallel, with a lighter weight (1.2) only on the normal
     * side. For the 40-90-block paths measured here the normal side usually wins, so measuring with its
     * weight is closest to the real game. For long distances the normal side can't reach, it falls back to the
     * deep side's {@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}.
     */
    private static final double PRODUCTION_WEIGHT = 1.2;

    /** Budget for the baseline search. Measured at most around 300k nodes, so effectively unlimited. */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    /** Same value as {@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}. Budget multiplier for the deep fallback. */
    private static final int DEEP_SEARCH_BUDGET_FACTOR = 8;

    /**
     * Random seed. <b>Fixing it is the point</b>: measuring different paths every time means failures can't be
     * reproduced, and you can't tell whether a harsh set just happened to be drawn or things really got worse.
     */
    private static final long SEED = 20260904L;

    /** Number of paths measured per terrain. */
    private static final int ROUTES_PER_TERRAIN = 20;

    private static final int MIN_ROUTE_BLOCKS = 40;
    private static final int MAX_ROUTE_BLOCKS = 90;

    /** Line catching overall regressions. Measured 0.823-1.014. */
    private static final double MEAN_LIMIT = 1.05;

    /** Line that fails if even one path is catastrophic. Measured 1.020-1.067. */
    private static final double WORST_LIMIT = 1.15;

    /**
     * How many times the baseline path's wasted up-and-down (ascent + descent minus net elevation change) is allowed.
     *
     * <p>Guards against the user report "on flat ground the route dips down 1-2 blocks and back up". <b>The
     * point is to compare against the baseline rather than the terrain's own relief</b>: going up and down is
     * natural in mountains, so it can't be measured in absolute terms. Since the cost model prices climbing
     * and descending ({@code ActionCosts#STEP_TRANSITION_TICKS}), the baseline path should avoid wasted
     * up-and-down, and any deviation from it is the implementation's share.
     *
     * <p><b>It won't be 1.00.</b> What remains is the weight ({@link #PRODUCTION_WEIGHT}) itself: the lower it
     * is, the straighter (savanna: 1.5 → 1.62x / 1.2 → 1.24x / 1.0 → 1.05x). But lowering it expands 3-5x more
     * nodes, and long mountain paths start failing to reach within the default budget (1 → 3 out of 20). The
     * real game absorbs that with the parallel deep-budget search
     * ({@code PathfindingState#QUALITY_HEURISTIC_WEIGHT}).
     *
     * <p>Measured: Overworld/plains 1.05, mountains 0.96, savanna 0.93, coast 1.04, forest 0.67, jungle 0.99,
     * swamp 1.01, Nether 0.43-1.00, End 1.40.
     */
    private static final double WOBBLE_LIMIT = 1.50;

    /**
     * Allowed number of paths per terrain that the baseline reached but production (including the deep-budget fallback) didn't.
     *
     * <p>It can't be 0: Nether/soul sand valley has one path with an orders-of-magnitude larger search space
     * (unreachable even with a deep budget of 800k regardless of the guide, reached only past 1M nodes with
     * weight 1.5 and an unlimited budget), and 1 in 20 is a limit specific to that terrain. The cause (soul
     * sand slowdown combined with the Nether's peculiarly convoluted terrain bloating the search) has not been
     * investigated. Here only regressions (2 or more) are detected.
     */
    private static final int UNREACHABLE_LIMIT_PER_TERRAIN = 1;

    private record Terrain(String name, String resource, boolean ceiling) {
    }

    /** Walking normally with tools. Kept the same regardless of terrain so differences come only from the terrain. */
    private static FakeCells walkingPlayer(SearchBounds bounds, boolean ceiling) {
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                .maxFallDamagePoints(6);
        // In the Nether the bedrock ceiling is above the exported box. Like the implementation's `ChunkView`, make the sky not open
        return ceiling ? cells.openSkyYOverride(bounds.maxY()) : cells;
    }

    private static List<Terrain> terrains() {
        return List.of(
                new Terrain("Overworld/plains-hills", "/overworld_terrain_columns.txt.gz", false),
                new Terrain("Overworld/mountains", "/overworld_mountains.txt.gz", false),
                new Terrain("Overworld/savanna", "/overworld_savanna.txt.gz", false),
                new Terrain("Overworld/coast", "/overworld_coast.txt.gz", false),
                new Terrain("Overworld/forest", "/overworld_forest.txt.gz", false),
                new Terrain("Overworld/jungle", "/overworld_jungle.txt.gz", false),
                new Terrain("Overworld/swamp", "/overworld_swamp.txt.gz", false),
                new Terrain("Nether/wastes", "/nether_terrain_columns.txt.gz", true),
                new Terrain("Nether/basalt", "/nether_basalt_deltas.txt.gz", true),
                new Terrain("Nether/soul", "/nether_soul_sand_valley.txt.gz", true),
                new Terrain("Nether/crimson forest", "/nether_crimson_forest.txt.gz", true),
                new Terrain("End", "/end_terrain_columns.txt.gz", false));
    }

    private static double cost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    /** Ascent + descent minus net elevation change. Zero for a path climbing straight up. */
    private static int wobble(BlockPos start, PathResult result) {
        int up = 0;
        int down = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            int dy = step.pos().getY() - previous.getY();
            up += Math.max(0, dy);
            down += Math.max(0, -dy);
            previous = step.pos();
        }
        int net = Math.abs(up - down);
        return up + down - net;
    }

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal, double weight,
                                     boolean guided, int budget) {
        CostToGo guide = null;
        if (guided) {
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), NEVER);
            guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
        }
        return new AStarPathfinder(cells, new SearchLimits(budget, 120_000, weight), guide)
                .search(start, goal, NEVER);
    }

    /**
     * The same two-stage approach as {@code PathfindingState#submitWithDeepFallback}. If the normal budget
     * (weight {@link #PRODUCTION_WEIGHT}) doesn't reach, try the deep budget (multiplier
     * {@link #DEEP_SEARCH_BUDGET_FACTOR}, weight {@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}). The real
     * game runs the two in parallel, but the adopted result (the normal one if it reaches, otherwise the deep
     * one) is the same when run sequentially, so it can be imitated as-is.
     */
    private static PathResult solveProduction(FakeCells cells, BlockPos start, BlockPos goal) {
        PathResult normal = solve(cells, start, goal, PRODUCTION_WEIGHT, true, PRODUCTION_NODE_BUDGET);
        if (normal.complete()) {
            return normal;
        }
        PathResult deep = solve(cells, start, goal, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, true,
                PRODUCTION_NODE_BUDGET * DEEP_SEARCH_BUDGET_FACTOR);
        return deep.complete() ? deep : normal;
    }

    @Test
    void routesStayCloseToTheBestThisSearcherCanFind() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Terrain terrain : terrains()) {
            FakeCells cells = TerrainFixture.load(terrain.resource(),
                    bounds -> walkingPlayer(bounds, terrain.ceiling()));
            double worst = 1.0;
            double total = 0.0;
            String worstRoute = "";
            int measured = 0;
            int bestWobble = 0;
            int productionWobble = 0;
            int unreachable = 0;
            String unreachableRoute = "";
            for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED,
                    ROUTES_PER_TERRAIN, MIN_ROUTE_BLOCKS, MAX_ROUTE_BLOCKS)) {
                PathResult best = solve(cells, route[0], route[1], 1.0, false, UNLIMITED_NODE_BUDGET);
                if (!best.complete()) {
                    continue;
                }
                PathResult production = solveProduction(cells, route[0], route[1]);
                if (!production.complete()) {
                    unreachable++;
                    unreachableRoute = route[0].toShortString() + "→" + route[1].toShortString();
                    continue;
                }
                measured++;
                bestWobble += wobble(route[0], best);
                productionWobble += wobble(route[0], production);
                double ratio = cost(production) / cost(best);
                total += ratio;
                if (ratio > worst) {
                    worst = ratio;
                    worstRoute = route[0].toShortString() + "→" + route[1].toShortString()
                            + String.format(Locale.ROOT, " (baseline %.0f production %.0f)",
                                    cost(best), cost(production));
                }
            }
            if (measured == 0) {
                failures.add(terrain.name() + ": no paths at all (terrain or coordinates are wrong)");
                continue;
            }
            double mean = total / measured;
            double wobbleRatio = bestWobble == 0 ? 1.0 : (double) productionWobble / bestWobble;
            report.add(String.format(Locale.ROOT,
                    "%-12s %2d paths avg %.3fx worst %.3fx wasted up/down %d/%d (%.2fx) unreached %d %s",
                    terrain.name(), measured, mean, worst, productionWobble, bestWobble,
                    wobbleRatio, unreachable, worstRoute));
            if (mean > MEAN_LIMIT) {
                failures.add(terrain.name() + ": paths are detouring overall");
            }
            if (worst > WORST_LIMIT) {
                failures.add(terrain.name() + ": a catastrophically detouring path exists " + worstRoute);
            }
            if (wobbleRatio > WOBBLE_LIMIT) {
                failures.add(terrain.name() + ": more wasted up/down than the baseline " + productionWobble
                        + " vs " + bestWobble);
            }
            if (unreachable > UNREACHABLE_LIMIT_PER_TERRAIN) {
                failures.add(terrain.name() + ": more paths that production can't reach though the baseline did "
                        + unreachable + " paths " + unreachableRoute);
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
