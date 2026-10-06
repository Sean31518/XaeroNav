package net.prason.xaeronav.pathfinding.world;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;

import net.minecraft.core.BlockPos;
//? if >=1.20.5 && <1.21 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
*///?}
//? if >=1.21 {
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
//?}
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.DigCost;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;

import java.util.function.Predicate;

/**
 * A view for reading blocks in the search range.
 *
 * <p>Block data itself is <b>not copied</b>. What {@link #capture} collects on the main thread is only
 * "references to loaded chunks"; the actual {@link BlockState}s are read by the worker thread directly
 * from the chunks.
 *
 * <p>Shape and hardness queries ({@code getCollisionShape}/{@code isFaceSturdy}/{@code getDestroySpeed}) just read
 * {@code BlockState}'s cache and do not touch the level, so these too can be called from a worker thread.
 * The only exception is blocks whose {@code Block#hasDynamicShape()} is true: they need the real level to resolve
 * their shape, so we err on the safe side and treat them as "obstacles that can be neither entered nor placed into".
 *
 * <p><b>On races with main-thread writes (verified, 2026-09-13):</b>
 * Workers read, without synchronization, {@code LevelChunk}s the main thread may update concurrently. Checking the
 * actual sources ({@code LevelChunk}/{@code LevelChunkSection}/{@code PalettedContainer}/{@code SimpleBitStorage})
 * found the following.
 * <ul>
 *   <li>Reference stability is guaranteed: {@code ChunkAccess.sections} is a {@code final} array, and
 *       {@code LevelChunkSection.states} is also a {@code final} {@code PalettedContainer}. While a chunk is alive,
 *       these references never turn into another chunk/section.</li>
 *   <li>{@code PalettedContainer.data} is {@code volatile}, and a read pins {@code palette} and {@code storage} to the
 *       same generation with a single volatile read. Even if it coincides with a palette resize (replacing the whole
 *       array when the bit width runs out), palette and storage from different generations never mix.</li>
 *   <li><b>The real race point is the non-{@code volatile} {@code long[]} held by {@code SimpleBitStorage}</b>.
 *       Block updates read-modify-write it without synchronization. One {@code long} packs the states of several
 *       blocks, so a worker reading another coordinate within the same {@code long} the main thread is rewriting is
 *       a data race with no visibility guarantee. The {@code ThreadingDetector} ({@code acquire}/{@code release})
 *       that {@code PalettedContainer} itself holds only detects concurrent writes, and reads pass straight through:
 *       Mojang also designed it assuming a single thread.</li>
 *   <li>However, on modern JVMs/hardware (HotSpot, x86-64/ARM64), writes to aligned {@code long} array elements are
 *       atomic, so a corrupted value becoming an out-of-range palette index and crashing practically never happens.
 *       What can actually happen is at most a minor stale read: "for a very brief moment right before or after the
 *       main thread's rewrite, the worker reads the block from before the update".</li>
 *   <li>This residual risk self-heals via the existing mechanism where {@code PathValidator.firstFailureFrom} re-reads
 *       the latest {@code level.getBlockState} on the main thread every tick to validate the route.
 *       It never becomes a permanent failure or a lingering inconsistency.</li>
 * </ul>
 * Based on the above, structural changes such as per-section copies or revision checks are deferred
 * (the hot-path performance cost is not worth it given how little real harm there is). Reconsider only if symptoms
 * beyond what this section assumes are confirmed in-game (permanent route failures that stale reads cannot explain, etc.).
 *
 * <p><b>Thread contract:</b> call {@link #capture} from the main thread. Once created, an instance is owned by a
 * single worker thread (it keeps the previous chunk and cell caches in mutable fields, so it must not be shared
 * across threads). <b>When running searches in parallel, split views with {@link #forParallelSearch}</b>: sharing
 * does not necessarily fail with an exception, and a route can come out having read blocks from a different
 * chunk.
 */
public final class ChunkView implements CellSource {

    // Sentinel for "not computed" in the cell cache. The upper 32 bits are the float bits of the dig tick count,
    // and all bits set = NaN is never produced, so it cannot collide with this value.
    private static final long NOT_CACHED = -1L;

    /** A single search touches tens to hundreds of thousands of cells. Growing the map as it fills would rehash everything each time along the way. */
    private static final int CELL_CACHE_CAPACITY = 1 << 15;

