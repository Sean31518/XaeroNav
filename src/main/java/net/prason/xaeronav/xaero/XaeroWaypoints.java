package net.prason.xaeronav.xaero;

import net.minecraft.core.BlockPos;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Places the destination on Xaero's minimap as a <b>temporary waypoint</b>.
 *
 * <p>We put it on a Xaero waypoint instead of drawing our own pin on the map because this rendering includes
 * things only possible inside Xaero: it cancels the minimap's rotation so it always stands upright, destinations
 * off screen are pinned to the edge with the distance, and the same marker also appears in the world. None of this
 * can be done from the position where we draw into the FBO (before rotation is applied).
 *
 * <p>Being a <b>temporary</b> waypoint is the key point. It is not saved to disk, so even if we forget to remove it,
 * it does not survive to the next launch. We still remove it explicitly with {@link #clearDestination()} because it
 * keeps showing in the waypoint list during the session.
 *
 * <p>Check {@link XaeroPresence#minimapPresent()} before calling. Without the minimap installed, loading
 * this class itself fails.
 */
public final class XaeroWaypoints {

    /** The text shown on the waypoint icon. The same as Xaero's own temporary waypoints. */
    private static final String SYMBOL = "X";

    /** The currently placed waypoint and the set it belongs to. Removing it requires looking up the same set. */
    private static Waypoint placed;
    private static WaypointSet placedIn;

    private XaeroWaypoints() {
    }

    /** Re-places the destination waypoint. {@code true} if it was placed. */
    public static boolean setDestination(BlockPos goal, String name) {
        clearDestination();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            return false;
        }
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) {
            return false;
        }
        WaypointSet set = world.getCurrentWaypointSet();
        if (set == null) {
            return false;
        }
        Waypoint waypoint = new Waypoint(goal.getX(), goal.getY(), goal.getZ(), name, SYMBOL,
                WaypointColor.BLUE, WaypointPurpose.NORMAL);
        waypoint.setTemporary(true);
        set.add(waypoint);
        placed = waypoint;
        placedIn = set;
        return true;
    }

    /** Removes the placed waypoint. Does nothing if none was placed. */
    public static void clearDestination() {
        if (placed == null) {
            return;
        }
        placedIn.remove(placed);
        placed = null;
        placedIn = null;
    }
}
