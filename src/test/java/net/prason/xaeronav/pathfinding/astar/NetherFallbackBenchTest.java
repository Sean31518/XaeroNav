package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;

/**
 * Measurement isolating the cause of "a better guide makes the walk-through worse" in the Nether.
 *
 * <p>{@code AStarPathfinder#selectFallback} picks the partial path's end point by {@code argmin(h + g/c)}.
 * Setting waste {@code w = g - (h0 - h)}, when the guide is {@code k} times the true value this is
 * equivalent to {@code maximize g - λw} ({@code λ = k / (k - 1/c)}); in other words,
 * <b>the guide's scale directly determines how much detour the end-point choice tolerates</b>.
 * This measures that {@code k}.
 */
@Tag("bench")
class NetherFallbackBenchTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** Budget for the baseline single solve (same as {@code ProgressiveWalk#fullVisibilityBest}). */
    private static final SearchLimits REFERENCE = new SearchLimits(3_000_000, 120_000, 1.0);

    /**
     * Segments whose remaining cost is below this aren't used for the ratio. Right before the goal the true value
     * drops to 0, so with any guide the ratio diverges and wrecks the average.
     */
    private static final double MIN_REMAINING_TICKS = 200.0;

    /** The guides measured, with their names. */
    private record Guide(String name, CostToGo guide) {
    }

    private static List<Guide> guides(FakeCells cells, BlockPos start, BlockPos goal) {
        return List.of(
                new Guide("3D coarse layer (prod)", XaeroMapModel.guide(cells, start, goal,
                        NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L)),
                new Guide("floors only (ideal)", WideVoxelGuide.build(cells, cells.bounds(), goal, true)),
                new Guide("full terrain (ideal)", WideVoxelGuide.build(cells, cells.bounds(), goal, false)));
    }

    /**
     * Guides used for the matrix. <b>The last two are stand-ins "with only their magnitude moved toward the true value"</b>:
     * constant multiples to get the perfect guide's {@code k=1} without rebuilding the closure. Their shape (which way to detour)
     * is still the original guide's, so they aren't the perfect guide itself, but to see <b>how the end-point choice reacts to k</b>,
     * being able to move only k makes these the more direct tool.
     */
    private static List<Guide> matrixGuides(FakeCells cells, BlockPos start, BlockPos goal) {
        CostToGo ideal = WideVoxelGuide.build(cells, cells.bounds(), goal, true);
        return List.of(
                new Guide("3D coarse layer (prod)", XaeroMapModel.guide(cells, start, goal,
                        NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L)),
                new Guide("floors only k≈0.8", ideal),
                new Guide("floors only x1.25 k≈1", scaled(ideal, 1.25)),
                new Guide("floors only x1.6 k≈1.3", scaled(ideal, 1.6)));
    }

    private static CostToGo scaled(CostToGo guide, double factor) {
        return (x, y, z) -> guide.estimate(x, y, z) * factor;
    }

    /**
     * Measures {@code h / true remaining cost} at each point of the baseline path. Sub-paths of an optimal path are optimal,
     * so the true remaining cost is exactly "total minus accumulated so far".
     */
    @Test
    void measuresHowMuchEachGuideInflates() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain();
        double descent = cells.minDescentTicksPerBlock(6);
        for (BlockPos[] route : NetherLiveWalkTest.routes()) {
            PathResult reference = new AStarPathfinder(cells, REFERENCE).search(route[0], route[1], NEVER);
            if (!reference.complete()) {
                System.out.printf(Locale.ROOT, "%s->%s baseline did not finish (%s)%n",
                        route[0].toShortString(), route[1].toShortString(), reference.termination());
                continue;
            }
            List<PathStep> steps = reference.steps();
            double total = ProgressiveWalk.cost(steps);
            System.out.printf(Locale.ROOT, "%n%s->%s baseline %.0ftick %d moves%n",
                    route[0].toShortString(), route[1].toShortString(), total, steps.size());

            List<BlockPos> at = new ArrayList<>();
            List<Double> remaining = new ArrayList<>();
            double walked = 0.0;
            at.add(route[0]);
            remaining.add(total);
            for (PathStep step : steps) {
                walked += step.cost();
                at.add(step.pos());
                remaining.add(total - walked);
            }

            report("geometric only", at, remaining, (x, y, z) -> Heuristic.estimate(x, y, z,
                    route[1].getX(), route[1].getY(), route[1].getZ(), descent,
                    ActionCosts.SPRINT_ONE_BLOCK));
            for (Guide guide : guides(cells, route[0], route[1])) {
                report(guide.name(), at, remaining, (x, y, z) -> guide.guide().estimate(x, y, z));
                report(guide.name() + "+geometric max", at, remaining, (x, y, z) -> Math.max(
                        guide.guide().estimate(x, y, z),
                        Heuristic.estimate(x, y, z, route[1].getX(), route[1].getY(), route[1].getZ(),
                                descent, ActionCosts.SPRINT_ONE_BLOCK)));
            }
        }
    }

    /** End-point selections to compare. With {@code budget} false the cap is not applied = the behavior before this change. */
    private record Rule(String name, boolean budget) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("no cap (old)", false),
            new Rule("capped (current)", true));

    /**
     * Matrix of guide quality x end-point selection. <b>What matters is the direction, not absolute values</b>:
     * is there a rule whose ratio drops as the guide gets better.
     */
    @Test
    void comparesFallbackRulesAcrossGuideQuality() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain();
        for (BlockPos[] route : NetherLiveWalkTest.routes()) {
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            System.out.printf(Locale.ROOT, "%n%s->%s baseline %.0f%n",
                    route[0].toShortString(), route[1].toShortString(), best);
            for (Guide guide : matrixGuides(cells, route[0], route[1])) {
                for (Rule rule : RULES) {
                    System.out.printf(Locale.ROOT, "  %-14s %-14s %s%n", guide.name(), rule.name(),
                            walk(cells, route, guide.guide(), rule, best));
                }
            }
        }
    }

    private static String walk(FakeCells cells, BlockPos[] route, CostToGo guide, Rule rule, double best) {
        AStarPathfinder.fallbackBudgetEnabled = rule.budget();
        try {
            long began = System.currentTimeMillis();
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, route[0], route[1],
                    NetherLiveWalkTest.WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR,
                    ProgressiveWalk.Aim.GOAL, guide);
            long took = (System.currentTimeMillis() - began) / 1000;
            if (trace.steps().isEmpty()) {
                return "not reached: " + trace.stopped() + String.format(Locale.ROOT, " (%ds)", took);
            }
            double cost = ProgressiveWalk.cost(trace.steps());
            return String.format(Locale.ROOT, "%6.0f(%.3fx) seams %2d re-solves %d/%d (%ds)",
                    cost, cost / best, trace.joints().size(), trace.repairsTaken(),
                    trace.repairAttempts(), took);
        } finally {
            AStarPathfinder.fallbackBudgetEnabled = true;
        }
    }

    private interface Estimate {
        double at(int x, int y, int z);
    }

    /** Mean, median and max of {@code h / true value} along the path, and the waste tolerance λ of end-point selection it implies. */
    private static void report(String name, List<BlockPos> at, List<Double> remaining, Estimate h) {
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) {
            double truth = remaining.get(i);
            if (truth < MIN_REMAINING_TICKS) {
                continue;
            }
            BlockPos pos = at.get(i);
            ratios.add(h.at(pos.getX(), pos.getY(), pos.getZ()) / truth);
        }
        if (ratios.isEmpty()) {
            System.out.printf(Locale.ROOT, "  %-16s no segments to measure%n", name);
            return;
        }
        List<Double> sorted = new ArrayList<>(ratios);
        sorted.sort(Double::compareTo);
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.get(sorted.size() / 2);
        System.out.printf(Locale.ROOT, "  %-16s k mean %.2f median %.2f min %.2f max %.2f -> λ(c=1.5)=%s%n",
                name, mean, median, sorted.get(0), sorted.get(sorted.size() - 1), lambda(median));
    }

    /** The λ in {@code g - λw} that {@code argmin(h + g/c)} actually maximizes. */
    private static String lambda(double k) {
        double denominator = k - 1.0 / 1.5;
        if (denominator <= 0) {
            return "diverges (the further forward the better)";
        }
        return String.format(Locale.ROOT, "%.2f", k / denominator);
    }
}
