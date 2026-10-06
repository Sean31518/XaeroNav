package net.prason.xaeronav.client;

import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Route color rules used by both in-world rendering and Xaero map rendering.
 *
 * <p>Colors are returned as shared constant arrays. In-world rendering, the world map, and the minimap each call
 * {@link #forStep} for every step every frame, so creating arrays here would produce thousands of pieces of
 * garbage per frame. Do not modify the returned arrays.
 */
final class PathColors {

    /** Straight line bridging a stretch whose route is unknown. Desaturated so it is not mistaken for a walkable route. */
    static final float[] STRAIGHT = {0.85f, 0.85f, 0.9f};
    /** Air route while gliding. A sky-like color so it is not mistaken for a ground route (green) or the dotted line (grayish white). */
    static final float[] FLIGHT = {0.35f, 0.85f, 1.0f};
    /** The coarse intermediate targets of a long-distance route. A different hue to tell it apart from the straight line to the goal ({@link #STRAIGHT}). */
    static final float[] COARSE_ROUTE = {0.95f, 0.75f, 0.2f};
    /** Pillar of light marking the landing point while gliding under open sky. Near white so it rarely clashes with Xaero waypoint colors. */
    static final float[] SKY_PILLAR = {1.0f, 0.95f, 0.7f};
    static final float[] BRIDGE = {0.4f, 0.9f, 0.9f};
    static final float[] LAVA_ADJACENT = {1.0f, 0.1f, 0.1f};
    static final float[] VOID_BELOW = {0.8f, 0.1f, 0.8f};
    static final float[] WATER_INFLOW = {0.1f, 0.7f, 1.0f};
    /**
     * An underwater stretch with no chance to breathe. <b>Making it a warm color is the point</b>: this line is seen
     * from under water, where the background and fog are blue. It used to be dark blue to distinguish it from
     * {@link #SWIM}, but that is the color that gets lost most under water (all the more since occluded drawing drops
     * to 0.3 opacity). It keeps some blue while leaning red, so it is not mistaken for the red of lava
     * ({@link #LAVA_ADJACENT}) or the orange of falls ({@link #FALL_DAMAGE}).
     */
    static final float[] DROWNING = {1.0f, 0.35f, 0.55f};
    /** A descent that costs health. On the warning side among danger colors (not as strong as the red of {@link #LAVA_ADJACENT}). */
    static final float[] FALL_DAMAGE = {1.0f, 0.35f, 0.0f};
    /** A descent that needs a water bucket just before landing. Same family as {@link #FALL_DAMAGE}, but a color that hints at using water. */
    static final float[] MLG_REQUIRED = {0.0f, 0.85f, 0.8f};
    /** Magma blocks crossed by sneaking. A warning color weaker than lava itself ({@link #LAVA_ADJACENT}). */
    static final float[] SNEAK_OVER_MAGMA = {1.0f, 0.5f, 0.25f};
    static final float[] DIGGING = {1.0f, 0.55f, 0.1f};
    static final float[] SWIM = {0.1f, 0.4f, 1.0f};
    /** A stretch crossed by boat. Movement on the water surface like swimming ({@link #SWIM}), so a similar hue, with green added to tell them apart. */
    static final float[] BOAT = {0.2f, 0.8f, 0.85f};
    static final float[] JUMP = {0.95f, 0.6f, 0.9f};
    static final float[] CLIMB = {0.7f, 0.5f, 1.0f};
    static final float[] ASCEND = {1.0f, 0.9f, 0.2f};
    static final float[] DESCEND = {0.3f, 0.6f, 1.0f};
    static final float[] WALK = {0.2f, 0.9f, 0.5f};
    /**
     * Destination pin placed on the map. Layers a red body, a white hole, and a dark outline.
     *
     * <p>Any single color is bound to vanish on some terrain: white on snowfields and deserts, dark in the Nether and
     * the deep ocean, red on lava and mushroom islands. With three layered colors, some combination always stands out.
     */
    static final float[] GOAL_MARKER = {0.95f, 0.15f, 0.15f};
    static final float[] GOAL_MARKER_HOLE = {1.0f, 1.0f, 1.0f};
    static final float[] GOAL_MARKER_OUTLINE = {0.05f, 0.05f, 0.07f};

    private PathColors() {
    }

    /** Identification that does not rely on color alone (A11Y-01). Lets code choosing line styles and symbols avoid reverse-mapping colors. */
    enum Kind { DANGER, WORK, MOVEMENT }

    /** Classifies with the same priority as {@link #forStep} (danger → work → movement). */
    static Kind kindFor(PathStep step) {
        if (step.risk() != PathRisk.NONE) {
            return Kind.DANGER;
        }
        if (step.bridging() || step.digging()) {
            return Kind.WORK;
        }
        return Kind.MOVEMENT;
    }

    /**
     * Checks danger → work (placing, digging) → movement type, in that order. The switches are exhaustive, so adding
     * a value to {@link PathRisk} or {@link MovementType} causes a compile error here
     * (back when it was an if-chain, newly added types were silently drawn in the {@link #WALK} color).
     */
    static float[] forStep(PathStep step) {
        float[] risk = switch (step.risk()) {
            case LAVA_ADJACENT -> LAVA_ADJACENT;
            case VOID_BELOW -> VOID_BELOW;
            case WATER_INFLOW -> WATER_INFLOW;
            case DROWNING -> DROWNING;
            case FALL_DAMAGE -> FALL_DAMAGE;
            case MLG_REQUIRED -> MLG_REQUIRED;
            case SNEAK_OVER_MAGMA -> SNEAK_OVER_MAGMA;
            case NONE -> null;
        };
        if (risk != null) {
            return risk;
        }
        if (step.bridging()) {
            return BRIDGE;
        }
        if (step.digging()) {
            return DIGGING;
        }
        return switch (step.movement()) {
            case SWIM -> SWIM;
            case BOAT -> BOAT;
            case JUMP -> JUMP;
            case CLIMB -> CLIMB;
            case ASCEND -> ASCEND;
            case DESCEND -> DESCEND;
            // Always caught earlier by the switch on risk, so execution never falls through to here
            case FALL_DAMAGE -> FALL_DAMAGE;
            case FALL_MLG -> MLG_REQUIRED;
            case TRAVERSE, RIDE, DISMOUNT -> WALK;
        };
    }
}
