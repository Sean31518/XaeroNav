package net.prason.xaeronav.pathfinding.cost;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.resources.ResourceLocation;
//? if >=1.17 {
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
//?}
import net.minecraft.world.level.block.Block;
//? if <1.17 {
/*import net.minecraft.world.level.block.EntityBlock;
*///?}
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;

/**
 * Defines which blocks may be dug through.
 *
 * <p><b>It counts "what may be dug", not "what must not be dug".</b> If unknown blocks (machines added by mods, other
 * add-on mods, vanilla blocks not yet in this version) defaulted to diggable, the guidance would tell the player to break
 * their belongings or buildings. Conversely, the cost of defaulting the other way is only "go around it", and even if
 * the path disappears, a detour is found within the range Xaero's map can read. Because it's asymmetric, an allowlist is used.
 *
 * <p>Only <b>naturally generated terrain</b> is allowed. Processed blocks (cobblestone, stone bricks, planks, nether
 * bricks, deepslate bricks...) were placed by someone, so they're never dug, whether in a stronghold, an ancient city,
 * or your own house. As a side effect, this line also automatically excludes "natural blocks that cause accidents
 * when broken", such as infested stone (silverfish), suspicious gravel (archaeology), and spawners, since all of them
 * are separate blocks from plain stone and sand.
 *
 * <p>Additions on both sides from the config ({@code XaeroNavConfig#additionalDiggableBlocks} /
 * {@code additionalForbiddenBlocks}) are applied by {@link #reloadFromConfig}.
 */
