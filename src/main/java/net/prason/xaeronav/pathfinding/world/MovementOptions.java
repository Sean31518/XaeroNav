package net.prason.xaeronav.pathfinding.world;

/**
 * The set of "what is allowed" brought into a search.
 *
 * <p>Bundled so that settings are read in exactly one place, the caller of {@link ChunkView#capture}. Adding each new
 * item as an argument to four call sites means that if you forget to fix one, <b>the types still match</b>
 * (with booleans and ints lined up, even the wrong order compiles).
 *
 * @param maxLavaBridgeRunBlocks length of bridge allowed over lava. Kept separately from {@code maxBridgeRunBlocks}:
 *                               a missed block over a gap just means a fall, but over lava it's instant death
 * @param maxVoidBridgeRunBlocks length of bridge allowed over bottomless void. Kept separate from lava because
 *                               in the End nearly every bridge falls under this
 * @param avoidRiskyJumps whether to avoid jumps over bottomless void or over fatal drops. Even when avoiding, the relaxation
 *                        ladder opens them up only when no route at all can be drawn (not with {@code strictLimits})
 * @param blockBudgetEnabled whether to use the number of blocks in the inventory as the cap on placements along the route. If off, the count is ignored
 * @param blockBudgetReserve number of blocks subtracted from the budget to keep on hand. Increase it to avoid routes that use them all up
 * @param strictLimits whether to keep the caps above even when no route at all can be drawn
 */
public record MovementOptions(boolean diggingEnabled, boolean bridgingEnabled, boolean jumpGapEnabled,
                               boolean lavaBridgingEnabled, int maxBridgeRunBlocks, int maxLavaBridgeRunBlocks,
                               int maxVoidBridgeRunBlocks, int maxSubmergedTicks,
                               boolean fallDamageToleranceEnabled, boolean avoidRiskyJumps,
                               boolean blockBudgetEnabled, int blockBudgetReserve, boolean strictLimits) {

    /**
     * Allows none of digging, placing, jumping or dangerous drops. Used when you only want to see whether terrain
     * "can be passed right now without modifying it" (finding footing for the destination or intermediate waypoints).
     */
    public static final MovementOptions NONE =
            new MovementOptions(false, false, false, false, 0, 0, 0, 0, false, true, false, 0, true);

    public MovementOptions withoutDigging() {
        return new MovementOptions(false, bridgingEnabled, jumpGapEnabled, lavaBridgingEnabled,
                maxBridgeRunBlocks, maxLavaBridgeRunBlocks, maxVoidBridgeRunBlocks, maxSubmergedTicks,
                fallDamageToleranceEnabled, avoidRiskyJumps, blockBudgetEnabled, blockBudgetReserve, strictLimits);
    }
}
