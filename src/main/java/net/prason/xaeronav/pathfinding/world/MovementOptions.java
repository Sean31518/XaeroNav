package net.prason.xaeronav.pathfinding.world;

import net.prason.xaeronav.pathfinding.cost.RouteProfile;

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
 * @param routeProfile what the route is optimised for (multipliers on the risk / placement / dig surcharges).
 *                     {@link RouteProfile#SAFEST} also forces {@code avoidRiskyJumps} on and
 *                     {@code fallDamageToleranceEnabled} off; that is done here, in the constructor, so every reader
 *                     (including the relaxation ladder) sees the effective values
 * @param swimmingEnabled whether routes may enter water to swim or wade. When off, a player already in water can still
 *                        swim out, and the exact destination may still be a water cell
 * @param boatsEnabled whether routes may launch a boat. When off, a boat in the inventory is ignored
 */
public record MovementOptions(boolean diggingEnabled, boolean bridgingEnabled, boolean jumpGapEnabled,
                               boolean lavaBridgingEnabled, int maxBridgeRunBlocks, int maxLavaBridgeRunBlocks,
                               int maxVoidBridgeRunBlocks, int maxSubmergedTicks,
                               boolean fallDamageToleranceEnabled, boolean avoidRiskyJumps,
                               boolean blockBudgetEnabled, int blockBudgetReserve, boolean strictLimits,
                               RouteProfile routeProfile, boolean swimmingEnabled, boolean boatsEnabled) {

    /**
     * Allows none of digging, placing, jumping or dangerous drops. Used when you only want to see whether terrain
     * "can be passed right now without modifying it" (finding footing for the destination or intermediate waypoints).
     * Swimming and boats stay as they always were for these callers (allowed, the boat still subject to the inventory),
     * since neither modifies the terrain.
     */
    public static final MovementOptions NONE =
            new MovementOptions(false, false, false, false, 0, 0, 0, 0, false, true, false, 0, true,
                    RouteProfile.BALANCED, true, true);

    public MovementOptions {
        if (routeProfile.forcesSafeLimits()) {
            avoidRiskyJumps = true;
            fallDamageToleranceEnabled = false;
        }
    }

    /**
     * Whether boat moves may be offered, given whether the player carries (or rides) a boat. The single place where
     * {@link #boatsEnabled} is applied to the inventory check, so {@link ChunkView#capture} and the tests agree.
     */
    public boolean boatUsable(boolean carriedOrRiding) {
        return boatsEnabled && carriedOrRiding;
    }

    /**
     * What auto-walk can follow on its own: no swimming, no placing blocks (bridges, pillars, lava), no gap jumps and no
     * painful falls. Digging and boats stay as configured; auto-walk hands those steps back to the player.
     */
    public MovementOptions forAutoWalk() {
        return new MovementOptions(diggingEnabled, false, false, false,
                maxBridgeRunBlocks, maxLavaBridgeRunBlocks, maxVoidBridgeRunBlocks, maxSubmergedTicks,
                false, true, blockBudgetEnabled, blockBudgetReserve, strictLimits,
                routeProfile, false, boatsEnabled);
    }

    public MovementOptions withoutDigging() {
        return new MovementOptions(false, bridgingEnabled, jumpGapEnabled, lavaBridgingEnabled,
                maxBridgeRunBlocks, maxLavaBridgeRunBlocks, maxVoidBridgeRunBlocks, maxSubmergedTicks,
                fallDamageToleranceEnabled, avoidRiskyJumps, blockBudgetEnabled, blockBudgetReserve, strictLimits,
                routeProfile, swimmingEnabled, boatsEnabled);
    }
}
