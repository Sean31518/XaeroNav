package net.prason.xaeronav.mixin.xaero;

//? if fabric && >=1.21.9 && <1.21.11 {
/*import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.XaeroNavClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/^*
 * Draws the path at the same point as Fabric API's {@code WorldRenderEvents.END_MAIN}. The 1.21.10 jar is
 * also used on 1.21.9, but the Fabric API for 1.21.9 has no WorldRenderEvents. {@code method_62214} is the
 * main pass lambda and has the same shape in 1.21.9 and 1.21.10.
 ^/
@Mixin(LevelRenderer.class)
public abstract class FabricLevelRendererMixin {
    @Inject(method = "method_62214",
            at = @At(value = "INVOKE:LAST", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"))
    private void xaeronav$endMain(CallbackInfo ci, @Local PoseStack poseStack) {
        XaeroNavClient.PATH_RENDERER.render(poseStack, ClientCompat.mainCamera(Minecraft.getInstance()));
    }
}
*///?}
