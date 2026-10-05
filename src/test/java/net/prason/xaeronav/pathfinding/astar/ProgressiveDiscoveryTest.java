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
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Measures how much of a detour the resulting path is in the situation where <b>chunks load while walking and the path grows
 * only as far as can be seen</b>.
 *
 * <p>{@code PathOptimalityTest} measures a single search assuming the whole world is visible. In-game that's not
 * the case: the first path is decided only within the loaded window, and is extended from its end. <b>Earlier
 * legs are never revisited</b>, so the player can keep walking along a road that is a detour in light of terrain seen later.
 * This is the user report "the stretch between the route decided earlier and the newly decided route isn't optimal".
 *
 * <p>Reproduced with {@link WindowedCells} (a world where only the area around the player is loaded) and
 * {@link ProgressiveWalk} (the same leg splitting and extension as in-game).
 *
 * <p><b>If you suspect extension, compare it with a version that replans every time.</b> {@link #extendingAndReplanningCostTheSame}
 * does that, and in measurements there's no difference between the two <b>over the whole path</b> (0.99-1.06x). In other words, dropping extension
 * and replanning everything doesn't improve the whole.
 *
 * <p>However, <b>looked at locally, detours accumulate only at seams</b> ({@code SeamDetourTest}
 * measures with 64-block windows; windows containing a seam reach 1.795x at worst). This gets buried in the overall ratio, so
 * don't conclude "seams aren't bad" from the numbers here alone.
 *
 * <p>This looks at 5 hand-picked routes. <b>Statistically measuring long distances with fixed-seed randomness is done by
 * {@code LongRouteOptimalityTest}</b>, which reports the window's share and layer 1's share separately.
 */
@Tag("slow")
class ProgressiveDiscoveryTest {

    /** Radius (blocks) of the loaded window. Equivalent to render distances of 6 and 10 chunks. */
    private static final int[] WINDOW_RADII = {96, 160};

    /**
     * Ratio allowed relative to the full-visibility optimum.
     *
     * <p>Measured: Overworld 1.05-1.11, End 1.02, the straightforward Nether stretch 1.00-1.02, <b>Nether 2 at 1.29-1.35</b>.
     * Nether 2 stands out because it's a 3D maze where whether passages exist outside the window decides the big picture;
     * as long as only the inside of the window is visible, this can't be tightened in principle. <b>This line is for "noticing if it gets
     * worse than now"</b>, not a proof of optimality.
     */
    private static final double WORST_LIMIT = 1.40;

    /** If the replanning version gets cheaper than this, the seams are worth suspecting. Measured at 0.99-1.06x. */
    private static final double REPLAN_ADVANTAGE_LIMIT = 1.10;

    private record Route(String name, String resource, BlockPos start, BlockPos goal) {
    }

    private static List<Route> routes() {
        return List.of(
                new Route("Overworld", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(30, 0, 30), new BlockPos(230, 0, 220)),
                new Route("Overworld 2", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(230, 0, 30), new BlockPos(40, 0, 210)),
                new Route("Nether", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-180, 0, -180), new BlockPos(-20, 0, -20)),
                // A 3D maze where whether passages exist outside the window decides the big picture; the hardest shape for this approach
                new Route("Nether 2", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-20, 0, -180), new BlockPos(-180, 0, -30)),
                new Route("End", "/end_terrain_columns.txt.gz",
                        new BlockPos(1160, 0, 1240), new BlockPos(1260, 0, 1160)));
    }

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void routesBuiltWhileWalkingStayUsable() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            double best = ProgressiveWalk.fullVisibilityBest(all, start, goal);
            for (int radius : WINDOW_RADII) {
                List<PathStep> steps = ProgressiveWalk.walk(all, start, goal, radius, true);
                double walked = steps.isEmpty() ? Double.POSITIVE_INFINITY : ProgressiveWalk.cost(steps);
                double ratio = walked / best;
                // A single search never closes the same cell twice, so any overlap was created at a seam.
                // In the 3D-maze Nether the path comes back near itself, so this is where it shows up most
                int overlaps = ProgressiveWalk.selfOverlaps(steps);
                report.add(String.format(Locale.ROOT,
                        "%s window=%d full view=%.0f walked path=%.0f (%.3fx) overlaps %d",
                        route.name(), radius, best, walked, ratio, overlaps));
                if (overlaps > 0) {
                    failures.add(String.format(Locale.ROOT,
                            "%s window=%d steps on the same position %d times again (the path overlaps at a seam)",
                            route.name(), radius, overlaps));
                }
                if (!(ratio <= WORST_LIMIT)) {
                    failures.add(String.format(Locale.ROOT,
                            "%s window=%d is %.3fx (walking while loading detours too much)",
                            route.name(), radius, ratio));
                }
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    /**
     * <b>Extension (which doesn't revisit earlier parts) costs the same as the version that replans every time.</b>
     *
     * <p><b>A guard against fixing the wrong thing.</b> If this breaks (the replanning version becomes clearly cheaper),
     * it means reworking the approach to replan the whole path has become worthwhile. The quality of the seams themselves
     * isn't visible here ({@code SeamDetourTest} looks at that).
     */
    @Test
    void extendingAndReplanningCostTheSame() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            int radius = WINDOW_RADII[0];
            double extending = ProgressiveWalk.walkToGoal(all, start, goal, radius, true);
            double replanning = ProgressiveWalk.walkToGoal(all, start, goal, radius, false);
            report.add(String.format(Locale.ROOT, "%s extend=%.0f replan every time=%.0f (%.3fx)",
                    route.name(), extending, replanning, extending / replanning));
            if (extending > replanning * REPLAN_ADVANTAGE_LIMIT) {
                failures.add(String.format(Locale.ROOT,
                        "%s: replanning is cheaper at %.0f→%.0f. Reworking the seams is now worthwhile",
                        route.name(), extending, replanning));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