    /**
     * Fraction of health used to derive the accepted limit (damage points = half-heart units) when fall damage is allowed.
     * At full health (20), that allows 6 points = 3 hearts = a 9-block fall.
     */
    private static final float FALL_DAMAGE_HEALTH_FRACTION = 3.0f;

    private final Long2ObjectMap<LevelChunk> chunks;
    private final int totalChunksInBounds;
    private final SearchBounds bounds;
    /** Hotbar copied on the main thread. Needed to compute dig costs on a worker thread. */
    private final ItemStack[] hotbar;
    /** Efficiency level of each hotbar slot. Resolving enchantments needs the registry, so this is read on the main thread. */
    private final int[] hotbarEfficiency;
    private final MovementOptions options;
    private final boolean canPlaceBlocks;
    private final int placedBlockBudget;
    private final int maxFallDamagePoints;
    private final int fatalFallBlocks;
    private final boolean canMlgWaterBucket;
    private final boolean boatAvailable;
    private final boolean ridingBoat;
    private final double minDescentTicksPerBlock;

    /**
     * Whether a fall with unlimited height is possible (falling into water, or a water bucket MLG). Needed to redo the
     * lower bound of descent when the fall damage allowance changes ({@link #minDescentTicksPerBlock(int)}).
     */
    private final boolean deepFallPossible;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final int minSection;

    /** If {@code null}, cells are not remembered ({@link #forGraphBuild}). */
    private final Long2LongOpenHashMap cells;

    /**
     * Check results per block state. {@link BlockState} is an immutable global singleton, so cells with the same state
     * give the same result regardless of coordinates. Only a few dozen states actually appear in a cave, yet collision
     * resolution and the mining speed comparison across every hotbar slot were running per cell.
     */
    private final Reference2LongOpenHashMap<BlockState> states = new Reference2LongOpenHashMap<>();

    // Pathfinding block lookups are strongly localized within a single chunk, so just remembering the previous chunk
    // skips the hash lookup for most accesses. null (unloaded) is remembered as-is too, to avoid looking it up again.
    private LevelChunk cachedChunk;
    private long cachedChunkKey = ChunkPos.INVALID_CHUNK_POS;

    private ChunkView(Long2ObjectMap<LevelChunk> chunks, int totalChunksInBounds, SearchBounds bounds,
                      ItemStack[] hotbar, int[] hotbarEfficiency, MovementOptions options, boolean canPlaceBlocks,
                      int placedBlockBudget, int maxFallDamagePoints, int fatalFallBlocks,
                      boolean canMlgWaterBucket, boolean boatAvailable, boolean ridingBoat,
                      boolean deepFallPossible, double minDescentTicksPerBlock, int minBuildHeight,
                      int maxBuildHeight, int minSection, boolean cacheCells) {
        this.deepFallPossible = deepFallPossible;
        this.chunks = chunks;
        this.totalChunksInBounds = totalChunksInBounds;
        this.bounds = bounds;
        this.hotbar = hotbar;
        this.hotbarEfficiency = hotbarEfficiency;
        this.options = options;
        this.canPlaceBlocks = canPlaceBlocks;
        this.placedBlockBudget = placedBlockBudget;
        this.maxFallDamagePoints = maxFallDamagePoints;
        this.fatalFallBlocks = fatalFallBlocks;
        this.canMlgWaterBucket = canMlgWaterBucket;
        this.boatAvailable = boatAvailable;
        this.ridingBoat = ridingBoat;
        this.minDescentTicksPerBlock = minDescentTicksPerBlock;
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
        this.minSection = minSection;
        this.cells = cacheCells ? new Long2LongOpenHashMap(CELL_CACHE_CAPACITY, 0.75f) : null;
        if (cacheCells) {
            this.cells.defaultReturnValue(NOT_CACHED);
        }
        // A real block state always has PRESENT set, so 0 (= ABSENT) can be used as the "not computed" sentinel
        this.states.defaultReturnValue(CellData.ABSENT);
    }

    /**
     * Whether moves crossing water by boat may be offered: either a boat is in the inventory, or <b>you are riding one
     * now</b>.
     *
     * <p>While riding, the boat is an entity rather than an item, so looking only at the inventory would mean that right
     * after guiding "place a boat at the shore", the moment you do so the premise vanishes and the route gets rebuilt.
     */
    public static boolean boatAvailable(Player player) {
        return ridingBoat(player)
                || hasItem(GameCompat.inventory(player), stack -> stack.getItem() instanceof BoatItem);
    }

