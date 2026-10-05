package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * Measures <b>where along the route</b> the detours accumulate, and compares three ways of fixing seams.
 *
 * <p>The ratio for the whole route ({@code ProgressiveDiscoveryTest}) cannot tell whether "only the seams are bad" or
 * "everything is equally bad". This slides a window (a stretch of {@link #WINDOW_BLOCKS} blocks), re-solves just that
 * stretch with full visibility, compares, and reports <b>windows containing a seam separately from those that do
 * not</b>. On a route left extended as-is, only the windows containing a seam average 1.02 to 1.21 times (worst
 * 1.795), clearly separated from the windows without one (1.00 to 1.05 times).
 *
 * <p>The three compared are {@link ProgressiveWalk.Mode}. <b>What this guard protects is the decision itself to
 * "fix only the seams"</b>: if re-planning everything becomes cheaper, it is worth rethinking
 * {@code PathfindingState#repairSeam} altogether.
 * It checks not only quality but also <b>line redraws</b>. "The guidance changes just from walking" is a symptom
 * that was fixed once, and we must not go back to it for the sake of quality.
 */
@Tag("slow")
class SeamDetourTest {

    /** Length of the window for measuring local detours (distance along the route, blocks). */
    private static final double WINDOW_BLOCKS = 64.0;

    /** Interval for sliding the window (blocks). */
    private static final double STRIDE_BLOCKS = 16.0;

    private static final int RADIUS = 96;

    /**
     * Fail if the route with repaired seams becomes more expensive than the unrepaired route by more than this ratio.
     * Measured at 0.88 to 1.00 times (cheaper on all 5 terrains).
     */
    private static final double REPAIR_VERSUS_EXTEND_LIMIT = 1.02;

    /**
     * <b>Fail if re-planning everything becomes clearly cheaper.</b> This is the basis for fixing only the seams
     * instead of adopting the user's request to "re-plan everything when re-planning": measurements showed that
     * re-planning only removes half of the seam detours (the re-planned part gets seams too), and the line underfoot
     * was redrawn 4 to 12 times. Measured at 0.94 to 1.00 times.
     */
    private static final double REPAIR_VERSUS_REPLAN_LIMIT = 1.05;

    /** Local detour allowed to remain on the route with repaired seams. Measured worst is 1.093 times. */
    private static final double REPAIRED_SEAM_WORST_LIMIT = 1.20;

    /** Number of times the line underfoot (within 32 blocks) may be redrawn. Measured 0 to 1; full re-planning is 4 to 12. */
    private static final int REPAIRED_NEAR_REDRAW_LIMIT = 2;

    private record Route(String name, String resource, BlockPos start, BlockPos goal) {
    }

    private static List<Route> routes() {
        return List.of(
                new Route("surface", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(30, 0, 30), new BlockPos(230, 0, 220)),
                new Route("surface2", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(230, 0, 30), new BlockPos(40, 0, 210)),
                new Route("nether", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-180, 0, -180), new BlockPos(-20, 0, -20)),
                new Route("nether2", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-20, 0, -180), new BlockPos(-180, 0, -30)),
                new Route("end", "/end_terrain_columns.txt.gz",
                        new BlockPos(1160, 0, 1240), new BlockPos(1260, 0, 1160)));
    }

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Window(int from, int to, double ratio, boolean seam) {
    }

    private static List<Window> windows(FakeCells all, ProgressiveWalk.Trace trace) {
        List<PathStep> steps = trace.steps();
        double[] along = new double[steps.size()];
        double[] cost = new double[steps.size()];
        for (int i = 1; i < steps.size(); i++) {
            along[i] = along[i - 1] + distance(steps.get(i - 1).pos(), steps.get(i).pos());
            cost[i] = cost[i - 1] + steps.get(i).cost();
        }
        List<Window> result = new ArrayList<>();
        double nextStart = 0.0;
        for (int i = 0; i < steps.size(); i++) {
            if (along[i] < nextStart) {
                continue;
            }
            int to = -1;
            for (int j = i + 1; j < steps.size(); j++) {
                if (along[j] - along[i] >= WINDOW_BLOCKS) {
                    to = j;
                    break;
                }
            }
            if (to < 0) {
                break;
            }
            nextStart = along[i] + STRIDE_BLOCKS;
            double best = ProgressiveWalk.fullVisibilityBest(all, steps.get(i).pos(), steps.get(to).pos());
            if (!Double.isFinite(best) || best <= 0) {
                continue;
            }
            boolean seam = false;
            for (int joint : trace.joints()) {
                if (joint > i && joint < to) {
                    seam = true;
                    break;
                }
            }
            result.add(new Window(i, to, (cost[to] - cost[i]) / best, seam));
        }
        return result;
    }

    private static String summarize(String label, List<Window> windows, List<PathStep> steps) {
        if (windows.isEmpty()) {
            return label + " no windows";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%s windows%d", label, windows.size()));
        for (boolean seam : new boolean[] {true, false}) {
            List<Window> subset = windows.stream().filter(w -> w.seam() == seam).toList();
            String kind = seam ? "with seam" : "without seam";
            if (subset.isEmpty()) {
                out.append(String.format(Locale.ROOT, " | %s 0", kind));
                continue;
            }
            double mean = subset.stream().mapToDouble(Window::ratio).average().orElse(0);
            Window worst = subset.stream().max((a, b) -> Double.compare(a.ratio(), b.ratio())).orElseThrow();
            out.append(String.format(Locale.ROOT, " | %s %d avg%.3f worst%.3f@%s",
                    kind, subset.size(), mean, worst.ratio(),
                    steps.get(worst.from()).pos().toShortString()));
        }
        return out.toString();
    }

    private static String label(ProgressiveWalk.Mode mode) {
        return switch (mode) {
            case EXTEND -> "extend";
            case REPLAN -> "replan all";
            case REPAIR -> "repair seams only";
        };
    }

    @Test
    void repairingSeamsBeatsBothExtendingAndReplanning() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            Map<ProgressiveWalk.Mode, ProgressiveWalk.Trace> traces = new EnumMap<>(ProgressiveWalk.Mode.class);
            for (ProgressiveWalk.Mode mode : ProgressiveWalk.Mode.values()) {
                ProgressiveWalk.Trace trace = ProgressiveWalk.trace(all, start, goal, RADIUS, mode);
                if (trace.steps().isEmpty()) {
                    failures.add(route.name() + " " + label(mode) + " did not reach the destination");
                    continue;
                }
                traces.put(mode, trace);
                report.add(summarize(String.format(Locale.ROOT,
                                "%s %s total%.0f seams%d redraws%d(near%d, total %.0f blocks)"
                                        + " repairs%d/%d expanded%d nodes",
                                route.name(), label(mode), ProgressiveWalk.cost(trace.steps()),
                                trace.joints().size(), trace.redraws(), trace.nearRedraws(),
                                trace.redrawnBlocks(), trace.repairsTaken(), trace.repairAttempts(),
                                trace.repairNodes()),
                        windows(all, trace), trace.steps()));
            }
            ProgressiveWalk.Trace repaired = traces.get(ProgressiveWalk.Mode.REPAIR);
            if (repaired == null) {
                continue;
            }
            double cost = ProgressiveWalk.cost(repaired.steps());
            for (ProgressiveWalk.Mode other : List.of(ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Mode.REPLAN)) {
                ProgressiveWalk.Trace trace = traces.get(other);
                double limit = other == ProgressiveWalk.Mode.EXTEND
                        ? REPAIR_VERSUS_EXTEND_LIMIT : REPAIR_VERSUS_REPLAN_LIMIT;
                if (trace != null && cost > ProgressiveWalk.cost(trace.steps()) * limit) {
                    failures.add(String.format(Locale.ROOT, "%s: repairing only seams gives %.0f, more than %s's %.0f",
                            route.name(), cost, label(other), ProgressiveWalk.cost(trace.steps())));
                }
            }
            double worst = windows(all, repaired).stream().filter(Window::seam)
                    .mapToDouble(Window::ratio).max().orElse(1.0);
            if (worst > REPAIRED_SEAM_WORST_LIMIT) {
                failures.add(String.format(Locale.ROOT, "%s: %.3f times still remains at a seam after repair",
                        route.name(), worst));
            }
            if (repaired.nearRedraws() > REPAIRED_NEAR_REDRAW_LIMIT) {
                failures.add(String.format(Locale.ROOT, "%s: the line underfoot was redrawn %d times (limit %d)",
                        route.name(), repaired.nearRedraws(), REPAIRED_NEAR_REDRAW_LIMIT));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
