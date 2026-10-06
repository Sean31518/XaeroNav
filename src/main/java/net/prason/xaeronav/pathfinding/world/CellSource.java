package net.prason.xaeronav.pathfinding.world;

import net.prason.xaeronav.pathfinding.cost.RouteProfile;

/**
 * Every query the search makes to the world.
 *
 * <p>Pathfinding ({@code AStarPathfinder} / {@link StanceFinder} /
 * {@code PathSafetyChecker}) only needs to know these four things about blocks, and needs nothing about
 * how chunks are fetched, how the hotbar is copied, or how dig costs are computed.
 *
 * <p>The only production implementation is {@link ChunkView}. Tests pass an implementation that assembles
 * {@link CellData} bit patterns directly ({@code ChunkView} can't be created without a {@code Level} and a
 * {@code Player}, so without this boundary the search core couldn't be driven from tests at all).
 *
 * <p><b>Thread contract:</b> an implementation may be owned by a single worker thread ({@link ChunkView}
 * keeps caches in mutable fields, so it must not be shared across threads).
 */
public interface CellSource {

    /**
     * Cell data at the given coordinates ({@link CellData} bit pattern).
     * Outside the search range or in unloaded chunks: {@link CellData#ABSENT}.
     */
    long cell(int x, int y, int z);

    boolean isInBounds(int x, int y, int z);

    SearchBounds bounds();

    /**
     * Whether moves that place footing (Bridge, Pillar) may be offered. If the inventory has no placeable
     * block at all, "place a block here" guidance is an instruction that can't be carried out, however short the route.
     */
    boolean canPlaceBlocks();

    /**
     * Whether placing-footing moves are allowed <b>by settings</b>. Doesn't look at the inventory.
     *
     * <p>This is separate from {@link #canPlaceBlocks()} because the two are opposite as to whether they may be
     * opened up to escape a dead end: "has no blocks" may be opened (once you know a bridge is needed, you can
     * go gather blocks), but <b>"refused by settings" must not be opened</b>. Merging them would show bridge
     * guidance even to players who turned placement off.
     *
     * <p>The default is {@link #canPlaceBlocks()} because implementations without a notion of inventory
     * (layer 2's {@code SurfaceCellSource}, the tests' {@code FakeCells}) have no such distinction.
     */
    default boolean bridgingAllowedBySettings() {
        return canPlaceBlocks();
    }

    /**
     * Total number of footing blocks that may be placed along the whole path. 0 means unlimited (same
     * convention as {@link #maxBridgeRunBlocks}).
     *
     * <p>Whereas {@link #maxBridgeRunBlocks} is a <b>run length</b>, this is <b>cumulative</b>. A run length
     * alone can't stop a route that "builds five 30-block bridges", and if the inventory runs out midway the
     * rest of the guidance can't be carried out; that's the cause of the user report "ended up having to dig
     * anyway, and digging would have been faster".
     *
     * <p>Like that one, it cuts at <b>move generation itself</b>. Expressed as a heavy price, A* expands
     * cheap edges first and would exhaust the surroundings before reaching for the bridge.
     */
    default int placedBlockBudget() {
        return 0;
    }

    /**
     * Whether gap-jumping moves may be offered. A missed landing means a fall, so it can be turned off in
     * settings for players who want to avoid that.
     */
    boolean jumpGapEnabled();

    /**
     * Whether moves that cross lava by placing footing may be offered. A single missed placement is death, so
     * this is used, with a large cost, as a last resort when there is no path avoiding lava at all.
     */
    boolean lavaBridgingEnabled();

    /**
     * How many blocks in a row a bridge built in midair may run. 0 means unlimited.
     *
     * <p>A value for cutting at <b>move generation itself</b> rather than by cost weight. Held down by weight,
     * A* expands cheap edges first, so it would "exhaust the surrounding terrain before reaching for the
     * bridge" and burn through the expanded-node budget (see {@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}).
     * If the edges aren't created, none of that cost arises.
     */
    int maxBridgeRunBlocks();

