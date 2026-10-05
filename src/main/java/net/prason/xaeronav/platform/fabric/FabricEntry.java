package net.prason.xaeronav.platform.fabric;

//? fabric {
/*import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
//? if >=1.19 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?} else if >=1.17 {
/^import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;
^///?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=26.1 {
/^import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
^///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=1.21.9 {
/^import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//? if >=26.1 {
/^¹import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
¹^///?} else if >=1.21.11 {
/^¹import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
¹^///?}
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
^///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.minecraft.commands.arguments.coordinates.Coordinates;
//? if >=1.21.2 {
/^import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
^///?}
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.client.XaeroNavKeys;

/^* A thin layer that just wires Fabric events to the loader-independent code. ^/
public final class FabricEntry implements ClientModInitializer {

    @Override
    @SuppressWarnings("deprecation")
    public void onInitializeClient() {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        XaeroNavClient.reloadBlockLists();

        //? if >=26.1 {
        /^XaeroNavKeys.register(KeyMappingHelper::registerKeyMapping);
        ^///?} else {
        XaeroNavKeys.register(KeyBindingHelper::registerKeyBinding);
        //?}

        ClientTickEvents.END_CLIENT_TICK.register(client -> XaeroNavClient.TICK_HANDLER.onClientTick());
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> XaeroNavClient.TICK_HANDLER.onLoggingIn(client.player));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> XaeroNavClient.TICK_HANDLER.onLoggingOut());

        //? if >=1.21.9 {
        /^// After translucent terrain has been drawn; equivalent to the old AFTER_TRANSLUCENT. The 1.21.10 jar is also used on 1.21.9, but
        // 1.21.9's Fabric API has no WorldRenderEvents, so on 1.21.10 FabricLevelRendererMixin draws at the same point
        //? if >=26.1 {
        /^¹LevelRenderEvents.END_MAIN.register(context -> XaeroNavClient.PATH_RENDERER.render(
                context.poseStack(), ClientCompat.mainCamera(Minecraft.getInstance())));
        ¹^///?} else if >=1.21.11 {
        /^¹WorldRenderEvents.END_MAIN.register(context -> XaeroNavClient.PATH_RENDERER.render(
                context.matrices(), ClientCompat.mainCamera(Minecraft.getInstance())));
        ¹^///?}
        HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                (graphics, tickCounter) -> XaeroNavClient.HUD.render(graphics));
        ^///?} else {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(
                context -> XaeroNavClient.PATH_RENDERER.render(context.matrixStack(), context.camera()));
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> XaeroNavClient.HUD.render(graphics));
        //?}

        //? if >=1.19 {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(XaeroNavCommands.<FabricClientCommandSource>tree(
                        ctx -> sink(ctx.getSource()), FabricEntry::blockPos)));
        //?} else if >=1.17 {
        /^// client command API v2 starts at 1.19. Before that, register on v1's static dispatcher
        ClientCommandManager.DISPATCHER.register(XaeroNavCommands.<FabricClientCommandSource>tree(
                ctx -> sink(ctx.getSource()), FabricEntry::blockPos));
        ^///?}
    }

    /^*
     * Resolving `~` relative coordinates needs a {@code CommandSourceStack}, but a Fabric client command's
     * source isn't one. Substitute a {@code CommandSourceStack} built from the player: {@code WorldCoordinates}
     * only looks at position and rotation and never touches the world or server.
     ^/
    //? if >=1.17 {
    private static BlockPos blockPos(CommandContext<FabricClientCommandSource> ctx, String name) {
        //? if >=1.21.2 {
        /^// The only way left to build a CommandSourceStack from a player is server-side (needs a ServerLevel).
        // Coordinate resolution only reads position, rotation and entity, so build one holding just those
        FabricClientCommandSource source = ctx.getSource();
        try {
            java.lang.reflect.Constructor<?> constructor = CommandSourceStack.class.getConstructors()[0];
            Class<?> permissionType = constructor.getParameterTypes()[4];
            Object permission = permissionType == int.class ? 0 : java.lang.reflect.Proxy.newProxyInstance(
                    permissionType.getClassLoader(), new Class<?>[] {permissionType},
                    (proxy, method, arguments) -> method.getReturnType() == boolean.class ? false : proxy);
            CommandSourceStack stack = (CommandSourceStack) constructor.newInstance(
                    CommandSource.NULL, source.getPosition(), source.getRotation(), null, permission,
                    "", Component.empty(), null, source.getPlayer());
            return ctx.getArgument(name, Coordinates.class).getBlockPos(stack);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct a client command source", exception);
        }
        ^///?} else {
        return ctx.getArgument(name, Coordinates.class)
                .getBlockPos(ctx.getSource().getPlayer().createCommandSourceStack());
        //?}
    }

    private static NavCommandSink sink(FabricClientCommandSource source) {
        return new NavCommandSink() {
            @Override
            public void success(Component message) {
                source.sendFeedback(message);
            }

            @Override
            public void failure(Component message) {
                source.sendError(message);
            }
        };
    }
    //?}
}
*///?}
