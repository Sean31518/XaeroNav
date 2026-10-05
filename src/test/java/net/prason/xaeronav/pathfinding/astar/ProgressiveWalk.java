package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.PlannedCellSource;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.navgraph.RouteReview;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Verifies the layer-1 guide, leg splitting, and extension from the end using saved terrain and a moving load window.
 * This differs from measurements that solve every leg with a single A*. It does not reproduce the real game's initial
 * parallel search, ticks, rendering, or gaps in the Xaero map, so it does not guarantee exactly the same route as the
 * full game. Digging and placement after walking and before extending are reflected, but there is a difference in that
 * a deep budget is used if the extension comes back empty too.
 * Must not be used as an integration test of the main PlannedCellSource.
 * For the control experiment that also reflects terrain changes at the stop position, see NetherStallReproTest.
 *
 * <p>Shared by {@code ProgressiveDiscoveryTest}, {@code LongRouteOptimalityTest}, and others.
 */
final class ProgressiveWalk {

    private static final BooleanSupplier NEVER = () -> false;

    /** Default of {@code PathfindingState#detailHorizonBlocks}. */
    static final int DETAIL_HORIZON = 96;

    /** {@code PathfindingState#MIN_DETAIL_REACH_BLOCKS}. Extends if the window reaches at least this far ahead. */
    static final int MIN_DETAIL_REACH = 24;

    /** {@code PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS}. Tails that advance less than this are not attached. */
    static final double MIN_EXTEND_PROGRESS = 12.0;

    /**
     * Total number of legs one walk-through may solve. <b>Without this, the measurement itself runs away</b>:
     * the 400-tick loop "continues as long as it advances even one tick", so once the search barely progresses it
     * runs for 400 ticks × several seconds = hours (it actually produced a 9-hour and a 1-hour run).
     * One route measures at 10 to 20 legs, so the ceiling is three times that.
     */
    private static final int MAX_LEGS = 60;

    /**
     * Wall-clock time (milliseconds) one walk-through may use. The leg-count ceiling alone is not enough:
     * a leg that goes up to the deep budget (800,000 nodes) takes several seconds, so solving up to the ceiling takes
     * tens of minutes. It is <b>a brake against runaway, not a result value</b>, so there is no need to worry about the
     * answer changing with CI speed (a measurement that hits this is discarded as "did not reach").
     */
    private static final long TRACE_BUDGET_MILLIS = Long.getLong("xaeronav.traceBudgetSeconds", 120L) * 1000L;

    /** {@code PathfindingState#INTERPOLATED_GOAL_RADIUS_BLOCKS}. Interpolated intermediate targets are aimed at as regions. */
    static final int INTERPOLATED_GOAL_RADIUS = 16;

    /** Distance the player walks during one plan (blocks). */
    static final int WALK_PER_TICK = 16;

    /**
     * Radius representing no window = the whole world visible. Larger than any fixture's box, so
     * {@link WindowedCells} hides nothing and extension reaches the destination in one go.
     */
    static final int NO_WINDOW = 4096;

    /** Budget passed to the search for one leg (the {@code PathfindingState} default). */
    private static final int LEG_NODE_BUDGET = 100_000;

    /** Share for the first search ({@code PathfindingExecutor#FIRST_PASS_PERCENT}). */
    private static final int FIRST_PASS_NODE_BUDGET = LEG_NODE_BUDGET * 40 / 100;

    /** Weights tried in order when the goal is not reached ({@code PathfindingExecutor#GREEDY_RETRY_WEIGHTS}). */
    private static final double[] GREEDY_RETRY_WEIGHTS = {2.5, 3.0};

    /** Budget passed to the baseline search. */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    private ProgressiveWalk() {
    }

    static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The destination itself, or if too far, the point {@code reach} toward it (same as the implementation). */
    private static BlockPos aimToward(BlockPos from, BlockPos goal, int reach) {
        double distance = horizontal(from, goal);
        if (distance <= reach) {
            return goal;
        }
        double t = reach / distance;
        return new BlockPos(from.getX() + (int) Math.round((goal.getX() - from.getX()) * t),
                from.getY() + (int) Math.round((goal.getY() - from.getY()) * t),
                from.getZ() + (int) Math.round((goal.getZ() - from.getZ()) * t));
    }

