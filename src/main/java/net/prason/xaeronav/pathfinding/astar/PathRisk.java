package net.prason.xaeronav.pathfinding.astar;

/** The cost calculation is only an advance estimate, so this is the result of rechecking safety right before presenting. */
public enum PathRisk {
    NONE,
    LAVA_ADJACENT,
    WATER_INFLOW,
    /**
     * A section where a misstep is fatal. <b>This covers not only bottomless (void) cases but also ones with a floor where the
     * drop is lethal</b>: the warning criterion is not "is there something below" but "would a miss kill you".
     */
    VOID_BELOW,
    /** A section of continued submersion with no chance to breathe. Air runs out and you drown ({@code PathSafetyChecker#drowningRuns}). */
    DROWNING,
    /** A section that deals fall damage on landing. Appears in paths only when allowed in the config. */
    FALL_DAMAGE,
    /** A section that deals fall damage unless a water bucket is placed just before landing. */
    MLG_REQUIRED,
    /**
     * A section over magma blocks. You can cross unharmed while sneaking (vanilla's
     * {@code isSteppingCarefully}), but stepping on them while running burns you. Since they're treated as passable,
     * not conveying that condition leads to "I followed the guidance and got burned".
     */
    SNEAK_OVER_MAGMA
}
