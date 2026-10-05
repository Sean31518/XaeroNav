package net.prason.xaeronav.pathfinding.flight;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.RandomSweepBenchTest;
import net.prason.xaeronav.pathfinding.astar.RandomSweepBenchTest.Dim;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Inside boxes exported from a saved world, flies from random airborne starts to ground goals, mimicking the production
 * flight-path procedure (re-planning and extending as in {@code FlightNavState}). A measurement with no assertions.
 *
 * <p>The player advances along the line by the wall-clock time the search took (at best-glide cruise speed). Time spent having
 * caught up with the tail is counted as "waiting": this waiting is what users perceive as "pathfinding is slow".
 */
@Tag("bench")
class FlightSweepBenchTest {

    private static final Path DIR = Path.of(System.getProperty("xaeronav.sweepDir", "."));
    private static final int ROUTES = Integer.getInteger("xaeronav.routes", 8);
    private static final int MIN_BLOCKS = Integer.getInteger("xaeronav.sweepMin", 300);
    private static final int MAX_BLOCKS = Integer.getInteger("xaeronav.sweepMax", 700);
    private static final int SPREAD = Integer.getInteger("xaeronav.sweepSpread", 360);
    /** {@code random} picks a new value each run. The value drawn is kept in the output header so the same set can be reproduced later. */
    private static final long SEED = seed(System.getProperty("xaeronav.sweepSeed", "0"));
    private static final String TAG = System.getProperty("xaeronav.sweepTag", "");
    /** In-game render distance of 15 chunks. */
    private static final int RENDER_RADIUS = Integer.getInteger("xaeronav.window", 240);
    private static final int CELL_BLOCKS = Integer.getInteger("xaeronav.flightCell", 4);
    private static final boolean SKIP_OPTIMAL = Boolean.getBoolean("xaeronav.skipClosure");
    /** {@code horizon} aims at the real goal and uses the edge of the readable range as the exit. */
    private static final double WEIGHT = Double.parseDouble(System.getProperty("xaeronav.flightWeight", "1.5"));
    /** When extending, re-plan instead of extending if close enough to the goal (same as production {@code FlightNavState}). */
    private static final boolean REPLAN = !Boolean.getBoolean("xaeronav.noReplan");
    private static final boolean TRACE = Boolean.getBoolean("xaeronav.walkTrace");

    // Same values as the FlightNavState constants
    private static final int DETAIL_HORIZON_BLOCKS = 256;
    private static final int EXTEND_LEAD_BLOCKS = 160;
    private static final int MIN_EXTENSION_BLOCKS = 64;
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 48.0;
    private static final double LOADED_MARGIN = 0.9;
    private static final int HANDOFF_BLOCKS = 96;
    private static final int VERTICAL_MARGIN = FlightLineRouter.VERTICAL_MARGIN_BLOCKS;
    /** After a re-plan, do not re-plan again until this much closer to the goal (same as the ground re-check's anti-oscillation). */
    private static final double REPLAN_PROGRESS_BLOCKS = 64.0;
    /** Even when re-planning, keep the current line up to this far ahead of the player (blocks), so the line nearby is not redrawn. */
    private static final double REPLAN_KEEP_BLOCKS = Double.parseDouble(System.getProperty("xaeronav.replanKeep", "48"));
    private static final boolean VCUT = !Boolean.getBoolean("xaeronav.noVCut");
    private static final double SPEED_BLOCKS_PER_TICK = 1.0 / FlightCosts.HORIZONTAL_TICKS_PER_BLOCK;

    @Test
    void overworld() throws IOException {
        sweep(Dim.OVERWORLD, boxes("ow1,ow2,ow3"));
    }

    @Test
    void nether() throws IOException {
        sweep(Dim.NETHER, boxes("ne1,ne2,ne3"));
    }

    @Test
    void end() throws IOException {
        sweep(Dim.END, boxes("en1,en2,en3"));
    }

