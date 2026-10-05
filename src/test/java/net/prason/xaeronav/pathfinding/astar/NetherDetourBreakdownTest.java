package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>Which layer is to blame for long Nether routes coming out about 3x the baseline ({@link NetherWideRouteTest})?</b>
 * For a single route, peels the real game's search apart component by component and measures each:
 *
 * <ol>
 * <li><b>Baseline</b>: a single search with full visibility, weight 1.0, and no guide (this is 1.000x)</li>
 * <li><b>Weight only</b>: no guide, weight {@value AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}.
 *     The share due to weighted A\*'s greediness</li>
 * <li><b>Guide only</b>: layer 1's cost-to-go guide, weight 1.0. If the guide breaks the lower bound,
 *     it deviates from optimal even at weight 1.0</li>
 * <li><b>Weight + guide</b>: the same settings as one real-game leg, run end to end without splitting into legs</li>
 * <li>Comparison of <b>stopping by intermediate targets (old implementation) vs. aiming at the destination (current
 *     implementation)</b>. Reports expanded node counts as well as quality; in the Overworld the switch is a loss, so that
 *     is measured with the same yardstick too</li>
 * </ol>
 *
 * <p>Alternatives not adopted here (retreating to an intermediate target only when stuck / cutting the box / placing a
 * corridor around intermediate targets / loosening the aim radius) were also measured once. Loosening the radius was
 * counterproductive (more unreached), and the others were equal to or worse than aiming at the destination. History in [[xaeronav-architecture]].
 *
 * <p>It also measures <b>whether the guide exceeds the remaining cost at each point of the optimal path</b> (lower bound
 * violation). {@code GuideAdmissibilityTest} only looks at the Nether on 224-block-square badlands at 60-160 blocks,
 * without lava seas or long distances.
 */
@Tag("slow")
class NetherDetourBreakdownTest {

    private static final String TERRAIN = "/nether_wide.txt.gz";

    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;
    private static final long TIME_LIMIT_MILLIS = 120_000;

