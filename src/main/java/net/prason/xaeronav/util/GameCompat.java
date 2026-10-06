package net.prason.xaeronav.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
//? if >=1.17 {
import net.minecraft.world.level.LevelHeightAccessor;
//?}
//? if >=1.21.11 {
/*import net.minecraft.world.attribute.EnvironmentAttributes;
*///?}
import net.minecraft.world.phys.Vec3;

/** Vanilla APIs that differ only in how they are called between Minecraft 1.16 and newer versions. Client-only ones live in {@code client.ClientCompat}. */
public final class GameCompat {
    private GameCompat() {
    }

    public static Inventory inventory(Player player) {
        //? if >=1.17 {
        return player.getInventory();
        //?} else {
        /*return player.inventory;
        *///?}
    }

    /** Shows on the action bar if {@code overlay}, otherwise in chat. */
    public static void tell(Player player, Component message, boolean overlay) {
        //? if >=26.1 {
        /*if (overlay) {
            player.sendOverlayMessage(message);
        } else {
            player.sendSystemMessage(message);
        }
        *///?} else {
        player.displayClientMessage(message, overlay);
        //?}
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        //? if >=26.1 {
        /*return ChunkPos.pack(chunkX, chunkZ);
        *///?} else {
        return ChunkPos.asLong(chunkX, chunkZ);
        //?}
    }

    public static float yaw(Player player) {
        //? if >=1.17 {
        return player.getYRot();
        //?} else {
        /*return player.yRot;
        *///?}
    }

    public static void setYaw(Player player, float yaw) {
        //? if >=1.17 {
        player.setYRot(yaw);
        //?} else {
        /*player.yRot = yaw;
        *///?}
    }

    public static boolean onGround(Player player) {
        //? if >=1.20 {
        return player.onGround();
        //?} else {
        /*return player.isOnGround();
        *///?}
    }

    public static Abilities abilities(Player player) {
        //? if >=1.17 {
        return player.getAbilities();
        //?} else {
        /*return player.abilities;
        *///?}
    }

    // All heights are returned in the old sense of "includes the bottom, excludes the top" (getMaxY() in 1.21.2+ includes the top)
    //? if >=1.17 {
    public static int minBuildHeight(LevelHeightAccessor level) {
        //? if >=1.21.2 {
        /*return level.getMinY();
        *///?} else {
        return level.getMinBuildHeight();
        //?}
    }

    public static int maxBuildHeight(LevelHeightAccessor level) {
        //? if >=1.21.2 {
        /*return level.getMaxY() + 1;
        *///?} else {
        return level.getMaxBuildHeight();
        //?}
    }

    public static int minSection(LevelHeightAccessor level) {
        //? if >=1.21.2 {
        /*return level.getMinSectionY();
        *///?} else {
        return level.getMinSection();
        //?}
    }
    //?} else {
    /*public static int minBuildHeight(Level level) {
        return 0;
    }

    public static int maxBuildHeight(Level level) {
        return level.getMaxBuildHeight();
    }

    public static int minSection(Level level) {
        return 0;
    }
    *///?}

    /** Whether placed water evaporates here (the Nether). */
    public static boolean waterEvaporates(Level level, BlockPos pos) {
        //? if >=1.21.11 {
        /*// It is an attribute that can vary per biome, so read it with a position. getDimensionValue throws only in
        // development environments (such as NeoForge dev runs), and in production silently ignores biome overrides
        return level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos);
        *///?} else {
        return level.dimensionType().ultraWarm();
        //?}
    }

    public static BlockPos containing(Vec3 pos) {
        //? if >=1.19.4 {
        return BlockPos.containing(pos);
        //?} else {
        /*return new BlockPos(pos);
        *///?}
    }
}
