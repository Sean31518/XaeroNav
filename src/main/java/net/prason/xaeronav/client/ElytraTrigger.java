package net.prason.xaeronav.client;

/**
 * Decides whether elytra gliding counts as "flight mode". Handles only the hysteresis that prevents flip-flopping at the boundary,
 * and doesn't touch {@code Minecraft}, so it can be tested on its own.
 *
 * <p>There are two kinds of hysteresis.
 *
 * <h4>Time: a momentary glide doesn't enter flight mode</h4>
 *
 * <p>Jumping repeatedly while wearing an elytra makes vanilla set {@code isFallFlying} for just a few ticks.
 * Switching to flight mode advances {@code generation} and discards the running search, and on landing the displayed
 * route is cleared and redrawn, so the whole route was rebuilt on every hop. <b>Requiring
 * {@link #SUSTAIN_TICKS} of continuity to enter</b> means glide flags from mere hops never reach
 * flight mode. Real gliding lasts far more than 0.5 seconds, so it isn't missed.
 *
 * <h4>Height: lower threshold for exiting</h4>
 *
 * <p>Using the same height for entering and exiting flips between flying and walking the whole time you glide just above the boundary.
 */
final class ElytraTrigger {

    /**
     * Flight mode is entered only after the glide flag has lasted this long (ticks). 0.5 seconds; the glide flag raised by a hop
     * is much shorter than this.
     */
    static final int SUSTAIN_TICKS = 10;

    private boolean gliding;
    private int fallFlyingTicks;

    /**
     * Updates and returns whether flight mode is active from this tick's state.
     *
     * @param fallFlying        whether gliding with an elytra ({@code Player#isFallFlying})
     * @param groundClearance   height (blocks) from the feet straight down to the ground
     * @param requiredClearance height required to enter. 0 or less means any height
     */
    boolean update(boolean fallFlying, int groundClearance, int requiredClearance) {
        if (!fallFlying) {
            gliding = false;
            fallFlyingTicks = 0;
            return false;
        }
        fallFlyingTicks++;
        if (requiredClearance <= 0) {
            gliding = fallFlyingTicks >= SUSTAIN_TICKS;
            return gliding;
        }
        if (gliding) {
            gliding = groundClearance >= Math.max(1, requiredClearance / 2);
            return gliding;
        }
        gliding = fallFlyingTicks >= SUSTAIN_TICKS && groundClearance >= requiredClearance;
        return gliding;
    }

    void reset() {
        gliding = false;
        fallFlyingTicks = 0;
    }
}
