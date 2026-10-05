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
 * <b>Measures that layer 1's cost-to-go guide is a lower bound on the actual cost.</b>
 *
 * <p>{@code AStarPathfinder} takes the <b>max</b> of the geometric Heuristic and the guide, so the moment the guide
 * exceeds the actual cost, <b>the search avoids a path that is actually cheap</b>. The path-quality ratio ({@code PathOptimalityTest})
 * only sees the outcome, but this looks at <b>the cause itself</b>. The ratio moves for other reasons too, so judging whether a
 * guide change helped by the ratio will always mislead.
 *
 * <p>The measurement is "at each point of the optimal path, does the guide's estimate stay within the actual remaining cost from there".
 * The reference path isn't strictly optimal (see the {@code PathOptimalityTest} javadoc), but
 * <b>since it's the cost of a path that actually exists, it's an upper bound on the true optimum</b>, so a guide exceeding it
 * definitely breaks the lower bound. It's a one-sided check: it may miss cases but has no false positives.
 *
 * <p><b>The stretch just before the goal isn't measured</b> ({@link #MIN_REMAINING_TICKS}). At points with only a few ticks left,
 * a tiny absolute error just inflates the ratio without affecting the search's decisions.
 */
@Tag("slow")
class GuideAdmissibilityTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final long SEED = 20260906L;

    /** Budget passed to the reference search. */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    /** Points with less remaining than this aren't measured. About 10 blocks of sprinting. */
    private static final double MIN_REMAINING_TICKS = 40.0;

    /**
     * The threshold is <b>1.00</b>: "is a lower bound" is the threshold itself. Measured values are 0.65-0.98,
     * and the margin we tightened becomes the safety zone as-is.
     */
    private static final double LIMIT = 1.00;

    private record Terrain(String name, String resource, boolean ceiling, int count, int min, int max) {
    }

    /**
     * Relief and water are weighted heavily. The guide breaks the lower bound when <b>a cell's summary statistics aren't a lower bound
     * on the best path through that cell</b>, and that happens on ridges (the representative height is above the saddle) and
     * at shorelines (a dry strip can be crossed even if most of the cell is water).
     */
    private static List<Terrain> terrains() {
        return List.of(
                new Terrain("overworld/plains+hills", "/overworld_terrain_columns.txt.gz", false, 12, 40, 120),
                new Terrain("overworld/mountains", "/overworld_mountains.txt.gz", false, 12, 40, 120),
                new Terrain("overworld/coast", "/overworld_coast.txt.gz", false, 12, 40, 120),
                new Terrain("overworld/wide", "/overworld_wide.txt.gz", false, 8, 120, 260),
                new Terrain("nether", "/nether_terrain_columns.txt.gz", true, 8, 60, 160),
                new Terrain("end", "/end_terrain_columns.txt.gz", false, 8, 60, 160));
    }

    /** Same "walking normally with tools in hand" state as {@code PathOptimalityTest}. */
    private static FakeCells walkingPlayer(SearchBounds bounds, boolean ceiling) {
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                .maxFallDamagePoints(6);
        return ceiling ? cells.openSkyYOverride(bounds.maxY()) : cells;
    }

    @Test
    void theCoarseGuideNeverOverestimatesTheRemainingCost() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Terrain terrain : terrains()) {
            FakeCells cells = TerrainFixture.load(terrain.resource(),
                    bounds -> walkingPlayer(bounds, terrain.ceiling()));
            double worst = 0.0;
            String worstAt = "";
            int measured = 0;
            for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED,
                    terrain.count(), terrain.min(), terrain.max())) {
                PathResult best = new AStarPathfinder(cells,
                        new SearchLimits(UNLIMITED_NODE_BUDGET, 120_000, 1.0))
                        .search(route[0], route[1], NEVER);
                if (!best.complete()) {
                    continue;
                }
                measured++;
                // Built the same way as in the real game (same arguments as `PathfindingExecutor#buildCostToGoGuide`)
                CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), route[0].getY(), NEVER);
                CostToGo guide = CoarseRouter.costToGo(map, route[1], false,
                        CoarseRouter.BridgePolicy.BRIDGE);
                double remaining = best.steps().stream().mapToDouble(PathStep::cost).sum();
                BlockPos at = route[0];
                for (PathStep step : best.steps()) {
                    if (remaining >= MIN_REMAINING_TICKS) {
                        double ratio = guide.estimate(at.getX(), at.getY(), at.getZ()) / remaining;
                        if (ratio > worst) {
                            worst = ratio;
                            worstAt = at.toShortString() + " (" + Math.round(remaining) + " ticks left)";
                        }
                    }
                    remaining -= step.cost();
                    at = step.pos();
                }
            }
            report.add(String.format(Locale.ROOT, "%-12s %2d paths worst %.3fx %s",
                    terrain.name(), measured, worst, worstAt));
            if (measured == 0) {
                failures.add(terrain.name() + ": not a single path found (terrain or coordinates are wrong)");
            } else if (worst > LIMIT) {
                failures.add(String.format(Locale.ROOT,
                        "%s: guide exceeds the actual remaining cost by up to %.3fx %s",
                        terrain.name(), worst, worstAt));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
