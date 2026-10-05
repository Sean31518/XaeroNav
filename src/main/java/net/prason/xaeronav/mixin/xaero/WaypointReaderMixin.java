package net.prason.xaeronav.mixin.xaero;

import java.util.ArrayList;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.xaero.XaeroHookMarker;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;
import xaero.map.mods.gui.WaypointReader;

/**
 * Adds "Pathfind here" to the end of the waypoint right-click menu (Edit/Teleport/Share/Disable/Delete).
 * {@code getRightClickOptions} also exists under the same name as a bridge method from generic erasure,
 * so the descriptor is given explicitly to target only the {@code Waypoint} version.
 *
 * <p>Belongs to a dedicated required=false mixin config; if the target method isn't found, only this feature is disabled.
 */
@Mixin(WaypointReader.class)
public abstract class WaypointReaderMixin implements XaeroHookMarker {

    @ModifyReturnValue(
            method = "getRightClickOptions(Lxaero/map/mods/gui/Waypoint;Lxaero/map/gui/IRightClickableElement;)Ljava/util/ArrayList;",
            at = @At("RETURN"),
            // It's Xaero's own method, so there's no SRG mapping. Letting it remap makes the 1.20.1-forge AP stop the build
            remap = false
    )
    private ArrayList<RightClickOption> xaeronav$addGoHereOption(ArrayList<RightClickOption> original,
                                                                   Waypoint element, IRightClickableElement target) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WAYPOINT_MENU);
        if (original == null) {
            return null;
        }
        original.add(new RightClickOption("gui.xaeronav_goto_waypoint", original.size(), target) {
            @Override
            public boolean isActive() {
                return element.isyIncluded();
            }

            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.setGoal(new BlockPos(element.getX(), element.getY(), element.getZ()));
            }
        });
        // Placed right below "Pathfind here". Pressing it is pointless while there's no destination, so it's shown grayed out
        original.add(new RightClickOption("gui.xaeronav_clear_route", original.size(), target) {
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
}
