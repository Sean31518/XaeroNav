package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Caps on "how many blocks / ticks something dangerous may continue". In each, 0 means unlimited.
 *
 * <p>Groups the values used to cut off <b>move generation itself</b> rather than weighting the cost. With weights,
 * A* expands cheap edges first, so it exhausts the surroundings before reaching for the dangerous path and burns
 * through expanded nodes (the measurement recorded in {@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}).
 *
 * @param maxLavaBridgeRunBlocks a stricter cap applied only to bridges over lava. The bridge run length itself is shared with
 *                               {@code maxBridgeRunBlocks}, so the one that actually applies is the smaller of the two
 * @param maxVoidBridgeRunBlocks a cap applied only to bridges over bottomless void (the End's void, huge caverns deeper than
 *                               the search range). Treated the same as {@code maxLavaBridgeRunBlocks}, and separated because
 *                               the outcome of a miss, not surviving, is the same
 */
public record RunCaps(int maxBridgeRunBlocks, int maxLavaBridgeRunBlocks, int maxVoidBridgeRunBlocks,
                       int maxSubmergedTicks) {

    /**
     * No caps. Used for the dead-end fallback retry when the caps leave no path at all within the search range
     * (the priority being "long bridges and drowning risk are last resorts, but better than a dead end").
     */
    public static final RunCaps NONE = new RunCaps(0, 0, 0, 0);

    public static RunCaps of(CellSource view) {
        return new RunCaps(view.maxBridgeRunBlocks(), view.maxLavaBridgeRunBlocks(),
                view.maxVoidBridgeRunBlocks(), view.maxSubmergedTicks());
    }

    /** The cap that actually applies over lava. The bridge length cap also applies, so the stricter one wins. */
    public int effectiveLavaBridgeRun() {
        return stricter(maxBridgeRunBlocks, maxLavaBridgeRunBlocks);
    }

    /** The cap that actually applies over the void. The stricter one wins, for the same reason as {@link #effectiveLavaBridgeRun()}. */
    public int effectiveVoidBridgeRun() {
        return stricter(maxBridgeRunBlocks, maxVoidBridgeRunBlocks);
    }

    /** 0 means unlimited, so a plain {@code min} can't pick the stricter one. */
    public static int stricter(int cap, int other) {
        if (cap == 0 || other == 0) {
            return Math.max(cap, other);
        }
        return Math.min(cap, other);
    }
}
