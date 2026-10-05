package net.prason.xaeronav.xaero;

import java.util.ArrayList;
import java.util.List;

import net.prason.xaeronav.platform.ModPresence;

/**
 * Checks whether the Xaero integration mixins actually applied, via the {@link XaeroHookMarker} attached to the target classes.
 *
 * <p>This class itself doesn't reference {@code xaero.*} (same reason as {@link XaeroPresence}). Targets are
 * referred to only by class name strings.
 */
public final class XaeroHooks {

    /** One target class = one feature from the user's point of view. */
    public enum Hook {
        WORLD_MAP("xaeroworldmap", "xaero.map.gui.GuiMap", "hud.xaeronav.hook_world_map"),
        WAYPOINT_MENU("xaeroworldmap", "xaero.map.mods.gui.WaypointReader", "hud.xaeronav.hook_waypoint_menu"),
        MINIMAP("xaerominimap", "xaero.common.minimap.render.MinimapFBORenderer", "hud.xaeronav.hook_minimap");

        private final String modId;
        private final String className;
        private final String nameKey;

        Hook(String modId, String className, String nameKey) {
            this.modId = modId;
            this.className = className;
            this.nameKey = nameKey;
        }

        public String modId() {
            return modId;
        }

        public String className() {
            return className;
        }

    /** Key of the text telling the user what has stopped working. */
        public String nameKey() {
            return nameKey;
        }
    }

    private XaeroHooks() {
    }

    /** Features whose mixin didn't apply even though the integrated mod is loaded. Empty if all applied. */
    public static List<Hook> missing() {
        List<Hook> missing = new ArrayList<>();
        for (Hook hook : Hook.values()) {
            if (ModPresence.isLoaded(hook.modId()) && !applied(hook.className())) {
                missing.add(hook);
            }
        }
        return List.copyOf(missing);
    }

    /** Whether that one integration actually applied. */
    public static boolean applied(Hook hook) {
        return applied(hook.className());
    }

    private static boolean applied(String className) {
        try {
            // initialize=false. Mixins are applied by the time the class is loaded, so just checking the marker
            // doesn't require running Xaero's static initialization
            Class<?> target = Class.forName(className, false, XaeroHooks.class.getClassLoader());
            return XaeroHookMarker.class.isAssignableFrom(target);
        } catch (ClassNotFoundException notFound) {
            // The target class itself was renamed. From the user's point of view the result is the same as "didn't apply",
            // so don't rethrow here; just include it in the report
            return false;
        }
    }
}
