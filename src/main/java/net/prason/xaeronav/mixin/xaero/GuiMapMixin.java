package net.prason.xaeronav.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

//? if >=1.19.3 {
import org.joml.Matrix4f;
//?} else {
/*import com.mojang.math.Matrix4f;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >=1.21.11 {
/*import xaero.lib.client.graphics.XaeroBufferProvider;
*///?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.prason.xaeronav.client.MapPathOverlay;
import net.prason.xaeronav.xaero.XaeroHookMarker;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * Right after the world map's terrain rendering, draws the route as a chain of 1x1-block colored rectangles.
 * Converting to map coordinates with just a subtraction from {@code flooredCameraX}/{@code flooredCameraZ} works because
 * the terrain rendering itself uses exactly the same transform.
 *
 * <p>The ordinal of the {@code endBatch()} call differs by version. On 1.20+ the terrain flush is the first
 * (ordinal 0), but 1.16.5-1.19.2's {@code GuiMap#render} has one more call near the start that flushes
 * leftovers from the previous frame, so the terrain + overlay flush is the second (ordinal 1)
 * (confirmed by comparing 1.18.2, 1.19.2 and 1.20.1 in Xaero 1.46.0 bytecode).
 *
 * <p>What to draw in which color is decided by {@link MapPathOverlay} (shared with the minimap side). This class
 * holds only the Xaero-specific render target and coordinate transform.
 *
 * <p>Belongs to a dedicated required=false mixin config. If Xaero's World Map isn't installed or a major refactor changes the
 * target method's shape, only this one feature is disabled, and the mod itself keeps working with in-world rendering only.
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin implements XaeroHookMarker {

    private static final float DOT_ALPHA = 0.9f;

    @WrapOperation(
            // In 26.1, Screen#render was renamed to extractRenderState
            //? if >=26.1 {
            /*method = "extractRenderState",
            *///?} else {
            method = "render",
            //?}
            //? if >=1.21.11 {
            /*at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/XaeroBufferProvider;endBatch()V", ordinal = 0)
            *///?} else if <1.20 {
            /*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1)
            *///?} else {
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
            //?}
    )
    private void xaeronav$drawPath(
            //? if >=1.21.11 {
            /*XaeroBufferProvider renderTypeBuffers,
            *///?} else {
            MultiBufferSource.BufferSource renderTypeBuffers,
            //?}
            Operation<Void> original,
            @Local(name = "matrixStack") PoseStack matrixStack,
            @Local(name = "flooredCameraX") int flooredCameraX,
            @Local(name = "flooredCameraZ") int flooredCameraZ) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WORLD_MAP_RENDER);
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX1, blockZ1, blockX2, blockZ2, red, green, blue) ->
                    MapRenderHelper.fillIntoExistingBuffer(pose, overlayBuffer,
                            blockX1 - flooredCameraX, blockZ1 - flooredCameraZ,
                            blockX2 - flooredCameraX, blockZ2 - flooredCameraZ,
                            red, green, blue, DOT_ALPHA),
                    MapPathOverlay.pixelsPerBlock(pose));
        }
        original.call(renderTypeBuffers);
    }
}
