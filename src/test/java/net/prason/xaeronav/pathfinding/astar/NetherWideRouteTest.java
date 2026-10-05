package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>Measures long Nether distances on the very terrain where the user reported "the route is wrong".</b>
 * The terrain is real saved data ({@code x -540..-29 / z 370..881}, 512 blocks square) and includes where the user
 * was standing, {@code (-447, 74, 525)}, and the destination {@code (-259, 64, 379)}.
 *
 * <p>No guard was looking at long Nether distances: {@code PathOptimalityTest} is <b>a single A*</b> over 40 to 90
 * blocks, {@code NetherLavaSeaTest} is a one-off deep search, and {@code LongRouteOptimalityTest} only covers
 * {@code overworld_wide}. Most detours come from <b>how the route is assembled</b>, so the source cannot be measured
 * without reproducing the assembly.
 *
 * <p><b>{@code ProgressiveWalk} is not used.</b> Its legs aim at "points on the straight line to the destination"
 * (the path corresponding to {@code PathfindingState#goalOrPointToward}). What the real game takes over long distances
 * is the path that <b>aims at layer 1's intermediate targets</b> ({@code selectDetailTarget} →
 * {@code reachableWaypointTarget}), and in a Nether with lava seas the two give completely different results: points
 * on the straight line land inside rock or over lava, so measuring with that produces "no route returned" in bulk,
 * indistinguishable from the real symptom.
 */
@Tag("slow")
class NetherWideRouteTest {

    private static final String TERRAIN = "/nether_wide.txt.gz";

    /** Why the seed is fixed is explained in {@link TerrainFixture#randomRoutes}. */
    private static final long SEED = 20260907L;

    private static final int ROUTES = 8;
    private static final int MIN_ROUTE_BLOCKS = 150;
    private static final int MAX_ROUTE_BLOCKS = 400;

    /**
     * Reference height for resolving both ends of a route.
     *
     * <p><b>Do not use {@code TerrainFixture#standableY} as-is.</b> It returns the top of the column, so on fixtures
     * that include the bedrock ceiling (y123 to 127) it returns <b>the top of the ceiling</b>. But "the highest floor
     * under the ceiling" does not match reality either: it lands on isolated ledges near the ceiling, giving mostly pairs
     * of points connected to nothing. Pick the floor closest to the height where the user was actually standing.
     */
    private static final int TYPICAL_WALKING_Y = 74;

    /** Upper limit for finding a floor. Below the bedrock ceiling (123 to 127). */
    private static final int UNDER_THE_CEILING = 118;

    /** Where the user was standing when reporting the symptom, and the destination they set. */
    private static final BlockPos REPORTED_FROM = new BlockPos(-447, 74, 525);
    private static final BlockPos REPORTED_TO = new BlockPos(-259, 64, 379);

    /** Search budget per leg. The in-game default (100,000 nodes, 2 seconds). */
    private static final SearchLimits LEG_LIMITS =
            new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * Budget when re-throwing a leg that did not reach. Same as the in-game {@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}
     * (8 times) and {@code DEEP_SEARCH_MAX_MILLIS} (30 seconds).
     */
    private static final SearchLimits DEEP_LEG_LIMITS =
            new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * Maximum number of re-throws toward one intermediate target.
     *
     * <p><b>Even if a leg does not reach, the part drawn so far is still guidance.</b> The real game extends from the end
     * ({@code PathfindingState#extendPath}), so the next one is thrown from the end of the cut-off route.
     * Treating "unreached = dead end" here would make the measurement more pessimistic than the real game.
     */
    private static final int LEG_ATTEMPTS = 4;

    /** Intermediate targets are at chunk resolution, so they are aimed at as region goals, as in the real game. */
    private static final int LEG_GOAL_RADIUS = 16;

    /** If an intermediate target's Y is farther than this from the real terrain floor, it cannot be landed on. */
    private static final int STANDABLE_TOLERANCE_BLOCKS = 8;

    /** Equivalent to an in-game render distance of 10 chunks. {@code SearchBounds.around} cuts the box with this. */
    private static final int WINDOW_RADIUS = 160;

    /** Maximum number of extensions. */
    private static final int MAX_SEGMENTS = 24;

    /**
     * Line that catches overall regressions. Measured at <b>an average of 1.377 times</b> (all 4 routes with a solved
     * baseline arrived; the user-reported coordinates are 1.060 times).
     *
     * <p>Back when it stopped at intermediate targets, the average was 2.964 times, worst 3.345 times, and one route
     * was a dead end. It shrank this far by changing the search goal to the final destination (in dimensions with a
     * ceiling). The same measurement on the Overworld ({@code LongRouteOptimalityTest}) is 1.046 times.
     */
    private static final double MEAN_LIMIT = 1.50;

    /** Line that fails if even one route is catastrophic. Measured worst is 1.688 times (the old implementation was 3.345 times). */
    private static final double WORST_LIMIT = 1.80;

    /**
     * Allowed number of routes that "dead-end even though the baseline connects". <b>0</b>: the old implementation had
     * one, but since aiming at the destination, all 4 routes with a solved baseline arrive.
     */
    private static final int ALLOWED_DEAD_ENDS = 0;

    private static FakeCells terrain() throws IOException {
        // Match the in-game defaults (maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96, fall tolerance 6)
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static boolean standableAt(CellSource cells, int x, int y, int z) {
        return CellData.standable(cells.cell(x, y - 1, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z));
    }

    /** The "standable Y" closest to {@link #TYPICAL_WALKING_Y}. {@link Integer#MIN_VALUE} if none. */
    private static int walkableY(CellSource cells, SearchBounds bounds, int x, int z) {
        int floor = bounds.minY() + 1;
        for (int offset = 0; offset <= UNDER_THE_CEILING; offset++) {
            int up = TYPICAL_WALKING_Y + offset;
            if (up <= UNDER_THE_CEILING && standableAt(cells, x, up, z)) {
                return up;
            }
            int down = TYPICAL_WALKING_Y - offset;
            if (down > floor && standableAt(cells, x, down, z)) {
                return down;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BlockPos onGround(FakeCells cells, int x, int z) {
        int y = walkableY(cells, cells.bounds(), x, z);
        return y == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }

    private static List<BlockPos[]> routes(FakeCells cells) {
        SearchBounds bounds = cells.bounds();
        Random random = new Random(SEED);
        List<BlockPos[]> routes = new ArrayList<>();
        routes.add(new BlockPos[] {onGround(cells, REPORTED_FROM.getX(), REPORTED_FROM.getZ()),
                onGround(cells, REPORTED_TO.getX(), REPORTED_TO.getZ())});
        int attempts = 0;
        while (routes.size() < ROUTES && attempts++ < 4000) {
            int x = bounds.minX() + 24 + random.nextInt(bounds.maxX() - bounds.minX() - 48);
            int z = bounds.minZ() + 24 + random.nextInt(bounds.maxZ() - bounds.minZ() - 48);
            BlockPos start = onGround(cells, x, z);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = MIN_ROUTE_BLOCKS + random.nextInt(MAX_ROUTE_BLOCKS - MIN_ROUTE_BLOCKS + 1);
            int goalX = start.getX() + (int) Math.round(distance * Math.cos(angle));
            int goalZ = start.getZ() + (int) Math.round(distance * Math.sin(angle));
            if (!cells.isInBounds(goalX, TYPICAL_WALKING_Y, goalZ)) {
                continue;
            }
            BlockPos goal = onGround(cells, goalX, goalZ);
            if (goal != null) {
                routes.add(new BlockPos[] {start, goal});
            }
        }
        return routes;
    }

    /** The same ladder as the real game ({@code PathfindingState#computeCoarseRoute}). */
    private record Attempt(CoarseRouter.BridgePolicy reachedWith, CoarseRouter.Route route) {

        String describe() {
            return (reachedWith == null ? "unreached" : reachedWith.toString())
                    + "/intermediate targets " + route.waypoints().size();
        }
    }

    private static Attempt ladder(CoarseMap map, BlockPos start, BlockPos goal) {
        CoarseRouter.Route furthest = null;
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, policy);
            if (route.reachedGoal()) {
                return new Attempt(policy, route);
            }
            if (furthest == null || route.waypoints().size() > furthest.waypoints().size()) {
                furthest = route;
            }
        }
        return new Attempt(null, furthest);
    }

    /** Whether there is a standable spot on the real terrain near that intermediate target's Y (checking the whole cell). */
    private static boolean standableThere(FakeCells terrain, BlockPos waypoint) {
        int baseX = (waypoint.getX() >> 4) << 4;
        int baseZ = (waypoint.getZ() >> 4) << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = waypoint.getY() - STANDABLE_TOLERANCE_BLOCKS;
                        y <= waypoint.getY() + STANDABLE_TOLERANCE_BLOCKS; y++) {
                    if (terrain.isInBounds(x, y, z) && standableAt(terrain, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int unstandableWaypoints(FakeCells terrain, CoarseRouter.Route route) {
        int bad = 0;
        for (BlockPos waypoint : route.waypoints()) {
            if (!standableThere(terrain, waypoint)) {
                bad++;
            }
        }
        return bad;
    }

    /**
     * <b>The current implementation (behavior in dimensions with a ceiling).</b>
     * The search goal is always the final destination, and layer 1 is used only as a {@code cost-to-go} guide. Partial
     * routes cut off by the box are extended from their end.
     */
    private static Walk followGoalAimed(FakeCells cells, BlockPos start, BlockPos goal, CoarseMap map) {
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
        BlockPos from = start;
        double cost = 0;
        int steps = 0;
        int segments = 0;
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            PathResult result = new AStarPathfinder(new WindowedCells(cells, from, WINDOW_RADIUS),
                    LEG_LIMITS, guide).search(from, goal, () -> false);
            if (result.steps().isEmpty()) {
                // The same escalation as the real game (PathfindingState#DEEP_SEARCH_BUDGET_FACTOR)
                result = new AStarPathfinder(new WindowedCells(cells, from, WINDOW_RADIUS),
                        DEEP_LEG_LIMITS, guide).search(from, goal, () -> false);
            }
            if (result.steps().isEmpty()
                    || result.steps().get(result.steps().size() - 1).pos().equals(from)) {
                return new Walk(cost, steps, segments, false);
            }
            for (PathStep step : result.steps()) {
                cost += step.cost();
            }
            steps += result.steps().size();
            segments++;
            from = result.steps().get(result.steps().size() - 1).pos();
            if (result.complete()) {
                return new Walk(cost, steps, segments, true);
            }
        }
        return new Walk(cost, steps, segments, false);
    }

    /** Route assembled by following intermediate targets in order. Same shape as the real game's leg splitting and extension. */
    private record Walk(double cost, int steps, int reachedLegs, boolean arrived) {
    }

    private static Walk follow(FakeCells terrain, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        int steps = 0;
        int reached = 0;
        for (BlockPos waypoint : route.waypoints()) {
            boolean arrived = false;
            for (int attempt = 0; attempt < LEG_ATTEMPTS && !arrived; attempt++) {
                SearchLimits limits = attempt == 0 ? LEG_LIMITS : DEEP_LEG_LIMITS;
                PathResult result =
                        executor.submit(terrain, from, waypoint, limits, true, LEG_GOAL_RADIUS).get();
                if (result.steps().isEmpty()) {
                    break;
                }
                for (PathStep step : result.steps()) {
                    cost += step.cost();
                }
                steps += result.steps().size();
                from = result.steps().get(result.steps().size() - 1).pos();
                arrived = result.complete();
            }
            if (!arrived) {
                return new Walk(cost, steps, reached, false);
            }
            reached++;
        }
        return new Walk(cost, steps, reached, route.reachedGoal());
    }

    @Test
    void longNetherRoutesFollowTheirWaypointsAndAreNotWildlyLonger() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Double> ratios = new ArrayList<>();
        List<String> deadEnds = new ArrayList<>();

        for (BlockPos[] route : routes(cells)) {
            BlockPos start = route[0];
            BlockPos goal = route[1];
            String name = start.toShortString() + "→" + goal.toShortString();
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
            Attempt attempt = ladder(map, start, goal);
            int unstandable = unstandableWaypoints(cells, attempt.route());
            Walk viaWaypoints = follow(cells, start, attempt.route());
            Walk walk = followGoalAimed(cells, start, goal, map);
            report.add(String.format(Locale.ROOT,
                    "%3.0f blocks baseline%s layer1=%s unstandable targets %d extensions %d (targets %d) route%s %s",
                    ProgressiveWalk.horizontal(start, goal),
                    Double.isFinite(best) ? String.format(Locale.ROOT, "%6.0f", best) : "unsolved",
                    attempt.describe(), unstandable, walk.reachedLegs(),
                    attempt.route().waypoints().size(),
                    !walk.arrived() ? "  unreached"
                            : Double.isFinite(best)
                                    ? String.format(Locale.ROOT, "%6.0f(%.3fx)", walk.cost(),
                                            walk.cost() / best)
                                    : String.format(Locale.ROOT, "%6.0f(no baseline)", walk.cost()),
                    name));

            if (!Double.isFinite(best)) {
                // Two points whose baseline (one search with full visibility, weight 1.0, no guide) could not be solved within budget.
                // <b>This is not proof that they "are not connected"</b>: in fact, one of the routes whose baseline
                // could not be solved here does arrive by following layer 1's intermediate targets. No ratio is available, so it is just excluded from measurement
                continue;
            }
            if (unstandable > 0) {
                failures.add(name + ": layer 1 laid out " + unstandable + " intermediate targets that cannot be stood on in the real terrain");
            }
            if (!walk.arrived()) {
                deadEnds.add(name + ": the baseline connects in " + Math.round(best)
                        + " ticks, but following layer 1's intermediate targets dead-ends at leg "
                        + walk.reachedLegs() + "/" + attempt.route().waypoints().size()
                        + " (" + attempt.describe() + ")");
                continue;
            }
            ratios.add(walk.cost() / best);
        }

        report.addAll(deadEnds);
        if (deadEnds.size() > ALLOWED_DEAD_ENDS) {
            failures.add("there are " + deadEnds.size() + " dead-end routes (allowed " + ALLOWED_DEAD_ENDS + ")");
        }
        report.add(check(ratios, failures));
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    private static String check(List<Double> ratios, List<String> failures) {
        if (ratios.isEmpty()) {
            failures.add("not a single route could be measured");
            return "no measurable routes";
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double worst = ratios.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (mean > MEAN_LIMIT) {
            failures.add("long Nether routes are detouring overall "
                    + String.format(Locale.ROOT, "%.3fx", mean));
        }
        if (worst > WORST_LIMIT) {
            failures.add("there is a catastrophically roundabout long route " + String.format(Locale.ROOT, "%.3fx", worst));
        }
        return String.format(Locale.ROOT, "%d routes avg %.3fx worst %.3fx", ratios.size(), mean, worst);
    }
}
