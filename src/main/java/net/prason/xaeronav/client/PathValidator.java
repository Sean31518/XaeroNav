package net.prason.xaeronav.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;


import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * Checks whether the path being shown still holds in the current world.
 *
 * <p>The world can change even when the player doesn't move. Still, re-searching just to pick up changes
 * is expensive, so only the cells on the path are examined. It takes a few hundred block lookups proportional to the step count,
 * two orders of magnitude cheaper than redoing the search.
 *
 * <p>The check shares {@link CellData#flagsOf} with the search. If this disagrees with the search,
 * a path the search let through is immediately judged invalid and recalculation never stops.
 */
final class PathValidator {

    private PathValidator() {
    }

    /**
     * The step that no longer holds, and why.
     *
     * <p>The reason string is for diagnostics. The <b>index</b> is needed for the detour decision: whether the change is in "a stretch already walked"
     * or "a stretch still to come", and if still to come, from where onward rebuilding is enough,
     * can't be decided without knowing which step broke.
     *
     * <p>{@code unusableCell} is <b>the cell the search judged usable that actually wasn't</b>
     * (the feet cell for footing, or the cell itself for cells the body is assumed to pass through). Not necessarily the step's own coordinates.
     * Needed so the next search doesn't pick it again ({@link
     * net.prason.xaeronav.pathfinding.world.AvoidedCellSource}).
     *
     * <p>{@code placedAhead} is whether what blocked it was <b>a position to be placed by a later step</b>. For a move that places underfoot and climbs, the placement target
     * is the very cell stood on by the previous move, so placing early into a slot seen ahead blocks that move. What was placed is planned footing,
     * not a mismatch between search and check, so it must not be added to the avoided cells; adding it means the placed block can't be stood on,
     * and what a one-block climb would settle becomes a detouring splice (in-game 2026-09-26: a repair reachable in 2 moves became a 19-move detour).
     */
    record Failure(int stepIndex, BlockPos unusableCell, String reason, boolean placedAhead) {
    }

    /** A cell that didn't hold, and why. A {@link Failure} without the step index. */
    private record CellFailure(BlockPos unusableCell, String reason) {
    }

    /**
     * The first step at or after {@code fromIndex} that no longer holds. {@code null} if all hold.
     *
     * <p>Earlier steps aren't checked because, when partially rebuilding a path, counting even "stretches not being rebuilt"
     * as reasons for invalidity would drop paths that could be detoured into a full replan.
     */
    static Failure firstFailureFrom(Level level, PathResult result, int fromIndex) {
        return firstFailureFrom(level, result, fromIndex, null, 0);
    }

    /**
     * {@link #firstFailureFrom} with steps beyond {@code horizonBlocks} horizontally from {@code near} excluded from
     * validation. If {@code near} is {@code null}, no distance cutoff is applied.
     *
     * <p><b>A gatekeeper so chunks still streaming in aren't mistaken for "terrain has vanished".</b>
     * Right after goto, regions across the full render distance arrive over several seconds. Meanwhile, distant chunks
     * have {@link #readable} returning {@code true} while their sections are unpopulated, and
     * {@code getBlockState} returns air, so "no footing" misfires
     * hundreds of blocks ahead, {@code handleBlockedPath} can't splice that far, and it becomes a full replan.
     * The path keeps growing via extensions, so grow → misfire → full replan → grow again went on for 20-25 seconds.
     *
     * <p>Looking only nearby still preserves detection of "the user placed a block on the road ahead". Changes at distant steps
     * are replanned by {@code extendPath} as they get closer, so the guidance follows even if they can't be picked up now.
     */
    static Failure firstFailureFrom(Level level, PathResult result, int fromIndex, BlockPos near,
                                    double horizonBlocks) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<PathStep> steps = result.steps();
        double horizonSq = horizonBlocks * horizonBlocks;
        // Digging accumulates over the whole path. <b>Cells dug by an earlier step are also
        // "assumed passable" for the steps after it</b>: digging happens only once the player gets there, so being blocked now
        // is expected, not a change. Back when only each step's own digging was considered, paths like
        // digging up through gravel were rejected by this check every time, and since the search produces the same path again, <b>a full replan every 2 seconds
        // went on forever</b> (110 seconds, 34 times in an in-game log). And since it isn't a "failure", neither relaxation
        // nor escalation runs
        Set<BlockPos> plannedDigs = new HashSet<>();
        Map<BlockPos, Integer> plannedPlacements = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            plannedDigs.addAll(step.digCells());
            if (i < fromIndex) {
                if (step.placedBlockPos() != null) {
                    plannedPlacements.putIfAbsent(step.placedBlockPos().immutable(), i);
                }
                continue;
            }
            if (near != null && horizonSq > 0 && horizontalDistSq(near, step.pos()) > horizonSq) {
                // A path doesn't necessarily move away steadily from the start (rounding a cape, coming back), so don't stop here;
                // check later steps too. If the path comes back nearby, that part does get validated
                if (step.placedBlockPos() != null) {
                    plannedPlacements.putIfAbsent(step.placedBlockPos().immutable(), i);
                }
                continue;
            }
            // Earlier bridges not yet placed are footing "assumed to exist" for the steps after them. As with digging,
            // being empty now is expected, not a change. The extension search uses earlier placements as footing
            // (PlannedCellSource), so when the path crosses its own bridge again, without this it's always rejected and
            // the whole path is replanned. Bridges already passed should have been placed, so if missing it's picked up as a real change
            int stepIndex = i;
            CellFailure failure = cellFailure(level, step, i, cursor, plannedDigs,
                    pos -> bridgeStillToBePlaced(steps, fromIndex, stepIndex, pos));
            if (failure != null) {
                int placedAt = laterPlacement(steps, i, failure.unusableCell());
                return new Failure(i, failure.unusableCell(), failure.reason()
                        + plannedPlacementNote(failure.unusableCell(), plannedPlacements, fromIndex)
                        + (placedAt < 0 ? "" : ", position to be placed at later step %d".formatted(placedAt)),
                        placedAt >= 0);
            }
            // Own placements are counted after the own-footing check. A block placed at this step isn't a precondition of this step
            if (step.placedBlockPos() != null) {
                plannedPlacements.putIfAbsent(step.placedBlockPos().immutable(), i);
            }
        }
        return null;
    }

    /** The step after {@code stepIndex} that places a block at {@code cell}, or -1 if none. */
    private static int laterPlacement(List<PathStep> steps, int stepIndex, BlockPos cell) {
        for (int j = stepIndex + 1; j < steps.size(); j++) {
            if (cell.equals(steps.get(j).placedBlockPos())) {
                return j;
            }
        }
        return -1;
    }

    /**
     * Whether {@code footing} is the position of a bridge <b>still to be placed</b> by a step at or after {@code fromIndex} and before {@code stepIndex}.
     * Bridges from steps already passed should have been placed, so they don't count; if missing, it's a real change.
     */
    static boolean bridgeStillToBePlaced(List<PathStep> steps, int fromIndex, int stepIndex, BlockPos footing) {
        for (int j = Math.max(fromIndex, 0); j < stepIndex; j++) {
            if (footing.equals(steps.get(j).placedBlockPos())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A note if the cell that didn't hold overlaps a block to be placed by an earlier step of the path.
     *
     * <p>Bridges not yet placed are treated as footing, so this is reached in cases such as a passed bridge being missing or a bridge position
     * being blocked. The note helps tell apart from the log whether the player placed a block somewhere other than the path expected,
     * or the terrain really changed.
     */
    private static String plannedPlacementNote(BlockPos cell, Map<BlockPos, Integer> plannedPlacements,
                                               int fromIndex) {
        Integer placedAt = plannedPlacements.get(cell);
        if (placedAt == null) {
            return "";
        }
        return ", bridge position to be placed at earlier step %d (%s)".formatted(placedAt,
                placedAt < fromIndex ? "already passed" : "not yet placed");
    }

    /**
     * Why this step <b>on its own</b> doesn't hold in the current world. {@code null} if it holds.
     *
     * <p>Doesn't factor in digging by earlier steps, so it's used for questions like "can you come in from elsewhere
     * and enter right here", as when looking for a splice point. The check that walks the path in order is {@link #firstFailureFrom}.
     */
    static String stepFailure(Level level, PathStep step, int index) {
        CellFailure failure = cellFailure(level, step, index, new BlockPos.MutableBlockPos(),
                Set.copyOf(step.digCells()), pos -> false);
        return failure == null ? null : failure.reason();
    }

    /**
     * Whether that cell can be read from the current world.
     *
     * <p><b>A gatekeeper so unloaded chunks aren't mistaken for "terrain has vanished".</b>
     * {@code Level#getBlockState} returns {@code EmptyLevelChunk}'s {@code VOID_AIR} for unloaded
     * coordinates ({@code ClientChunkCache#getChunk} falls back to {@code emptyChunk}).
     * That reads as <b>no footing and no need to dig</b>, so without the gatekeeper
     * "no footing" and "a cell assumed to be dug is already empty" would both misfire at once.
     *
     * <p>It matters for <b>paths extending beyond the loaded range</b>. Layer 2 builds stretches beyond the render distance
     * from Xaero's map data too, so those were always judged invalid and the whole path was thrown away
     * (in-game log: {@code step 522 (TRAVERSE) no footing pos=0, 3, -1} = 245 blocks ahead).
     * Unknowns must not be counted as "broken"; not being able to pick up changes is already accounted for,
     * and they become readable when you get closer.
     */
    private static boolean readable(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static double horizontalDistSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Whether a cell the body passes through is currently impassable.
     *
     * <p>Cells the path plans to dig are assumed passable whether blocked (not dug yet) or open (the player just dug them).
     * Rebuilding because they opened up would throw away a path of hundreds of moves every time something is dug and replan to a short partial path
     * that ran out of budget (in-game Nether: 723 moves → 39, reached the tip immediately, and the direction changed too). Markers for dug cells
     * aren't drawn by PathRenderer. However, surrounding lava flows into dug holes; before digging they're solid, so lava or fire inside is
     * a change after digging, and guiding as is would make the player walk through lava.
     */
    static boolean bodyCellBlocked(long flags, boolean plannedDig) {
        if (plannedDig) {
            return CellData.lava(flags) || CellData.hazard(flags);
        }
        // Closed doors are assumed passable (open and go through), so they aren't considered blocked
        return !CellData.occupiableWithoutDigging(flags) && !CellData.openable(flags);
    }

    private static CellFailure cellFailure(Level level, PathStep step, int i, BlockPos.MutableBlockPos cursor,
                                           Set<BlockPos> plannedDigs, Predicate<BlockPos> pendingPlacement) {
        BlockPos pos = step.pos();
        if (!readable(level, pos)) {
            // The surroundings of this step can't be read at all. The per-cell gatekeeper would reach the same conclusion,
            // but bailing out first avoids the block lookups themselves while scanning an unloaded stretch
            return null;
        }
        if (step.swimming() || step.boating()) {
            // Both swimming and boat stretches depend on the water itself, not on footing
            if (!CellData.water(CellData.flagsOf(level.getBlockState(pos)))) {
                return new CellFailure(pos, "step %d (%s) no water for the swim/boat move pos=%s"
                        .formatted(i, step.movement(), pos.toShortString()));
            }
        } else if (step.climbing()) {
            // Ladder/vine stretches also depend on the climbable itself, not on footing
            if (!CellData.climbable(CellData.flagsOf(level.getBlockState(pos)))) {
                return new CellFailure(pos, "step %d (%s) nothing to climb pos=%s"
                        .formatted(i, step.movement(), pos.toShortString()));
            }
        } else if (!step.bridging()) {
            // Stretches crossed by placing blocks depend on the feet cell being empty, so the floor isn't checked
            cursor.set(pos.getX(), pos.getY() - 1, pos.getZ());
            if (readable(level, cursor)
                    && !CellData.standable(CellData.flagsOf(level.getBlockState(cursor)))
                    && !pendingPlacement.test(cursor)) {
                BlockPos footing = cursor.immutable();
                return new CellFailure(footing, "step %d (%s) no footing pos=%s"
                        .formatted(i, step.movement(), footing.toShortString()));
            }
        }
        for (BlockPos cell : step.bodyCells()) {
            if (!readable(level, cell)) {
                continue;
            }
            long flags = CellData.flagsOf(level.getBlockState(cell));
            boolean plannedDig = plannedDigs.contains(cell);
            if (bodyCellBlocked(flags, plannedDig)) {
                String what = plannedDig ? "lava/hazard entered a cell planned to be dug" : "a cell the body passes through is blocked";
                return new CellFailure(cell, "step %d (%s, bridging=%s) %s cell=%s state=%s"
                        .formatted(i, step.movement(), step.bridging(), what, cell.toShortString(),
                                level.getBlockState(cell)));
            }
        }
        return null;
    }
}
