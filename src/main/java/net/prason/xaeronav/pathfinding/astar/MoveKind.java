package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Kinds of movement inside the search. Whereas {@link MovementType} is a coarse classification for display, this one has
 * enough granularity to reconstruct, from the arrival node, "the cells the body passed through" and "the blocks placed".
 *
 * <p>During the search, nodes hold only this enum value; the {@link BlockPos} lists are generated only when assembling
 * the final path. Creating them during the search would produce throwaway lists for every expanded node.
 */
enum MoveKind {

    TRAVERSE(MovementType.TRAVERSE),
    DIAGONAL(MovementType.TRAVERSE),
    BRIDGE(MovementType.TRAVERSE),
    /** Place a block underfoot and climb straight up (Pillar). The vertical version of {@link #BRIDGE}. */
    PILLAR(MovementType.ASCEND),
    SWIM(MovementType.SWIM),
    SWIM_UP(MovementType.SWIM),
    SWIM_DOWN(MovementType.SWIM),
    SWIM_DESCEND(MovementType.SWIM),
    /** Rise one block while moving through water. The diagonal version of {@link #SWIM_UP}; without it, surfacing becomes L-shaped. */
    SWIM_ASCEND(MovementType.SWIM),
    /** Launch a boat from the shore, board it, and row out onto the water. Only this pays {@code BOAT_LAUNCH_TICKS}. */
    BOAT_ENTER(MovementType.BOAT),
    /** Travel across the water surface by boat. */
    BOAT_PADDLE(MovementType.BOAT),
    CLIMB(MovementType.CLIMB),
    CLIMB_UP(MovementType.CLIMB),
    CLIMB_DOWN(MovementType.CLIMB),
    ASCEND(MovementType.ASCEND),
    DESCEND(MovementType.DESCEND),
    /** Climb one step with one diagonal block (expanding the short-range repertoire). Shortens a two-move cardinal decomposition to one move. */
    DIAGONAL_ASCEND(MovementType.ASCEND),
    /** Descend one step with one diagonal block. Same aim as {@link #DIAGONAL_ASCEND}. */
    DIAGONAL_DESCEND(MovementType.DESCEND),
    FALL(MovementType.DESCEND),
    FALL_TO_WATER(MovementType.SWIM),
    /** A fall beyond the safe height. Health drops on landing. */
    FALL_DAMAGE(MovementType.FALL_DAMAGE),
    /** A fall beyond the safe height, made harmless by placing a water bucket just before landing. */
    FALL_MLG(MovementType.FALL_MLG),
    JUMP(MovementType.JUMP),
    RIDE(MovementType.RIDE),
    RIDE_ASCEND(MovementType.RIDE),
    RIDE_DESCEND(MovementType.RIDE),
    DISMOUNT(MovementType.DISMOUNT);

    private final MovementType movementType;

    MoveKind(MovementType movementType) {
        this.movementType = movementType;
    }

    MovementType movementType() {
        return movementType;
    }

    /** Cells the body passes through in this move (= that may need digging). Reconstructed from the two endpoints. */
    List<BlockPos> bodyCells(int fromX, int fromY, int fromZ, int x, int y, int z) {
        return switch (this) {
            // A one-step descent passes through the two cells before dropping and the one cell after
            case DESCEND, SWIM_DESCEND, DIAGONAL_DESCEND ->
                    List.of(new BlockPos(x, y + 1, z), new BlockPos(x, y + 2, z), new BlockPos(x, y, z));
            // While jumping, it also passes through the cell above the takeoff point
            case ASCEND, DIAGONAL_ASCEND -> List.of(new BlockPos(x, y, z), new BlockPos(x, y + 1, z),
                    new BlockPos(fromX, fromY + 2, fromZ));
            // A fall passes through the vertical column from the landing point up to above the takeoff point
            case FALL, FALL_TO_WATER, FALL_DAMAGE, FALL_MLG -> column(x, y, fromY + 1, z);
            // The body also passes through the gap being jumped. If it's blocked, the path doesn't hold
            case JUMP -> jumpCells(fromX, fromZ, x, y, z);
            default -> List.of(new BlockPos(x, y, z), new BlockPos(x, y + 1, z));
        };
    }

    /**
     * Two body cells each, from the cell after the takeoff point to the landing point. Jumps are cardinal-only, so
     * one of dx/dz is always 0, and the step count is the sum of the horizontal distances.
     */
    private static List<BlockPos> jumpCells(int fromX, int fromZ, int x, int y, int z) {
        int stepX = Integer.signum(x - fromX);
        int stepZ = Integer.signum(z - fromZ);
        int steps = Math.abs(x - fromX) + Math.abs(z - fromZ);
        List<BlockPos> cells = new ArrayList<>(steps * 2);
        for (int i = 1; i <= steps; i++) {
            cells.add(new BlockPos(fromX + stepX * i, y, fromZ + stepZ * i));
            cells.add(new BlockPos(fromX + stepX * i, y + 1, fromZ + stepZ * i));
        }
        return List.copyOf(cells);
    }

    private static List<BlockPos> column(int x, int bottomY, int topY, int z) {
        List<BlockPos> cells = new ArrayList<>(topY - bottomY + 1);
        for (int y = bottomY; y <= topY; y++) {
            cells.add(new BlockPos(x, y, z));
        }
        return List.copyOf(cells);
    }

    /**
     * Position where a block is placed for this move. {@code null} for other moves.
     * For Bridge it's the floor ahead, for Pillar the original footing used as a step; both are one below the arrival point.
     */
    BlockPos placedBlockPos(int x, int y, int z) {
        return this == BRIDGE || this == PILLAR ? new BlockPos(x, y - 1, z) : null;
    }
}
