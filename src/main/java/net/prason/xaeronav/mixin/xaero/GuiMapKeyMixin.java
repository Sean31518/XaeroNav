package net.prason.xaeronav.mixin.xaero;

//? if forge && >=1.20.2 && <1.21 {
/*import org.spongepowered.asm.mixin.Dynamic;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.client.TextCompat;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.xaero.XaeroMapCoords;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.map.gui.GuiMap;

/**
 * Key that sets pathfinding to the coordinates under the mouse cursor on the world map screen ({@link XaeroNavKeys#GOTO_MAP_CURSOR}).
 * Like Xaero's own in-map shortcuts (B = create waypoint, etc.), it must only work inside GuiMap#keyPressed,
 * so instead of the normal in-game key handling ({@code XaeroNavKeys#handleInput}), it's checked directly
 * through an {@code @Inject} here.
 *
 * <p>The {@code isUsingTextField()} check comes first so that when focus is on something like the map's coordinate
 * input field, text input takes priority as Xaero intends (Xaero's own {@code keyPressed} also does this check
 * first, in the same order).
 *
 * <p>Belongs to a dedicated required=false mixin config, so if the target method isn't found only this feature is disabled.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapKeyMixin {

    @Shadow(remap = false)
    private int mouseBlockPosX;

    @Shadow(remap = false)
    private int mouseBlockPosY;

    @Shadow(remap = false)
    private int mouseBlockPosZ;

    @Shadow(remap = false)
    private ResourceKey<Level> mouseBlockDim;

    @Shadow(remap = false)
    private boolean isUsingTextField() {
        throw new UnsupportedOperationException();
    }

    // On Forge 1.20.4 the shipped Xaero uses SRG names, while the dev environment that goes through Renamer uses named names.
    // The Mixin AP can't resolve an override of an external class into the refmap, so both are candidates and the real environment picks one.
    //? if >=1.21.9 {
    /*// From 1.21.9 on, key input is bundled into a single KeyEvent
    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void xaeronav$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WORLD_MAP_KEY);
        if (this.isUsingTextField() || !XaeroNavKeys.GOTO_MAP_CURSOR.matches(event)) {
    *///?} else {
    //? if forge && >=1.20.2 && <1.21 {
    /*@SuppressWarnings("target")
    @Dynamic("Xaero's keyPressed override has named and SRG forms across Forge environments")
    @Inject(method = { "keyPressed(III)Z", "m_7933_(III)Z" }, at = @At("HEAD"), cancellable = true, remap = false)
    *///?} else if forge && <1.21 {
    /*@Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    *///?} else {
    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    //?}
    private void xaeronav$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                        CallbackInfoReturnable<Boolean> cir) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WORLD_MAP_KEY);
        if (this.isUsingTextField() || !XaeroNavKeys.GOTO_MAP_CURSOR.matches(keyCode, scanCode)) {
    //?}
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!XaeroMapCoords.isSameDimensionAsPlayer(mouseBlockDim, mc.level)) {
            return;
        }

        int goalY = XaeroMapCoords.resolveGoalY(mouseBlockPosY, mc.player);
        BlockPos goal = new BlockPos(mouseBlockPosX, goalY, mouseBlockPosZ);
        BlockPos resolved = PathfindingState.INSTANCE.setGoal(goal);
        if (resolved != null) {
            GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.goal_walk",
                    resolved.toShortString()), true);
        }
        cir.setReturnValue(true);
    }
}