    /** Flies each of {@code -Pxaeronav.alongPoints=box:x,y,z:x,y,z;...} with tracing. */
    @Test
    void focus() throws IOException {
        Path out = Path.of(System.getProperty("xaeronav.profileOut", "."), "flight-focus" + TAG + ".txt");
        Files.deleteIfExists(out);
        for (String spec : System.getProperty("xaeronav.alongPoints", "").split(";")) {
            String[] p = spec.split(":");
            Dim dim = p[0].startsWith("ow") ? Dim.OVERWORLD : p[0].startsWith("ne") ? Dim.NETHER : Dim.END;
            FakeCells cells = RandomSweepBenchTest.load(DIR.resolve(p[0] + ".txt.gz"), dim);
            SearchBounds b = cells.bounds();
            CoarseMap map = dim == Dim.NETHER
                    ? LiveCoarseSampler.sample(cells, new SearchBounds(b.minX(), b.minY(), b.minZ(), b.maxX(), 120,
                            b.maxZ()), 64, () -> false)
                    : null;
            Vec3 start = parseVec(p[1]);
            Vec3 goal = parseVec(p[2]);
            Flight flight = fly(cells, dim, map, start, goal);
            double optimal = optimal(cells, start, flight.end());
            log(out, String.format(Locale.ROOT, "%s %s→%s cost%.0f optimalRatio%.3f retreat%.0f", p[0], p[1], p[2],
                    cost(cells, flight.points()), cost(cells, flight.points()) / optimal,
                    worstRetreat(flight.points(), goal)));
            StringBuilder line = new StringBuilder("  line");
            for (Vec3 v : flight.points()) {
                line.append(' ').append(shortVec(v));
            }
            log(out, line.toString());
            FlightRoute best = new FlightPathfinder(new AirGrid(cells, CELL_BLOCKS), false,
                    new SearchLimits(4_000_000, 120_000, 1.0), 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK)
                    .search(start, flight.end(), CELL_BLOCKS * 1.5);
            StringBuilder opt = new StringBuilder("  optimal");
            for (Vec3 v : best.points()) {
                opt.append(' ').append(shortVec(v));
            }
            log(out, opt.toString());
            FlightRoute toGoal = new FlightPathfinder(new AirGrid(cells, CELL_BLOCKS), false,
                    new SearchLimits(4_000_000, 120_000, 1.0), 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK)
                    .search(start, goal, CELL_BLOCKS * 1.5);
            log(out, String.format(Locale.ROOT, "  full-view to goal %s tail%s horizontalRemaining%.0f", toGoal.termination(),
                    toGoal.isEmpty() ? "-" : shortVec(toGoal.tail()),
                    toGoal.isEmpty() ? Double.NaN : horizontal(toGoal.tail(), goal)));
        }
    }

    private static Vec3 parseVec(String s) {
        String[] v = s.split(",");
        return new Vec3(Double.parseDouble(v[0]) + 0.5, Double.parseDouble(v[1]), Double.parseDouble(v[2]) + 0.5);
    }

    private static long seed(String value) {
        return value.equals("random") ? new Random().nextLong() : Long.parseLong(value);
    }

    private static List<String> boxes(String defaults) {
        return List.of(System.getProperty("xaeronav.sweepBoxes", defaults).split(","));
    }

    private static FlightTuning tuning(int nodes) {
        return new FlightTuning(CELL_BLOCKS, 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK,
                new SearchLimits(nodes, 2_000, WEIGHT));
    }