    /**
     * Solves one leg. Raising the weight and re-planning when it does not reach is the same as
     * {@code PathfindingExecutor#retryGreedier}, and <b>without it not a single End island crossing returns</b>
     * (measured: 5 of 6).
     */
    private static PathResult leg(CellSource view, BlockPos from, BlockPos goal) {
        BlockPos aim = aimToward(from, goal, DETAIL_HORIZON);
        int radius = aim.equals(goal) ? 0 : INTERPOLATED_GOAL_RADIUS;
        CoarseMap map = LiveCoarseSampler.sample(view, view.bounds(), from.getY(), NEVER);
        CostToGo guide = CoarseRouter.costToGo(map, aim, false, CoarseRouter.BridgePolicy.BRIDGE);
        PathResult first = search(view, guide, from, aim, radius,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, FIRST_PASS_NODE_BUDGET);
        if (first.complete()) {
            return first;
        }
        for (double weight : GREEDY_RETRY_WEIGHTS) {
            PathResult attempt = search(view, guide, from, aim, radius, weight, LEG_NODE_BUDGET);
            if (attempt.complete()) {
                return attempt;
            }
        }
        return first;
    }

    private static PathResult search(CellSource view, CostToGo guide, BlockPos from, BlockPos aim,
                                     int radius, double weight, int budget) {
        return new AStarPathfinder(view, new SearchLimits(budget, 30_000, weight), guide)
                .search(from, aim, NEVER, Carryover.NONE, radius);
    }

    /** What the detailed search aims at. The two forms of the implementation's {@code PathfindingState#selectDetailTarget}. */
    enum Aim {
        /** A far destination is cut {@link #DETAIL_HORIZON} short and aimed at ({@code goalOrPointToward}). */
        HORIZON,
        /**
         * Always aims at the final destination and extends partial routes cut off by the box (the behavior in
         * dimensions with a ceiling). Measurements with the 3D coarse layer also aim this way.
         */
        GOAL
    }

    /** Default of {@code XaeroNavConfig#searchHorizontalMargin}. */
    private static final int SEARCH_HORIZONTAL_MARGIN = Integer.getInteger("xaeronav.searchMargin", 64);

    /** {@code PathfindingState#DESCENT_BOX_PAD_BLOCKS}. */
    private static final int DESCENT_BOX_PAD_BLOCKS = 16;

