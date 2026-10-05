package net.prason.xaeronav.pathfinding.world;

//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries;
//?} else {
/*import net.minecraft.core.Registry;
*///?}
//? if forge && <1.21 {
/*import net.minecraftforge.registries.ForgeRegistries;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Block registry reads and writes. Only on 1.20.1-forge is {@code BuiltInRegistries.BLOCK} deprecated
 * (steering toward Forge's own {@code ForgeRegistries.BLOCKS}); other nodes aren't deprecated, so they
 * use it as-is. A shared entry point so this loader branch isn't copied into multiple places.
 */
public final class BlockRegistryCompat {

    private BlockRegistryCompat() {
    }

    public static ResourceLocation keyOf(Block block) {
        //? if forge && <1.21 {
        /*return ForgeRegistries.BLOCKS.getKey(block);
        *///?} else if <1.19.3 {
        /*return Registry.BLOCK.getKey(block);
        *///?} else {
        return BuiltInRegistries.BLOCK.getKey(block);
        //?}
    }

    /** Returns {@code null} for unknown IDs. */
    public static Block byId(ResourceLocation id) {
        //? if forge && <1.21 {
        /*return ForgeRegistries.BLOCKS.containsKey(id) ? ForgeRegistries.BLOCKS.getValue(id) : null;
        *///?} else if <1.19.3 {
        /*return Registry.BLOCK.containsKey(id) ? Registry.BLOCK.get(id) : null;
        *///?} else if >=1.21.2 {
        /*return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.getValue(id) : null;
        *///?} else {
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
        //?}
    }
}