    private static void sweep(Dim dim, List<String> boxes) throws IOException {
        Path out = Path.of(System.getProperty("xaeronav.profileOut", "."), "flight-" + dim + TAG + ".txt");
        Files.deleteIfExists(out);
        boolean warmed = false;
        double totalSearchMs = 0;
        double totalWaitTicks = 0;
        int totalRoutes = 0;
        int arrived = 0;
        int failures = 0;
        int unreachable = 0;
        for (String box : boxes) {
            FakeCells cells = RandomSweepBenchTest.load(DIR.resolve(box + ".txt.gz"), dim);
            SearchBounds b = cells.bounds();
            int top = switch (dim) {
                case OVERWORLD -> 319;
                case END -> 255;
                case NETHER -> b.maxY();
            };
            cells.bounds(new SearchBounds(b.minX(), b.minY(), b.minZ(), b.maxX(), top, b.maxZ()));
            List<Vec3[]> routes = routes(cells, dim, box.hashCode() + SEED);
            CoarseMap map = dim == Dim.NETHER
                    ? LiveCoarseSampler.sample(cells, new SearchBounds(b.minX(), b.minY(), b.minZ(), b.maxX(), 120,
                            b.maxZ()), 64, () -> false)
                    : null;
            log(out, String.format(Locale.ROOT, "# box%s routes%d seed%d grid%d window%d weight%.2f", box,
                    routes.size(), SEED, CELL_BLOCKS, RENDER_RADIUS, WEIGHT));
            if (!warmed && !routes.isEmpty()) {
                Vec3[] first = routes.get(0);
                fly(cells, dim, map, first[0], first[1]);
                warmed = true;
            }
            for (Vec3[] route : routes) {
                totalRoutes++;
                Flight flight;
                double optimal;
                double cost;
                try {
                    flight = fly(cells, dim, map, route[0], route[1]);
                    optimal = SKIP_OPTIMAL ? Double.NaN : optimal(cells, route[0], flight.end());
                    cost = cost(cells, flight.points());
                } catch (RuntimeException | OutOfMemoryError e) {
                    failures++;
                    log(out, String.format(Locale.ROOT, "%s %s→%s exception %s", box, shortVec(route[0]),
                            shortVec(route[1]), e));
                    continue;
                }
                arrived += flight.arrived() ? 1 : 0;
                // For routes that did not arrive, record separately whether even full view cannot get within the handoff distance from the air (goal in an enclosed space,
                // or start in an enclosed space), or whether it could but the guidance did not get there
                String reach = "";
                if (!flight.arrived()) {
                    boolean possible = reachable(cells, route[0], route[1]);
                    unreachable += possible ? 0 : 1;
                    reach = possible ? " reachable with full view" : " unreachable from the air even with full view";
                }
                totalSearchMs += flight.searchMs();
                totalWaitTicks += flight.waitTicks();
                log(out, String.format(Locale.ROOT,
                        "%s %s→%s straight%.0f arrived=%s searches%d total%.0fms max%.0fms budgetOuts%d expandedTotal%d wait%.1fs cost%.0f optimalRatio%.3f retreat%.0f %s",
                        box, shortVec(route[0]), shortVec(route[1]), horizontal(route[0], route[1]), flight.arrived(),
                        flight.searches(), flight.searchMs(), flight.maxMs(), flight.budgetOuts(), flight.nodes(),
                        flight.waitTicks() / 20.0, cost, cost / optimal, worstRetreat(flight.points(), route[1]),
                        flight.note() + reach));
            }
        }
        log(out, String.format(Locale.ROOT, "# total %d routes, %d arrived, exceptions%d unreachableFromAir%d searchTotal%.0fms waitTotal%.1fs seed%d",
                totalRoutes, arrived, failures, unreachable, totalSearchMs, totalWaitTicks / 20.0, SEED));
    }

    /** Record of the in-game procedure. {@code end} is the last point the flight path reached (what the optimum is compared against). */
    private record Flight(boolean arrived, int searches, double searchMs, double maxMs, int budgetOuts, long nodes,
                          double waitTicks, List<Vec3> points, Vec3 end, String note) {
    }