    /**
     * The 4 routes whose baseline was solved in {@link NetherWideRouteTest}. Coordinates are held as-is so that
     * <b>the same routes keep being measured</b> even if the random seed or the endpoint resolution rules change.
     */
    private static List<BlockPos[]> routes() {
        return List.of(
                new BlockPos[] {new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379)},
                new BlockPos[] {new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496)},
                new BlockPos[] {new BlockPos(-317, 44, 567), new BlockPos(-523, 66, 465)},
                new BlockPos[] {new BlockPos(-474, 69, 629), new BlockPos(-271, 73, 482)});
    }

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal,
                                     CostToGo guide, double weight) {
        return new AStarPathfinder(cells,
                new SearchLimits(UNLIMITED_NODE_BUDGET, TIME_LIMIT_MILLIS, weight), guide)
                .search(start, goal, () -> false);
    }

    private static double cost(PathResult result) {
        return result.complete()
                ? result.steps().stream().mapToDouble(PathStep::cost).sum()
                : Double.POSITIVE_INFINITY;
    }

    private static String ratio(double value, double best) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%6.0f(%.3fx)", value, value / best)
                : "   unreached";
    }

    /** Whether the guide exceeds the remaining cost at each point of the optimal path. Above 1.0 is a lower bound violation. */
    private static double worstOverestimate(PathResult best, CostToGo guide, BlockPos start) {
        double remaining = best.steps().stream().mapToDouble(PathStep::cost).sum();
        double worst = 0.0;
        BlockPos at = start;
        for (PathStep step : best.steps()) {
            if (remaining >= 50.0) {
                worst = Math.max(worst, guide.estimate(at.getX(), at.getY(), at.getZ()) / remaining);
            }
            remaining -= step.cost();
            at = step.pos();
        }
        return worst;
    }

    /** Length of a polyline (blocks). */
    private static double polylineLength(List<BlockPos> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += Math.sqrt(points.get(i).distSqr(points.get(i - 1)));
        }
        return length;
    }

    private static double pathLength(PathResult result) {
        List<BlockPos> points = new ArrayList<>();
        for (PathStep step : result.steps()) {
            points.add(step.pos());
        }
        return polylineLength(points);
    }

    /**
     * <b>Measures, per leg, "is it optimal as a path between those two points".</b> If every leg is solved optimally yet the
     * whole is 3x, what's bad isn't how legs are solved but <b>the sequence of intermediate targets itself</b>, i.e. layer 1's big picture.
     */
    private static String legByLeg(FakeCells cells, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double walked = 0;
        double optimal = 0;
        double walkedDistance = 0;
        double straightDistance = 0;
        int solved = 0;
        List<String> worst = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            PathResult leg = executor
                    .submit(cells, from, waypoint, new SearchLimits(800_000, 16_000,
                            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT), true, LEG_GOAL_RADIUS)
                    .get();
            if (leg.steps().isEmpty() || !leg.complete()) {
                break;
            }
            BlockPos to = leg.steps().get(leg.steps().size() - 1).pos();
            PathResult ideal = solve(cells, from, to, null, 1.0);
            double legCost = leg.steps().stream().mapToDouble(PathStep::cost).sum();
            walkedDistance += pathLength(leg);
            straightDistance += Math.sqrt(from.distSqr(to));
            double idealCost = cost(ideal);
            walked += legCost;
            if (Double.isFinite(idealCost)) {
                optimal += idealCost;
                solved++;
                if (legCost / idealCost > 1.15) {
                    worst.add(String.format(Locale.ROOT, "%s->%s %.2fx",
                            from.toShortString(), to.toShortString(), legCost / idealCost));
                }
            }
            from = to;
        }
        return String.format(Locale.ROOT,
                "%d legs walked%.0f/optimal%.0f=%.3fx / distance actually walked %.0f blocks"
                        + " (sum of straight lines between intermediate targets %.0f = %.1fx, a huge detour) %s",
                solved, walked, optimal, optimal > 0 ? walked / optimal : 0,
                walkedDistance, straightDistance,
                straightDistance > 0 ? walkedDistance / straightDistance : 0,
                worst.isEmpty() ? "" : "bad legs: " + String.join(", ", worst));
    }

    /** Intermediate targets are at chunk resolution, so aim at them as area goals, as in the real game. */
    private static final int LEG_GOAL_RADIUS = 16;

    /** Cap on the number of extensions. The real game extends any number of times while walking, so this is for detecting dead ends. */
    private static final int MAX_SEGMENTS = 24;

    /**
     * <b>Measurement of a design change proposal.</b> Instead of making intermediate targets "points that must be visited",
     * <b>aim directly at the destination, steer with the layer 1 guide, take the partial path cut off by the budget, and extend from its end</b>.
     *
     * <p>The current implementation treats intermediate targets as via points in order to "split a distance that can't be solved at once
     * into legs". But measurements showed that even when each leg is optimal, <b>going via them itself</b> tripled the cost.
     * The guide doesn't break the lower bound, so aiming at a distant destination should still give the right direction; that's the hypothesis.
     */
    private record GuidedWalk(double cost, boolean arrived, String trace, long nodes) {
    }

    private static GuidedWalk followGuidedToGoal(FakeCells cells, BlockPos start, BlockPos goal,
                                                  CostToGo guide, SearchLimits limits) {
        BlockPos from = start;
        double cost = 0;
        long nodes = 0;
        List<String> trace = new ArrayList<>();
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            PathResult result = new AStarPathfinder(cells, limits, guide).search(from, goal, () -> false);
            nodes += result.expandedNodes();
            if (result.steps().isEmpty()) {
                // The same escalation as the real game (PathfindingState#DEEP_SEARCH_BUDGET_FACTOR).
                // The side aiming at intermediate targets was given this, so without matching it the comparison is meaningless
                result = new AStarPathfinder(cells, new SearchLimits(800_000, 16_000,
                        limits.heuristicWeight()), guide).search(from, goal, () -> false);
                nodes += result.expandedNodes();
                trace.add("to deep budget");
            }
            if (result.steps().isEmpty()) {
                trace.add("0 steps(" + result.termination() + ")");
                return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
            }
            cost += result.steps().stream().mapToDouble(PathStep::cost).sum();
            BlockPos end = result.steps().get(result.steps().size() - 1).pos();
            trace.add(String.format(Locale.ROOT, "%d steps->%s(%.0f to destination, %s)",
                    result.steps().size(), end.toShortString(), Math.sqrt(end.distSqr(goal)),
                    result.termination()));
            if (result.complete()) {
                return new GuidedWalk(cost, true, String.join(" ", trace), nodes);
            }
            if (end.equals(from)) {
                trace.add("end doesn't advance");
                return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
            }
            from = end;
        }
        trace.add("didn't reach in " + MAX_SEGMENTS + " extensions");
        return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
    }

    /**
     * <b>Checks "does the assembly work as long as the intermediate target sequence is right".</b> Thins the optimal path itself
     * into a sequence of intermediate targets and re-follows it with the same leg splitting as the real game. If this comes close to 1.0x,
     * what needs fixing is <b>only layer 1's route choice</b>, and the leg splitting and extension mechanisms are innocent.
     */
    private static double followOptimalAsWaypoints(FakeCells cells, PathResult best, BlockPos start,
                                                    BlockPos goal) throws Exception {
        List<BlockPos> waypoints = new ArrayList<>();
        BlockPos last = start;
        for (PathStep step : best.steps()) {
            if (Math.sqrt(step.pos().distSqr(last)) >= 64) {
                waypoints.add(step.pos());
                last = step.pos();
            }
        }
        waypoints.add(goal);
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        for (BlockPos waypoint : waypoints) {
            PathResult leg = executor.submit(cells, from, waypoint,
                    new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT),
                    true, LEG_GOAL_RADIUS).get();
            if (leg.steps().isEmpty() || !leg.complete()) {
                return Double.POSITIVE_INFINITY;
            }
            cost += leg.steps().stream().mapToDouble(PathStep::cost).sum();
            from = leg.steps().get(leg.steps().size() - 1).pos();
        }
        return cost;
    }

    /** How the intermediate targets' cells looked to layer 1. */
    private static String waypointKinds(CoarseMap map, CoarseRouter.Route route) {
        List<String> kinds = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            int cx = waypoint.getX() >> 4;
            int cz = waypoint.getZ() >> 4;
            int floor = map.nearestFloor(cx, cz, waypoint.getY());
            byte kind = floor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(cx, cz, floor);
            int span = floor < 0 ? 0
                    : map.maxHeightAtFloor(cx, cz, floor) - map.minHeightAtFloor(cx, cz, floor);
            kinds.add(kindName(kind) + "(relief" + span + ")");
        }
        return String.join(" ", kinds);
    }

    private static String kindName(byte kind) {
        return switch (kind) {
            case CoarseMap.LAND -> "land";
            case CoarseMap.WATER -> "water";
            case CoarseMap.LAVA -> "lava";
            case CoarseMap.LAVA_MIXED -> "lava-mixed";
            case CoarseMap.VOID -> "void";
            default -> "unknown";
        };
    }



    /**
     * <b>Proposal 4.</b> Intermediate targets are used, but <b>the aim radius is loosened</b>. Currently it's 16 blocks (half a cell's width),
     * close to "stop by that chunk". Widening the radius relaxes it to "just head in that direction".
     *
     * <p>The implementation only changes the {@code goalRadius} that {@code PathfindingState} passes to
     * {@code PathfindingExecutor#submit}, so if it works it's the cheapest fix.
     */
    private static GuidedWalk followWithRadius(FakeCells cells, BlockPos start, CoarseRouter.Route route,
                                                int radius) throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        long nodes = 0;
        for (BlockPos waypoint : route.waypoints()) {
            PathResult leg = executor.submit(cells, from, waypoint,
                    new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT),
                    true, radius).get();
            nodes += leg.expandedNodes();
            if (leg.steps().isEmpty() || !leg.complete()) {
                return new GuidedWalk(cost, false, "", nodes);
            }
            cost += leg.steps().stream().mapToDouble(PathStep::cost).sum();
            from = leg.steps().get(leg.steps().size() - 1).pos();
        }
        return new GuidedWalk(cost, true, "", nodes);
    }


    /**
     * <b>Compares the cells of the road layer 1 chose with the cells the optimal path went through, using only the information layer 1 has.</b>
     * If they differ here, adding that information to the cost would let layer 1 choose the right road = a fundamental fix.
     * If they don't, a chunk-resolution map fundamentally can't tell them apart.
     */
    private static String compareCells(CoarseMap map, FakeCells cells, PathResult best,
                                        BlockPos start, CoarseRouter.Route coarse) {
        return "  optimal path cells: " + cellStats(map, cellsAlong(pathPoints(best, start)))
                + " / cells layer 1 chose: " + cellStats(map, cellsAlong(coarse.waypoints()));
    }

    private static List<BlockPos> pathPoints(PathResult result, BlockPos start) {
        List<BlockPos> points = new ArrayList<>();
        points.add(start);
        for (PathStep step : result.steps()) {
            points.add(step.pos());
        }
        return points;
    }

    /** Cells a point sequence passed through (no duplicates, in order of passage). */
    private static List<BlockPos> cellsAlong(List<BlockPos> points) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos point : points) {
            BlockPos cell = new BlockPos(point.getX() >> 4, point.getY(), point.getZ() >> 4);
            if (cells.isEmpty() || cells.get(cells.size() - 1).getX() != cell.getX()
                    || cells.get(cells.size() - 1).getZ() != cell.getZ()) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private static String cellStats(CoarseMap map, List<BlockPos> cells) {
        int total = 0;
        int floors = 0;
        int span = 0;
        int lava = 0;
        int gapSum = 0;
        for (BlockPos cell : cells) {
            int cx = cell.getX();
            int cz = cell.getZ();
            if (!map.containsChunk(cx, cz)) {
                continue;
            }
            total++;
            int count = map.floorCount(cx, cz);
            floors += count;
            int floor = map.nearestFloor(cx, cz, cell.getY());
            if (floor >= 0) {
                span += map.maxHeightAtFloor(cx, cz, floor) - map.minHeightAtFloor(cx, cz, floor);
                byte kind = map.kindAtFloor(cx, cz, floor);
                if (kind == CoarseMap.LAVA || kind == CoarseMap.LAVA_MIXED) {
                    lava++;
                }
            }
            // Height separation between floors (the depth of the 3D maze)
            for (int i = 1; i < count; i++) {
                gapSum += map.heightAtFloor(cx, cz, i) - map.heightAtFloor(cx, cz, i - 1);
            }
        }
        if (total == 0) {
            return "no cells";
        }
        return String.format(Locale.ROOT,
                "%d cells floors%.2f/cell relief%.1f floor separation%.1f lava%d%%",
                total, floors / (double) total, span / (double) total, gapSum / (double) total,
                lava * 100 / total);
    }

    /**
     * <b>Is the same switch a gain in the Overworld too?</b> In the Nether, aiming at the destination was better in both quality and
     * expanded nodes, but in the Overworld intermediate targets are close, so searches end early; switching would sweep the whole box
     * every time, possibly increasing computation without changing quality. This decides whether it can be applied everywhere.
     */
    @Test
    void comparesTheSameSwapInTheOverworld() throws Exception {
        FakeCells cells = TerrainFixture.load("/overworld_wide.txt.gz",
                bounds -> FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                        .maxFallDamagePoints(6));
        List<String> report = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), 20260907L, 4,
                200, 400)) {
            BlockPos start = route[0];
            BlockPos goal = route[1];
            PathResult best = solve(cells, start, goal, null, 1.0);
            if (!best.complete()) {
                continue;
            }
            double bestCost = cost(best);
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
            CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
            CoarseRouter.Route coarse = null;
            for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
                CoarseRouter.Route candidate = CoarseRouter.findRoute(map, start, goal, false, policy);
                if (candidate.reachedGoal()) {
                    coarse = candidate;
                    break;
                }
            }
            if (coarse == null) {
                continue;
            }
            GuidedWalk viaWaypoints = followWithRadius(cells, start, coarse, 16);
            GuidedWalk toGoal = followGuidedToGoal(cells, start, goal, guide,
                    new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT));
            report.add(String.format(Locale.ROOT,
                    "%s->%s baseline%6.0f current%s expanded%,d / aim-at-destination%s expanded%,d",
                    start.toShortString(), goal.toShortString(), bestCost,
                    ratio(viaWaypoints.arrived() ? viaWaypoints.cost() : Double.POSITIVE_INFINITY,
                            bestCost),
                    viaWaypoints.nodes(),
                    ratio(toGoal.arrived() ? toGoal.cost() : Double.POSITIVE_INFINITY, bestCost),
                    toGoal.nodes()));
        }
        System.out.println("=== Overworld ===\n" + String.join("\n", report));
        assertTrue(!report.isEmpty(), "not a single route measured");
    }

    /** Equivalent to the real game's render distance of 10 chunks. {@code SearchBounds.around} cuts the box with this. */
    private static final int WINDOW_RADIUS = 160;

    /**
     * <b>A guide built the same way as the real game's {@code PathfindingExecutor#buildCostToGoGuide}.</b>
     * Builds layer 1's map from <b>only inside the search box</b>. If the destination is outside the box,
     * {@code CoarseRouter#costToGo} doesn't include the destination cell in the map, so every cost is infinite and
     * {@code estimate()} returns 0 everywhere: <b>the guide vanishes</b>.
     */
    private static CostToGo guideFromSearchBox(FakeCells cells, BlockPos start, BlockPos goal) {
        SearchBounds box = new SearchBounds(
                start.getX() - WINDOW_RADIUS, cells.bounds().minY(), start.getZ() - WINDOW_RADIUS,
                start.getX() + WINDOW_RADIUS, cells.bounds().maxY(), start.getZ() + WINDOW_RADIUS);
        CoarseMap boxMap = LiveCoarseSampler.sample(cells, box, start.getY(), () -> false);
        return CoarseRouter.costToGo(boxMap, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
    }

    @Test
    void showsWhichLayerTheNetherDetourComesFrom() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        int measured = 0;
        double worstGuideRatio = 0.0;
        String worstGuideAt = "";

        for (BlockPos[] route : routes()) {
            BlockPos start = route[0];
            BlockPos goal = route[1];
            PathResult best = solve(cells, start, goal, null, 1.0);
            if (!best.complete()) {
                report.add(start.toShortString() + "→" + goal.toShortString() + ": baseline can't be solved");
                continue;
            }
            measured++;
            double bestCost = cost(best);
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
            // Built the same way as the real game (same arguments as PathfindingExecutor#buildCostToGoGuide)
            CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);

            double weighted = cost(solve(cells, start, goal, null, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT));
            double guided = cost(solve(cells, start, goal, guide, 1.0));
            double both = cost(solve(cells, start, goal, guide, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT));

            double overestimate = worstOverestimate(best, guide, start);
            if (overestimate > worstGuideRatio) {
                worstGuideRatio = overestimate;
                worstGuideAt = start.toShortString() + "→" + goal.toShortString();
            }

            report.add(String.format(Locale.ROOT,
                    "%s->%s baseline%6.0f weight-only%s guide-only%s weight+guide%s guide lower-bound violation%.3fx",
                    start.toShortString(), goal.toShortString(), bestCost,
                    ratio(weighted, bestCost), ratio(guided, bestCost), ratio(both, bestCost),
                    overestimate));

            // See whether layer 1's sequence of intermediate targets is itself a detour. Compare the optimal path's distance with
            // the length of the polyline connecting intermediate targets in order (both distances in blocks)
            CoarseRouter.Route coarse = null;
            for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
                CoarseRouter.Route candidate = CoarseRouter.findRoute(map, start, goal, false, policy);
                if (candidate.reachedGoal()) {
                    coarse = candidate;
                    break;
                }
            }
            if (coarse == null) {
                report.add("  layer 1 doesn't reach the destination");
                continue;
            }
            List<BlockPos> polyline = new ArrayList<>();
            polyline.add(start);
            polyline.addAll(coarse.waypoints());
            report.add(String.format(Locale.ROOT,
                    "  optimal path distance %.0f blocks / intermediate target polyline %.0f blocks(%.3fx) / straight line %.0f blocks",
                    pathLength(best), polylineLength(polyline),
                    polylineLength(polyline) / pathLength(best),
                    Math.sqrt(start.distSqr(goal))));
            report.add("  " + legByLeg(cells, start, coarse));

            // Design change proposal: don't go via intermediate targets; aim at the destination and extend (with the real game's default budget)
            SearchLimits liveLimits = new SearchLimits(100_000, 2_000,
                    AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
            report.add("  intermediate target cells: " + waypointKinds(map, coarse));
            report.add(compareCells(map, cells, best, start, coarse));
            double asWaypoints = followOptimalAsWaypoints(cells, best, start, goal);
            report.add("  [control] thin the optimal path into intermediate targets and re-follow " + ratio(asWaypoints, bestCost));
            CostToGo boxGuide = guideFromSearchBox(cells, start, goal);
            GuidedWalk boxed = followGuidedToGoal(cells, start, goal, boxGuide, liveLimits);
            report.add(String.format(Locale.ROOT,
                    "  [current implementation] build the guide from only inside the box %s expanded%,d (guide value at start=%.0f)",
                    ratio(boxed.arrived() ? boxed.cost() : Double.POSITIVE_INFINITY, bestCost),
                    boxed.nodes(), boxGuide.estimate(start.getX(), start.getY(), start.getZ())));
            GuidedWalk toGoal = followGuidedToGoal(cells, start, goal, guide, liveLimits);
            report.add(String.format(Locale.ROOT, "  [proposal] aim at the destination and extend %s expanded%,d",
                    ratio(toGoal.arrived() ? toGoal.cost() : Double.POSITIVE_INFINITY, bestCost),
                    toGoal.nodes()));
            if (!toGoal.arrived()) {
                report.add("    " + toGoal.trace());
            }
            GuidedWalk viaWaypoints = followWithRadius(cells, start, coarse, 16);
            report.add(String.format(Locale.ROOT, "  [current] stop by intermediate targets %s expanded%,d",
                    ratio(viaWaypoints.arrived() ? viaWaypoints.cost() : Double.POSITIVE_INFINITY,
                            bestCost),
                    viaWaypoints.nodes()));
        }

        report.add(String.format(Locale.ROOT, "worst guide lower-bound violation: %.3fx %s",
                worstGuideRatio, worstGuideAt));
        System.out.println(String.join("\n", report));
        assertTrue(measured > 0, "not a single route measured\n" + String.join("\n", report));
    }
}
