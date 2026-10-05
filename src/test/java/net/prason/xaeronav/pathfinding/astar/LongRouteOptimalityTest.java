package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>For paths over 200 blocks, measures separately whether detours come from "layer 1 + segmentation" or
 * from "the narrowness of the window".</b>
 *
 * <p>{@code PathOptimalityTest} measures 40-90 blocks, and moreover <b>paths solved by a single A*</b>.
 * The real game doesn't solve that distance in one go: layer 1's cost-to-go guide decides the big picture,
 * it's cut into segments every {@link ProgressiveWalk#DETAIL_HORIZON}, and extended from the end. Long
 * detours mostly come from that assembly, so <b>the source can't be measured without reproducing the assembly</b>.
 *
 * <p>Each route is measured three ways:
 * <ul>
 * <li><b>Baseline</b>: a single search with full visibility, weight 1.0 and no guide</li>
 * <li><b>Full visibility</b>: the same assembly as the real game with the whole world visible. The difference
 *     from the baseline is the share of <b>layer 1's resolution (16 blocks) and segmentation</b></li>
 * <li><b>Window 160</b>: additionally applies a window equivalent to a render distance of 10 chunks. The difference from full visibility is the share of <b>the window's narrowness</b></li>
 * </ul>
 *
 * <p><b>The window's share is nearly zero.</b> Detours come from layer 1 and segmentation, and widening the
 * window doesn't shrink them. This test first put that into numbers, revealing the culprit as <b>layer 1's
 * guide violating the lower bound</b> ({@code GuideAdmissibilityTest}); before the fix the average was 1.119x.
 *
 * <p><b>The End isn't included here</b> because island hopping beyond 200 blocks needs
 * {@code PathfindingExecutor}'s relaxation ladder (lifting the cap on bridge run length, etc.), which
 * {@link ProgressiveWalk} doesn't reproduce (measured: 3 of 6 routes end with no path).
 * Bringing in a ladder bound by wall-clock time would make results depend on CI speed, so as a guard we chose to leave it out.
 * Long distances in The End are covered by {@code ProgressiveDiscoveryTest} and {@code RealEndTerrainTest}.
 */
@Tag("slow")
class LongRouteOptimalityTest {

    /** Why the seed is fixed is explained in {@link TerrainFixture#randomRoutes}. */
    private static final long SEED = 20260906L;

    /** Radius of the loaded window. Equivalent to a render distance of 10 chunks. */
    private static final int WINDOW_RADIUS = 160;

    /**
     * Terrain half sea and half land, including a 512-block open-ocean crossing. <b>It's narrowed to one</b>
     * because the baseline search with weight 1.0 and no guide takes 1-7 seconds per route: varying distance on
     * the same terrain matters more for layer 1's resolution than adding terrains (how many layer 1 cells are crossed determines the effect).
     */
    private static final String TERRAIN = "/overworld_wide.txt.gz";

    private static final int ROUTES = 8;
    private static final int MIN_ROUTE_BLOCKS = 200;
    private static final int MAX_ROUTE_BLOCKS = 450;

    /** Line catching overall regressions. Measured: full visibility 1.046, window 1.045. */
    private static final double MEAN_LIMIT = 1.10;

    /** Line that fails if even one path is catastrophic. Measured: full visibility 1.078, window 1.093. */
    private static final double WORST_LIMIT = 1.20;

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void longRoutesShowWhereTheDetourComesFrom() throws IOException {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Double> openRatios = new ArrayList<>();
        List<Double> windowedRatios = new ArrayList<>();
        int overlaps = 0;
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, ROUTES,
                MIN_ROUTE_BLOCKS, MAX_ROUTE_BLOCKS)) {
            String name = route[0].toShortString() + "→" + route[1].toShortString();
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            List<PathStep> openWalk = ProgressiveWalk.walk(cells, route[0], route[1],
                    ProgressiveWalk.NO_WINDOW, true);
            List<PathStep> windowedWalk = ProgressiveWalk.walk(cells, route[0], route[1],
                    WINDOW_RADIUS, true);
            if (!Double.isFinite(best) || openWalk.isEmpty() || windowedWalk.isEmpty()) {
                failures.add(name + ": no path returned (baseline " + best + ")");
                continue;
            }
            double open = ProgressiveWalk.cost(openWalk);
            double windowed = ProgressiveWalk.cost(windowedWalk);
            overlaps += ProgressiveWalk.selfOverlaps(openWalk)
                    + ProgressiveWalk.selfOverlaps(windowedWalk);
            openRatios.add(open / best);
            windowedRatios.add(windowed / best);
            report.add(String.format(Locale.ROOT,
                    "%3.0f blocks baseline %6.0f full %6.0f (%.3fx) window %6.0f (%.3fx) window share %.3fx %s",
                    ProgressiveWalk.horizontal(route[0], route[1]), best, open, open / best,
                    windowed, windowed / best, windowed / open, name));
        }
        if (openRatios.size() < ROUTES) {
            failures.add("only " + openRatios.size() + " paths could be measured");
        }
        // The real game folds with PathLoops, but here we look before folding: make sure the seams don't
        // produce overlaps in the first place, so folding doesn't hide bugs in the assembly
        report.add("steps passing the same position twice at extension seams (before folding): " + overlaps);
        if (overlaps > 0) {
            failures.add("path re-steps the same position " + overlaps + " times at extension seams");
        }
        report.add(check("layer 1 + segmentation", openRatios, failures));
        report.add(check("including window 160", windowedRatios, failures));
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    private static String check(String what, List<Double> ratios, List<String> failures) {
        if (ratios.isEmpty()) {
            return what + ": no paths measured";
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double worst = ratios.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (mean > MEAN_LIMIT) {
            failures.add(what + ": long-distance paths are detouring overall " + String.format(Locale.ROOT, "%.3fx", mean));
        }
        if (worst > WORST_LIMIT) {
            failures.add(what + ": there is a catastrophically roundabout long route " + String.format(Locale.ROOT, "%.3fx", worst));
        }
        return String.format(Locale.ROOT, "%s: avg %.3fx worst %.3fx", what, mean, worst);
    }
}
