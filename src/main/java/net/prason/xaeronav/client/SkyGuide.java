package net.prason.xaeronav.client;

import java.util.List;
import java.util.function.IntBinaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/**
 * Guidance while gliding under open sky. Instead of drawing an air route, it raises a pillar of light at the landing
 * point and points to it with the HUD arrow.
 *
 * <p>Where the sky is visible, "where to head" matters more than a line around obstacles. The air route searches the
 * loaded area hundreds of blocks ahead even when flying high above the terrain would do, and the line is cut off at
 * the render distance. Dimensions with a ceiling (the Nether) and under a roof (caves) still draw an air route as before.
 *
 * <p>The pillar stands at <b>the point where you should land</b>: the destination if it is on the surface, or, if the
 * walking long-distance route dives underground partway, the surface point just before that (e.g. where a route to a
 * stronghold enters a cave).
 */
final class SkyGuide {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Switch once the sky has been visible / not visible for this long (ticks). Re-drawing the air route every time you
     * pass under a tree or a bridge would run a search each time and swap the display back and forth.
     */
    private static final int SWITCH_TICKS = 40;

    /** Points this far below the surface count as underground (blocks). Absorbs surface relief and the map's averaging error. */
    static final int UNDERGROUND_MARGIN_BLOCKS = 4;

    /** Interval for re-choosing the pillar location (ticks). Once chunks load, the surface height goes from the map's estimate to the measured value. */
    private static final int PILLAR_REFRESH_TICKS = 40;

    private boolean active;
    private int pendingTicks;

    private @Nullable BlockPos pillar;
    private @Nullable BlockPos pillarGoal;
    private @Nullable List<BlockPos> pillarRoute;
    private int pillarAge;

    /** Whether we have switched to under-the-sky guidance. */
    boolean active() {
        return active;
    }

    /** The moment of takeoff. The first decision is made with no grace period; waiting here would search an air route for a few seconds even under open sky. */
    void begin(Level level, Player player) {
        active = openSky(level, player);
        pendingTicks = 0;
    }

    void reset() {
        active = false;
        pendingTicks = 0;
        pillar = null;
        pillarGoal = null;
        pillarRoute = null;
    }

    /** One tick while gliding. True if it switched. */
    boolean tick(Level level, Player player) {
        boolean open = openSky(level, player);
        if (open == active) {
            pendingTicks = 0;
            return false;
        }
        if (++pendingTicks < SWITCH_TICKS) {
            return false;
        }
        active = open;
        pendingTicks = 0;
        return true;
    }

    private static boolean openSky(Level level, Player player) {
        return !level.dimensionType().hasCeiling() && level.canSeeSky(player.blockPosition().above());
    }

    /**
     * Where to raise the pillar. {@code route} is the intermediate targets of the walking long-distance route (all of
     * them including those passed; empty if none). The result is re-chosen every {@link #PILLAR_REFRESH_TICKS}.
     */
    BlockPos pillar(Level level, BlockPos goal, List<BlockPos> route, @Nullable CoarseMap map) {
        if (pillar != null && goal.equals(pillarGoal) && route == pillarRoute && ++pillarAge < PILLAR_REFRESH_TICKS) {
            return pillar;
        }
        BlockPos chosen = descentPoint(goal, route, (x, z) -> surfaceY(level, map, x, z));
        if (!chosen.equals(pillar)) {
            LOGGER.debug("XaeroNav: light pillar location ({}, {}, {}, goal={}, {}, {}, intermediate targets {}, map {})",
                    chosen.getX(), chosen.getY(), chosen.getZ(), goal.getX(), goal.getY(), goal.getZ(),
                    route.size(), map == null ? "none" : "present");
        }
        pillar = chosen;
        pillarGoal = goal;
        pillarRoute = route;
        pillarAge = 0;
        return pillar;
    }

    /**
     * The landing point. Follows the route from the start and returns the point <b>just before</b> (a surface point)
     * the first point that goes underground. If it never goes underground, the destination. The returned Y is that
     * column's surface (the base of the pillar).
     *
     * @param surface surface height of a column; {@link Integer#MIN_VALUE} if unknown (never judged underground)
     */
    static BlockPos descentPoint(BlockPos goal, List<BlockPos> route, IntBinaryOperator surface) {
        BlockPos previous = null;
        for (BlockPos point : route) {
            if (underground(point, surface)) {
                return previous == null ? atSurface(point, surface) : atSurface(previous, surface);
            }
            previous = point;
        }
        return atSurface(goal, surface);
    }

    private static boolean underground(BlockPos point, IntBinaryOperator surface) {
        int top = surface.applyAsInt(point.getX(), point.getZ());
        return top != Integer.MIN_VALUE && point.getY() < top - UNDERGROUND_MARGIN_BLOCKS;
    }

    private static BlockPos atSurface(BlockPos point, IntBinaryOperator surface) {
        int top = surface.applyAsInt(point.getX(), point.getZ());
        return top == Integer.MIN_VALUE ? point : new BlockPos(point.getX(), Math.max(top, point.getY()), point.getZ());
    }

    /**
     * Surface height of a column. The world heightmap if loaded, otherwise the highest floor in Xaero's map.
     * {@link Integer#MIN_VALUE} if neither is available.
     */
    private static int surfaceY(Level level, @Nullable CoarseMap map, int x, int z) {
        if (level.hasChunk(x >> 4, z >> 4)) {
            // Exclude leaves. Otherwise intermediate targets passing over a forest fall below leaf height, get judged underground, and the pillar stands too early
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        if (map == null) {
            return Integer.MIN_VALUE;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int top = Integer.MIN_VALUE;
        for (int floor = 0; floor < map.floorCount(chunkX, chunkZ); floor++) {
            if (map.kindAtFloor(chunkX, chunkZ, floor) != CoarseMap.VOID) {
                top = Math.max(top, map.heightAtFloor(chunkX, chunkZ, floor));
            }
        }
        return top;
    }
}
