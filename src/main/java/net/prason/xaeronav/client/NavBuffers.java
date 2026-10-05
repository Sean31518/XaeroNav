package net.prason.xaeronav.client;

//? if >=26.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if >=26.3 {
/^import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;

import java.util.Optional;
import java.util.OptionalDouble;

import net.minecraft.client.Minecraft;
^///?}
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;

/^*
 * {@code MultiBufferSource} is gone in 26.2, so this rebuilds the {@code getBuffer} -> {@code endBatch}
 * flow on top of {@link StagedVertexBuffer}. {@code endBatch} uploads the vertices and draws them on the spot.
 *
 * <p>Call {@link #endFrame} at the end of each {@code render}. GPU buffers are reclaimed there.
 ^/
final class NavBuffers {

    private static final StagedVertexBuffer STAGED = new StagedVertexBuffer(() -> "XaeroNav path", 1 << 16);

    private StagedVertexBuffer.Draw currentDraw;

    static NavBuffers begin() {
        return new NavBuffers();
    }

    VertexConsumer getBuffer(RenderType type) {
        VertexSorting sorting = type.sortOnUpload() ? RenderSystem.getProjectionType().vertexSorting() : null;
        currentDraw = STAGED.appendDraw(type.format(), type.primitiveTopology(), sorting);
        return STAGED.getVertexBuilder(currentDraw);
    }

    void endBatch(RenderType type) {
        STAGED.upload();
        StagedVertexBuffer.ExecuteInfo info = STAGED.getExecuteInfo(currentDraw);
        if (info != null) {
            //? if >=26.3 {
            /^// We have to open the target render pass ourselves (up to 26.2, PreparedRenderType opened it itself)
            RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "XaeroNav path", target.getColorTextureView(), Optional.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                type.prepare().drawFromBuffer(info, pass);
            }
            ^///?} else {
            type.prepare().drawFromBuffer(info);
            //?}
        }
        STAGED.endDraw();
        currentDraw = null;
    }

    void endFrame() {
        STAGED.endFrame();
    }
}
*///?}