    /**
     * The {@code FlightNavState} procedure. First plans toward the goal (with a map, the farthest intermediate target within reach),
     * then extends every time the remaining distance to the tail drops below 160 blocks.
     */
    private static Flight fly(FakeCells cells, Dim dim, CoarseMap map, Vec3 start, Vec3 goal) {
        var coarse = map == null ? null : CoarseFlightRouter.findRoute(
                CoarseAirMap.from(map, cells.bounds().minY() + 10, 117), BlockPos.containing(start),
                BlockPos.containing(goal), false);
        CoarseFlightField field = map == null ? null : CoarseFlightField.toward(
                CoarseAirMap.from(map, cells.bounds().minY() + 10, 117), BlockPos.containing(goal), false);
        if (TRACE) {
            System.out.println("  coarse route " + (coarse == null ? "none" : coarse.waypoints().size() + " points reached="
                    + coarse.reachedGoal() + " " + coarse.waypoints()));
        }
        Vec3 player = start;
        double searchMs = 0;
        double maxMs = 0;
        int searches = 0;
        int budgetOuts = 0;
        long nodes = 0;
        double waitTicks = 0;
        List<Vec3> flown = new ArrayList<>();
        flown.add(start);

        Vec3 aim = goal;
        FlightHorizon firstHorizon = new FlightHorizon(player.x, player.z, RENDER_RADIUS * LOADED_MARGIN);
        long began = System.nanoTime();
        FlightRoute route = FlightRouter.route(view(cells, player, aim), player, aim, false, tuning(150_000),
                firstHorizon, field, () -> false);
        double ms = (System.nanoTime() - began) / 1e6;
        searches++;
        searchMs += ms;
        maxMs = Math.max(maxMs, ms);
        nodes += route.expandedNodes();
        budgetOuts += route.budgetExhausted() ? 1 : 0;
        if (TRACE) {
            System.out.println(String.format(Locale.ROOT, "  initial %s→aim%s tail%s %s expanded%d", shortVec(start),
                    shortVec(aim), route.isEmpty() ? "-" : shortVec(route.tail()), route.termination(),
                    route.expandedNodes()));
        }
        if (route.isEmpty()) {
            return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, flown, start, "initial empty");
        }
        // The player keeps flying during the search, but for the first line there is nothing to do but wait until it appears
        waitTicks += ms / 50.0;
        List<Vec3> line = new ArrayList<>(route.points());
        int replans = 0;
        int vcuts = 0;
        int nearRedraws = 0;
        double bestReplanDistance = Double.POSITIVE_INFINITY;
        double along = 0;
        Vec3 blockedAt = null;
        Vec3 blockedFrom = null;
        for (int guard = 0; guard < 400; guard++) {
            player = pointAt(line, along);
            if (horizontal(player, goal) <= HANDOFF_BLOCKS) {
                return new Flight(true, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line, line.get(
                        line.size() - 1), (replans == 0 ? "" : "replans" + replans) + (vcuts == 0 ? "" : " vCuts" + vcuts)
                        + " nearRedraws" + nearRedraws);
            }
            Vec3 tail = line.get(line.size() - 1);
            double toTail = length(line) - along;
            if (toTail > EXTEND_LEAD_BLOCKS) {
                along = length(line) - EXTEND_LEAD_BLOCKS;
                continue;
            }
            if (tail.equals(blockedAt) && blockedFrom.distanceTo(player) < EXTEND_RETRY_MOVE_BLOCKS) {
                if (toTail <= 1e-6) {
                    return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line, tail,
                            "dead end at tail");
                }
                along = Math.min(length(line), along + EXTEND_RETRY_MOVE_BLOCKS);
                continue;
            }
            double lead = Math.min(DETAIL_HORIZON_BLOCKS, RENDER_RADIUS * LOADED_MARGIN - player.distanceTo(tail));
            Vec3 target = goal;
            if (lead < MIN_EXTENSION_BLOCKS || tail.distanceTo(target) < MIN_EXTENSION_BLOCKS) {
                blockedAt = tail;
                blockedFrom = player;
                continue;
            }
            Vec3 here = pointAt(line, along);
            if (REPLAN && horizontal(here, goal) < bestReplanDistance - REPLAN_PROGRESS_BLOCKS) {
                bestReplanDistance = horizontal(here, goal);
                // As in production, keep the current line up to KEEP ahead of the player and re-plan from there
                player = pointAt(line, along);
                double keepAlong = Math.min(length(line), along + REPLAN_KEEP_BLOCKS);
                Vec3 from = pointAt(line, keepAlong);
                began = System.nanoTime();
                FlightRoute replanned = FlightRouter.route(view(cells, player, goal), from, goal,
                        false, tuning(150_000), new FlightHorizon(player.x, player.z, RENDER_RADIUS * LOADED_MARGIN),
                        field, () -> false);
                ms = (System.nanoTime() - began) / 1e6;
                searches++;
                replans++;
                searchMs += ms;
                maxMs = Math.max(maxMs, ms);
                nodes += replanned.expandedNodes();
                if (TRACE) {
                    System.out.println(String.format(Locale.ROOT, "  re-plan from %s tail%s", shortVec(from),
                            replanned.isEmpty() ? "-" : shortVec(replanned.tail())));
                }
                if (!replanned.isEmpty()) {
                    List<Vec3> kept = new ArrayList<>(flownPrefix(line, keepAlong));
                    kept.addAll(replanned.points().subList(1, replanned.points().size()));
                    if (nearShift(ahead(line, along), ahead(kept, along)) > NEAR_SHIFT_BLOCKS) {
                        nearRedraws++;
                    }
                    line = kept;
                    blockedAt = null;
                    blockedFrom = null;
                    continue;
                }
            }
            began = System.nanoTime();
            FlightHorizon horizon = new FlightHorizon(player.x, player.z, RENDER_RADIUS * LOADED_MARGIN);
            FlightRoute extension = FlightRouter.route(view(cells, player, target), tail, target, false,
                    tuning(60_000), horizon, field, () -> false);
            ms = (System.nanoTime() - began) / 1e6;
            searches++;
            searchMs += ms;
            maxMs = Math.max(maxMs, ms);
            nodes += extension.expandedNodes();
            budgetOuts += extension.budgetExhausted() ? 1 : 0;
            double flownDuring = ms / 50.0 * SPEED_BLOCKS_PER_TICK;
            double room = length(line) - along;
            if (flownDuring > room) {
                waitTicks += (flownDuring - room) / SPEED_BLOCKS_PER_TICK;
            }
            along = Math.min(length(line), along + flownDuring);
            Vec3 grown = extension.tail();
            if (extension.isEmpty()) {
                blockedAt = tail;
                blockedFrom = player;
                continue;
            }
            if (!extension.complete() && tail.distanceTo(grown) < MIN_EXTENSION_BLOCKS) {
                blockedAt = grown;
                blockedFrom = player;
            } else {
                blockedAt = null;
                blockedFrom = null;
            }
            if (TRACE) {
                System.out.println(String.format(Locale.ROOT, "  extend %s→aim%s tail%s %s expanded%d", shortVec(tail),
                        shortVec(target), shortVec(grown), extension.termination(), extension.expandedNodes()));
            }
            List<Vec3> extensionPoints = extension.points();
            if (VCUT) {
                TurnBack.Cut cut = TurnBack.cut(ahead(line, along), extensionPoints, new AirGrid(
                        view(cells, pointAt(line, along), target), CELL_BLOCKS)::clearLine);
                if (cut != null) {
                    // Keep the part ahead of the player up to the point it came back to, and join the rest of the extension from there
                    List<Vec3> kept = new ArrayList<>(flownPrefix(line, along));
                    kept.addAll(cut.aheadKept().subList(1, cut.aheadKept().size()));
                    double flownLength = length(flownPrefix(line, along));
                    kept.addAll(cut.rest());
                    vcuts++;
                    if (TRACE) {
                        System.out.println(String.format(Locale.ROOT, "  cut V-turn, rejoined at %s",
                                shortVec(cut.rest().get(0))));
                    }
                    line = kept;
                    along = flownLength;
                    continue;
                }
            }
            line.addAll(extensionPoints.subList(1, extensionPoints.size()));
        }
        return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line,
                line.get(line.size() - 1), "aborted");
    }

    /** As with production {@code ChunkView.capture}, exposes only the render radius around the player and the player/aim box. */
    private static CellSource view(FakeCells cells, Vec3 player, Vec3 target) {
        BlockPos p = BlockPos.containing(player);
        int minY = Math.max(cells.bounds().minY(), (int) Math.min(player.y, target.y) - VERTICAL_MARGIN);
        int maxY = Math.min(cells.bounds().maxY(), (int) Math.max(player.y, target.y) + VERTICAL_MARGIN);
        SearchBounds box = new SearchBounds(p.getX() - RENDER_RADIUS, minY, p.getZ() - RENDER_RADIUS,
                p.getX() + RENDER_RADIUS, maxY, p.getZ() + RENDER_RADIUS);
        return new WindowedCells(cells, p, RENDER_RADIUS, box);
    }

    /** Whether, with full view, the start can get from the air within the goal's handoff distance ({@link #HANDOFF_BLOCKS}). */
    private static boolean reachable(FakeCells cells, Vec3 start, Vec3 goal) {
        return new FlightPathfinder(new AirGrid(cells, CELL_BLOCKS), false,
                new SearchLimits(4_000_000, 120_000, 1.0), 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK)
                .search(start, goal, HANDOFF_BLOCKS).complete();
    }

    /** With full view and weight 1, the optimum from the same start to the last point the flight path reached. */
    private static double optimal(FakeCells cells, Vec3 start, Vec3 end) {
        FlightRoute best = new FlightPathfinder(new AirGrid(cells, CELL_BLOCKS), false,
                new SearchLimits(4_000_000, 120_000, 1.0), 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK)
                .search(start, end, CELL_BLOCKS * 1.5);
        return best.complete() ? cost(cells, best.points()) : Double.POSITIVE_INFINITY;
    }

    private static double cost(FakeCells cells, List<Vec3> points) {
        AirGrid grid = new AirGrid(cells, CELL_BLOCKS);
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 c = points.get(i);
            total += FlightCosts.segmentTicks(Math.hypot(c.x - a.x, c.z - a.z), c.y - a.y, false)
                    + Clearance.alongLine(grid, a, c, 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK);
        }
        return total;
    }

    /** While following the line, the farthest horizontal distance (max) retreated from the point closest to the goal so far. */
    private static double worstRetreat(List<Vec3> line, Vec3 goal) {
        double closest = Double.POSITIVE_INFINITY;
        double worst = 0;
        for (int i = 1; i < line.size(); i++) {
            Vec3 a = line.get(i - 1);
            Vec3 b = line.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 4.0));
            for (int k = 0; k <= steps; k++) {
                double left = horizontal(a.lerp(b, k / (double) steps), goal);
                closest = Math.min(closest, left);
                worst = Math.max(worst, left - closest);
            }
        }
        return worst;
    }

    private static final double NEAR_SHIFT_BLOCKS = 8.0;

    /** How far apart the first 64 blocks of two lines are (max distance from a point on one to the other line). */
    private static double nearShift(List<Vec3> before, List<Vec3> after) {
        double worst = 0;
        for (Vec3 p : samplesUpTo(after, 64.0)) {
            double best = Double.POSITIVE_INFINITY;
            for (Vec3 q : samplesUpTo(before, 96.0)) {
                best = Math.min(best, p.distanceTo(q));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    private static List<Vec3> samplesUpTo(List<Vec3> line, double limit) {
        List<Vec3> result = new ArrayList<>();
        double walked = 0;
        result.add(line.get(0));
        for (int i = 1; i < line.size() && walked < limit; i++) {
            Vec3 a = line.get(i - 1);
            Vec3 b = line.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 2.0));
            for (int k = 1; k <= steps && walked + a.distanceTo(b) * k / steps <= limit; k++) {
                result.add(a.lerp(b, k / (double) steps));
            }
            walked += a.distanceTo(b);
        }
        return result;
    }

    /** The part of the line from position {@code along} to the tail (starting at that position). */
    private static List<Vec3> ahead(List<Vec3> line, double along) {
        List<Vec3> result = new ArrayList<>();
        result.add(pointAt(line, along));
        double walked = 0;
        for (int i = 1; i < line.size(); i++) {
            walked += line.get(i - 1).distanceTo(line.get(i));
            if (walked > along) {
                result.add(line.get(i));
            }
        }
        return result;
    }

    /** The part of the line from its start to position {@code along} (ending at that position). */
    private static List<Vec3> flownPrefix(List<Vec3> line, double along) {
        List<Vec3> result = new ArrayList<>();
        result.add(line.get(0));
        double walked = 0;
        for (int i = 1; i < line.size(); i++) {
            walked += line.get(i - 1).distanceTo(line.get(i));
            if (walked >= along) {
                break;
            }
            result.add(line.get(i));
        }
        result.add(pointAt(line, along));
        return result;
    }

    private static double length(List<Vec3> line) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += line.get(i - 1).distanceTo(line.get(i));
        }
        return total;
    }

    private static Vec3 pointAt(List<Vec3> line, double along) {
        double left = along;
        for (int i = 1; i < line.size(); i++) {
            double segment = line.get(i - 1).distanceTo(line.get(i));
            if (left <= segment) {
                return segment < 1e-9 ? line.get(i) : line.get(i - 1).lerp(line.get(i), left / segment);
            }
            left -= segment;
        }
        return line.get(line.size() - 1);
    }

    /**
     * Starts are airborne (Overworld/End: 30 blocks above the surface; Nether: where a 4x4x4 space above the floor is clear),
     * goals are on the surface floor.
     */
    private static List<Vec3[]> routes(FakeCells cells, Dim dim, long seed) {
        SearchBounds b = cells.bounds();
        int cx = (b.minX() + b.maxX()) / 2;
        int cz = (b.minZ() + b.maxZ()) / 2;
        Random random = new Random(seed);
        List<Vec3[]> routes = new ArrayList<>();
        for (int attempt = 0; attempt < 50000 && routes.size() < ROUTES; attempt++) {
            Vec3 start = pick(cells, dim, random, cx, cz, true);
            Vec3 goal = pick(cells, dim, random, cx, cz, false);
            if (start == null || goal == null) {
                continue;
            }
            double d = horizontal(start, goal);
            if (d >= MIN_BLOCKS && d <= MAX_BLOCKS) {
                routes.add(new Vec3[] {start, goal});
            }
        }
        return routes;
    }

    private static Vec3 pick(FakeCells cells, Dim dim, Random random, int cx, int cz, boolean air) {
        int x = cx - SPREAD + random.nextInt(2 * SPREAD + 1);
        int z = cz - SPREAD + random.nextInt(2 * SPREAD + 1);
        List<Integer> floors = new ArrayList<>();
        int top = dim == Dim.NETHER ? 120 : cells.bounds().maxY() - 2;
        for (int y = top; y > cells.bounds().minY() + 1; y--) {
            if (CellData.standable(cells.cell(x, y - 1, z)) && CellData.passableEmpty(cells.cell(x, y, z))
                    && CellData.passableEmpty(cells.cell(x, y + 1, z))) {
                floors.add(y);
            }
        }
        if (floors.isEmpty()) {
            return null;
        }
        int floor = dim == Dim.NETHER ? floors.get(random.nextInt(floors.size())) : floors.get(0);
        if (!air) {
            return new Vec3(x + 0.5, floor, z + 0.5);
        }
        int y = dim == Dim.NETHER ? floor + 6 : floor + 30;
        if (y > cells.bounds().maxY() - 12) {
            return null;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!CellData.passableEmpty(cells.cell(x + dx, y + dy, z + dz))) {
                        return null;
                    }
                }
            }
        }
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static String shortVec(Vec3 v) {
        return Mth.floor(v.x) + "," + Mth.floor(v.y) + "," + Mth.floor(v.z);
    }

    private static void log(Path out, String line) throws IOException {
        System.out.println(line);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            w.println(line);
        }
    }
}