    /** Budget passed to one leg (the {@code XaeroNavConfig#searchLimits} default). */
    private static final SearchLimits LIVE_LIMITS =
            new SearchLimits(LEG_NODE_BUDGET, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * Budget stacked when not a single step comes back ({@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}=8).
     * In this model it also applies to extensions. The main extendPath uses only the normal budget, which differs from
     * recalculate doing a deep re-search, so this result is not a guarantee of reaching in the real game.
     */
    private static final SearchLimits DEEP_LIVE_LIMITS =
            new SearchLimits(LEG_NODE_BUDGET * 8, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * The real game's search box (cut the same way as {@code SearchBounds#around}). <b>It cannot be measured without
     * this</b>: the layer-1 guide being invalidated because the destination is outside the box is what the
     * destination-aiming design actually looks like.
     */
    static SearchBounds searchBox(CellSource all, BlockPos from, BlockPos to, int radius) {
        SearchBounds world = all.bounds();
        return new SearchBounds(
                Math.max(from.getX() - radius, Math.min(from.getX(), to.getX()) - SEARCH_HORIZONTAL_MARGIN),
                world.minY(),
                Math.max(from.getZ() - radius, Math.min(from.getZ(), to.getZ()) - SEARCH_HORIZONTAL_MARGIN),
                Math.min(from.getX() + radius, Math.max(from.getX(), to.getX()) + SEARCH_HORIZONTAL_MARGIN),
                world.maxY(),
                Math.min(from.getZ() + radius, Math.max(from.getZ(), to.getZ()) + SEARCH_HORIZONTAL_MARGIN));
    }

    /** Solves one leg with the same box and the same relaxation ladder as the real game. The window is around the player = loaded chunks. */
    private static CellSource boxedView(CellSource all, BlockPos player, int radius, BlockPos from,
                                        BlockPos to) {
        return new WindowedCells(all, player, radius, searchBox(all, from, to, radius));
    }

    /**
     * As in the real End, while the destination is outside the window, aims at the point on the last floor reached by
     * descending the window's guide ({@code PathfindingState#landing}). Set only when measuring the End.
     */
    static volatile boolean END_LANDING;

    /**
     * <b>A leg aiming at the final destination.</b> As in the real game, it goes through {@link PathfindingExecutor#submit},
     * to reproduce the whole ladder: the first search uses only {@code FIRST_PASS_PERCENT} of the budget, and if it
     * does not reach, it re-plans with a higher weight (discarding that partial route).
     */
    private static PathResult legToGoal(PathfindingExecutor executor, CellSource all, BlockPos player,
                                        int radius, BlockPos from, BlockPos goal, List<PathStep> planned,
                                        CostToGo wide, double weight) {
        if (END_LANDING && wide instanceof WindowField field) {
            BlockPos[] landed = {null};
            BlockPos[] last = {null};
            WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ(), (x, y, z) -> {
                last[0] = new BlockPos(x, y, z);
                if (!net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(all.cell(x, y - 1, z))) {
                    landed[0] = new BlockPos(x, y, z);
                }
            });
            BlockPos target = landed[0] != null && !landed[0].equals(from) ? landed[0] : last[0];
            if (descent != null && !descent.reachedGoal() && target != null && !target.equals(from)) {
                double base = field.exact(target.getX(), target.getY(), target.getZ());
                CostToGo shifted = new CostToGo() {
                    @Override
                    public double estimate(int x, int y, int z) {
                        return Math.max(0.0, field.estimate(x, y, z) - base);
                    }

                    @Override
                    public double searchEstimate(int x, int y, int z) {
                        double value = field.searchEstimate(x, y, z);
                        return Double.isNaN(value) ? value : Math.max(0.0, value - base);
                    }
                };
                // The real game's {@code navGraphBounds(wholeWindow=true)} looks at the whole window
                SearchBounds lbox = new SearchBounds(player.getX() - radius, all.bounds().minY(), player.getZ() - radius,
                        player.getX() + radius, all.bounds().maxY(), player.getZ() + radius);
                CellSource lview = new PlannedCellSource(new WindowedCells(all, player, radius, lbox), planned, 0);
                try {
                    PathResult result = executor.submit(lview, from, target, withWeight(LIVE_LIMITS, weight), true, 0,
                            Carryover.after(planned), shifted).get();
                    if (!result.steps().isEmpty()) {
                        return result;
                    }
                    return executor.submit(lview, from, target, withWeight(DEEP_LIVE_LIMITS, weight), true, 0,
                            Carryover.after(planned), shifted).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        // As in the real game's PathfindingState#navGraphBounds, include the path descending the guide in the box
        SearchBounds box = searchBox(all, from, goal, radius);
        int[] trail = wide instanceof WindowField field ? field.descentBox(from.getX(), from.getY(), from.getZ(),
                DESCENT_BOX_PAD_BLOCKS) : null;
        if (trail != null) {
            box = new SearchBounds(Math.min(box.minX(), trail[0]), box.minY(), Math.min(box.minZ(), trail[1]),
                    Math.max(box.maxX(), trail[2]), box.maxY(), Math.max(box.maxZ(), trail[3]));
        }
        CellSource view = new PlannedCellSource(new WindowedCells(all, player, radius, box), planned, 0);
        Carryover carried = Carryover.after(planned);
        try {
            PathResult result =
                    executor.submit(view, from, goal, withWeight(LIVE_LIMITS, weight), true, 0, carried, wide).get();
            if (!result.steps().isEmpty()) {
                return result;
            }
            return executor.submit(view, from, goal, withWeight(DEEP_LIVE_LIMITS, weight), true, 0, carried, wide)
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SearchLimits withWeight(SearchLimits limits, double weight) {
        return new SearchLimits(limits.maxExpandedNodes(), limits.timeLimitMillis(), weight);
    }

    /** Whether to also skip the navigation graph when the leg's start is outside the shell connected to the destination (measurement switch). */
    private static final boolean REFUSE_DISCONNECTED_START = Boolean.getBoolean("xaeronav.navGraphRefuseCut");

    /** Threshold for reviewing a route drawn with a rebuilt guide ({@code PathfindingState#REVIEW_MIN_EXTRA_TICKS}). No review if negative. */
    private static final double REVIEW_MIN_EXTRA_TICKS = Double.parseDouble(System.getProperty("xaeronav.reviewTicks", "40"));

    /** {@code PathfindingState#REVIEW_RETRY_MOVE_BLOCKS}. */
    private static final double REVIEW_RETRY_MOVE = 32.0;

    /** Number of re-plans made by review (for measurement). */
    static final java.util.concurrent.atomic.AtomicInteger REVIEWS = new java.util.concurrent.atomic.AtomicInteger();

    /** Number of legs solved the old way because the navigation graph did not reach the start (for measurement). */
    static final java.util.concurrent.atomic.AtomicInteger UNGUIDED_LEGS = new java.util.concurrent.atomic.AtomicInteger();

    /** Version of {@link #walk} that only looks at the cost. {@link Double#POSITIVE_INFINITY} if it does not reach. */
    static double walkToGoal(CellSource all, BlockPos start, BlockPos goal, int radius,
                             boolean extending) {
        List<PathStep> walked = walk(all, start, goal, radius, extending);
        return walked.isEmpty() ? Double.POSITIVE_INFINITY : cost(walked);
    }

    /**
     * Number of steps passing the same position twice. <b>This cannot happen within a single search</b>
     * ({@code AStarPathfinder} never closes the same cell twice), so if it is nonzero it was created at an extension seam.
     */
    static int selfOverlaps(List<PathStep> steps) {
        Set<BlockPos> seen = new HashSet<>();
        int overlaps = 0;
        for (PathStep step : steps) {
            if (!seen.add(step.pos())) {
                overlaps++;
            }
        }
        return overlaps;
    }

    /** How the route is assembled. The three variants {@link #trace} measures separately. */
    enum Mode {
        /** As implemented. Extends from the end and never revisits the earlier part. */
        EXTEND,
        /** Discards the earlier part on every plan and re-plans from the player (the user's request to "re-plan everything"). */
        REPLAN,
        /** After extending, re-solves only the stretch spanning a seam and replaces it if cheaper. */
        REPAIR
    }

    /** {@code PathfindingState#SEAM_REPAIR_SPAN_BLOCKS}. How many blocks to look at before and after a seam. */
    private static final double REPAIR_SPAN_BLOCKS = 48.0;

    /** {@code PathfindingState#SEAM_REPAIR_KEEP_BLOCKS}. This much ahead of the player is never redrawn. */
    private static final double REPAIR_KEEP_BLOCKS = 16.0;

    /** {@code PathfindingState#SEAM_REPAIR_MIN_GAIN}. The line is not redrawn unless it gets cheaper than this. */
    private static final double REPAIR_MIN_GAIN = 0.98;

    /**
     * Budget passed to the repair. The same as what {@code PathfindingState} passes to one leg (the default
     * {@code maxExpandedNodes}).
     *
     * <p><b>Capping at 60,000 is not enough.</b> Measured, 1 of 3 repairs on nether2 failed, and the local seam
     * detour went back from a worst of 1.059 times to 1.927 times (the in-game log also showed
     * "re-solve didn't reach past the seam (NODE_BUDGET)").
     */
    private static final int REPAIR_NODE_BUDGET = 100_000;

    /** Distance (blocks) at which "the line was redrawn near the player". */
    private static final double NEAR_PLAYER_BLOCKS = 32.0;

    /**
     * The walked route, the <b>positions where legs switched</b> on it (= seams), and
     * <b>how much the line was redrawn</b>.
     *
     * @param joints         indices into {@code steps}. A new leg starts at that step
     * @param redraws        number of times an already drawn line was redrawn
     * @param redrawnBlocks  total length of redrawn stretches (blocks)
     * @param nearRedraws    of those, the number of times the redraw started within
     *                       {@link #NEAR_PLAYER_BLOCKS} of the player
     */
    record Trace(List<PathStep> steps, List<Integer> joints, int redraws, double redrawnBlocks,
                 int nearRedraws, int repairAttempts, int repairsTaken, long repairNodes,
                 String stopped) {

        /** Why the walk did not reach the destination (for diagnostics). Empty if it did. */
        static Trace failed(String reason) {
            return new Trace(List.of(), List.of(), 0, 0, 0, 0, 0, 0, reason);
        }
    }

    /**
     * Walks to the destination while moving the window, and returns the route actually walked. Empty if it does not reach.
     *
     * @param radius    radius of the loaded window. Full visibility if {@link #NO_WINDOW}
     * @param extending if true, extends from the end as implemented. If false, discards the earlier part on every plan
     *                  and re-plans from the player. <b>The walking is the same in both</b>
     */
    static List<PathStep> walk(CellSource all, BlockPos start, BlockPos goal, int radius,
                               boolean extending) {
        return trace(all, start, goal, radius, extending ? Mode.EXTEND : Mode.REPLAN).steps();
    }

    /** The first index where the line was redrawn. {@code -1} if identical. */
    private static int firstDifference(List<PathStep> before, List<PathStep> after) {
        int shared = Math.min(before.size(), after.size());
        for (int i = 0; i < shared; i++) {
            if (!before.get(i).pos().equals(after.get(i).pos())) {
                return i;
            }
        }
        // Merely growing at the back (extension) is not a redraw
        return before.size() > after.size() ? shared : -1;
    }

    /** Length along the route from {@code from} to {@code to} in {@code steps} (blocks). */
    private static double lengthBetween(List<PathStep> steps, int from, int to) {
        double length = 0;
        for (int i = from + 1; i <= to && i < steps.size(); i++) {
            length += Math.sqrt(steps.get(i).pos().distSqr(steps.get(i - 1).pos()));
        }
        return length;
    }

    /** The replaced line and the replaced stretch (from {@code from} on, it became {@code length} steps). */
    private record Repair(List<PathStep> steps, int from, int replaced, int length) {
    }

    /** Cost of one seam repair attempt. If {@code repair} is null, it was not adopted. */
    private record RepairAttempt(Repair repair, long expandedNodes) {
    }

    /**
     * Re-solves only the stretch spanning a seam. The replaced line if it got cheaper, otherwise {@code null}.
     */
    private static RepairAttempt repairSeam(CellSource view, BlockPos player, List<PathStep> planned,
                                            int seam) {
        int first = 0;
        while (first < planned.size() && distance(player, planned.get(first).pos()) < REPAIR_KEEP_BLOCKS) {
            first++;
        }
        int from = seam;
        while (from > first && lengthBetween(planned, from - 1, seam) < REPAIR_SPAN_BLOCKS) {
            from--;
        }
        int to = seam;
        while (to < planned.size() - 1 && lengthBetween(planned, seam, to + 1) <= REPAIR_SPAN_BLOCKS) {
            to++;
        }
        if (from < 1 || from >= seam || to <= seam || to - from < 4) {
            return new RepairAttempt(null, 0);
        }
        // from is the head of the stretch being replaced, so the search starts one step before it
        BlockPos fromPos = planned.get(from - 1).pos();
        BlockPos toPos = planned.get(to).pos();
        double current = cost(planned.subList(from, to + 1));
        // The layer-1 guide is not applied. <b>We measured that it has no effect at this distance</b>: on a 96-block stretch
        // the 16-block-resolution guide falls below the geometric Heuristic and always loses in the max, so the number of
        // expanded nodes did not change at all (exactly identical on all 5 terrains). Applying it only adds work
        CellSource future = new PlannedCellSource(view, planned.subList(0, from), 0);
        PathResult result = new AStarPathfinder(future,
                new SearchLimits(REPAIR_NODE_BUDGET, 30_000, 1.0)).search(fromPos, toPos, NEVER);
        if (!result.complete() || result.steps().isEmpty()
                || !result.steps().get(result.steps().size() - 1).pos().equals(toPos)
                || cost(result.steps()) >= current * REPAIR_MIN_GAIN) {
            return new RepairAttempt(null, result.expandedNodes());
        }
        List<PathStep> repaired = new ArrayList<>(planned.subList(0, from));
        repaired.addAll(result.steps());
        repaired.addAll(planned.subList(to + 1, planned.size()));
        return new RepairAttempt(new Repair(repaired, from, to + 1 - from, result.steps().size()),
                result.expandedNodes());
    }

    /**
     * The view passed to the seam re-solve. The real game cuts the box at both ends of the stretch being replaced
     * ({@code repairSeam}), so this is also narrowed to {@link #REPAIR_SPAN_BLOCKS} + margin around the seam.
     */
    private static CellSource repairView(CellSource all, BlockPos player, int radius,
                                          List<PathStep> planned, int seam) {
        BlockPos at = planned.get(seam).pos();
        int reach = (int) REPAIR_SPAN_BLOCKS + SEARCH_HORIZONTAL_MARGIN;
        return boxedView(all, player, radius, at.offset(-reach, 0, -reach), at.offset(reach, 0, reach));
    }

    /** Returns the same as {@link #walk}, with the seam positions and the amount of redrawing. */
    static Trace trace(CellSource all, BlockPos start, BlockPos goal, int radius, Mode mode) {
        return trace(all, start, goal, radius, mode, Aim.HORIZON);
    }

    /** Version that specifies how to aim. {@link Aim#GOAL} goes through the real game's {@link PathfindingExecutor} as-is. */
    static Trace trace(CellSource all, BlockPos start, BlockPos goal, int radius, Mode mode, Aim aim) {
        return trace(all, start, goal, radius, mode, aim, null);
    }

    /** Swaps only the guide's data source, keeping box, budget, and extension the same for comparison. */
    static Trace trace(CellSource all, BlockPos start, BlockPos goal, int radius, Mode mode, Aim aim,
                       CostToGo wide) {
        return trace(all, start, goal, radius, mode, aim, wide, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
    }

    /** Version that also specifies the weight of leg searches. Only takes effect with {@link Aim#GOAL}. */
    static Trace trace(CellSource all, BlockPos start, BlockPos goal, int radius, Mode mode, Aim aim,
                       CostToGo wide, double weight) {
        return trace(all, start, goal, radius, mode, aim, player -> wide, weight);
    }

    /** Version that rebuilds the guide for each leg from the player's position at that time (reproducing the moving loaded window). */
    static Trace trace(CellSource all, BlockPos start, BlockPos goal, int radius, Mode mode, Aim aim,
                       java.util.function.Function<BlockPos, CostToGo> guideAt, double weight) {
        CellSource original = all;
        goal = StanceFinder.resolveGoal(all, goal);
        PathfindingExecutor executor = new PathfindingExecutor();
        List<PathStep> walked = new ArrayList<>();
        List<PathStep> planned = new ArrayList<>();
        // Where new legs start within planned. Shifted back by the amount walked
        List<Integer> plannedJoints = new ArrayList<>();
        List<Integer> joints = new ArrayList<>();
        int redraws = 0;
        int nearRedraws = 0;
        double redrawnBlocks = 0;
        int repairAttempts = 0;
        int repairsTaken = 0;
        long repairNodes = 0;
        BlockPos player = start;
        CostToGo reviewed = null;
        BlockPos reviewReplannedAt = null;
        double reviewReplannedValue = Double.POSITIVE_INFINITY;
        int legs = 0;
        long deadline = System.currentTimeMillis() + TRACE_BUDGET_MILLIS;
        for (int tick = 0; tick < 400; tick++) {
            if (System.currentTimeMillis() > deadline) {
                return Trace.failed(String.format("used up %d seconds (%s, %.0f to goal, %d legs)",
                        TRACE_BUDGET_MILLIS / 1000, player.toShortString(),
                        horizontal(player, goal), legs));
            }
            // Each trace owns its edits; comparisons never alter the shared fixture.
            all = new PlannedCellSource(original, walked, 0);
            CellSource view = new WindowedCells(all, player, radius);
            List<PathStep> before = planned;
            if (mode == Mode.REPLAN) {
                planned = new ArrayList<>();
                plannedJoints = new ArrayList<>();
                // When re-planning, the head of the leg about to be added is itself the seam (the earlier part was discarded)
                plannedJoints.add(0);
            }
            if (aim == Aim.GOAL) {
                // The real game also checks whether to rebuild the guide on every recalculation that does not throw a leg (NavGraphGuide#forGoal)
                CostToGo latest = guideAt.apply(player);
                if (REVIEW_MIN_EXTRA_TICKS >= 0 && latest != reviewed && latest instanceof WindowField field
                        && field.reachesGoal() && !planned.isEmpty()
                        && (reviewReplannedAt == null || horizontal(player, reviewReplannedAt) >= REVIEW_RETRY_MOVE
                                && field.estimate(player.getX(), player.getY(), player.getZ()) < reviewReplannedValue)) {
                    reviewed = latest;
                    RouteReview.Detour detour = RouteReview.detour(field, player, planned, 0);
                    if (Boolean.getBoolean("xaeronav.navGraphVerbose") && detour.extraTicks() > 0) {
                        System.out.printf(java.util.Locale.ROOT, "  review %s extra%.0f leg%.0f h=%.0f plan%d steps remainingCost%.0f%n",
                                player.toShortString(), detour.extraTicks(), detour.walkedTicks(),
                                field.exact(player.getX(), player.getY(), player.getZ()), planned.size(), cost(planned));
                    }
                    if (detour.worthReplanning(REVIEW_MIN_EXTRA_TICKS)) {
                        REVIEWS.incrementAndGet();
                        reviewReplannedAt = player;
                        reviewReplannedValue = field.estimate(player.getX(), player.getY(), player.getZ());
                        planned = new ArrayList<>();
                        plannedJoints = new ArrayList<>();
                        plannedJoints.add(0);
                    }
                }
            }
            BlockPos end = planned.isEmpty() ? player : planned.get(planned.size() - 1).pos();
            while (horizontal(player, end) <= radius - MIN_DETAIL_REACH && !end.equals(goal)) {
                if (++legs > MAX_LEGS) {
                    return Trace.failed(String.format("exceeded %d legs (%s, %.0f to goal)",
                            MAX_LEGS, player.toShortString(), horizontal(player, goal)));
                }
                CostToGo guide = aim == Aim.HORIZON ? null : guideAt.apply(player);
                // The real game (PathfindingState#goalGuide) does not use the navigation graph if it does not reach the leg's
                // start, and solves the leg the old way, via intermediate targets
                boolean unguided = guide instanceof WindowField field && (!field.reachesGoal()
                        || REFUSE_DISCONNECTED_START && !field.connects(end.getX(), end.getY(), end.getZ()));
                if (unguided) {
                    if (Boolean.getBoolean("xaeronav.navGraphVerbose")) {
                        WindowField cut = (WindowField) guide;
                        System.out.printf(java.util.Locale.ROOT, "  unusable leg player%s start%s goal%s windowCenter%d,%d toGoal%.0f%n",
                                player.toShortString(), end.toShortString(), goal.toShortString(), cut.centerX(),
                                cut.centerZ(), horizontal(player, goal));
                    }
                    UNGUIDED_LEGS.incrementAndGet();
                }
                PathResult result = aim == Aim.HORIZON || unguided
                        ? leg(new PlannedCellSource(view, planned, 0), end, goal)
                        : legToGoal(executor, all, player, radius, end, goal, planned, guide, weight);
                if (result.steps().isEmpty()) {
                    break;
                }
                if (aim != Aim.HORIZON && !planned.isEmpty() && !result.complete()
                        && horizontal(end, result.steps().get(result.steps().size() - 1).pos())
                                < MIN_EXTEND_PROGRESS) {
                    // The real game (PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS) discards this tail without attaching it,
                    // and records that it cannot extend from that end. Attaching a few crawling blocks would
                    // mark even a proven route as unreached.
                    // <b>The brake applies only to extension</b>: re-planning from the player (recalculate)
                    // outputs it as a provisional route however short it is
                    break;
                }
                int seam = planned.size();
                if (!planned.isEmpty() || !plannedJoints.contains(0)) {
                    plannedJoints.add(seam);
                }
                planned.addAll(result.steps());
                BlockPos next = planned.get(planned.size() - 1).pos();
                if (mode == Mode.REPAIR && seam > 0) {
                    RepairAttempt attempt = repairSeam(
                            aim == Aim.HORIZON ? view : repairView(all, player, radius, planned, seam),
                            player, planned, seam);
                    repairAttempts++;
                    repairNodes += attempt.expandedNodes();
                    if (attempt.repair() != null) {
                        repairsTaken++;
                        planned = attempt.repair().steps();
                        plannedJoints = shifted(plannedJoints, attempt.repair());
                    }
                }
                if (next.equals(end)) {
                    break;
                }
                end = next;
            }
            if (planned.isEmpty()) {
                return Trace.failed(String.format("tick%d not a single route came out (%s, %.0f to goal)",
                        tick, player.toShortString(), horizontal(player, goal)));
            }
            int changed = firstDifference(before, planned);
            if (changed >= 0) {
                redraws++;
                redrawnBlocks += lengthBetween(before, changed, before.size() - 1);
                if (changed < before.size()
                        && distance(player, before.get(changed).pos()) <= NEAR_PLAYER_BLOCKS) {
                    nearRedraws++;
                }
            }
            int walkTo = 0;
            while (walkTo < planned.size()
                    && horizontal(player, planned.get(walkTo).pos()) < WALK_PER_TICK) {
                walkTo++;
            }
            walkTo = Math.max(1, Math.min(walkTo, planned.size()));
            int walkedBefore = walked.size();
            for (int offset : plannedJoints) {
                if (offset < walkTo) {
                    joints.add(walkedBefore + offset);
                }
            }
            int consumed = walkTo;
            List<Integer> remaining = new ArrayList<>();
            for (int offset : plannedJoints) {
                if (offset >= consumed) {
                    remaining.add(offset - consumed);
                }
            }
            plannedJoints = remaining;
            walked.addAll(planned.subList(0, walkTo));
            planned = new ArrayList<>(planned.subList(walkTo, planned.size()));
            player = walked.get(walked.size() - 1).pos();
            if (Boolean.getBoolean("xaeronav.walkTrace")) {
                CostToGo traceGuide = aim == Aim.GOAL ? guideAt.apply(player) : null;
                System.out.printf(java.util.Locale.ROOT, "  tick%d %s walked%.0f plan%d steps(end%s) h=%.0f%n", tick, player.toShortString(),
                        cost(walked), planned.size(), planned.isEmpty() ? "-" : planned.get(planned.size() - 1).pos().toShortString(),
                        traceGuide == null ? Double.NaN : traceGuide.estimate(player.getX(), player.getY(), player.getZ()));
            }
            if (player.equals(goal)) {
                return new Trace(walked, joints, redraws, redrawnBlocks, nearRedraws,
                        repairAttempts, repairsTaken, repairNodes, "");
            }
        }
        return Trace.failed(String.format("did not reach in 400 ticks (%s, %.0f to goal, walked %d steps)",
                player.toShortString(), horizontal(player, goal), walked.size()));
    }

    /** Re-indexes seams moved by a replacement. Seams inside the replaced stretch are collapsed to its head. */
    private static List<Integer> shifted(List<Integer> joints, Repair repair) {
        int after = repair.from() + repair.replaced();
        int shift = repair.length() - repair.replaced();
        List<Integer> moved = new ArrayList<>();
        boolean inside = false;
        for (int joint : joints) {
            if (joint < repair.from()) {
                moved.add(joint);
            } else if (joint >= after) {
                moved.add(joint + shift);
            } else {
                inside = true;
            }
        }
        if (inside) {
            moved.add(repair.from());
        }
        moved.sort(Integer::compareTo);
        return moved;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    /** A single search with full visibility, weight 1.0, and no guide. {@link Double#POSITIVE_INFINITY} if it does not reach. */
    static double fullVisibilityBest(CellSource all, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(all,
                new SearchLimits(UNLIMITED_NODE_BUDGET, 120_000, 1.0)).search(start, goal, NEVER);
        return result.complete() ? cost(result.steps()) : Double.POSITIVE_INFINITY;
    }
}
