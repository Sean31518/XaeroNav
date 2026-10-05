package net.prason.xaeronav.platform.forge;

// From 1.21.6 (Forge 56) on, ForgeMod and ForgeClientSetup handle this
//? if forge && <1.21.6 {
/*//? if >=1.20.6 {
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//?} else {
/^//? if >=1.19 {
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
//?}
^///?}
import net.minecraftforge.api.distmarker.Dist;
//? if >=1.19 {
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//?} else if >=1.17 {
/^import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.ConfigGuiHandler;
^///?} else {
/^import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.client.registry.ClientRegistry;
^///?}
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
//? if >=1.17 {
import net.minecraftforge.fml.event.config.ModConfigEvent;
//?}
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//? if <1.21 {
/^import net.minecraftforge.fml.ModLoadingContext;
^///?}
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.ForgeConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

@Mod(XaeroNav.MOD_ID)
public final class ForgeEntry {

    // The config screen is registered inside FMLClientSetupEvent, so keep the context until then
    private static FMLJavaModLoadingContext context;

    // Injecting the context into the constructor needs Forge 47.x or later. The 1.20.1 jar also runs on 1.20.0 (Forge 46),
    // so below 1.21 we fetch it ourselves without arguments (get() is marked for removal in Forge 47, but 46 has nothing else)
    @SuppressWarnings("removal")
    //? if >=1.21 {
    public ForgeEntry(FMLJavaModLoadingContext context) {
    //?} else {
    /^public ForgeEntry() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
    ^///?}
        XaeroNav.LOGGER.info("XaeroNav initialized");
        ForgeEntry.context = context;
        //? if >=1.21 {
        context.registerConfig(ModConfig.Type.CLIENT, forgeConfigSpec());
        //?} else {
        /^ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, forgeConfigSpec());
        ^///?}
        context.getModEventBus().addListener(ForgeEntry::onConfigReloaded);
    }

    private static ForgeConfigSpec forgeConfigSpec() {
        return ((ForgeConfigSpecStore) XaeroNavConfig.store()).forgeConfigSpec();
    }

    private static void onConfigReloaded(
            //? if >=1.17 {
            ModConfigEvent.Reloading event
            //?} else {
            /^ModConfig.Reloading event
            ^///?}
    ) {
        if (event.getConfig().getSpec() == forgeConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }

    // Keep references to client-only classes (Minecraft, RenderLevelStageEvent, etc.) inside FMLClientSetupEvent.
    // Guarding with dist=CLIENT means this class is not even loaded on a dedicated server
    // (same structure as NeoForgeEntry; Forge's @Mod has no dist argument, so we guard here).
    // bus=MOD must be explicit: unlike NeoForge, Forge's @EventBusSubscriber defaults to the FORGE bus, and
    // if omitted FMLClientSetupEvent/RegisterKeyMappingsEvent/AddGuiOverlayLayersEvent
    // (all of which fire only on the mod event bus) are never called
    @Mod.EventBusSubscriber(modid = XaeroNav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientSetup {

        @SubscribeEvent
        @SuppressWarnings("removal")
        public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroNavClient.reloadBlockLists();
            MinecraftForge.EVENT_BUS.register(new ForgeEvents());

            // Let the Mods list open the same screen as the key binding (XaeroNavKeys.OPEN_CONFIG_SCREEN)
            //? if >=1.21 {
            context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            parent -> new XaeroNavConfigScreen(parent)));
            //?} else if >=1.19 {
            /^// Forge 48 (1.20.2)'s ConfigScreenFactory has no constructor taking only (Screen)
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new XaeroNavConfigScreen(parent)));
            ^///?} else if >=1.17 {
            /^// Forge 40 (1.18.2) has neither RegisterKeyMappingsEvent nor ConfigScreenHandler
            XaeroNavKeys.register(ClientRegistry::registerKeyBinding);
            ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
                    () -> new ConfigGuiHandler.ConfigGuiFactory(
                            (minecraft, parent) -> new XaeroNavConfigScreen(parent)));
            ^///?} else {
            /^XaeroNavKeys.register(ClientRegistry::registerKeyBinding);
            ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,
                    () -> (minecraft, parent) -> new XaeroNavConfigScreen(parent));
            ^///?}
        }

        //? if >=1.19 {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            XaeroNavKeys.register(event::register);
        }
        //?}

        // Forge has no NeoForge-style RenderGuiEvent.Post. We hook in HUD rendering by registering it
        // as an overlay (this is the only structural difference between Forge and NeoForge/Fabric). The registration
        // event itself is a different class on 1.20.6+ vs 1.20.4 and earlier (AddGuiOverlayLayersEvent /
        // RegisterGuiOverlaysEvent), with different signatures too.
        // 1.20.6 has no event to hook the HUD until AddGuiOverlayLayersEvent arrived in Forge 50.2.1.
        // 1.18.2 and earlier have no overlay registration event, so ForgeEvents draws via RenderGameOverlayEvent
        //? if >=1.19 {
        @SubscribeEvent
        //? if >=1.20.6 {
        public static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
            event.getLayeredDraw().add(
                    //? if >=1.21 {
                    ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                    //?} else {
                    /^// Forge has marked the ResourceLocation constructor for removal
                    ResourceLocation.tryBuild(XaeroNav.MOD_ID, "hud"),
                    ^///?}
                    (graphics, partialTick) -> XaeroNavClient.HUD.render(graphics));
        }
        //?} else {
        /^public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("hud",
                    (gui, graphics, partialTick, screenWidth, screenHeight) -> XaeroNavClient.HUD.render(graphics));
        }
        ^///?}
        //?}
    }
}
*///?}
