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
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.render.util.RenderBufferUtil;

/**
 * Minimap-side hook. Whichever branch of {@code useWorldMap} true/false draws the terrain,
 * both branches converge on the {@code endBatch()} right after it, so one hook is enough.
 *
 * <p>The ordinal of the {@code endBatch()} call differs by version. On 1.20+, the first flush to the target {@code renderTypeBuffers}
 * is ordinal 0. In 1.16.5-1.19.2, {@code renderChunksToFBO} has one
 * {@code this.mc.renderBuffers().bufferSource().endBatch()} (a separate buffer on the main game side)
 * before it, so the flush of {@code renderTypeBuffers} itself, which is the one to target, is ordinal 1
 * (confirmed in Xaero 26.5.0 bytecode, comparing 1.18.2, 1.19.2 and 1.20.1). Leaving it at ordinal 0 throws no exception, but
 * draws into the other buffer, and the path doesn't appear on the minimap.
 *
 * <p>What to draw in which color is decided by {@link MapPathOverlay} (shared with the world map side). All this holds is
 * the Xaero-specific draw target and coordinate transform, plus culling of distant parts that don't fit on the FBO.
 *
 * <p>Belongs to a dedicated required=false mixin config; if the target method's shape changes, only this feature is disabled.
 */
@Mixin(MinimapFBORenderer.class)
public abstract class MinimapFBORendererMixin implements XaeroHookMarker {

    private static final float DOT_ALPHA = 0.9f;

    /**
     * The minimap FBO is 512x512, and in this draw 1 unit is 1 block. So anything beyond 256 blocks
     * from the center can't land on the FBO in principle. Cutting at this distance with some margin prevents a path
     * extending far away from being queued in full every frame.
     */
    private static final int CULL_RADIUS_BLOCKS = 320;

    /**
     * Roughly how many on-screen pixels one FBO unit becomes. Only the destination marker should have a constant on-screen
     * size, but here the FBO is drawn at 1 unit = 1 block, and the on-screen scale is applied when it's pasted (outside
     * this draw), so it can't be read from the matrix. A representative value at the default size and zoom is used; reading
     * the zoom setting too would add a dependency on Xaero's settings class only to change the marker by a few pixels.
     */
    private static final double SCREEN_PIXELS_PER_BLOCK = 2.0;

    @WrapOperation(
            method = "renderChunksToFBO",
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
            @Local(name = "xFloored") int xFloored,
            @Local(name = "zFloored") int zFloored) {
        XaeroHookProbe.record(XaeroHookProbe.Point.MINIMAP_RENDER);
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_CHUNK_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX1, blockZ1, blockX2, blockZ2, red, green, blue) -> {
                int x1 = blockX1 - xFloored;
                int z1 = blockZ1 - zFloored;
                int x2 = blockX2 - xFloored;
                int z2 = blockZ2 - zFloored;
                // Discard only those whose whole rectangle is outside. If even one corner is inside, draw it
                if (x2 < -CULL_RADIUS_BLOCKS || x1 > CULL_RADIUS_BLOCKS
                        || z2 < -CULL_RADIUS_BLOCKS || z1 > CULL_RADIUS_BLOCKS) {
                    return;
                }
                RenderBufferUtil.addColoredRect(pose, overlayBuffer, x1, z1, x2 - x1, z2 - z1,
                        red, green, blue, DOT_ALPHA);
            }, MapPathOverlay.pixelsPerBlock(pose) * SCREEN_PIXELS_PER_BLOCK);
        }
        original.call(renderTypeBuffers);
    }
}
