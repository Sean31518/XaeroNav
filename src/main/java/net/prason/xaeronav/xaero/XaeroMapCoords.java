package net.prason.xaeronav.xaero;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Small map-coordinate checks shared by {@code GuiMapRightClickMixin} and {@code GuiMapKeyMixin}.
 * Both do the same thing, "build a pathfinding destination from a point on the map (right-click position /
 * mouse cursor position)", from different Xaero hooks (right-click menu / key input), so the logic would be duplicated.
 *
 * <p>After the mixins are applied these are called from the target classes (outside the mixin package), so they
 * must live outside the mixin package to avoid being rejected by the Mixin class loader.
 */
public final class XaeroMapCoords {

    /**
     * Sentinel value meaning the map has no height information for the coordinate. Xaero itself also
     * omits Y from the coordinate display when it sees this value.
     */
    public static final int UNKNOWN_HEIGHT = 32767;

    private XaeroMapCoords() {
    }

    /**
     * Whether the map's dimension info ({@code null} if not yet known) disagrees with the dimension the player is in.
     * When viewing another dimension's map, the coordinates are scale-converted, and you cannot walk there anyway.
     */
    public static boolean isSameDimensionAsPlayer(ResourceKey<Level> mapDim, Level playerLevel) {
        return mapDim == null || mapDim == playerLevel.dimension();
    }

    /** For coordinates with unknown height, aim at the player's height (the search also spreads vertically, so it gets close). */
    public static int resolveGoalY(int mapY, Player player) {
        return mapY == UNKNOWN_HEIGHT ? player.blockPosition().getY() : mapY;
    }
}
