package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.Mount;

/**
 * Moves while riding an animal ({@link Mount}): running level, stepping up as high as the animal steps, dropping down
 * safely, and getting off.
 *
 * <p>Deliberately a small move set. A ridden animal can't dig, place blocks, climb ladders or swim with a rider, and
 * its charged jump is too unreliable to plan on, so anything beyond running and stepping is left to the player on foot:
 * {@link MoveKind#DISMOUNT} switches to the walking state at the current cell, and from there every walking move is
 * available again. There is no getting back on; the animal stays where it was left.
 *
 * <p>The animal's whole footprint must be clear, so routes keep it out of one-wide gaps and low tunnels it doesn't fit,
 * and the rider's column must be clear above it so they don't suffocate under a ceiling.
 */
final class MountMoves {

    /** Ticks to get off. Also keeps a route from dismounting for a few blocks of walking it could have ridden. */
    static final double DISMOUNT_TICKS = 10.0;

    /** Highest drop taken while riding (blocks). Same as the safe fall on foot; the animal takes damage beyond it. */
    static final int MAX_SAFE_DROP = 3;

    /** Extra ticks for a step up: the animal slows down for a moment as it climbs. */
    private static final double STEP_UP_TICKS = 1.0;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final AStarPathfinder owner;

    MountMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    void expand(PathNode from, Mount mount) {
        for (int i = 0; i < DX.length; i++) {
            addRide(from, mount, DX[i], DZ[i]);
        }
        owner.relax(from, from.x, from.y, from.z, DISMOUNT_TICKS, MoveKind.DISMOUNT);
    }

    private void addRide(PathNode from, Mount mount, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        boolean diagonal = dx != 0 && dz != 0;
        // Cutting a corner sweeps the body over both cells beside the corner
        if (diagonal && !(bodyClear(mount, from.x + dx, from.y, from.z) && bodyClear(mount, from.x, from.y, from.z + dz))) {
            return;
        }
        double distance = diagonal ? Math.sqrt(2.0) : 1.0;
        double run = mount.ticksPerBlock() * distance;

        if (fits(mount, x, from.y, z)) {
            owner.relaxMounted(from, x, from.y, z, run / speedFactor(x, from.y, z), MoveKind.RIDE);
            return;
        }
        for (int up = 1; up <= mount.stepHeight(); up++) {
            // The rider rises with the step before moving over it
            if (!riderClear(from.x, from.y + mount.riderHeight() + up - 1, from.z)) {
                break;
            }
            if (fits(mount, x, from.y + up, z)) {
                owner.relaxMounted(from, x, from.y + up, z,
                        run / speedFactor(x, from.y + up, z) + STEP_UP_TICKS * up, MoveKind.RIDE_ASCEND);
                return;
            }
        }
        if (!bodyClear(mount, x, from.y, z)) {
            return;
        }
        // Nothing to stand on at the same height: the animal walks off the edge and lands below
        for (int drop = 1; drop <= MAX_SAFE_DROP; drop++) {
            int y = from.y - drop;
            if (!bodyClear(mount, x, y, z)) {
                return;
            }
            if (fits(mount, x, y, z)) {
                owner.relaxMounted(from, x, y, z, run / speedFactor(x, y, z) + drop, MoveKind.RIDE_DESCEND);
                return;
            }
        }
    }

    /** Whether the animal can stand with its feet at {@code (x, y, z)}: firm ground, room for its body and the rider. */
    boolean fits(Mount mount, int x, int y, int z) {
        long floor = owner.view.cell(x, y - 1, z);
        if (!CellData.standable(floor) || CellData.hazard(floor) || CellData.sneakRequired(floor)) {
            return false;
        }
        if (!bodyClear(mount, x, y, z)) {
            return false;
        }
        for (int h = mount.bodyHeight(); h < mount.riderHeight(); h++) {
            if (!riderClear(x, y + h, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the animal's footprint is empty from its feet up to the top of its body.
     *
     * <p>The edge of the footprint (everything but the centre column) may touch something up to the animal's step
     * height: the body only overhangs its cell by a fraction of a block, and where that edge meets a low obstacle the
     * animal either walks up onto it or slides past it off-centre. Requiring it clear at foot level too would forbid
     * every step up (the cell before a step always overhangs the step) and every ride along a low wall.
     */
    private boolean bodyClear(Mount mount, int x, int y, int z) {
        int r = mount.footprintRadius();
        int edgeFrom = Math.min(mount.stepHeight(), mount.bodyHeight() - 1);
        for (int ox = -r; ox <= r; ox++) {
            for (int oz = -r; oz <= r; oz++) {
                boolean centre = ox == 0 && oz == 0;
                for (int h = centre ? 0 : edgeFrom; h < mount.bodyHeight(); h++) {
                    if (!empty(owner.view.cell(x + ox, y + h, z + oz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean riderClear(int x, int y, int z) {
        return empty(owner.view.cell(x, y, z));
    }

    /** Air-like and harmless. Water counts as blocked: a horse in deep water throws its rider off. */
    private static boolean empty(long cell) {
        return CellData.present(cell) && CellData.passableEmpty(cell) && !CellData.water(cell)
                && !CellData.lava(cell) && !CellData.hazard(cell) && !CellData.cobweb(cell);
    }

    /** Soul sand and the like slow the animal just like the player ({@code Entity#getBlockSpeedFactor}). */
    private double speedFactor(int x, int y, int z) {
        double factor = CellData.speedFactor(owner.view.cell(x, y, z));
        if (factor == 1.0) {
            factor = CellData.speedFactor(owner.view.cell(x, y - 1, z));
        }
        return factor;
    }
}
