package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.GameCompat;

/**
 * Logs to debug what the adopted route is made of and why it took that shape.
 *
 * <p>The first line (route breakdown) is emitted every time a route is adopted. It lists the step count and cost per
 * move type, the expensive runs, the deviation from a straight line, and the gap between the guide's estimate and
 * reality, so most "why does this look like a detour" questions are answered by this one line (e.g. it went around
 * the hillside to avoid stacking 7 blocks).
 *
 * <p>The second line (for reproduction) is the settings and terrain export range needed to re-solve the same search
 * on the model ({@code FakeCells} + {@code tools/dump_terrain_columns.py}). Emitted only when its content changes:
 * the start is already on the first line, so emitting it every time would just repeat the same line.
 */
final class RouteExplain {

    private static final Logger LOGGER = LogManager.getLogger();

    /** How many expensive runs to list. */
    private static final int COSTLY_RUNS = 3;

    /** How far to expand the export range beyond the bounding box of start and destination. Without a bit beyond the window edge, the model's window is incomplete. */
    private static final int DUMP_PAD_BLOCKS = 128;

    /**
     * Do not extend the export range farther than this from the start. With a destination thousands of blocks away the
     * export would be hundreds of MB, and examining the local shape only needs the window (up to 224) and a bit beyond.
     */
    private static final int DUMP_MAX_REACH_BLOCKS = 1024;

    /** Rounding for the export range, so the reproduction line is not re-emitted every time you walk a little. */
    private static final int DUMP_GRID_BLOCKS = 256;

    private static final ChangeGate<String> reproGate = new ChangeGate<>();

    private RouteExplain() {
    }

    /**
     * @param kind   what produced this route (re-plan trigger, extension, etc.)
     * @param target the point this search aimed at (intermediate target, landing point, destination)
     * @param guide  the guide applied to the search; {@code null} if none
     * @param window the navigation graph guide to the destination. Searches aiming at an End landing point have
     *               {@code guide} re-based to values up to the landing point, so the source of values (which window
     *               edge they come from) is read from this one
     */
    static void log(String kind, Level level, BlockPos start, BlockPos target, BlockPos goal, PathResult result,
                    @Nullable CostToGo guide, @Nullable CostToGo window, CellSource view, MovementOptions options,
                    int renderRadius) {
        if (!LOGGER.isDebugEnabled() || result.steps().isEmpty()) {
            return;
        }
        // Dimension and height are read from the world, so copy them here. Counting walks down the navigation graph guide and is heavy, so it goes to the logging thread
        String repro = repro(level, start, target, goal, window, view, options, renderRadius);
        List<String> digging = diggingDetails(level, view, start, result);
        NavGraphGuide.logOffThread(() -> {
            LOGGER.debug("XaeroNav: route breakdown ({})", summary(kind, start, target, goal, result, guide, window));
            digging.forEach(detail -> LOGGER.debug("XaeroNav: dig check ({})", detail));
            if (reproGate.changed(repro)) {
                LOGGER.debug("XaeroNav: route repro ({})", repro);
            }
        });
    }

    /** Only for the adopted route, up to 8 steps. Reads of the world and the search cells are copied by the caller. */
    private static List<String> diggingDetails(Level level, CellSource view, BlockPos start, PathResult result) {
        List<String> details = new ArrayList<>();
        BlockPos from = start;
        for (PathStep step : result.steps()) {
            if (step.digging()) {
                StringJoiner blocks = new StringJoiner("; ");
                double raw = 0;
                for (BlockPos pos : step.digCells()) {
                    double ticks = CellData.digTicks(
                            view.cell(pos.getX(), pos.getY(), pos.getZ()));
                    raw += ticks;
                    blocks.add("%s=%s/%.3ftick".formatted(pos.toShortString(), level.getBlockState(pos), ticks));
                }
                boolean sourceWater = CellData.water(
                        view.cell(from.getX(), from.getY() + 1, from.getZ()));
                boolean sourceFloor = CellData.standable(
                        view.cell(from.getX(), from.getY() - 1, from.getZ()));
                boolean targetFloor = CellData.standable(
                        view.cell(step.pos().getX(), step.pos().getY() - 1, step.pos().getZ()));
                details.add("from=%s, to=%s, movement=%s, startHeadInWater=%s, startFooting=%s, endFooting=%s, rawDig=%.3ftick, withMove=%.3ftick, targets=[%s]"
                        .formatted(from.toShortString(), step.pos().toShortString(), step.movement(), sourceWater,
                                sourceFloor, targetFloor, raw, step.cost(), blocks));
                if (details.size() == 8) {
                    break;
                }
            }
            from = step.pos();
        }
        return List.copyOf(details);
    }

