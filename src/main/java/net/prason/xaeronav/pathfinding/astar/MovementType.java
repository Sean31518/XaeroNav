package net.prason.xaeronav.pathfinding.astar;

/**
 * Diagonal moves at the same height are treated as TRAVERSE ({@code AStarPathfinder#addDiagonalTraverse}).
 * Fall/Pillar are deferred.
 */
public enum MovementType {
    TRAVERSE,
    ASCEND,
    DESCEND,
    /** Moving in water (swimming at the surface, diving, walking on the bottom). Unlike the others, works without footing. */
    SWIM,
    /** A leg crossing the water surface by boat. Needs neither footing nor swimming, but the effort of placing, boarding and leaving is charged at the entrance. */
    BOAT,
    /** Climbing ladders and vines. Like water, requires no footing. */
    CLIMB,
    /** A leg jumping over a 1-block gap ({@code AStarPathfinder#addJumpGap}). Unlike the others, involves neither digging nor placing. */
    JUMP,
    /** A leg descending while taking fall damage (only when allowed in the config). Actually costs health. */
    FALL_DAMAGE,
    /** A leg that places a water bucket just before landing to cancel fall damage. Requires timing. */
    FALL_MLG,
    /** Riding an animal ({@code MountMoves}). */
    RIDE,
    /** Getting off the animal; from here on the route is walked. */
    DISMOUNT
}
