package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * Move-candidate generation in and on water (swimming, surfacing, diving, boats). Part of the {@link AStarPathfinder} split;
 * see the {@link GroundMoves} class Javadoc.
 */
final class WaterMoves {

    private final AStarPathfinder owner;

    WaterMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    /**
     * Swims forward underwater. Unlike {@link GroundMoves#addTraverse}, it doesn't require a foothold; without it
     * the ocean could only be crossed by "descending to the seabed and walking" or "placing blocks on the surface to cross".
     */
    void addSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // If there is a foothold, the Traverse side produces the same move. Don't produce it twice under two MoveKinds
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK, MoveKind.SWIM);
    }

    /**
     * Swims diagonally underwater. {@link GroundMoves#addDiagonalTraverse} requires a foothold so it doesn't apply underwater,
     * and without this, swimming alone is restricted to the 4 cardinal directions: moving diagonally costs 2 moves (1.41x the actual cost),
     * so routes across the ocean are estimated as more expensive than they really are, and the number of expanded nodes grows.
     *
     * <p>The two corner cells must be passable for the same reason as in {@link GroundMoves#addDiagonalTraverse}
     * (so the body doesn't slip through a wall's corner).
     */
    void addDiagonalSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // If there is a foothold, the DiagonalTraverse side produces the same move. Don't produce it twice under two MoveKinds
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE, MoveKind.SWIM);
    }

    /** Rises underwater. Needed to build routes that go up to the surface and then swim horizontally. */
    void addSwimUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.water(owner.view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(from.x, y + 1, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.SWIM_UP_ONE_BLOCK, MoveKind.SWIM_UP);
    }

    /**
     * Rises one block while moving forward underwater. {@link #addSwimUp} can only go straight up, so without this
     * surfacing becomes an L shape of "go up in place, then sideways"; a swimming person rises diagonally while
     * facing the destination, so that would also look unnatural as guidance.
     *
     * <p>Like {@link GroundMoves#addAscend} on land, requires the cell above the takeoff point (= the cell the body
     * passes through on the way up) to be passable. Digging is not allowed (rather than digging upward underwater, swimming to an
     * open spot is faster).
     */
    void addSwimAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.SWIM_ASCEND_ONE_BLOCK, MoveKind.SWIM_ASCEND);
    }

    /**
     * Rises one block while moving diagonally. If {@link #addSwimAscend} existed only in the 4 cardinal directions,
     * just the stretch heading to the surface would be split into "go straight, then up" or "go up, then diagonally",
     * and the path would bend at a right angle there.
     *
     * <p>The two corner cells must be passable for the same reason as in {@link #addDiagonalSwim} (so the body doesn't
     * slip through a wall's corner).
     */
    void addDiagonalSwimAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // If there is a foothold, the DiagonalAscend side produces the same move. Don't produce it twice under two MoveKinds
            return;
        }
        if (!CellData.water(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.DIAGONAL_SWIM_ASCEND_ONE_BLOCK, MoveKind.SWIM_ASCEND);
    }

    /** Dives underwater. Used when following the seabed terrain is shorter. */
    void addSwimDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.water(owner.view.cell(from.x, y, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.SWIM_DOWN_ONE_BLOCK, MoveKind.SWIM_DOWN);
    }

    /**
     * Moves across the water surface by boat. Per block it's less than half of swimming. Only produced from the riding state,
     * so the cost of boarding ({@link ActionCosts#BOAT_LAUNCH_TICKS}) is always paid first in {@link #addBoatEnter}.
     *
     * <p>Moves getting off the water surface are handled by the existing Traverse/Ascend as-is, and the cost of breaking and picking up the boat
     * ({@link ActionCosts#BOAT_STOW_TICKS}) is added to the disembarking move by {@code AStarPathfinder#relax}.
     */
    void addBoatPaddle(PathNode from, int dx, int dz, boolean diagonal) {
        if (!from.boating) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        if (!owner.boatFits(x, from.y, z)) {
            return;
        }
        if (diagonal && (!owner.clearWithoutDigging(x, from.y, from.z)
                || !owner.clearWithoutDigging(from.x, from.y, z))) {
            return;
        }
        double cost = ActionCosts.PADDLE_ONE_BLOCK * (diagonal ? ActionCosts.DIAGONAL_DISTANCE : 1.0);
        owner.relaxBoating(from, x, from.y, z, cost, MoveKind.BOAT_PADDLE);
    }

    /**
     * Places a boat and boards it. Boarding is paid here and breaking/picking up on disembarking, so on short waterways
     * swimming across stays cheaper (see {@link ActionCosts#BOAT_STOW_TICKS} for the break-even point).
     *
     * <p>Covers both launching from the shore and placing one mid-swim. The water surface is usually one block lower
     * than the shore, so both the same height and one below are tried.
     */
    void addBoatEnter(PathNode from, int dx, int dz) {
        if (!owner.view.boatAvailable() || from.boating) {
            return;
        }
        // Standing on the shore or floating on the surface. A boat can't be placed while submerged
        boolean onShore = CellData.standable(owner.view.cell(from.x, from.y - 1, from.z))
                && !CellData.water(owner.view.cell(from.x, from.y, from.z));
        if (!onShore && !owner.isBoatSurface(from.x, from.y, from.z)) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        for (int y = from.y; y >= from.y - 1; y--) {
            if (owner.boatFits(x, y, z)) {
                owner.relaxBoating(from, x, y, z,
                        ActionCosts.PADDLE_ONE_BLOCK + ActionCosts.BOAT_LAUNCH_TICKS
                                + ActionCosts.MODE_SWITCH_PENALTY_TICKS, MoveKind.BOAT_ENTER);
                return;
            }
        }
    }
}