    private static String summary(String kind, BlockPos start, BlockPos target, BlockPos goal, PathResult result,
                                  @Nullable CostToGo guide, @Nullable CostToGo window) {
        List<PathStep> steps = result.steps();
        Map<String, double[]> byKind = new LinkedHashMap<>();
        List<Run> runs = new ArrayList<>();
        Run run = null;
        int up = 0;
        int down = 0;
        int minY = start.getY();
        int maxY = start.getY();
        double walked = 0.0;
        double cost = 0.0;
        double gx = target.getX() - start.getX();
        double gz = target.getZ() - start.getZ();
        double line = Math.hypot(gx, gz);
        double deviation = 0.0;
        BlockPos deviationAt = start;
        BlockPos previous = start;
        for (PathStep step : steps) {
            BlockPos pos = step.pos();
            String action = action(step);
            double[] tally = byKind.computeIfAbsent(action, key -> new double[2]);
            tally[0]++;
            tally[1] += step.cost();
            cost += step.cost();
            if (run == null || !run.action.equals(action)) {
                run = new Run(action, pos);
                runs.add(run);
            }
            run.steps++;
            run.cost += step.cost();
            int dy = pos.getY() - previous.getY();
            if (dy > 0) {
                up += dy;
            } else {
                down -= dy;
            }
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            walked += Math.hypot(pos.getX() - previous.getX(), pos.getZ() - previous.getZ());
            if (line >= 1.0) {
                double off = Math.abs((pos.getX() - start.getX()) * gz - (pos.getZ() - start.getZ()) * gx) / line;
                if (off > deviation) {
                    deviation = off;
                    deviationAt = pos;
                }
            }
            previous = pos;
        }
        BlockPos end = previous;
        double straight = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());

        StringJoiner kinds = new StringJoiner(" ");
        byKind.forEach((action, tally) -> kinds.add("%s%d steps/%d".formatted(action, (int) tally[0], Math.round(tally[1]))));
        // Runs of walking are not a reason just for being long. What we want to see is "where an expensive move was paid"
        StringJoiner costly = new StringJoiner(" ");
        runs.stream().filter(r -> !r.action.equals("walk")).sorted(Comparator.comparingDouble((Run r) -> r.cost).reversed())
                .limit(COSTLY_RUNS)
                .forEach(r -> costly.add("%s%d@%s(%d)".formatted(r.action, r.steps, r.from.toShortString(),
                        Math.round(r.cost))));

