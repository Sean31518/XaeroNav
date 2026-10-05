package net.prason.xaeronav.xaero;

import net.prason.xaeronav.platform.ModPresence;

/**
 * Whether Xaero's World Map is "loaded as a mod".
 *
 * <p>Don't judge by class presence ({@code Class.forName}). When the jar is only on the classpath, as in dev runs,
 * the classes are found but Xaero itself isn't initialized, and Xaero's classes are placed in a separate layer that
 * can't resolve Minecraft's classes. Touching them in that state crashes the whole game with
 * {@code NoClassDefFoundError: net/minecraft/client/Minecraft}.
 *
 * <p>This class itself doesn't reference {@code xaero.*}. If it did, in environments without Xaero just reading this
 * check would throw {@link NoClassDefFoundError}. Always go through here before calling {@link XaeroMapReader}.
 */
public final class XaeroPresence {

    private static final String WORLD_MAP_MOD_ID = "xaeroworldmap";
    private static final String MINIMAP_MOD_ID = "xaerominimap";

    private XaeroPresence() {
    }

    public static boolean mapPresent() {
        return ModPresence.isLoaded(WORLD_MAP_MOD_ID);
    }

    /** The minimap side. Map data is held by the world map, so this is used only to decide whether waypoints can be placed. */
    public static boolean minimapPresent() {
        return ModPresence.isLoaded(MINIMAP_MOD_ID);
    }
}
