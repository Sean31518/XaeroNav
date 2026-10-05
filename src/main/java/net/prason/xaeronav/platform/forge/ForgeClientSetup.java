package net.prason.xaeronav.platform.forge;

//? if forge && >=1.21.6 {
/*import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
//? if >=1.21.9 {
/^import net.minecraft.client.renderer.state.LevelRenderState;
^///?}
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;

/^* A thin layer that wires Forge 56+ events to the loader-independent logic. Loaded only on the client. ^/
final class ForgeClientSetup {

    private ForgeClientSetup() {
    }

    static void register(FMLJavaModLoadingContext context) {
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(event -> {
            XaeroNavClient.reloadBlockLists();
            // Also let the Mods list open the same screen as the keybinding (XaeroNavKeys.OPEN_CONFIG_SCREEN)
            context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(XaeroNavConfigScreen::new));
        });
        //? if >=1.21.9 {
        /^RegisterKeyMappingsEvent.BUS.addListener(event -> XaeroNavKeys.register(event::register));
        AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw().add(
        ^///?} else {
        RegisterKeyMappingsEvent.getBus(context.getModBusGroup()).addListener(event -> XaeroNavKeys.register(event::register));
        AddGuiOverlayLayersEvent.getBus(context.getModBusGroup()).addListener(event -> event.getLayeredDraw().add(
        //?}
                ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                (graphics, deltaTracker) -> XaeroNavClient.HUD.render(graphics)));
            // Fires once when the LevelRenderer is created, earlier than FMLClientSetupEvent, so register here
        AddFramePassEvent.BUS.addListener(event -> event.addPass(
                ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "path"), new PathPass()));

        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> XaeroNavClient.TICK_HANDLER.onClientTick());
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                event -> XaeroNavClient.TICK_HANDLER.onLoggingIn(event.getPlayer()));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> XaeroNavClient.TICK_HANDLER.onLoggingOut());
        RegisterClientCommandsEvent.BUS.addListener(event -> event.getDispatcher().register(
                XaeroNavCommands.<CommandSourceStack>tree(ctx -> sink(ctx.getSource()), BlockPosArgument::getBlockPos)));
    }

    /^*
     * Pass that draws the world path. Forge places extra passes after all vanilla passes, and modelView still
     * holds the camera rotation in between, so the matrix passed in can be identity (same as Fabric's {@code END_MAIN}).
     ^/
    private static final class PathPass implements FramePassManager.PassDefinition {

        @Override
        //? if >=26.3 {
        /^public void extracts(LevelTargetBundle bundle, FramePass pass, LevelRenderState state) {
        ^///?} else {
        public void extracts(LevelTargetBundle bundle, FramePass pass, DeltaTracker deltaTracker) {
        //?}
            // Outside Fabulous! there is only main. We draw only to main too (NavRenderTypes)
            bundle.main = pass.readsAndWrites(bundle.main);
        }

        @Override
        //? if >=1.21.9 {
        /^public void executes(LevelRenderState state) {
        ^///?} else {
        public void executes() {
        //?}
            XaeroNavClient.PATH_RENDERER.render(new PoseStack(), ClientCompat.mainCamera(Minecraft.getInstance()));
        }
    }

    private static NavCommandSink sink(CommandSourceStack source) {
        return new NavCommandSink() {
            @Override
            public void success(Component message) {
                source.sendSuccess(() -> message, false);
            }

            @Override
            public void failure(Component message) {
                source.sendFailure(message);
            }
        };
    }
}
*///?}
