package net.prason.xaeronav.platform.forge;

//? if forge && >=1.21.6 {
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.config.ForgeConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

/^*
 * Entry point for Forge 56+ (Minecraft 1.21.6+). With EventBus 7, each event has its own bus, so
 * apart from {@code ForgeEntry}, which subscribes via annotations, it registers with each bus explicitly.
 ^/
@Mod(XaeroNav.MOD_ID)
public final class ForgeMod {

    public ForgeMod(FMLJavaModLoadingContext context) {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        context.registerConfig(ModConfig.Type.CLIENT, forgeConfigSpec());
        ModConfigEvent.Reloading.getBus(context.getModBusGroup()).addListener(ForgeMod::onConfigReloaded);
        // Forge's @Mod has no dist setting, so this is called on dedicated servers too. Registrations that touch
        // client classes are split into ForgeClientSetup so that the server never loads that class at all
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientSetup.register(context);
        }
    }

    private static ForgeConfigSpec forgeConfigSpec() {
        return ((ForgeConfigSpecStore) XaeroNavConfig.store()).forgeConfigSpec();
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == forgeConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }
}
*///?}
