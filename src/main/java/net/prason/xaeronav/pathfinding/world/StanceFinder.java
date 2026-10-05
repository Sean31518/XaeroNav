package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * Moves the search's start and end to "a place you can actually stand".
 *
 * <p>Every move A* creates ends at "a cell with footing, water, or a ladder", so passing other coordinates as the start or end
 * means either no path extends at all or the search is cut off as unreachable. Yet both the start and the end
 * are values coming from outside the search, and it's not unusual for them not to hold as-is:
 *
 * <ul>
 *   <li>Start: falling, inside a cobweb, on a minecart. We still want guidance to continue without footing underfoot</li>
 *   <li>End: the coordinates clicked on the map, or a waypoint's Y. It may point underground or into the air</li>
 * </ul>
 */
public final class StanceFinder {

    /** Range to search up and down the same column (blocks). Kept around the search range's vertical margin. */
    private static final int VERTICAL_SEARCH = 32;

    private StanceFinder() {
    }

    /** Whether the player can stand there (= it can be the end of an A* move). */
    public static boolean isStance(CellSource view, int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (!CellData.occupiableWithoutDigging(feet)
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return false;
        }
        return CellData.standable(view.cell(x, y - 1, z))
                || CellData.water(feet)
                || CellData.climbable(feet);
    }

    /**
     * The search start. If there's no footing, lowers it to the landing point directly below. This is so that even while falling
     * or riding a minecart, the path starts from "where you'll stand next".
     */
    public static BlockPos resolveStart(CellSource view, BlockPos start) {
        int x = start.getX();
        int z = start.getZ();
        if (isStance(view, x, start.getY(), z)) {
            return start;
        }
        for (int dy = 1; dy <= VERTICAL_SEARCH; dy++) {
            if (isStance(view, x, start.getY() - dy, z)) {
                return new BlockPos(x, start.getY() - dy, z);
            }
        }
        // Only when the feet are buried in a block (inside a slab, or sunk into the ground) look one block up
        if (isStance(view, x, start.getY() + 1, z)) {
            return start.above();
        }
        return start;
    }

    /**
     * The search end. If the coordinates are fundamentally unreachable, moves them to the nearest reachable spot in the same column.
     *
     * <p>A map or waypoint points at "that place", not "that block". Treating a goal whose Y alone
     * is off as unreachable would leave no path even when you're right in front of it.
     *
     * <p>{@link #isReachable} rather than {@link #isStance} decides whether to move it, in order to
     * distinguish an underground goal reachable by digging (where producing a tunnel to it is correct) from an aerial goal
     * with no footing where you can't stand no matter what.
     */
    public static BlockPos resolveGoal(CellSource view, BlockPos goal) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (isReachable(view, x, y, z)) {
            return goal;
        }
        for (int dy = 1; dy <= VERTICAL_SEARCH; dy++) {
            if (isReachable(view, x, y - dy, z)) {
                return new BlockPos(x, y - dy, z);
            }
            if (isReachable(view, x, y + dy, z)) {
                return new BlockPos(x, y + dy, z);
            }
        }
        return goal;
    }

    /** Whether a move arriving there can be made. Body cells can be dug out, so they may be blocked. */
    private static boolean isReachable(CellSource view, int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (!occupiableOrDiggable(feet) || !occupiableOrDiggable(view.cell(x, y + 1, z))) {
            return false;
        }
        // Only the footing can't be made by digging
        return CellData.standable(view.cell(x, y - 1, z))
                || CellData.water(feet)
                || CellData.climbable(feet);
    }

    private static boolean occupiableOrDiggable(long cell) {
        return CellData.occupiableWithoutDigging(cell)
                || CellData.present(cell) && !Double.isInfinite(CellData.digTicks(cell));
    }
}
