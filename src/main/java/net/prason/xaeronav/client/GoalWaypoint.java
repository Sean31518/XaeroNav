package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.xaero.XaeroWaypoints;

/**
 * Shows the destination as a waypoint on Xaero's minimap. While that works, {@link MapPathOverlay}'s own pin is
 * hidden, since it would just stack two markers on the same spot.
 *
 * <p>This class doesn't reference {@code xaero.*}. References are confined to {@link XaeroWaypoints}; this class
 * only looks at "is it installed", "when to place it", and "give up if it's broken".
 *
 * <p><b>Catching {@link LinkageError} is the key point.</b> The map rendering mixins are required=false and silently
 * disable themselves on versions where the injection target changed, but this calls Xaero's classes directly. On a
 * Xaero version that changed types or arguments, a {@link NoSuchMethodError} or similar is thrown the moment it's
 * called, and left alone it takes the whole game down. Losing one integration and crashing the game are very
 * different levels of damage, so only here is it caught and the feature turned off (after one failure, it's never called again).
 */
final class GoalWaypoint {

    /** Whether a call failed because the Xaero version didn't match. After one failure, it's never touched again. */
    private static boolean unavailable;

    /** The destination a waypoint is currently placed for. {@code null} if none is placed. */
    private static volatile BlockPos placedAt;

    private GoalWaypoint() {
    }

    /** Whether the destination is being shown by a Xaero waypoint. Used to decide whether to show our own pin. */
    static boolean placed() {
        return placedAt != null;
    }

    /**
     * Re-places the waypoint to match the current destination. Does nothing if the destination hasn't changed.
     *
     * <p>Call every tick. Toggled settings and re-entering the world are also caught up here; if only the place that
     * sets it ({@link PathfindingState#setGoal}) took care of this, the waypoint would remain until the destination
     * is reached even after the setting is turned off.
     */
    static void sync(BlockPos goal) {
        BlockPos wanted = goal != null && XaeroNavConfig.INSTANCE.goalMarkerEnabled()
                && !unavailable && XaeroPresence.minimapPresent()
                ? goal : null;
        BlockPos current = placedAt;
        if (wanted == null ? current == null : wanted.equals(current)) {
            return;
        }
        try {
            if (wanted == null) {
                XaeroWaypoints.clearDestination();
                placedAt = null;
            } else {
                placedAt = XaeroWaypoints.setDestination(wanted,
                        TextCompat.translatable("gui.xaeronav.destination_waypoint").getString()) ? wanted : null;
            }
        } catch (LinkageError incompatible) {
            unavailable = true;
            placedAt = null;
            XaeroNav.LOGGER.warn("XaeroNav: Can't place the destination as a Xaero waypoint, disabling this integration"
                    + " (the Xaero version may be outside the supported range)", incompatible);
        }
    }
}
