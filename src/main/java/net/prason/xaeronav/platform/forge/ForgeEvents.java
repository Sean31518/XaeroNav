package net.prason.xaeronav.platform.forge;

// From 1.21.6 (Forge 56) on, ForgeMod and ForgeClientSetup handle this
//? if forge && <1.21.6 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.commands.CommandSourceStack;
//? if >=1.20 {
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
//?} else {
/^import net.minecraft.commands.arguments.coordinates.Coordinates;
^///?}
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
//? if <1.19 {
/^import net.minecraftforge.client.event.RenderGameOverlayEvent;
^///?}
//? if >=1.17 {
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
//? if <1.21.2 {
import net.minecraftforge.client.event.RenderLevelStageEvent;
//?}
//?} else {
/^import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraft.client.Minecraft;
^///?}
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;

/^* Just a layer that connects Forge game events to loader-independent handling. ^/
public final class ForgeEvents {

    // Forge 54 (1.21.4) and 55 (1.21.5) have no RenderLevelStageEvent while the frame-graph renderer
    // was in transition. ForgeLevelRendererMixin supplies the equivalent callback for those release lines.
    //? if <1.21.2 {
    @SubscribeEvent
    public void onRenderLevelStage(
            //? if >=1.17 {
            RenderLevelStageEvent event
            //?} else {
            /^RenderWorldLastEvent event
            ^///?}
    ) {
        //? if >=1.17 {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        //?}
        //? if >=1.20.5 {
        // From Forge 1.20.6, RenderLevelStageEvent carries a Matrix4f instead of a PoseStack (because Mojang stopped
        // passing PoseStack around for GUI rendering). PathRenderer depends on PoseStack's push/pop API, so
        // it's loaded into a standalone PoseStack and passed on (just one copy of the matrix with rotation/translation; per-frame cost is light).
        // event.getPoseStack() is forRemoval=true and slated for removal (Forge 1.21+). The same value can also be read from
        // RenderSystem.getModelViewMatrix() (when Mojang removed PoseStack from the rendering pipeline's
        // arguments, Forge kept the value on the event but deprecated only the accessor)
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(RenderSystem.getModelViewMatrix());
        //?} else {
        /^// Before 1.20.4, RenderLevelStageEvent carries the PoseStack directly (before 1.20.5 switched to Matrix4f)
        //? if >=1.17 {
        PoseStack poseStack = event.getPoseStack();
        //?} else {
        /^¹PoseStack poseStack = event.getMatrixStack();
        ¹^///?}
        ^///?}
        XaeroNavClient.PATH_RENDERER.render(poseStack,
                //? if >=1.17 {
                event.getCamera()
                //?} else {
                /^ClientCompat.mainCamera(Minecraft.getInstance())
                ^///?}
        );
    }
    //?}

    // Before Forge 49.1.10 (1.20.4), TickEvent.ClientTickEvent wasn't split into Post/Pre nested classes;
    // it's the old form that distinguishes before/after with the phase field (START/END)
    @SubscribeEvent
    public void onClientTick(
            //? if >=1.20.4 {
            TickEvent.ClientTickEvent.Post event
            //?} else {
            /^TickEvent.ClientTickEvent event
            ^///?}
    ) {
        //? if <1.20.4 {
        /^if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ^///?}
        XaeroNavClient.TICK_HANDLER.onClientTick();
    }

    // ClientPlayerNetworkEvent.LoggingIn/LoggingOut exist from Forge 41 (1.19). Before that it's LoggedInEvent/LoggedOutEvent
    @SubscribeEvent
    public void onLoggingIn(
            //? if >=1.19 {
            ClientPlayerNetworkEvent.LoggingIn event
            //?} else {
            /^ClientPlayerNetworkEvent.LoggedInEvent event
            ^///?}
    ) {
        XaeroNavClient.TICK_HANDLER.onLoggingIn(event.getPlayer());
    }

    @SubscribeEvent
    public void onLoggingOut(
            //? if >=1.19 {
            ClientPlayerNetworkEvent.LoggingOut event
            //?} else {
            /^ClientPlayerNetworkEvent.LoggedOutEvent event
            ^///?}
    ) {
        XaeroNavClient.TICK_HANDLER.onLoggingOut();
    }

    //? if >=1.17 {
    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(XaeroNavCommands.<CommandSourceStack>tree(
                //? if >=1.20 {
                ctx -> sink(ctx.getSource()), BlockPosArgument::getBlockPos));
                //?} else {
                /^ctx -> sink(ctx.getSource()),
                // Before 1.20, BlockPosArgument has no getBlockPos(CommandContext, String) (getLoadedBlockPos is for servers)
                (context, name) -> context.getArgument(name, Coordinates.class).getBlockPos(context.getSource())));
                ^///?}
    }
    //?}

    // Forge's overlay registration event (RegisterGuiOverlaysEvent) exists from 1.19. In 1.18.2 and earlier, drawing happens here
    //? if <1.19 {
    /^@SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            XaeroNavClient.HUD.render(event.getMatrixStack());
        }
    }
    ^///?}

    private static NavCommandSink sink(CommandSourceStack source) {
        return new NavCommandSink() {
            @Override
            public void success(net.minecraft.network.chat.Component message) {
                //? if >=1.20 {
                source.sendSuccess(() -> message, false);
                //?} else {
                /^source.sendSuccess(message, false);
                ^///?}
            }

            @Override
            public void failure(net.minecraft.network.chat.Component message) {
                source.sendFailure(message);
            }
        };
    }
}
*///?}