    /**
     * The length of that which may be built <b>over lava</b>. 0 means only {@link #maxBridgeRunBlocks} applies.
     *
     * <p>Kept separate from bridges over a gap because the consequences of a miss differ: over a gap you just
     * fall, over lava you die instantly. The bridge run length itself is shared, so the effective limit is the
     * smaller of the two.
     */
    default int maxLavaBridgeRunBlocks() {
        return maxBridgeRunBlocks();
    }

    /**
     * The length of that which may be built over <b>a bottomless void</b> (the End's void, caverns deeper than
     * the search range). 0 means only {@link #maxBridgeRunBlocks} applies.
     *
     * <p>It isn't lumped together with lava because how often each is encountered as terrain is completely
     * different: in the End almost every bridge falls under this, so tightening it as for lava would wipe out
     * travel between islands entirely. The consequence of a miss (instant death) is the same, so the weight
     * ({@code ActionCosts#VOID_BRIDGE_PENALTY_TICKS}) is set equal to lava's.
     */
    default int maxVoidBridgeRunBlocks() {
        return maxBridgeRunBlocks();
    }

    /**
     * How many ticks the head may stay underwater continuously. 0 means unlimited.
     *
     * <p>Like {@link #maxBridgeRunBlocks}, it cuts at <b>move generation itself</b>. Expressing drowning risk as
     * a heavy cost makes A* exhaust the surface detours before reaching for diving, burning expanded nodes
     * (the same story as the measurement recorded in {@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}).
     *
     * <p>The key is that the unit is <b>ticks</b>, not blocks. Underwater moves differ in speed by type
     * (swimming, rising, sinking, and digging differ several-fold), so counting blocks can't track air
     * correctly. Indeed, when it was held in blocks, merely rising from the bottom to the surface exceeded the
     * limit, and deep underwater destinations became <b>unreachable</b>.
     *
     * <p>This line represents a physical limit (one breath of air); the preference "dive as little as possible"
     * is handled by {@code ActionCosts#SUBMERGED_TRAVEL_PENALTY}. Packing both into this would erase genuinely
     * passable routes by however much it leans toward safety.
     *
     * <p>Only when the limit leaves no path at all does the caller drop the limit and search again
     * ("drowning risk is a last resort, but better than being stuck").
     */
    int maxSubmergedTicks();

    /**
     * How many points (0.5-heart units) of fall damage may be tolerated. 0 means no fall beyond the safe height is ever offered.
     * It depends on health, so it is determined from the player's state at the time the search is built.
     */
    int maxFallDamagePoints();

    /**
     * Drop (blocks) that kills you if you miss a jump and fall. Above a drop this large, "a miss is unrecoverable".
     * Like {@link #maxFallDamagePoints()}, determined from the player's health at the time the search is built.
     *
     * <p>Its role differs from that one. {@code maxFallDamagePoints} is "the height you may <b>intentionally</b>
     * drop", where landing is guaranteed even if it hurts. This is "what happens if you <b>miss</b> a jump", and
     * as a route it only concerns jumps that are meant to land.
     */
    int fatalFallBlocks();

    /**
     * Whether to avoid jumps over a bottomless void or over a drop that kills on a miss.
     *
     * <p>Even with avoidance on, the relaxation ladder ({@code PathfindingExecutor}) opens it only when no path
     * at all can be found; the user's intent is "go around if you can", not "never jump".
     */
    boolean avoidRiskyJumps();

    /**
     * Whether to keep the limits (bridge run length, diving, fall damage, risky jumps, inventory counts) even when
     * no path can be found. If the destination is unreachable without relaxing, the path is returned unreached
     * with {@code PathResult#limitsHeld} set.
     */
    boolean strictLimits();

    /**
     * The cheapest (ticks per block) descent move this search can generate.
     * Used as the lower bound of the descent component of {@link net.prason.xaeronav.pathfinding.astar.Heuristic}.
     *
     * <p>The terminal-velocity lower bound (0.2551) assumes "a fall of any depth can happen". The largest drop
     * actually generated depends on settings and dimension: {@code FALL_TO_WATER} is generated only when there
     * is water at the landing spot, and <b>ultraWarm dimensions (the Nether) have no water</b> (it evaporates
     * when placed). With fall damage tolerance off, the safe height of 3 blocks is the cap, and the bound
     * tightens to 4.392 (17x).
     */
    double minDescentTicksPerBlock();

