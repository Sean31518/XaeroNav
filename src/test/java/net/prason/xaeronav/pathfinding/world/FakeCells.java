package net.prason.xaeronav.pathfinding.world;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * A {@link CellSource} for tests. Lets terrain be written as text.
 *
 * <p>The production {@link ChunkView} can't be created without a {@code Level} and {@code Player}, and
 * {@link CellData#flagsOf} requires a {@code BlockState} (i.e. Minecraft's registries must be bootstrapped).
 * Here the flags are assembled directly so the search core can run without the registries.
 *
 * <p>Terrain is written with the top row at the highest Y. Reading it in the same orientation as it actually looks
 * makes it harder to make mistakes when transcribing "I want this path" into a test.
 *
 * <pre>{@code
 * FakeCells.of(0, 60, 0, """
 *     ...
 *     ...
 *     ###""");   // y=60 is the floor, y=61/62 are air
 * }</pre>
 */
public final class FakeCells implements CellSource {

    /** Air. Passable without digging. */
    public static final char AIR = '.';
    /** Stone. Passable if dug (dig cost is {@link #STONE_DIG_TICKS}). */
    public static final char STONE = '#';
    /**
     * A solid that digs at the speed of dirt/grass with a shovel ({@link #SOFT_DIG_TICKS}). <b>This is what you actually dig
     * in normal play while walking on the surface</b>, so the dig-vs-detour balance is measured with this.
     */
    public static final char SOFT = 'D';
    /** Undiggable bedrock. */
    public static final char BEDROCK = 'B';
    /** Water. Passable without footing. */
    public static final char WATER = '~';
    /** Lava. */
    public static final char LAVA = 'L';
    /** Soul sand. Footing like stone, but moving across it slows down to {@link #SOUL_SAND_SPEED_FACTOR}. */
    public static final char SOUL_SAND = 'S';
    /** Magma block. Footing, but crossing it requires sneaking (running on it burns). */
    public static final char MAGMA = 'M';
    /** Ordinary vines. Climbable, and replaceable so blocks can be placed. */
    public static final char VINE = 'V';
    /** Nether weeping/twisting vines. Climbable, but <b>not replaceable</b>, so blocks can't be placed. */
    public static final char NETHER_VINE = 'N';
    /** Ladder. Can be grabbed to move up and down. */
    public static final char LADDER = 'H';
    /** Cobweb. No collision box so it can be walked through, but it slows you heavily while passing. */
    public static final char COBWEB = 'W';
    /** Treated as out of range / unloaded ({@link CellData#ABSENT}). */
    public static final char ABSENT = '?';

    public static final double STONE_DIG_TICKS = 40.0;

    /**
     * Ticks to dig through dirt/grass with an iron shovel. Hardness 0.6, speed 6, correct tool, so
     * {@code 0.6 × 30/6 + }{@link ActionCosts#DIG_OVERHEAD_TICKS} = 25.0.
     *
     * <p>{@link #STONE_DIG_TICKS} (40) is about the weight of digging hardness 1.5 with a wooden/stone pickaxe, and
     * <b>is not the value for digging ground or embankments</b>. Measuring the dig/detour branch with 40 skews the balance
     * nearly twice as far toward detouring, so tests examining that branch must use this one.
     */
    public static final double SOFT_DIG_TICKS = 0.6 * 30.0 / 6.0 + ActionCosts.DIG_OVERHEAD_TICKS;

    /** Exactly vanilla's {@code speedFactor(0.4F)} for {@code Blocks.SOUL_SAND}. */
    public static final float SOUL_SAND_SPEED_FACTOR = 0.4f;

    /** Movement speed while sneaking (matches how {@code CellData} handles magma blocks). */
    public static final float MAGMA_SPEED_FACTOR = 0.3f;

    private static final int NO_OVERRIDE = Integer.MIN_VALUE;

    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap();
    /**
     * Per-column runs (a sequence of {@code [bottom, top, symbol]}, ascending from the bottom). Only columns written with {@link #setColumn} have them.
     * Holding a layout larger than 1000 blocks square cell by cell in {@link #cells} would take tens of GB, so wide terrain is held here.
     */
    private final Long2ObjectOpenHashMap<short[]> columns = new Long2ObjectOpenHashMap<>();
    private SearchBounds bounds;
    private boolean canPlaceBlocks;
    /** true to match the config default. Only tests that want to forbid jumping turn it off explicitly. */
    private boolean jumpGapEnabled = true;
    /** true to match the config default. Only tests that want to forbid lava bridges turn it off explicitly. */
    private boolean lavaBridgingEnabled = true;
    private int maxBridgeRunBlocks;
    private int maxLavaBridgeRunBlocks;
    private int maxVoidBridgeRunBlocks;
    /** Default is 0 (unlimited). Only tests probing the diving limit set it explicitly. */
    private int maxSubmergedTicks;
    private double minDescentTicksPerBlock = ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
    /** Default 0 = unlimited. Only tests that want the inventory block count to apply set it explicitly. */
    private int placedBlockBudget;
    /** If null, follows {@code canPlaceBlocks}. Set only to model "allowed by config but not carried". */
    private Boolean bridgingAllowedBySettings;
    /** 0 to match the config default (= painful falls are not offered). */
    private int maxFallDamagePoints;
    /** true to match the config default (= don't jump over void or fatal drops). */
    private boolean avoidRiskyJumps = true;
    private boolean strictLimits;
    /** Equivalent to full health (20). Matches the most common state in practice. */
    private int fatalFallBlocks = ActionCosts.SAFE_FALL_BLOCKS + 20;
    private boolean canMlgWaterBucket;
    /** Default is false. Only tests that want the player to carry a boat set it explicitly. */
    private boolean boatAvailable;
    /** Default is false. Only tests that want to start while riding set it explicitly. */
    private boolean ridingBoat;
    /** Default for unwritten coordinates. Making it empty (passableEmpty) means only rows with floors written become terrain. */
    private long fill = air();
    /**
     * The fixed value returned by {@link #openSkyY}. In dimensions with a ceiling (the Nether), the real {@code ChunkView}
     * returns a heightmap-derived ceiling that can be above the search range; this reproduces that.
     */
    private int openSkyYOverride = NO_OVERRIDE;

    private FakeCells() {
        cells.defaultReturnValue(Long.MIN_VALUE);
    }

    public static FakeCells empty(SearchBounds bounds) {
        FakeCells fake = new FakeCells();
        fake.bounds = bounds;
        return fake;
    }

    /**
     * Build terrain from a text cross-section. Stacks it in the {@code originX}/{@code originZ} column
     * so the bottom row is at {@code baseY} (1 character = 1 block along X).
     */
    public static FakeCells of(int originX, int baseY, int originZ, String diagram) {
        List<String> rows = new ArrayList<>(List.of(diagram.stripTrailing().split("\n")));
        int height = rows.size();
        int width = rows.stream().mapToInt(String::length).max().orElse(1);

        FakeCells fake = new FakeCells();
        fake.bounds = new SearchBounds(originX - 32, baseY - 32, originZ - 32,
                originX + width + 32, baseY + height + 32, originZ + 32);
        for (int row = 0; row < height; row++) {
            // Read rows upside down so the top row is the highest Y
            int y = baseY + (height - 1 - row);
            String line = rows.get(row);
            for (int col = 0; col < line.length(); col++) {
                fake.set(originX + col, y, originZ, line.charAt(col));
            }
        }
        return fake;
    }

    /** Write column {@code x,z} as runs ({@code runs} repeats {@code [bottom, top, symbol]}, ascending from the bottom). {@link #set} takes precedence. */
    public FakeCells setColumn(int x, int z, short[] runs) {
        columns.put(BlockPos.asLong(x, 0, z), runs);
        return this;
    }

    public FakeCells set(int x, int y, int z, char symbol) {
        cells.put(BlockPos.asLong(x, y, z), flagsFor(symbol));
        return this;
    }

    /** Extend the same cross-section thickly along {@code z}. Needed to test diagonal moves and jumps. */
    public FakeCells extrudeZ(int fromZ, int toZ) {
        Long2LongOpenHashMap copy = new Long2LongOpenHashMap(cells);
        copy.long2LongEntrySet().forEach(entry -> {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            for (int z = fromZ; z <= toZ; z++) {
                cells.put(BlockPos.asLong(pos.getX(), pos.getY(), z), entry.getLongValue());
            }
        });
        return this;
    }

    /** Fill unwritten coordinates with {@code symbol} (default is air). */
    public FakeCells fillWith(char symbol) {
        this.fill = flagsFor(symbol);
        return this;
    }

    public FakeCells canPlaceBlocks(boolean value) {
        this.canPlaceBlocks = value;
        return this;
    }

    public FakeCells jumpGapEnabled(boolean value) {
        this.jumpGapEnabled = value;
        return this;
    }

    /** Pin {@code openSkyY}. Used to model a bedrock ceiling outside the search range. */
    public FakeCells openSkyYOverride(int value) {
        this.openSkyYOverride = value;
        return this;
    }

    /** Length of consecutive bridge allowed (blocks). The default 0 is unlimited. */
    public FakeCells maxBridgeRunBlocks(int value) {
        this.maxBridgeRunBlocks = value;
        return this;
    }

    /** Length of bridge allowed over lava (blocks). With the default 0, only {@code maxBridgeRunBlocks} applies. */
    public FakeCells maxLavaBridgeRunBlocks(int value) {
        this.maxLavaBridgeRunBlocks = value;
        return this;
    }

    /** Length of bridge allowed over bottomless void (blocks). With the default 0, only {@code maxBridgeRunBlocks} applies. */
    public FakeCells maxVoidBridgeRunBlocks(int value) {
        this.maxVoidBridgeRunBlocks = value;
        return this;
    }

    /** Time (ticks) the head may stay underwater continuously. The default 0 is unlimited. */
    public FakeCells maxSubmergedTicks(int value) {
        this.maxSubmergedTicks = value;
        return this;
    }

    public FakeCells lavaBridgingEnabled(boolean value) {
        this.lavaBridgingEnabled = value;
        return this;
    }

    public FakeCells avoidRiskyJumps(boolean value) {
        this.avoidRiskyJumps = value;
        return this;
    }

    public FakeCells strictLimits(boolean value) {
        this.strictLimits = value;
        return this;
    }

    public FakeCells fatalFallBlocks(int value) {
        this.fatalFallBlocks = value;
        return this;
    }

    public FakeCells maxFallDamagePoints(int value) {
        this.maxFallDamagePoints = value;
        return this;
    }

    public FakeCells canMlgWaterBucket(boolean value) {
        this.canMlgWaterBucket = value;
        return this;
    }

    public FakeCells boatAvailable(boolean value) {
        this.boatAvailable = value;
        return this;
    }

    /** Start the search while riding. Also set {@link #boatAvailable}. */
    public FakeCells ridingBoat(boolean value) {
        this.ridingBoat = value;
        return this;
    }

    public FakeCells bounds(SearchBounds value) {
        this.bounds = value;
        return this;
    }

    private static final long[] FLAGS = new long[128];

    static {
        for (char c : new char[] {AIR, STONE, SOFT, BEDROCK, WATER, LAVA, SOUL_SAND, MAGMA, VINE, NETHER_VINE, LADDER,
                COBWEB, ABSENT}) {
            FLAGS[c] = flagsFor(c);
        }
    }

    private static long flagsFor(char symbol) {
        return switch (symbol) {
            case AIR -> air();
            // An ordinary solid that is passable if dug. It is also footing before being dug
            case STONE -> CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, STONE_DIG_TICKS);
            case SOFT -> CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, SOFT_DIG_TICKS);
            case BEDROCK -> CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, Double.POSITIVE_INFINITY);
            // Water has no collision box so it isn't footing, but the body can occupy it without digging
            case WATER -> CellData.withDigTicks(CellData.PRESENT | CellData.WATER, 0.0);
            case LAVA -> CellData.withDigTicks(CellData.PRESENT | CellData.LAVA, Double.POSITIVE_INFINITY);
            case SOUL_SAND -> CellData.withSpeedFactor(
                    CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, STONE_DIG_TICKS),
                    SOUL_SAND_SPEED_FACTOR);
            case MAGMA -> CellData.withSpeedFactor(
                    CellData.withDigTicks(
                            CellData.PRESENT | CellData.STANDABLE | CellData.SNEAK_REQUIRED, STONE_DIG_TICKS),
                    MAGMA_SPEED_FACTOR);
            case LADDER -> CellData.withDigTicks(CellData.PRESENT | CellData.CLIMBABLE, 0.0);
            case COBWEB -> CellData.withDigTicks(
                    CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.COBWEB, STONE_DIG_TICKS);
            case VINE -> CellData.withDigTicks(CellData.PRESENT | CellData.PASSABLE_EMPTY
                    | CellData.CLIMBABLE | CellData.REPLACEABLE, 0.0);
            case NETHER_VINE -> CellData.withDigTicks(
                    CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.CLIMBABLE, 0.0);
            case ABSENT -> CellData.ABSENT;
            default -> throw new IllegalArgumentException("Unknown terrain symbol: " + symbol);
        };
    }

    private static long air() {
        return CellData.withDigTicks(
                CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.REPLACEABLE, 0.0);
    }

    @Override
    public long cell(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return CellData.ABSENT;
        }
        long value = cells.get(BlockPos.asLong(x, y, z));
        if (value != Long.MIN_VALUE) {
            return value;
        }
        short[] runs = columns.get(BlockPos.asLong(x, 0, z));
        if (runs != null) {
            for (int i = 0; i < runs.length && runs[i] <= y; i += 3) {
                if (y <= runs[i + 1]) {
                    return FLAGS[runs[i + 2]];
                }
            }
        }
        return fill;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    @Override
    public boolean canPlaceBlocks() {
        return canPlaceBlocks;
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return bridgingAllowedBySettings == null ? canPlaceBlocks : bridgingAllowedBySettings;
    }

    /**
     * Represents "allowed by config, but no placeable blocks are carried".
     * Use together with {@code canPlaceBlocks(false)} (by default it follows {@code canPlaceBlocks}).
     */
    public FakeCells bridgingAllowedBySettings(boolean value) {
        this.bridgingAllowedBySettings = value;
        return this;
    }

    @Override
    public int placedBlockBudget() {
        return placedBlockBudget;
    }

    /** Number of footing blocks that may be placed over the whole path. Default 0 = unlimited (the old behavior that ignores inventory). */
    public FakeCells placedBlockBudget(int value) {
        this.placedBlockBudget = value;
        return this;
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return lavaBridgingEnabled;
    }

    @Override
    public int maxBridgeRunBlocks() {
        return maxBridgeRunBlocks;
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return maxLavaBridgeRunBlocks;
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return maxVoidBridgeRunBlocks;
    }

    @Override
    public int maxSubmergedTicks() {
        return maxSubmergedTicks;
    }

    /** Default is "a floor safe everywhere". Only tests trying a tightened version override it. */
    @Override
    public double minDescentTicksPerBlock() {
        return minDescentTicksPerBlock;
    }

    public FakeCells minDescentTicksPerBlock(double value) {
        this.minDescentTicksPerBlock = value;
        return this;
    }

    @Override
    public boolean jumpGapEnabled() {
        return jumpGapEnabled;
    }

    @Override
    public int maxFallDamagePoints() {
        return maxFallDamagePoints;
    }

    @Override
    public boolean avoidRiskyJumps() {
        return avoidRiskyJumps;
    }

    @Override
    public boolean strictLimits() {
        return strictLimits;
    }

    @Override
    public int fatalFallBlocks() {
        return fatalFallBlocks;
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

    /**
     * The production {@link ChunkView} looks up the heightmap, but here there is only terrain, so the column is scanned from the top.
     * What blocks the head is any non-air cell, <b>water included</b>. This matches the {@code MOTION_BLOCKING} predicate used in production,
     * {@code blocksMotion() || !getFluidState().isEmpty()}, which counts fluids
     * (on the sea, "one above the water surface" is returned).
     */
    @Override
    public int openSkyY(int x, int z) {
        if (openSkyYOverride != NO_OVERRIDE) {
            return openSkyYOverride;
        }
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            long flags = cell(x, y, z);
            if (CellData.present(flags) && !CellData.passableEmpty(flags)) {
                return y + 1;
            }
        }
        return bounds.minY();
    }
}
