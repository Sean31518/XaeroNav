package net.prason.xaeronav.platform.neoforge;

//? neoforge {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
//? if <1.20.5 {
/*import net.neoforged.fml.ModLoadingContext;
*///?}
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
//? if >=1.20.5 {
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//?} else {
/*import net.neoforged.neoforge.client.ConfigScreenHandler;
*///?}
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.ModConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

// On 21.x, specify dist=CLIENT as a client-only mod. 20.4's @Mod has no dist attribute, so
// anything using client classes is confined within FMLClientSetupEvent.
//? if >=1.21 {
@Mod(value = XaeroNav.MOD_ID, dist = Dist.CLIENT)
//?} else {
/*@Mod(XaeroNav.MOD_ID)
*///?}
public final class NeoForgeEntry {

    /** The config screen is registered on the client side, so the container is carried over until then. */
    private static ModContainer container;

    public NeoForgeEntry(IEventBus modEventBus, ModContainer modContainer) {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        container = modContainer;
        //? if >=1.20.5 {
        modContainer.registerConfig(ModConfig.Type.CLIENT, modConfigSpec());
        //?} else {
        /*ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, modConfigSpec());
        *///?}
        modEventBus.addListener(NeoForgeEntry::onConfigReloaded);
        modEventBus.addListener(ClientSetup::onClientSetup);
        modEventBus.addListener(ClientSetup::onRegisterKeyMappings);
    }

    private static net.neoforged.neoforge.common.ModConfigSpec modConfigSpec() {
        return ((ModConfigSpecStore) XaeroNavConfig.store()).modConfigSpec();
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == modConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }

    // References to client-only classes (Minecraft/RenderLevelStageEvent etc.) are confined within FMLClientSetupEvent.
    // Guarding with @Mod(dist=CLIENT) keeps this class itself from loading even on dedicated servers.
    // Annotation-based subscription (@EventBusSubscriber) isn't used because NeoForge 21.0.x doesn't pick the bus
    // automatically and rejects mod bus events. addListener can state the bus explicitly regardless of version
    public static final class ClientSetup {

        public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroNavClient.reloadBlockLists();
            NeoForge.EVENT_BUS.register(new NeoForgeEvents());

            // Make the same screen as the keybind (XaeroNavKeys.OPEN_CONFIG_SCREEN) openable from the Mods list too
            //? if >=1.20.5 {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer, parent) -> new XaeroNavConfigScreen(parent));
            //?} else {
            /*container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new XaeroNavConfigScreen(parent)));
            *///?}
        }

        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            //? if >=1.21.9 {
            /*event.registerCategory(XaeroNavKeys.CATEGORY);
            *///?}
            XaeroNavKeys.register(event::register);
        }
    }
}
//?}
