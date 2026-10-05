package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Cost calculation is only an advance estimate, so right before presenting a route
 * the safety of dig stretches is re-checked (adjacent lava, water inflow, exposure to deep shafts).
 * This does not affect A*'s search cost; it is kept separate as a post-hoc annotation on the result.
 */
public final class PathSafetyChecker {

    private static final int VOID_SCAN_DEPTH = 5;
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Scan depth for looking through to lava beneath a placement stretch. Kept in line with
     * {@code AStarPathfinder#COLUMN_SCAN_DEPTH}: if this is shallow, lava far below an open cavern (the Nether's 3D maze)
     * only looks like "air underfoot", slips past the adjacency check ({@link #hasAdjacent}, which only looks 1 cell
     * below the feet), and gets no warning color.
     */
    private static final int BRIDGE_LAVA_SCAN_DEPTH = 128;

    /**
     * Number of underwater steps allowed without a breath. Air runs out after {@code AIR_SUPPLY_TICKS}, and from then on
     * damage is dealt every second. Prone swimming ({@code SWIM_ONE_BLOCK} = about 5.6 ticks) covers about 54 cells,
     * swimming without sprinting (about 12.5 ticks) about 24 cells.
     *
     * <p>It sits short of {@code CellSource#maxSubmergedTicks} (default 250 ticks) because this is a <b>warning</b>.
     * That one counts in movement time (so heavy digging matters), while this stays a rough measure counted in steps.
     * The limit is a hard line beyond which no moves are generated, and dives exceeding it only appear in the
     * anti-stuck fallback. Those stretches always get a color, but even inside the limit your air is not necessarily
     * full when you start diving, so the warning starts earlier.
     */
    private static final int SUBMERGED_STEP_LIMIT = 20;

    private PathSafetyChecker() {
    }

    public static PathResult annotate(CellSource view, PathResult result) {
        List<PathStep> steps = result.steps();
        boolean[] drowning = drowningRuns(view, steps);
        List<PathStep> annotated = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            PathRisk risk = assessRisk(view, step);
            if (risk == PathRisk.NONE && drowning[i]) {
                risk = PathRisk.DROWNING;
            }
            // Most stretches have no hazard = left as input. Only the ones that need rebuilding are replaced
            annotated.add(risk == step.risk() ? step
                    : new PathStep(step.pos(), step.movement(), step.cost(), step.bodyCells(), step.digCells(),
                            risk, step.placedBlockPos()));
        }
        return new PathResult(annotated, result.termination(), result.expandedNodes(), result.distinctNodes(),
                result.limitsHeld());
    }

    /**
     * Marks stretches that continue fully submerged for more than {@link #SUBMERGED_STEP_LIMIT} steps.
     *
     * <p>A hazard you cannot see one step at a time, so it is judged by the length of consecutive submerged stretches.
     * Short dives are fine with a breath, and swimming on the surface (feet underwater but head above) never drowns
     * however long it is.
     */
    private static boolean[] drowningRuns(CellSource view, List<PathStep> steps) {
        boolean[] flagged = new boolean[steps.size()];
        int runStart = -1;
        for (int i = 0; i <= steps.size(); i++) {
            if (i < steps.size() && headUnderwater(view, steps.get(i))) {
                if (runStart < 0) {
                    runStart = i;
                }
                continue;
            }
            if (runStart >= 0 && i - runStart > SUBMERGED_STEP_LIMIT) {
                for (int submerged = runStart; submerged < i; submerged++) {
                    flagged[submerged] = true;
                }
            }
            runStart = -1;
        }
        return flagged;
    }

    private static boolean headUnderwater(CellSource view, PathStep step) {
        BlockPos pos = step.pos();
        return CellData.water(view.cell(pos.getX(), pos.getY() + 1, pos.getZ()));
    }

    private static PathRisk assessRisk(CellSource view, PathStep step) {
        if (step.bridging()) {
            // A placed block stays directly under your body the whole time you cross it. Crossing a 1-cell gap needs no warning,
            // but over lava or a bottomless void, missing one block ends in death.
            // Look not only at adjacency but also at lava far below an open cavern
            // (the counterpart of AStarPathfinder#addBridge using the same check to set its penalty and limit)
            if (hasAdjacent(view, step.placedBlockPos(), CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
            return switch (footingUnder(view, step.placedBlockPos())) {
                case LAVA -> PathRisk.LAVA_ADJACENT;
                // A lethal drop is treated the same as the void. The warning criterion is "does missing it kill you", not whether a floor exists
                case VOID, FATAL_DROP -> PathRisk.VOID_BELOW;
                case GROUND -> PathRisk.NONE;
            };
        }
        if (CellData.sneakRequired(view.cell(step.pos().getX(), step.pos().getY() - 1, step.pos().getZ()))) {
                // Magma block underfoot. Having made it passable, guidance is incomplete unless it says sneaking is needed
            return PathRisk.SNEAK_OVER_MAGMA;
        }
        if (step.movement() == MovementType.FALL_DAMAGE) {
            return PathRisk.FALL_DAMAGE;
        }
        if (step.movement() == MovementType.FALL_MLG) {
            return PathRisk.MLG_REQUIRED;
        }
        if (step.movement() == MovementType.JUMP) {
            // For jump stretches, what matters is where you land if it fails. Lava is instant death, a deep shaft a serious injury,
            // so change the color like dig stretches to show "falling here is the end"
            return assessJumpRisk(view, step.bodyCells());
        }
        return step.digging() ? assessDigRisk(view, step.digCells()) : PathRisk.NONE;
    }

    private static PathRisk assessJumpRisk(CellSource view, List<BlockPos> bodyCells) {
        for (BlockPos cell : bodyCells) {
            if (hasAdjacent(view, cell, CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : bodyCells) {
            if (isVoidBelow(view, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    /**
     * Checks not only the arrival cell but every cell dug by this move (e.g. the 2 cells above/below the body for a
     * Traverse, 3 cells for a Descend, plus any falling-block chain overhead). Looking only at the single arrival cell
     * misses cases where only the overhead side is next to lava.
     */
    private static PathRisk assessDigRisk(CellSource view, List<BlockPos> digCells) {
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::water)) {
                return PathRisk.WATER_INFLOW;
            }
        }
        for (BlockPos cell : digCells) {
            if (isVoidBelow(view, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    private static boolean hasAdjacent(CellSource view, BlockPos pos, LongPredicate test) {
        for (Direction dir : DIRECTIONS) {
            long neighbor = view.cell(pos.getX() + dir.getStepX(), pos.getY() + dir.getStepY(), pos.getZ() + dir.getStepZ());
            if (test.test(neighbor)) {
                return true;
            }
        }
        return false;
    }

    /** Where you fall if you miss a placed block. */
    private enum Footing {
        /** Lava is visible below, however far. */
        LAVA,
        /** Following only readable cells hit nothing = no bottom. */
        VOID,
        /**
         * There is a floor, but the drop to it is at least {@link CellSource#fatalFallBlocks()} = missing it kills you.
         * The warning criterion is "does missing it kill you", not "is there a floor", so it is treated like {@link #VOID}.
         */
        FATAL_DROP,
        /** A floor that is neither lava nor void, where a fall may be survivable. Also falls back to this when unreadable. */
        GROUND
    }

    /**
     * What lies directly below this coordinate. Follows downward only while there is air.
     *
     * <p>The classification matches what {@code AStarPathfinder#addBridge} uses to set its limit and penalty.
     * If they disagree, bridges the search built get no warning color (or too much of it).
     * Only when the scan stops at an unloaded chunk does it fall back to {@link Footing#GROUND}: the search never builds
     * such a bridge in the first place, so this is reached only when "a chunk unloaded after the route was built", and
     * drawing the unknown as dangerous would fill the whole route with warning colors.
     */
    private static Footing footingUnder(CellSource view, BlockPos pos) {
        for (int depth = 1; depth <= BRIDGE_LAVA_SCAN_DEPTH; depth++) {
            int y = pos.getY() - depth;
            long cell = view.cell(pos.getX(), y, pos.getZ());
            if (CellData.lava(cell)) {
                return Footing.LAVA;
            }
            if (CellData.present(cell)) {
                if (CellData.water(cell)) {
                    // Landing in water resets fall distance in vanilla, so no fall height is fatal
                    return Footing.GROUND;
                }
                if (!CellData.passableEmpty(cell)) {
                    // There is a floor. What remains is <b>how many cells down</b>: if it is a lethal drop, missing the block ends the same as the void
                    return depth >= view.fatalFallBlocks() ? Footing.FATAL_DROP : Footing.GROUND;
                }
                continue;
            }
            return view.isInBounds(pos.getX(), y, pos.getZ()) ? Footing.GROUND : Footing.VOID;
        }
        return Footing.VOID;
    }

    private static boolean isVoidBelow(CellSource view, BlockPos pos) {
        for (int depth = 1; depth <= VOID_SCAN_DEPTH; depth++) {
            // Only true void counts. Water and ladders also stop a fall, so
            // checking with occupiableWithoutDigging would claim "void below" every time we dig above a water surface
            if (!CellData.passableEmpty(view.cell(pos.getX(), pos.getY() - depth, pos.getZ()))) {
                return false;
            }
        }
        return true;
    }
}