    /** Whether you are currently riding a boat. */
    public static boolean ridingBoat(Player player) {
        return player.getVehicle() instanceof Boat;
    }

    /**
     * Whether the inventory has a stack matching the condition. {@code Inventory#contains(Predicate)} is an overload
     * that only exists in 1.21.1, so it is replaced with a hand-written search that gives the same result on 1.20.1
     * (the policy is not to gate JDK/vanilla API differences that can be avoided without a version gate).
     */
    public static boolean hasItem(Inventory inventory, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (predicate.test(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** Main thread only. Collects only references to loaded chunks and a copy of the hotbar. */
    public static ChunkView capture(Level level, Player player, SearchBounds bounds, MovementOptions options) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        Long2ObjectOpenHashMap<LevelChunk> chunks =
                new Long2ObjectOpenHashMap<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // Unloaded chunks cannot be read at all. By not picking them up here, routes are naturally
                // cut off at the edge of the loaded area (they are treated as impassable cells).
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    chunks.put(GameCompat.chunkKey(chunkX, chunkZ), chunk);
                }
            }
        }

        // Before 1.21, enchantments are not registry Holders but the old model that passes static fields directly
        // under Enchantments to EnchantmentHelper (the field name changed from BLOCK_EFFICIENCY to EFFICIENCY in 1.20.5).
        // The vanilla API shape itself differs, so this is the one exception where a gate is placed in pathfinding/
        //? if >=1.21.2 {
        /*Holder<Enchantment> efficiency = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.EFFICIENCY);
        *///?} else if >=1.21 {
        Holder<Enchantment> efficiency = level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.EFFICIENCY);
        //?} else if >=1.20.5 {
        /*Enchantment efficiency = Enchantments.EFFICIENCY;
        *///?} else {
        /*Enchantment efficiency = Enchantments.BLOCK_EFFICIENCY;
        *///?}
        ItemStack[] hotbar = new ItemStack[Inventory.getSelectionSize()];
        int[] hotbarEfficiency = new int[hotbar.length];
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack stack = GameCompat.inventory(player).getItem(slot);
            hotbar[slot] = stack.copy();
            // ItemStack#getEnchantmentLevel added by NeoForge/Forge is not used. This layer must not depend on the
            // loader, and there is no need to pick up other mods dynamically rewriting enchantment values.
            // 1.20.1-forge/1.21.1-neoforge replaced getItemEnchantmentLevel with that dynamic value
            // (deprecated), so use getTagEnchantmentLevel, which returns the NBT value as-is. On Fabric the unmodified
            // vanilla API's getItemEnchantmentLevel returns the NBT value from the start, and 1.21.1-forge does not even
            // have getTagEnchantmentLevel (Forge and NeoForge patched it separately in 1.21), so
            // those two can keep getItemEnchantmentLevel. 1.20.5 to 1.20.6 came right after NBT was replaced by data
            // components, so no loader has getTagEnchantmentLevel, and NeoForge deprecates getItemEnchantmentLevel,
            // so the stored value is read directly from the data component
            //? if >=1.20.5 && <1.21 {
            /*hotbarEfficiency[slot] = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                    .getLevel(efficiency);
            *///?} else if (forge && <1.21) || neoforge {
            //? if >=1.19 {
            hotbarEfficiency[slot] = EnchantmentHelper.getTagEnchantmentLevel(efficiency, stack);
            //?} else {
            /*hotbarEfficiency[slot] = EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
            *///?}
            //?} else {
            /*hotbarEfficiency[slot] = EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
            *///?}
        }
        // Placeable blocks are counted across the <b>whole</b> inventory. Back when only the hotbar was checked,
        // carrying a stack in the inventory produced no bridge guidance, while a single block on the hotbar produced
        // a 64-block bridge. The requirement differs from limiting mining tools (hotbar) to the hotbar: tools must be
        // held to be used, but footing blocks can be moved to the hotbar before placing
        int placeableBlocks = countPlaceableBlocks(player);

        int maxFallDamagePoints = options.fallDamageToleranceEnabled()
                ? (int) (player.getHealth() / FALL_DAMAGE_HEALTH_FRACTION) : 0;
        // Vanilla fall damage is ceil(fall distance - SAFE_FALL_BLOCKS) points (half-heart units),
        // and you die if that is at least your health. Drops are whole blocks, so applying ceil to the health side is enough.
        // Computed independently of the fall damage setting: that one is "the height you may intentionally descend",
        // this one is "do you die if a jump misses", and jumps are generated regardless of the setting
        int fatalFallBlocks = ActionCosts.SAFE_FALL_BLOCKS + (int) Math.ceil(player.getHealth());
        // In ultraWarm dimensions (the Nether), placed water evaporates instantly, so an MLG placing a water bucket just
        // before landing is physically impossible. Allowing it without checking the dimension would put impossible falls on routes
        boolean waterEvaporates = GameCompat.waterEvaporates(level, player.blockPosition());
        boolean canMlgWaterBucket = options.fallDamageToleranceEnabled() && !waterEvaporates
                && hasItem(GameCompat.inventory(player), stack -> stack.getItem() == Items.WATER_BUCKET);
        // A boat in the inventory is ignored when boats are turned off in the settings. Already riding one is still
        // the physical starting state (paddling on to the shore is cheaper than getting out mid-water)
        boolean boatAvailable = options.boatUsable(boatAvailable(player));
        boolean ridingBoat = ridingBoat(player);

        // The lower bound of the descent heuristic is set by the largest drop that can actually be generated.
        // FALL_TO_WATER is generated only when there is water at the landing spot, and ultraWarm dimensions (the Nether)
        // have no water (placed water evaporates; BucketItem says so). With no water, no water bucket MLG, and
        // no fall damage allowed, falls stop at the safe height
        boolean deepFallPossible = !waterEvaporates || canMlgWaterBucket;
        double minDescentTicksPerBlock = descentBound(deepFallPossible, maxFallDamagePoints);

        // Creative does not consume placed blocks, so no budget applies (0 = unlimited). It can also be turned off in settings.
        //
        // The lower bound is 1 because 0 means "unlimited": if the reserve setting exceeds what you carry, setting 0
        // there would remove the limit entirely and make it looser instead. Falling back to a state where just one can be
        // used lets the relaxation ladder (the step that lifts the budget) take routes that need more. The reserve is not
        // built into canPlaceBlocks for the same reason: rather than getting stuck, it is fine to use the reserve
        boolean creative = GameCompat.abilities(player).instabuild;
        int placedBlockBudget = options.blockBudgetEnabled() && !creative
                ? Math.max(1, placeableBlocks - options.blockBudgetReserve())
                : 0;
        // Creative can place even with an empty inventory (any block can be taken from the inventory screen).
        // Back when only carried blocks were checked, arriving in the End without blocks generated not a single bridge,
        // so island-crossing routes could never come out in principle, and since the guidance showed nothing,
        // it just looked like "only island crossing fails"
        boolean canPlaceBlocks = options.bridgingEnabled() && (placeableBlocks > 0 || creative);

        int totalChunksInBounds = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency, options,
                canPlaceBlocks, placedBlockBudget,
                maxFallDamagePoints, fatalFallBlocks, canMlgWaterBucket, boatAvailable, ridingBoat,
                deepFallPossible, minDescentTicksPerBlock, GameCompat.minBuildHeight(level),
                GameCompat.maxBuildHeight(level), GameCompat.minSection(level), true);
    }

    /**
     * Total number of blocks in the inventory usable as footing. Looks at <b>every slot</b>, not just the hotbar:
     * they can be moved to the hotbar before placing, so not counting them would make the budget stricter than reality.
     *
     * <p>Also used by the HUD to warn about shortages. <b>It recounts on the spot rather than remembering the value from
     * search time</b>: routes are cached keyed by destination, so using or picking up blocks along the way does not
     * re-plan (the same known trap as boats and rockets).
     */
    public static int countPlaceableBlocks(Player player) {
        Inventory inventory = GameCompat.inventory(player);
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isBuildingBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Whether this view holds the chunk (loaded and within range). */
    public boolean chunkLoaded(int chunkX, int chunkZ) {
        return chunks.containsKey(GameCompat.chunkKey(chunkX, chunkZ));
    }

    public int loadedChunksInBounds() {
        return chunks.size();
    }

    public int totalChunksInBounds() {
        return totalChunksInBounds;
    }

    /**
     * An independent view using the same chunk references with only the caches recreated. <b>When running two searches
     * at once, always pass this to one of them</b> ({@code PathfindingExecutor#submitWithDeepFallback}).
     *
     * <p>Only {@code chunks} may be shared: once {@link #capture} has built it, it is read-only, so lookups from
     * multiple threads do not break it. On the other hand, {@code cells}, {@code states}, and the chunk memo are all
     * rewritten during search, so sharing them corrupts the {@code Long2LongOpenHashMap} internally, causing an
     * {@code ArrayIndexOutOfBoundsException}, or twists keys and values so it <b>reads blocks from a different
     * chunk</b> (the memo writes {@code cachedChunkKey} and {@code cachedChunk} separately).
     *
     * <p>The hotbar is copied because {@link ItemStack} may hold an internal lazy cache when resolving mining speed.
     * It is only 9 slots, so it is practically free.
     */
    public ChunkView forParallelSearch() {
        ItemStack[] copiedHotbar = new ItemStack[hotbar.length];
        for (int slot = 0; slot < hotbar.length; slot++) {
            copiedHotbar[slot] = hotbar[slot].copy();
        }
        return new ChunkView(chunks, totalChunksInBounds, bounds, copiedHotbar, hotbarEfficiency.clone(),
                options, canPlaceBlocks, placedBlockBudget, maxFallDamagePoints, fatalFallBlocks,
                canMlgWaterBucket, boatAvailable, ridingBoat, deepFallPossible, minDescentTicksPerBlock,
                minBuildHeight, maxBuildHeight, minSection, true);
    }

    /**
     * A view owned by the thread building the navigation graph. Unlike {@link #forParallelSearch}, it <b>does not
     * remember cells</b>.
     *
     * <p>The graph sweeps the whole window (thousands of sections), so remembering would make one view hold a table of
     * hundreds of thousands to millions of entries, multiplied by the number of parallel workers. Move generation
     * remembers cells per leg on the search side ({@code MemoCells}), so remembering here would not make it faster.
     */
    public ChunkView forGraphBuild() {
        ItemStack[] copiedHotbar = new ItemStack[hotbar.length];
        for (int slot = 0; slot < hotbar.length; slot++) {
            copiedHotbar[slot] = hotbar[slot].copy();
        }
        return new ChunkView(chunks, totalChunksInBounds, bounds, copiedHotbar, hotbarEfficiency.clone(),
                options, canPlaceBlocks, placedBlockBudget, maxFallDamagePoints, fatalFallBlocks,
                canMlgWaterBucket, boatAvailable, ridingBoat, deepFallPossible, minDescentTicksPerBlock,
                minBuildHeight, maxBuildHeight, minSection, false);
    }

    /**
     * A view using the same chunk references with only digging disabled. Derived so that {@link #capture} need not be
     * called again.
     *
     * <p>Cell check results depend on whether digging is allowed, so the cache is not shared but recreated. Chunk
     * references and the hotbar are read-only and may be shared. The source and derived views must not be used
     * concurrently on different threads ({@link CellSource}'s thread contract still applies).
     */
    public ChunkView withoutDigging() {
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency,
                options.withoutDigging(), canPlaceBlocks, placedBlockBudget, maxFallDamagePoints, fatalFallBlocks,
                canMlgWaterBucket, boatAvailable, ridingBoat, deepFallPossible, minDescentTicksPerBlock,
                minBuildHeight, maxBuildHeight, minSection, true);
    }

    /**
     * Derives the per-block lower bound of descent from the fall damage allowance. If drop height is unlimited, there
     * is no choice but to relax it down to the terminal velocity lower bound.
     */
    private static double descentBound(boolean deepFallPossible, int maxFallDamagePoints) {
        return deepFallPossible ? ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK
                : ActionCosts.descentBoundForMaxDrop(ActionCosts.SAFE_FALL_BLOCKS + maxFallDamagePoints);
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return descentBound(deepFallPossible, maxFallDamagePoints);
    }

    /**
     * Whether it can be placed as footing to cross a gap. Besides being standable ({@code standable}), it must stay put
     * even when placed in mid-air — sand and gravel fall the moment they are placed, so they cannot be footing.
     */
    private static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        long flags = CellData.flagsOf(blockItem.getBlock().defaultBlockState());
        return CellData.standable(flags) && !CellData.fallingBlock(flags) && !CellData.unresolvedShape(flags);
    }

    @Override
    public boolean canPlaceBlocks() {
        return canPlaceBlocks;
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return options.bridgingEnabled();
    }

    @Override
    public int placedBlockBudget() {
        return placedBlockBudget;
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return options.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return options.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return options.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return options.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return options.maxSubmergedTicks();
    }

    @Override
    public boolean jumpGapEnabled() {
        return options.jumpGapEnabled();
    }

    @Override
    public int maxFallDamagePoints() {
        return maxFallDamagePoints;
    }

    @Override
    public int fatalFallBlocks() {
        return fatalFallBlocks;
    }

    @Override
    public boolean avoidRiskyJumps() {
        return options.avoidRiskyJumps();
    }

    @Override
    public boolean strictLimits() {
        return options.strictLimits();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return minDescentTicksPerBlock;
    }

    @Override
    public boolean canMlgWaterBucket() {
        return canMlgWaterBucket;
    }

    @Override
    public boolean boatAvailable() {
        return boatAvailable;
    }

    @Override
    public boolean ridingBoat() {
        return ridingBoat;
    }

    @Override
    public RouteProfile routeProfile() {
        return options.routeProfile();
    }

    @Override
    public boolean swimmingEnabled() {
        return options.swimmingEnabled();
    }

    /** Computed and cached on first access. */
    @Override
    public long cell(int x, int y, int z) {
        if (cells == null) {
            return computeCell(x, y, z);
        }
        long key = BlockPos.asLong(x, y, z);
        long cached = cells.get(key);
        if (cached != NOT_CACHED) {
            return cached;
        }
        long computed = computeCell(x, y, z);
        cells.put(key, computed);
        return computed;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    /**
     * One above the {@code MOTION_BLOCKING} heightmap. {@code canSeeSky} (skylight 15) goes through the light engine,
     * so it cannot be touched from a worker thread, but the heightmap is just a bit array the chunk holds, so it can be
     * read from here under the same conditions as block states.
     *
     * <p>{@code MOTION_BLOCKING} is a heightmap sent to the client, so it is always filled for loaded chunks
     * (if ungenerated, {@code getHeight} would rebuild it, but only server-side chunks get there).
     */
    @Override
    public int openSkyY(int x, int z) {
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
    }

    /**
     * For when you want the same value as {@link #openSkyY} for a single column <b>before</b> building a
     * {@link ChunkView}. Unloaded columns give {@link Integer#MAX_VALUE} (if it cannot be asserted to be under open sky,
     * it is not treated as the surface).
     *
     * <p>Some situations need the surface height before deciding the search range: what counts as the surface decides
     * the search box itself, so a {@link ChunkView} built from the box is too late. Main thread only.
     */
    public static int openSkyY(Level level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    private long computeCell(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return CellData.ABSENT;
        }
        BlockState state = blockStateAt(x, y, z);
        if (state == null) {
            return CellData.ABSENT;
        }
        long cached = states.getLong(state);
        if (cached != CellData.ABSENT) {
            return cached;
        }
        long computed = computeState(state);
        states.put(state, computed);
        return computed;
    }

    private long computeState(BlockState state) {
        long flags = CellData.flagsOf(state);
        double digTicks;
        if (CellData.occupiableWithoutDigging(flags)) {
            digTicks = 0.0;
        } else if (CellData.lava(flags) || CellData.hazard(flags) || CellData.unresolvedShape(flags)
                || !options.diggingEnabled()) {
            // Liquids are not dig targets, so impassability is expressed as bare-hand digTicks.
            // Hazard cells (fire, powder snow, portals, etc.) are not dug through either.
            // Likewise when diggingEnabled=false, the option to "dig in" itself is removed.
            digTicks = ActionCosts.INFEASIBLE;
        } else {
            // The falling-block chain cost is not added here (AStarPathfinder scans once from the top of the required cells.
            // Adding the chain per cell here would double-count between adjacent required cells).
            digTicks = DigCost.compute(hotbar, hotbarEfficiency, state);
        }
        return CellData.withDigTicks(flags, digTicks);
    }

    private BlockState blockStateAt(int x, int y, int z) {
        if (y < minBuildHeight || y >= maxBuildHeight) {
            return null;
        }
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }
        LevelChunkSection section = chunk.getSections()[(y >> 4) - minSection];
        // Up to 1.17, empty sections are held as null (from 1.18 on, a section is always present even if empty)
        return section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
    }

    private LevelChunk chunkAt(int chunkX, int chunkZ) {
        long key = GameCompat.chunkKey(chunkX, chunkZ);
        if (key == cachedChunkKey) {
            return cachedChunk;
        }
        LevelChunk chunk = chunks.get(key);
        cachedChunkKey = key;
        cachedChunk = chunk;
        return chunk;
    }
}
