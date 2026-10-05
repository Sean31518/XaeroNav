package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
//? if >=1.17 {
import net.minecraft.world.level.block.BigDripleafBlock;
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
//? if >=1.17 {
import net.minecraft.world.level.block.PowderSnowBlock;
//?}
//? if >=1.19 {
import net.minecraft.world.level.block.SculkShriekerBlock;
//?}
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Search data for one block, packed into a {@code long}.
 *
 * <p>Holding it as a record costs close to 100 bytes per cell including the Map entry. Packing it into a long lets it
 * go straight into fastutil's primitive Maps, with zero allocation per cache entry.
 *
 * <p>The dig cost is kept at {@code float} precision. The value is a tick count (tens to thousands), so the
 * significant digits are plenty, and {@link net.prason.xaeronav.pathfinding.cost.ActionCosts#INFEASIBLE}
 * (positive infinity) also survives the round trip through float exactly.
 */
public final class CellData {

    /**
     * Represents outside the search range or an unloaded chunk. Every {@link CellData} predicate returns {@code false},
     * so it is handled on the safe side: "cannot touch, cannot stand, cannot dig" = the route does not extend.
     */
    public static final long ABSENT = 0L;

    // Flags are kept package-private so that tests in the same package (FakeCells)
    // can build cells without going through BlockState —
    // flagsOf(BlockState) requires Minecraft's registries to be bootstrapped, so unit tests cannot call it.
    static final long PRESENT = 1L;
    static final long PASSABLE_EMPTY = 1L << 1;
    static final long WATER = 1L << 2;
    static final long LAVA = 1L << 3;
    static final long STANDABLE = 1L << 4;
    static final long FALLING_BLOCK = 1L << 5;
    static final long UNRESOLVED_SHAPE = 1L << 6;
    static final long CLIMBABLE = 1L << 7;
    static final long OPENABLE = 1L << 8;
    static final long COBWEB = 1L << 9;
    static final long HAZARD = 1L << 10;
    /**
     * A floor that requires sneaking to walk on. Only magma blocks: stepping on one deals damage, but vanilla's
     * {@code isSteppingCarefully} (while sneaking) lets you cross unharmed. It is a marker for "does the guidance need a
     * note", not for passability, so it is kept separate from {@link #HAZARD}.
     */
    static final long SNEAK_REQUIRED = 1L << 11;
    /**
     * Whether a block can be placed here (vanilla {@code BlockBehaviour.BlockStateBase#canBeReplaced}).
     *
     * <p>Having no collision is not the same as being placeable. Weeping vines, twisting vines, cave vines, torches,
     * rails, and flowers can be walked through but are <b>not replaceable</b>, so even if you aim to place there,
     * {@code BlockPlaceContext#getClickedPos} returns the neighboring cell: nothing is ever placed at the guided
     * position. Only regular vines ({@code vine}) are replaceable and can be placed into.
     */
    static final long REPLACEABLE = 1L << 12;

    private static final long OCCUPIABLE = PASSABLE_EMPTY | WATER | CLIMBABLE;

    /**
     * Bit position for the movement speed multiplier ({@link #travelSpeedFactor}) times 100.
     * 0 means "unset = normal speed" (an {@link #ABSENT} cell also has 0 here, so it stays consistent).
     */
    private static final int SPEED_FACTOR_SHIFT = 16;
    private static final long SPEED_FACTOR_MASK = 0xFFL << SPEED_FACTOR_SHIFT;

    /** Tolerance for checking whether a collision boundary touches the edge of the cell. */
    private static final double EDGE_EPSILON = 1.0E-7;

    /**
     * Lower bound of friction for a "slippery floor". Normal blocks are 0.6; only ice, packed ice, and blue ice are 0.98
     * or higher. Slime blocks (0.8) only bounce and are not faster, so the line is drawn between to exclude them.
     */
    private static final float SLIPPERY_FRICTION = 0.9f;

    /** Speed multiplier when moving on ice ({@link #travelSpeedFactor}). */
    private static final float ICE_SPEED_FACTOR = 1.2f;

    /**
     * Speed multiplier when moving on magma blocks ({@link #travelSpeedFactor}). Vanilla's
     * {@code isSteppingCarefully} (while sneaking) avoids damage, but movement speed drops to about 0.3 times sprinting.
     * Instead of making it impassable, that slowness becomes the cost as-is.
     */
    private static final float MAGMA_SPEED_FACTOR = 0.3f;

    private CellData() {
    }

    /**
     * Determines the flags other than dig cost from a {@link BlockState}. Shape and fluid queries just read
     * {@code BlockState}'s cache and do not touch the level, so this can be called from any thread.
     *
     * <p>This lets the search (worker thread) and route re-check (main thread) share the same checks.
     * If they disagree, the re-check immediately invalidates the route the search let through, and recalculation
     * never stops.
     *
     * <p>The return value does not include the dig cost ({@link #digTicks} returns 0).
     * Build the full cell data used for searching with {@link #withDigTicks}.
     */
    public static long flagsOf(BlockState state) {
        // The hazard check comes first so that blocks like powder snow, which both "have a dynamic shape (changed by
        // leather boots)" and are "dangerous to enter", are treated as the closer-in-meaning HAZARD.
        // Either way the conclusion is "cannot enter", so search behavior does not change
        if (harmful(state)) {
            // A cell that causes an accident on contact. Neither entering nor standing on it is allowed, and it is not dug through either
            return PRESENT | HAZARD;
        }

        if (state.getBlock().hasDynamicShape()) {
            // Blocks whose shape resolution needs the real level. They cannot be evaluated correctly from a worker thread,
            // so treat them as obstacles that cannot be passed, stood on, or dug.
            return PRESENT | UNRESOLVED_SHAPE;
        }

        FluidState fluid = state.getFluidState();
        boolean water = fluid.is(FluidTags.WATER);
        boolean lava = fluid.is(FluidTags.LAVA);
        // Waterlogged stairs, slabs, and fences have "water as fluid" yet still have collision.
        // Setting WATER from the fluid alone would create routes that swim through solids
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean collisionEmpty = collision.isEmpty();
        boolean openable = openableByHand(state);
        // Open doors, fence gates, and trapdoors keep a thin panel of collision, so their collision is not empty.
        // Check whether they can be passed with the same check as vanilla mob pathfinding (it does not touch the level, so it can be called from a worker thread)
        // Before 1.20.5, isPathfindable has the old signature taking (BlockGetter, BlockPos, PathComputationType).
        // The check does not look at the level, so passing the same empty probe values as getCollisionShape keeps the meaning.
        // The vanilla API signature itself differs, so this is the one exception where a gate is placed in pathfinding/
        boolean passable = collisionEmpty
                || openable && state.isPathfindable(
                        //? if >=1.20.5 {
                        PathComputationType.LAND
                        //?} else {
                        /*EmptyBlockGetter.INSTANCE, BlockPos.ZERO, PathComputationType.LAND
                        *///?}
                );

        long flags = PRESENT;
        if (water && passable) {
            flags |= WATER;
        }
        if (lava) {
            flags |= LAVA;
        }
        if (!water && !lava && passable) {
            flags |= PASSABLE_EMPTY;
        }
        if (openable && !passable) {
            flags |= OPENABLE;
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            flags |= CLIMBABLE;
        }
        if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP) || walkableTop(collision)) {
            flags |= STANDABLE;
        }
        if (state.getBlock() instanceof FallingBlock) {
            flags |= FALLING_BLOCK;
        }
        if (state.getBlock() instanceof WebBlock) {
            flags |= COBWEB;
        }
        if (state.getBlock() instanceof MagmaBlock) {
            flags |= SNEAK_REQUIRED;
        }
        if (
                //? if >=1.20 {
                state.canBeReplaced()
                //?} else {
                /*state.getMaterial().isReplaceable()
                *///?}
        ) {
            // The no-argument version only reads the replaceable flag and does not touch the level,
            // so it can be called from a worker thread (the version taking BlockPlaceContext is about "holding the
            // same block in hand", so it is not used to judge placing an ordinary block)
            flags |= REPLACEABLE;
        }
        return flags | speedFactorBits(state);
    }

    /**
     * Whether this is a floor you can stand on. {@link BlockState#isFaceSturdy} alone is not enough.
     *
     * <p>{@code isFaceSturdy} checks "whether the top face at the cell boundary (y=1) is a full 1×1", so many floors
     * you can actually walk on normally fail it — stairs, slabs, farmland, dirt paths, leaves, hoppers, and cauldrons
     * all become "unstandable", and village paths and house stairs become impassable (no move is generated at all
     * toward a spot with no footing, so the route disappears rather than the cost being off).
     *
     * <p>So instead it checks whether the collision covers one full cell horizontally. It also requires "not sticking
     * out above its own cell", which excludes fences, walls, and fence gates (height 1.5) — you can stand on them, but
     * a normal jump (1.25 blocks) from below cannot climb them, so no climbing move must be created.
     */
    private static boolean walkableTop(VoxelShape collision) {
        if (collision.isEmpty()) {
            return false;
        }
        AABB bounds = collision.bounds();
        return bounds.minX <= EDGE_EPSILON && bounds.maxX >= 1.0 - EDGE_EPSILON
                && bounds.minZ <= EDGE_EPSILON && bounds.maxZ >= 1.0 - EDGE_EPSILON
                && bounds.maxY <= 1.0 + EDGE_EPSILON;
    }

    /**
     * Blocks that cause an accident on contact. Many have no collision and look "the same as air" to the pathfinder,
     * but entering them burns, freezes, or sends you to another dimension.
     *
     * <p>They are not dug through either. You would stay standing next to them the whole time you dig, so
     * going around is safer and usually cheaper.
     */
    private static boolean harmful(BlockState state) {
        Block block = state.getBlock();
        // MagmaBlock is not included here. It is a full footing with collision, and sneaking lets you walk it unharmed
        // (vanilla {@code isSteppingCarefully}); it is made passable as a slow but safe path, and
        // {@link #travelSpeedFactor} reflects that slowness in the cost
        return block instanceof BaseFireBlock                       // fire, soul fire. No collision
                || block instanceof SweetBerryBushBlock             // thorn damage + heavy slowdown
                || block instanceof WitherRoseBlock                 // wither on contact
                //? if >=1.17 {
                || block instanceof PowderSnowBlock                 // falling in freezes you. Indistinguishable from the ground in snowfields
                || block instanceof BigDripleafBlock                // tilts when stood on and drops you below
                //?}
                //? if >=1.19 {
                || block instanceof SculkShriekerBlock              // stepping on it summons the Warden
                //?}
                || (block instanceof CampfireBlock && state.getValue(CampfireBlock.LIT))
                // Sends you to another dimension the moment you pass through. End portals have no way back either
                || block instanceof NetherPortalBlock
                || block instanceof EndPortalBlock
                || block instanceof EndGatewayBlock
                //? if >=1.17 {
                || state.is(Blocks.LAVA_CAULDRON)
                || state.is(Blocks.POWDER_SNOW_CAULDRON)
                //?}
                ;
    }

    /** Packs the speed multiplier for moving on this block, times 100. Not packed for normal speed (1.0). */
    private static long speedFactorBits(BlockState state) {
        return speedFactorBits(travelSpeedFactor(state));
    }

    private static long speedFactorBits(float factor) {
        if (factor == 1.0f) {
            return 0L;
        }
        // Rounding to 0 would be indistinguishable from "unset = normal speed", so the floor is 1 (0.01 times)
        long scaled = Math.max(1L, Math.round(factor * 100.0f));
        return (scaled << SPEED_FACTOR_SHIFT) & SPEED_FACTOR_MASK;
    }

    /**
     * Speed multiplier for moving on this block (1.0 = normal speed).
     *
     * <p>For slowdowns, {@code Block#getSpeedFactor} is used as-is (0.4 for soul sand and honey blocks).
     *
     * <p>For speedups, only ice is considered. Ice's speed comes not from the speed factor but from friction (0.98 to
     * 0.989 versus the default 0.6). The steady-state speed of just running barely changes, while chaining sprint jumps
     * is clearly faster because each landing slows you less. Reproducing the acceleration accurately would require
     * looking at the stretch length, so this stays an approximation with a constant multiplier: "ice is somewhat
     * faster". The value sits on the conservative side, between plain sprinting (5.6 m/s) and sprint-jumping on flat
     * ground (7.1 m/s).
     */
    // Forge 61 deprecates vanilla getFriction to steer toward its own position-aware extension. This layer must not
    // depend on the loader, so it does not pick up modded blocks that vary friction by position and uses the vanilla value
    @SuppressWarnings("deprecation")
    private static float travelSpeedFactor(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof MagmaBlock) {
            return MAGMA_SPEED_FACTOR;
        }
        float speedFactor = block.getSpeedFactor();
        if (speedFactor < 1.0f) {
            return speedFactor;
        }
        return block.getFriction() >= SLIPPERY_FRICTION ? ICE_SPEED_FACTOR : 1.0f;
    }

    /** Whether this is a door, fence gate, or trapdoor that can be opened and closed by hand without redstone. */
    private static boolean openableByHand(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            //? if >=1.20 {
            return door.type().canOpenByHand();
            //?} else {
            /*return !state.is(Blocks.IRON_DOOR);
            *///?}
        }
        if (block instanceof FenceGateBlock) {
            // There are no redstone-only fence gates
            return true;
        }
        if (block instanceof TrapDoorBlock) {
            // TrapDoorBlock#getType() is protected, so BlockSetType cannot be read directly.
            // In vanilla, the only trapdoor that opens only with redstone is the iron one
            return !state.is(Blocks.IRON_TRAPDOOR);
        }
        return false;
    }

    public static long withDigTicks(long flags, double digTicks) {
        return flags | (Integer.toUnsignedLong(Float.floatToRawIntBits((float) digTicks)) << 32);
    }

    /** Injects a speed multiplier. Real data is packed by {@link #flagsOf}, so this is for describing test terrain. */
    static long withSpeedFactor(long flags, float factor) {
        return flags | speedFactorBits(factor);
    }

    public static boolean present(long cell) {
        return (cell & PRESENT) != 0L;
    }

    public static boolean passableEmpty(long cell) {
        return (cell & PASSABLE_EMPTY) != 0L;
    }

    public static boolean water(long cell) {
        return (cell & WATER) != 0L;
    }

    public static boolean lava(long cell) {
        return (cell & LAVA) != 0L;
    }

    public static boolean standable(long cell) {
        return (cell & STANDABLE) != 0L;
    }

    public static boolean fallingBlock(long cell) {
        return (cell & FALLING_BLOCK) != 0L;
    }

    /** Whether this block's shape could not be evaluated from a worker thread. Must not be a dig target. */
    public static boolean unresolvedShape(long cell) {
        return (cell & UNRESOLVED_SHAPE) != 0L;
    }

    /**
     * Whether this is a cobweb. It has no collision, so as {@link #passableEmpty} it is indistinguishable from air,
     * but movement is actually multiplied by 0.25 ({@code WebBlock#entityInside} → {@code Entity#move}).
     */
    public static boolean cobweb(long cell) {
        return (cell & COBWEB) != 0L;
    }

    /** Whether it can be grabbed to move up and down: ladders, vines, scaffolding, etc. */
    public static boolean climbable(long cell) {
        return (cell & CLIMBABLE) != 0L;
    }

    /**
     * Whether the cell is harmful to enter (fire, magma, powder snow, wither roses, portals, etc.).
     * Cannot be entered or stood on, and cannot be dug ({@code ChunkView} sets the dig cost to infinity).
     */
    public static boolean hazard(long cell) {
        return (cell & HAZARD) != 0L;
    }

    /**
     * Speed multiplier for moving on this cell (1.0 = normal speed; soul sand and honey blocks are 0.4, ice is 1.2).
     * Vanilla uses the factor of the cell at your feet, and if that is 1.0 it looks at the block below
     * ({@code Entity#getBlockSpeedFactor}).
     */
    /** Whether a block can be placed here. See {@link #REPLACEABLE}. */
    public static boolean replaceable(long cell) {
        return (cell & REPLACEABLE) != 0;
    }

    /** Whether this floor requires sneaking to walk on (magma blocks). */
    public static boolean sneakRequired(long cell) {
        return (cell & SNEAK_REQUIRED) != 0;
    }

    public static double speedFactor(long cell) {
        long raw = (cell & SPEED_FACTOR_MASK) >>> SPEED_FACTOR_SHIFT;
        return raw == 0L ? 1.0 : raw / 100.0;
    }

    /** Whether it is closed but can be opened by hand to pass (doors, fence gates, trapdoors). Not something to break. */
    public static boolean openable(long cell) {
        return (cell & OPENABLE) != 0L;
    }

    /** Whether the player's body can occupy it without digging (air, water without collision, ladders, vines). */
    public static boolean occupiableWithoutDigging(long cell) {
        return (cell & OCCUPIABLE) != 0L;
    }

    public static double digTicks(long cell) {
        return Float.intBitsToFloat((int) (cell >>> 32));
    }
}
