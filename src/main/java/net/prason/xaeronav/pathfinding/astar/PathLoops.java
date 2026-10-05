package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * <b>Folds stretches where the path passes the same position twice.</b>
 *
 * <p>This doesn't happen within a single {@link AStarPathfinder} run (it never closes the same cell twice). It arises
 * <b>at extend and splice seams</b>, because the later leg is solved without knowing where the earlier leg went.
 * This is the user report "two routes overlap on the same block".
 *
 * <p><b>Stretches containing digging or placement aren't folded.</b> Later moves may be walking on placed blocks,
 * and removing them removes the footing too. The same goes for dug holes: later moves are connected on the assumption of passing through them.
 *
 * <p>Turnarounds back to the start itself aren't folded (the start isn't a step, so it doesn't appear in this list).
 */
public final class PathLoops {

    /**
     * @param steps    the list after folding
     * @param newIndex the post-fold index for each pre-fold index. Removed steps point to the index of <b>the one that remained at that position</b>
     *                 (used to re-establish leg boundaries)
     */
    public record Folded(List<PathStep> steps, int[] newIndex) {

        public Folded {
            steps = List.copyOf(steps);
            newIndex = newIndex.clone();
        }

        @Override
        public int[] newIndex() {
            return newIndex.clone();
        }

        public boolean changed() {
            return steps.size() != newIndex.length;
        }
    }

    private PathLoops() {
    }

    public static Folded fold(List<PathStep> steps) {
        List<PathStep> out = new ArrayList<>(steps.size());
        int[] newIndex = new int[steps.size()];
        Map<BlockPos, Integer> seen = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            Integer previous = seen.get(step.pos());
            if (previous != null && foldable(out, previous) && !edits(step)) {
                for (int drop = out.size() - 1; drop > previous; drop--) {
                    seen.remove(out.get(drop).pos());
                    out.remove(drop);
                }
                // Indices that pointed into the removed stretch are moved to the fold target = the remaining step
                for (int old = 0; old < i; old++) {
                    newIndex[old] = Math.min(newIndex[old], previous);
                }
                newIndex[i] = previous;
                continue;
            }
            out.add(step);
            seen.put(step.pos(), out.size() - 1);
            newIndex[i] = out.size() - 1;
        }
        return new Folded(out, newIndex);
    }

    /** Whether there's no digging or placement after {@code from}. */
    private static boolean foldable(List<PathStep> out, int from) {
        for (int i = from + 1; i < out.size(); i++) {
            if (edits(out.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean edits(PathStep step) {
        return step.digging() || step.bridging();
    }

    /**
     * A loop where an extended leg came back near the existing path. The loop runs from {@code route[entry]} to {@code tail[rejoin]},
     * and reconnecting the two ends makes the whole loop unnecessary.
     *
     * @param gap number of steps in the loop along the path
     */
    public record Return(int entry, int rejoin, int gap) {
    }

    /**
     * Among the places where the extension leg {@code tail} comes back near path {@code route} at or after {@code fromIndex}, the pair with the biggest detour along the path.
     *
     * <p>When the exit switches at a dead end beyond the window's edge, the extension turns back from its end. The returning line runs a few to a dozen-odd blocks
     * apart from the incoming line (the V shapes in the in-game End and Nether), so loops back to the same cell ({@link #fold}) alone don't catch it. The farther apart they are, the longer
     * the normal walking length between the two ends, so to count as a loop a detour longer in proportion to the separation is required.
     *
     * @param nearBlocks horizontal distance considered near (per axis)
     * @param nearY      height difference considered near
     * @param minGap     minimum number of steps along the path to count as a loop
     * @param gapPerBlock steps along the path required per block of separation between the two ends
     */
    public static @Nullable Return widestReturn(List<PathStep> route, List<PathStep> tail, int fromIndex,
                                                int nearBlocks, int nearY, int minGap, int gapPerBlock) {
        Return best = null;
        for (int j = 0; j < tail.size(); j++) {
            BlockPos at = tail.get(j).pos();
            for (int k = Math.max(0, fromIndex); k < route.size(); k++) {
                int gap = route.size() - k + j;
                if (gap < minGap || best != null && gap <= best.gap()) {
                    break;
                }
                BlockPos p = route.get(k).pos();
                int apart = Math.max(Math.abs(p.getX() - at.getX()), Math.abs(p.getZ() - at.getZ()));
                if (apart <= nearBlocks && Math.abs(p.getY() - at.getY()) <= nearY
                        && gap >= Math.max(minGap, gapPerBlock * apart)) {
                    best = new Return(k, j, gap);
                    break;
                }
            }
        }
        return best;
    }

    /**
     * Whether removing {@code steps[from..to]} makes the moves after {@code to} lose their footing or passage.
     *
     * <p>They lose it if any move stands on a block placed in the removed stretch, places the next block against it, or passes through a hole dug there.
     * If none do, it may be reconnected even if the stretch has digging or placement.
     */
    public static boolean laterStepsDependOn(List<PathStep> steps, int from, int to) {
        Set<BlockPos> placed = new HashSet<>();
        Set<BlockPos> dug = new HashSet<>();
        for (int i = from; i <= to; i++) {
            PathStep step = steps.get(i);
            if (step.placedBlockPos() != null) {
                placed.add(step.placedBlockPos());
            }
            dug.addAll(step.digCells());
        }
        if (placed.isEmpty() && dug.isEmpty()) {
            return false;
        }
        for (int i = to + 1; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            if (placed.contains(step.pos().below())) {
                return true;
            }
            BlockPos place = step.placedBlockPos();
            if (place != null) {
                for (Direction side : Direction.values()) {
                    if (placed.contains(place.relative(side))) {
                        return true;
                    }
                }
            }
            for (BlockPos cell : step.bodyCells()) {
                if (dug.contains(cell)) {
                    return true;
                }
            }
            if (dug.contains(step.pos()) || dug.contains(step.pos().above())) {
                return true;
            }
        }
        return false;
    }
}
