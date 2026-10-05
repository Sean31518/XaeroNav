package net.prason.xaeronav.mixin.xaero;

import java.util.ArrayList;
import java.util.Set;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.xaero.XaeroMapCoords;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Adds "Navigate Here" to the menu shown when right-clicking an empty spot on the world map.
 * {@code GuiMap} itself is an {@code IRightClickableElement}, and this {@code getRightClickOptions} is called
 * only when right-clicking the map background (right-clicking on a waypoint is handled by
 * {@link WaypointReaderMixin}).
 *
 * <p>Belongs to a dedicated mixin config with required=false; if the target method isn't found, only this feature is disabled.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapRightClickMixin {

    /**
     * We want to insert after the leading info rows (title, chunk coordinates, block coordinates) and before the first
     * action item, so it isn't buried under the distance readout Xaero draws at the end on its own (e.g. "245.0m", not an
     * element of the list {@code getRightClickOptions} returns). However, the number of leading info rows varies from 0 to 2
     * depending on the "Display Map Distances" setting and whether a tile is selected, so a fixed index
     * can't be hard-coded (found through in-game feedback, 2026-08-13). So insert right before the first action item
     * actually found (a translation key added by Xaero's own {@code GuiMap#getRightClickOptions} implementation).
     * If none is found, append to the end (same as the original behavior, the safe side).
     */
    private static final Set<String> FIRST_ACTION_KEYS = Set.of(
            "gui.xaero_right_click_map_create_waypoint",
            "gui.xaero_right_click_map_create_temporary_waypoint",
            "gui.xaero_right_click_map_teleport",
            "gui.xaero_wm_right_click_map_teleport_not_allowed",
            "gui.xaero_right_click_map_cant_teleport",
            "gui.xaero_right_click_map_cant_teleport_world",
            "gui.xaero_right_click_map_share_location",
            "gui.xaero_right_click_map_waypoints_menu",
            "gui.xaero_right_click_box_map_export",
            "gui.xaero_right_click_box_map_settings");

    @Shadow(remap = false)
    private int rightClickX;

    @Shadow(remap = false)
    private int rightClickY;

    @Shadow(remap = false)
    private int rightClickZ;

    @Shadow(remap = false)
    private ResourceKey<Level> rightClickDim;

    // Xaero's own method, so there's no SRG mapping. Letting it remap makes the AP on 1.20.1-forge stop the build
    @ModifyReturnValue(method = "getRightClickOptions", at = @At("RETURN"), remap = false)
    private ArrayList<RightClickOption> xaeronav$addGoHereOption(ArrayList<RightClickOption> original) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WORLD_MAP_MENU);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return original;
        }
        if (!XaeroMapCoords.isSameDimensionAsPlayer(rightClickDim, mc.level)) {
            return original;
        }

        int goalX = rightClickX;
        int goalZ = rightClickZ;
        int goalY = XaeroMapCoords.resolveGoalY(rightClickY, mc.player);

        int insertIndex = firstActionIndex(original);
        original.add(insertIndex, new RightClickOption("gui.xaeronav_goto_here", insertIndex, (GuiMap) (Object) this) {
            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.setGoal(new BlockPos(goalX, goalY, goalZ));
            }
        });
        // Placed right below "Navigate Here". Pointless to press while there's no goal, so it's shown grayed out
        // (removing the item itself would shift menu positions between searching/not searching and invite misclicks)
        int clearIndex = insertIndex + 1;
        original.add(clearIndex, new RightClickOption("gui.xaeronav_clear_route", clearIndex, (GuiMap) (Object) this) {
            @Override
            public boolean isActive() {
                return PathfindingState.INSTANCE.goal() != null;
            }

            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.clear();
            }
        });
        return original;
    }

    private static int firstActionIndex(ArrayList<RightClickOption> options) {
        for (int i = 0; i < options.size(); i++) {
            if (FIRST_ACTION_KEYS.contains(((RightClickOptionAccessor) options.get(i)).xaeronav$translationKey())) {
                return i;
            }
        }
        return options.size();
    }
}