        return String.format(Locale.ROOT,
                "%s, start=%s, end=%s, target=%s, goal=%s, %s, reached=%s, expanded=%d, %d steps, cost=%dtick, breakdown=[%s], "
                        + "costly=[%s], up%d down%d y%d to %d, straight%d→walked%d(%.2fx), max off straight-to-target %d@%s, %s",
                kind, start.toShortString(), end.toShortString(), target.toShortString(), goal.toShortString(),
                result.termination(), result.complete(), result.expandedNodes(), steps.size(), Math.round(cost),
                kinds, costly.length() == 0 ? "none" : costly, up, down, minY, maxY, Math.round(straight),
                Math.round(walked), straight < 1.0 ? 0.0 : walked / straight, Math.round(deviation),
                deviationAt.toShortString(), guideVerdict(guide, window, start, end, cost));
    }

    /**
     * Compares the remainder the guide predicted at the start with the cost actually paid + the remainder at the end.
     * A large mismatch means the search's shape is being pulled by the guide (e.g. the outside-window estimate is too
     * cheap, so it drifts outward). For the navigation graph, the source of the values is added too.
     */
    private static String guideVerdict(@Nullable CostToGo guide, @Nullable CostToGo window, BlockPos start,
                                       BlockPos end, double cost) {
        String measured = guide == null ? "guide=none" : "guide%s startRemaining%d paid%d+endRemaining%d".formatted(
                guide == window ? "" : "(to target)", Math.round(guide.estimate(start.getX(), start.getY(), start.getZ())),
                Math.round(cost), Math.round(guide.estimate(end.getX(), end.getY(), end.getZ())));
        if (!(window instanceof WindowField field)) {
            return measured;
        }
        return "%s, windowCenter=%d,%d radius%d, startValueSource=%s, endValueSource=%s".formatted(measured,
                field.centerX(), field.centerZ(), field.radius(), NavGraphGuide.origin(field, start),
                NavGraphGuide.origin(field, end));
    }

    private static String action(PathStep step) {
        if (step.bridging()) {
            return step.movement() == MovementType.ASCEND ? "pillar" : "bridge";
        }
        if (step.digging()) {
            return "dig";
        }
        return switch (step.movement()) {
            case TRAVERSE -> "walk";
            case ASCEND -> "climb";
            case DESCEND -> "descend";
            case JUMP -> "jump";
            case FALL_DAMAGE -> "fall(damage)";
            case FALL_MLG -> "fall(water bucket)";
            case SWIM -> "swim";
            case BOAT -> "boat";
            case CLIMB -> "ladder";
        };
    }

    /**
     * One line for re-solving on the model. Settings are listed in a form that can be passed as-is to the same-named
     * methods of {@code FakeCells}. The outside-window estimate is rebuilt from the terrain in the exported range, so
     * when the range is clipped near the start, far-away values differ from the real game.
     */
    private static String repro(Level level, BlockPos start, BlockPos target, BlockPos goal, @Nullable CostToGo guide,
                                CellSource view, MovementOptions options, int renderRadius) {
        int window = guide instanceof WindowField field ? field.radius() : NavGraphGuide.window(renderRadius);
        String settings = String.format(Locale.ROOT,
                "canPlaceBlocks(%s).placedBlockBudget(%d).maxFallDamagePoints(%d).fatalFallBlocks(%d)"
                        + ".maxBridgeRunBlocks(%d).lavaBridgingEnabled(%s).maxLavaBridgeRunBlocks(%d)"
                        + ".maxVoidBridgeRunBlocks(%d).jumpGapEnabled(%s).maxSubmergedTicks(%d).canMlgWaterBucket(%s)"
                        + ".boatAvailable(%s)",
                view.canPlaceBlocks(), view.placedBlockBudget(), view.maxFallDamagePoints(), view.fatalFallBlocks(),
                view.maxBridgeRunBlocks(), view.lavaBridgingEnabled(), view.maxLavaBridgeRunBlocks(),
                view.maxVoidBridgeRunBlocks(), view.jumpGapEnabled(), view.maxSubmergedTicks(),
                view.canMlgWaterBucket(), view.boatAvailable());
        int minX = Math.max(Math.min(start.getX(), goal.getX()) - DUMP_PAD_BLOCKS, start.getX() - DUMP_MAX_REACH_BLOCKS);
        int maxX = Math.min(Math.max(start.getX(), goal.getX()) + DUMP_PAD_BLOCKS, start.getX() + DUMP_MAX_REACH_BLOCKS);
        int minZ = Math.max(Math.min(start.getZ(), goal.getZ()) - DUMP_PAD_BLOCKS, start.getZ() - DUMP_MAX_REACH_BLOCKS);
        int maxZ = Math.min(Math.max(start.getZ(), goal.getZ()) + DUMP_PAD_BLOCKS, start.getZ() + DUMP_MAX_REACH_BLOCKS);
        boolean clipped = maxX - minX < Math.abs(goal.getX() - start.getX()) + 2 * DUMP_PAD_BLOCKS
                || maxZ - minZ < Math.abs(goal.getZ() - start.getZ()) + 2 * DUMP_PAD_BLOCKS;
        // In the Nether, exporting above the bedrock ceiling (y128+) only adds routes that walk on the roof
        int bandTop = level.dimensionType().hasCeiling() ? 127 : GameCompat.maxBuildHeight(level) - 1;
        return String.format(Locale.ROOT,
                "goal=%s, target=%s, window=%d, dig=%s, settings=%s, export=python3 tools/dump_terrain_columns.py "
                        + "saves/<world>/%s %d %d %d %d --band %d,%d --out <name>.txt.gz%s",
                goal.toShortString(), target.toShortString(), window, options.diggingEnabled(), settings,
                regionDir(level), Math.floorDiv(minX, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS,
                Math.floorDiv(minZ, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS,
                Math.floorDiv(maxX, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS + DUMP_GRID_BLOCKS - 1,
                Math.floorDiv(maxZ, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS + DUMP_GRID_BLOCKS - 1,
                GameCompat.minBuildHeight(level), bandTop,
                clipped ? " (destination is far, so only near the start. The outside-window estimate differs from the real game)" : "");
    }

    private static String regionDir(Level level) {
        if (level.dimension() == Level.NETHER) {
            return "DIM-1/region";
        }
        if (level.dimension() == Level.END) {
            return "DIM1/region";
        }
        if (level.dimension() == Level.OVERWORLD) {
            return "region";
        }
        return "dimensions/<namespace>/<dimension>/region";
    }

    private static final class Run {
        final String action;
        final BlockPos from;
        int steps;
        double cost;

        Run(String action, BlockPos from) {
            this.action = action;
            this.from = from;
        }
    }
}
