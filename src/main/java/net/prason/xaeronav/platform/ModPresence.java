package net.prason.xaeronav.platform;

//? neoforge {
import net.neoforged.fml.ModList;
//?} forge {
/*import net.minecraftforge.fml.ModList;
*///?} fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?}

/**
 * Answers "is that mod loaded?" across loaders.
 *
 * <p>Don't substitute a class-existence check ({@code Class.forName}). When the jar is only on the
 * classpath, as in a dev run, the class is found but the mod itself isn't initialized, and the class
 * sits in a separate layer that can't resolve Minecraft classes. Touching it takes the whole game down
 * with a {@link NoClassDefFoundError}.
 */
public final class ModPresence {

    private ModPresence() {
    }

    public static boolean isLoaded(String modId) {
        //? neoforge {
        return ModList.get().isLoaded(modId);
        //?} forge {
        /*//? if >=26.1 {
        /^return ModList.isLoaded(modId);
        ^///?} else {
        return ModList.get().isLoaded(modId);
        //?}
        *///?} fabric {
        /*return FabricLoader.getInstance().isModLoaded(modId);
        *///?}
    }

    /** Version string of the loaded mod, or {@code "unknown"} if it isn't installed. */
    public static String version(String modId) {
        //? neoforge {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        //?} forge {
        /*//? if >=26.1 {
        /^return ModList.getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        ^///?} else {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        //?}
        *///?} fabric {
        /*return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        *///?}
    }
}