    /**
     * Lower bound per block of descent when the fall damage allowance is replaced with {@code maxFallDamagePoints}.
     *
     * <p>Needed for the re-search ({@code PathfindingExecutor}) that relaxes the allowance step by step when stuck.
     * <b>Raising the allowance without also relaxing the bound makes the heuristic inadmissible</b>: the larger
     * the allowed drop, the closer the real cost per block gets to terminal velocity, i.e. <b>cheaper</b>, so a
     * bound computed from the original (tighter) drop can exceed the real cost.
     *
     * <p>By default it returns {@link #minDescentTicksPerBlock()} unchanged. For implementations that return a
     * drop-independent bound (terminal velocity) this is correct, and relaxing never goes below it.
     */
    default double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return minDescentTicksPerBlock();
    }

    /**
     * Whether moves that place a water bucket just before landing to cancel fall damage may be offered.
     * Without a water bucket, it's an instruction that can't be carried out.
     */
    boolean canMlgWaterBucket();

    /**
     * Whether the player has a boat. Used to decide whether moves crossing water by boat may be offered.
     *
     * <p>Like {@link #canMlgWaterBucket()}, it depends on the player's inventory, so layer 2, which doesn't
     * know the inventory, always returns false.
     */
    boolean boatAvailable();

    /**
     * Whether the player is currently riding a boat. Used to start the search in the "already aboard" state.
     *
     * <p>Kept separate from {@link #boatAvailable()} so the cost of getting in and out isn't paid twice. If the
     * cost of the one move that paddles off from shore were counted again while already aboard, then when little
     * water remains the guidance would turn into "cheaper to get out and swim".
     */
    default boolean ridingBoat() {
        return false;
    }

    /**
     * What the route is optimised for ({@link MovementOptions#routeProfile()}). The move generators read their
     * risk / placement / dig surcharges from it instead of the {@code ActionCosts} base constants.
     *
     * <p>The default is {@link RouteProfile#BALANCED} (exactly the base constants) so that implementations without
     * settings (the tests' terrain sources) keep today's prices. Wrappers must forward it.
     */
    default RouteProfile routeProfile() {
        return RouteProfile.BALANCED;
    }

    /**
     * Whether routes may enter water to swim or wade ({@link MovementOptions#swimmingEnabled()}).
     *
     * <p>Even when false, moves <b>out of</b> water stay allowed: a player who starts in water, or ends up there,
     * must be able to get back to land. Only stepping, falling or swimming from a dry cell into water is cut, at
     * move generation itself like the other limits (a heavy price would just burn expanded nodes around the shore).
     * When the exact destination is itself a water cell, the search swims as usual so the goal stays reachable.
     *
     * <p>Boats are governed separately by {@link #boatAvailable()}.
     */
    default boolean swimmingEnabled() {
        return true;
    }

    /**
     * The lowest Y in this column with nothing overhead. If {@code y >= openSkyY(x, z)}, the cell is under
     * open sky.
     *
     * <p>Judging "reached the surface" by height alone also counts caves under a ceiling as surface. Deep caves
     * have long horizontal tunnels, and it's not unusual for them to run above the default surface height (60).
     *
     * <p>Columns of unknown height (unloaded chunks) return {@link Integer#MAX_VALUE}. Since it can't be
     * asserted that they're under open sky, they must not be treated as surface.
     */
    int openSkyY(int x, int z);

    /**
     * The lowest Y in this column that may be considered "reached the surface". On land it equals {@link #openSkyY}.
     *
     * <p>Only water columns go one block lower. The MOTION_BLOCKING heightmap used by {@code openSkyY}
     * <b>includes fluids</b>, so at sea it points one above the water surface, i.e. outside the water. That's
     * air with no footing and can't be a node a swimming player stands on; in the open ocean, the "reach the
     * surface" relay search could in principle never succeed. If your face is out of the water, that already
     * counts as surface.
     */
    default int surfacedY(int x, int z) {
        int sky = openSkyY(x, z);
        if (sky == Integer.MAX_VALUE) {
            return sky;
        }
        return CellData.water(cell(x, sky - 1, z)) ? sky - 1 : sky;
    }
}