public final class DiggableBlocks {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Vanilla tags that denote natural terrain. More robust than listing individual block names, since stone and dirt
     * added by mods come along as-is (mods' worldgen blocks are customarily added to
     * {@code #*_carver_replaceables} so that caves generate through them).
     */
    //? if >=1.19 {
    private static final List<TagKey<Block>> TERRAIN_TAGS = List.of(
            // Blocks that cave carving may replace = exactly "terrain that may be dug through". Stone, dirt, sand,
            // terracotta, iron/copper ore, gravel, sandstone, calcite, snow, packed ice; on the Nether side, nylium and soul sand types
            //? if >=26.3 {
            /*// Caves in 26.3 carve anything but bedrock (only #uncarvable), so the replaceable tags can't count terrain.
            // Approximate the same range with the stone, dirt, grass, mud, moss, sand, terracotta, and nylium tags, plus TERRAIN_BLOCKS listed explicitly below
            BlockTags.BASE_STONE_OVERWORLD,
            BlockTags.BASE_STONE_NETHER,
            BlockTags.SUBSTRATE_OVERWORLD,
            BlockTags.SAND,
            BlockTags.TERRACOTTA,
            BlockTags.NYLIUM,
            *///?} else {
            BlockTags.OVERWORLD_CARVER_REPLACEABLES,
            BlockTags.NETHER_CARVER_REPLACEABLES,
            //?}
            // Add clay, dripstone, end stone, and smooth basalt, which the two above don't pick up
            BlockTags.SCULK_REPLACEABLE,
            BlockTags.LEAVES,
            BlockTags.WART_BLOCKS,
            BlockTags.SNOW,
            BlockTags.ICE,
            //? if >=26.2 {
            /*// 26.2 removed the constants other than iron, copper, and gold ores, but the tags themselves remain
            vanillaBlockTag("coal_ores"), BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            vanillaBlockTag("redstone_ores"), vanillaBlockTag("lapis_ores"),
            vanillaBlockTag("diamond_ores"), vanillaBlockTag("emerald_ores")
            *///?} else {
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES
            //?}
    );

    //? if >=26.2 {
    /*private static TagKey<Block> vanillaBlockTag(String path) {
        return TagKey.create(net.minecraft.core.registries.Registries.BLOCK, ResourceLocation.withDefaultNamespace(path));
    }
    *///?}
    //?} else if >=1.17 {
    /*// The cave replaceable tags (*_carver_replaceables) and #sculk_replaceable exist from 1.19. Before that, approximate the same range
    // with the stone, dirt, sand, terracotta, nylium, etc. tags
    private static final List<TagKey<Block>> TERRAIN_TAGS = List.of(
            BlockTags.BASE_STONE_OVERWORLD,
            BlockTags.BASE_STONE_NETHER,
            BlockTags.DIRT,
            BlockTags.SAND,
            BlockTags.TERRACOTTA,
            BlockTags.NYLIUM,
            BlockTags.LEAVES,
            BlockTags.WART_BLOCKS,
            BlockTags.SNOW,
            BlockTags.ICE,
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES
    );
    *///?}

    /** Natural terrain not included in the tags. */
    //? if >=1.19 {
    private static final Set<Block> TERRAIN_BLOCKS = Set.of(
            Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS, Blocks.GILDED_BLACKSTONE,
            Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.MAGMA_BLOCK,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POINTED_DRIPSTONE, Blocks.AMETHYST_BLOCK,
            Blocks.SCULK, Blocks.SCULK_VEIN,
            // Cap/stem asymmetry is the same as surface trees: the cap, which acts as leaves, is diggable, while stems (#logs) stay walls
            Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
            Blocks.MELON, Blocks.PUMPKIN,
            // Bamboo, azaleas, and chorus plants have collision, so if they couldn't be dug, groves would become walls
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER,
            Blocks.MANGROVE_ROOTS, Blocks.DIRT_PATH, Blocks.FARMLAND
            //? if >=26.3 {
            /*,
            // What the replaceable tags used to pick up (the rest of #overworld_carver_replaceables and #nether_carver_replaceables)
            Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.END_STONE, Blocks.SMOOTH_BASALT,
            Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM
            *///?}
    );
    //?} else if >=1.17 {
    /*private static final Set<Block> TERRAIN_BLOCKS = Set.of(
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS, Blocks.GILDED_BLACKSTONE,
            Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.MAGMA_BLOCK,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POINTED_DRIPSTONE, Blocks.DRIPSTONE_BLOCK, Blocks.AMETHYST_BLOCK, Blocks.CALCITE,
            // On 1.18.2, list explicitly what #*_carver_replaceables picks up from 1.19 on, to match
            Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.END_STONE, Blocks.SMOOTH_BASALT,
            Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
            Blocks.MELON, Blocks.PUMPKIN,
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER,
            Blocks.DIRT_PATH, Blocks.FARMLAND
    );
    *///?} else {
    /*private static final Set<Block> TERRAIN_BLOCKS = Set.of(
            Blocks.STONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL,
            Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.CLAY,
            Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.SNOW_BLOCK,
            Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE,
            Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT,
            Blocks.BLACKSTONE, Blocks.END_STONE, Blocks.OBSIDIAN,
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE,
            Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.EMERALD_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.MAGMA_BLOCK,
            Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
            Blocks.MELON, Blocks.PUMPKIN, Blocks.BAMBOO, Blocks.BAMBOO_SAPLING,
            Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER, Blocks.FARMLAND
    );
    *///?}

    // Dig cost calculation runs on worker threads, so updates must always swap in a new Set
    // (modifying in place would race with search threads that are iterating).
    private static volatile Set<Block> allowed = Set.of();
    private static volatile Set<Block> forbidden = Set.of();

    private DiggableBlocks() {
    }

    @SuppressWarnings("deprecation")
    public static boolean isDiggable(BlockState state) {
        Block block = state.getBlock();
        if (forbidden.contains(block)) {
            return false;
        }
        if (allowed.contains(block)) {
            return true;
        }
        // Blocks with contents lose those contents when broken. Excluding them all here, instead of listing chests,
        // furnaces, and spawners individually, also excludes machines added by mods. They can also be mixed into tags
        // (#sand includes suspicious sand), so this comes before the tag check
        if (
                //? if >=1.17 {
                state.hasBlockEntity()
                //?} else {
                /*state.getBlock() instanceof EntityBlock
                *///?}
        ) {
            return false;
        }
        if (TERRAIN_BLOCKS.contains(block)) {
            return true;
        }
        //? if >=1.17 {
        for (TagKey<Block> tag : TERRAIN_TAGS) {
            if (state.is(tag)) {
                return true;
            }
        }
        //?}
        return false;
    }

    /** Applies the config file's two block ID lists (e.g. "minecraft:cobblestone"). */
    public static synchronized void reloadFromConfig(Collection<? extends String> diggableIds,
                                                      Collection<? extends String> forbiddenIds) {
        allowed = resolve(diggableIds);
        forbidden = resolve(forbiddenIds);
    }

    private static Set<Block> resolve(Collection<? extends String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            Block block = location == null ? null : BlockRegistryCompat.byId(location);
            if (block == null) {
                LOGGER.warn("XaeroNav config: Ignored unknown block ID: {}", id);
                continue;
            }
            blocks.add(block);
        }
        return Set.copyOf(blocks);
    }
}
