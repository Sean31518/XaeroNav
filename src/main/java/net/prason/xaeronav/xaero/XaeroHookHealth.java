package net.prason.xaeronav.xaero;

import net.minecraft.client.Minecraft;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.ClientCompat;

/**
 * Detects "the mixin applied, but nothing is actually drawn on the map".
 *
 * <p>{@link XaeroHooks} only checks whether the injection target class got a marker, so a failure where the injection
 * succeeded but Xaero's rendering changed and nothing shows up slips through. To the user,
 * both just look like "no line on the map".
 *
 * <p>The check is limited to while the world map screen is open. During that time our injection point ({@code GuiMap#render})
 * is always passed every frame, so if it isn't, it's definitely broken. On the minimap side,
 * the user can turn off the display, so "not drawn" doesn't mean a failure.
 */
public final class XaeroHookHealth {

    /** Xaero's world map screen. Referencing the class would make this whole class unloadable without Xaero installed. */
    private static final String WORLD_MAP_SCREEN = "xaero.map.gui.GuiMap";

    /**
     * If the injection point isn't passed even once within this many ticks of opening the world map, it's considered broken.
     * For the first few frames after opening the screen, rendering may not run while terrain loads, so wait a little.
     */
    private static final int GRACE_TICKS = 40;

    private static int ticksWithMapOpen;
    private static boolean renderBroken;

    private XaeroHookHealth() {
    }

    /** Called from the map-side injection point. Reaching here = the mixin is actually running. */
    public static void hookRan() {
        ticksWithMapOpen = 0;
        renderBroken = false;
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (ClientCompat.screen(minecraft) == null || !WORLD_MAP_SCREEN.equals(ClientCompat.screen(minecraft).getClass().getName())) {
            ticksWithMapOpen = 0;
            return;
        }
        if (renderBroken) {
            return;
        }
        if (++ticksWithMapOpen > GRACE_TICKS) {
            renderBroken = true;
            XaeroNav.LOGGER.warn("XaeroNav: the world map mixin applied, but the render injection point was never reached. "
                    + "The Xaero version may be outside the supported range");
        }
    }

    /** The state where drawing onto the world map is judged not to be getting through. */
    public static boolean worldMapRenderBroken() {
        return renderBroken;
    }
}
