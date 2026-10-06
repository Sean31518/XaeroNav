package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.Mount;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Handles Traverse/Diagonal/Ascend/Descend/Bridge.
 * Meant to be called from a worker thread; touches no Minecraft state other than {@link CellSource}.
 *
 * <p>No objects are created inside the search. Coordinates stay as {@code int}, and the result of
 * evaluating a neighbor is written straight into the node. {@link BlockPos} and lists of body-passage
 * cells are only created when assembling the final path (creating them during the search would produce
 * a dozen-odd pieces of garbage per expanded node every time, and worker-thread GC would stall the main thread too).
 */
public final class AStarPathfinder {

    /**
     * The primary cutoff condition. Using the expanded node count rather than time as the primary condition
     * means the same terrain with the same start and end always returns the same path. Cutting off by time
     * would make the reached point depend on the machine load at that moment, so the displayed path would change on every recalculation.
     */
    public static final int DEFAULT_MAX_EXPANDED_NODES = 100_000;

    /** Safety valve so a worker thread doesn't stay pinned on unexpectedly heavy terrain. Normally the expansion cap kicks in first. */
    public static final long DEFAULT_TIME_LIMIT_MILLIS = 2_000;

    /**
     * Weight applied to the heuristic (weighted A*). 1.0 is ordinary A*, which guarantees the shortest path.
     *
     * <p>At 1.0, on terrain where the real cost far exceeds the heuristic (digging, at dozens of ticks per stone cell,
     * or swimming, at 5.56 ticks/block against a lower bound of 3.56), A* nearly degenerates into Dijkstra and the expansion cap
     * runs out a few dozen blocks ahead. Applying a weight loses the shortest-path guarantee, but greatly extends the distance reachable with the same expansion count.
     * Since the design cuts off by expansion count, "same terrain, same path" still holds with a weight.
     *
     * <p>A weight breaks the heuristic's consistency, so the cost of an already-expanded node can improve later.
     * Expanded nodes are never put back into the open set ({@link PathNode#closed}), so each cell is expanded
     * at most once, and the path cost stays within this multiple of the optimum.
     */
    public static final double DEFAULT_HEURISTIC_WEIGHT = 1.5;

    /** Safety valve so evaluating one edge doesn't hang even on an abnormal tower of endlessly stacked falling blocks. */
    private static final int MAX_FALLING_CHAIN_SCAN = 16;

    /**
     * Tracks fallback endpoint candidates for when the goal can't be reached, using several metrics {@code h + g / coefficient} at once.
     * Picking the best point by the heuristic alone grabs dead ends (cliff edges and the like) that are merely
     * close to the goal. The smaller the coefficient, the more weight "distance actually travelled" gets.
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /**
     * Walking cost (ticks) one leg is allowed to gamble.
     *
     * <p><b>Without this, the more accurate the guide, the worse the path.</b> The {@link #COEFFICIENTS} score
     * {@code h + g/c} changes its answer when the guide is scaled by a constant: the effective tolerance for waste {@code w = g - (h0 - h)}
     * is {@code k/(k - 1/c)} (k being the ratio of guide to true value), which in Nether measurements
     * loosens to λ≈6 at k=0.8 and λ=3 at k=1.0 (a perfect guide). Once loosened, one leg gambles far ahead and
     * rides a bad partial path (seams 5 → 2, 1.03x → 1.34x).
     *
     * <p><b>Being an absolute value is the point.</b> Making it "some fraction of the greedily chosen cost"
     * grows the budget the farther the greedy answer is, so it stops working as a cap (measured: back to 2 seams, 1.22-1.31x).
     */
    private static final double FALLBACK_BUDGET_TICKS = 400.0;

    /**
     * Lower bound (as a fraction) on forward progress relative to the original choice, below which the cap may not be applied.
     *
     * <p><b>The cap exists to stop over-gambling; it doesn't license throwing away progress itself.</b>
     * Without this, on the thin Nether map the ladder's "142 blocks ahead (6889 ticks)" was
     * replaced with "25 blocks ahead (313 ticks)", and the leg was used up just filling in the lookahead
     * ({@code NetherThinMapGuideTest}). Raising it to 0.5 in turn stops the cap from working where it should,
     * and the improvement vanishes entirely (measured: 1.127x at k≈1, same as current).
     */
    private static final double MIN_CAPPED_PROGRESS_SHARE = 0.25;

    /** Whether to apply the cap. Turned off only so {@code NetherFallbackBenchTest} can compare against the old behavior. */
    static boolean fallbackBudgetEnabled = true;

    /** A provisional path that advances less than this is not worth presenting (blocks). */
    private static final double MIN_DIST_PATH = 5.0;

    /**
     * Forward progress (blocks) at which a within-budget candidate is accepted as "worth swapping in".
     * A value with some margin over {@code PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS} (12).
     *
     * <p><b>Without this, it crawls on digging terrain.</b> The budget is in ticks, so on terrain where a block
     * is expensive (digging is about 7x sprinting, bridging about 11x) the same budget only buys a few blocks.
     * Candidates that don't get that far are rejected, and the unrestricted ladder's choice is used as is.
     */
    private static final double MIN_USEFUL_PROGRESS = 16.0;

    /**
     * On flat ground, combinations of straight and diagonal moves can produce cost differences on the order of 10^-16.
     * Running re-propagation or decrease-key for improvements this small isn't worth it for the resulting path quality.
     */
    private static final double MIN_IMPROVEMENT = 0.01;

    /** Interval (in nodes) for checking the time and cancellation. Calls to the monotonic clock are thinned out internally too. */
    private static final int CHECK_INTERVAL_MASK = (1 << 6) - 1;

    private static final int[] CARDINAL_DX = {0, 1, 0, -1};
    private static final int[] CARDINAL_DZ = {-1, 0, 1, 0};
    private static final int[] DIAGONAL_DX = {1, 1, -1, -1};
    private static final int[] DIAGONAL_DZ = {1, -1, 1, -1};

    final CellSource view;

    /**
     * What this search optimises for ({@link CellSource#routeProfile()}). The move generators take their risk
     * surcharges from here rather than from the {@link ActionCosts} base constants. Read once: it is constant
     * during the search, and the generators would otherwise go through {@link MemoCells} for it on every edge.
     */
    final RouteProfile profile;

    /** {@link CellSource#swimmingEnabled()}. See {@link #entersWater}. */
    private final boolean swimmingEnabled;

    /**
     * Whether this search refuses moves into water ({@link #entersWater}). Off whenever {@link #swimmingEnabled} is on,
     * and also when the exact destination itself is a water cell: the user asked to go into the water there, and
     * refusing every entry would leave only a partial path ending on the shore (followed by a pointless round of
     * the relaxation ladder). Decided per search, since one pathfinder may run {@link #exhaust} or a search.
     */
    private boolean waterEntryBlocked;

    /** Extra ticks per dug cell from the profile ({@link RouteProfile#digSurchargeTicks()}). 0 for most profiles. */
    private final double digSurchargeTicks;
    private final int maxExpandedNodes;
    private final long timeLimitMillis;
    private final double heuristicWeight;
    /**
     * Hook for also using layer 1's cost-to-go. If {@code null}, {@link #node} uses
     * {@link Heuristic} (the default geometric lower bound) as is.
     */
    private final CostToGo costToGo;

    private @Nullable EdgeSink edgeSink;

    /** Has every generated edge reported to {@code sink} ({@link EdgeSink}). Call before starting the search. */
    void edgeSink(@Nullable EdgeSink sink) {
        this.edgeSink = sink;
    }

    /** Whether a node may be expanded. {@code null} means all. Narrowed when the nav graph collects edges for just one section. */
    @FunctionalInterface
    interface ExpandFilter {
        boolean expandable(int x, int y, int z);
    }

    private @Nullable ExpandFilter expandFilter;

    void expandFilter(@Nullable ExpandFilter filter) {
        this.expandFilter = filter;
    }

    /**
     * Uses every one of {@code seeds} ({@link BlockPos#asLong}) as a start and expands until the open nodes run out.
     * Receive edges via {@link #edgeSink} and narrow the expansion range via {@link #expandFilter}.
     *
     * <p>The goal column ({@code goalX}, {@code goalZ}) is taken so that bridges over the void are only laid
     * in the direction approaching the goal ({@link BuildMoves#addBridge}). Laying them in every direction would leave the graph
     * knowing about bridges the search never actually lays, and the remaining cost would be estimated optimistically.
     *
     * @return the number of nodes expanded; negative if cut off
     */
    int exhaust(long[] seeds, int count, int goalX, int goalZ, BooleanSupplier cancelled) {
        surfaceGoal = false;
        goalRadius = 0;
        this.goalX = goalX;
        goalY = Integer.MIN_VALUE / 2;
        this.goalZ = goalZ;
        lineTieBreak = false;
        waterEntryBlocked = !swimmingEnabled;
        for (int i = 0; i < count; i++) {
            long seed = seeds[i];
            PathNode node = node(BlockPos.getX(seed), BlockPos.getY(seed), BlockPos.getZ(seed), false);
            if (node.cost == 0.0 || node.closed) {
                continue;
            }
            node.cost = 0.0;
            node.combinedCost = 0.0;
            if (node.isOpen()) {
                open.update(node);
            } else {
                open.insert(node);
            }
        }
        int expanded = 0;
        while (!open.isEmpty()) {
            if ((expanded & CHECK_INTERVAL_MASK) == 0 && cancelled.getAsBoolean()) {
                return -1;
            }
            PathNode current = open.removeLowest();
            current.closed = true;
            expanded++;
            if (expandFilter == null || expandFilter.expandable(current.x, current.y, current.z)) {
                expand(current);
            }
        }
        return expanded;
    }

    /** Vertical scan and its per-column notes. Thrown away after one search. */
    final ColumnScans scans;

    /** Maximum consecutive bridge length (blocks). 0 means unlimited. {@link CellSource#maxBridgeRunBlocks()}. */
    final int maxBridgeRun;

    /**
     * Bridge length cap (blocks) that applies over lava. 0 means unlimited. This is the value after {@link RunCaps#effectiveLavaBridgeRun()}
     * has picked the stricter of it and {@link #maxBridgeRun}, so it can be compared on its own here.
     */
    final int maxLavaBridgeRun;

    /**
     * Bridge length cap (blocks) that applies over bottomless void. 0 means unlimited.
     * Like {@link #maxLavaBridgeRun}, {@link #maxBridgeRun} is already folded in.
     */
    final int maxVoidBridgeRun;

    /**
     * Whether this search discarded even one bridge move because of {@link #maxBridgeRun}, {@link #maxLavaBridgeRun}, or
     * {@link #maxVoidBridgeRun}.
     */
    private boolean bridgeRunCapBlocked;

    /** Called when {@link BuildMoves} or this class itself discards a move because of the consecutive bridge length cap. */
    void markBridgeRunCapBlocked() {
        bridgeRunCapBlocked = true;
    }

    /**
     * Total number of footing blocks that may be placed over the whole path. 0 means unlimited. {@link Tolerances#placedBlockBudget()}.
     *
     * <p>Whereas {@link #maxBridgeRun} is a consecutive length, this is cumulative: a path that lays many short bridges
     * isn't stopped by the consecutive length, but uses up just as much inventory.
     */
    final int placedBudget;

    /** Whether this search discarded even one placement move because of {@link #placedBudget}. */
    private boolean placedBudgetBlocked;

    /** Called when {@link BuildMoves} discards a move because of the total placement cap. */
    void markPlacedBudgetBlocked() {
        placedBudgetBlocked = true;
    }

    /**
     * Price (ticks) of the act of placing one footing block itself. The default is exactly
     * {@link ActionCosts#PLACE_BLOCK_AIM_TICKS}; <b>only when inventory is scarce</b> does
     * the caller pass a marked-up value (the {@code PathfindingExecutor} frugal re-plan).
     *
     * <p><b>The markup applies only to the placing action itself</b>, not to the cost of interrupting the run
     * ({@link ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}). What the frugal re-plan wants to
     * reduce is <b>the number of blocks used</b>, so it makes sense to mark up only the component proportional to that count.
     * And {@code PathfindingExecutor} can subtract the markup and compare the two paths only
     * because every placement is inflated by the same amount.
     *
     * <p><b>It must be a uniform value fixed at the start of the search.</b> Varying the price with the remaining count
     * would make the same edge's price depend on how it was reached, breaking A*'s assumptions ({@link PathNode#placedTotal} isn't
     * part of node identity, which makes it even more meaningless).
     *
     * <p>Marking up is the safe direction: it only raises the real cost, so both {@link Heuristic} and
     * the {@link CostToGo} guide remain lower bounds.
     *
     * <p>The route profile's factor ({@link RouteProfile#placementCostScale()}, never below 1.0) is folded in on top,
     * for the same reasons: uniform for the whole search, and only ever a markup.
     */
    final double placementCostTicks;

    /** Whether placement moves may be generated even with no blocks in the inventory. {@link Tolerances#placeWithoutBlocks()}. */
    final boolean placeWithoutBlocks;

    /** Whether this search discarded even one placement move because "no placeable blocks are held". */
    private boolean placementBlockedByEmptyInventory;

    /** Called when {@link BuildMoves} discards a placement move because the inventory ran out. */
    void markPlacementBlockedByEmptyInventory() {
        placementBlockedByEmptyInventory = true;
    }

    /** Number of placement steps {@link #trimUnfinishedPlacements} dropped from the end. For diagnostics. */
    private int trimmedPlacements;

    /** How many points of fall damage to tolerate. Can override {@link CellSource#maxFallDamagePoints()}. */
    final int maxFallDamagePoints;

    /**
     * Whether this search discarded a landing <b>solely</b> because of the fall damage tolerance.
     *
     * <p>Set only when a standable floor was readable and falling onto it would reach it, but the damage exceeded the tolerance.
     * Not set when discarded over the void ({@link #NOTHING_BELOW}) or unloaded terrain ({@link #UNREADABLE_BELOW}),
     * since no landing appears there however much the tolerance is relaxed, so searching again gives the same result.
     */
    private boolean fallDamageCapBlocked;

    /** Called when {@link GroundMoves} discards a landing because of the fall damage tolerance. */
    void markFallDamageCapBlocked(boolean blocked) {
        fallDamageCapBlocked |= blocked;
    }

    /** Whether to avoid jumps over the void or lethal drops. The inverse of {@link Tolerances#allowRiskyJumps()}. */
    final boolean avoidRiskyJumps;

    /**
     * Whether this search discarded even one jump because of {@link #avoidRiskyJumps}. If not,
     * allowing them and searching again won't change the result (same role as {@code bridgeRunCapBlocked}).
     */
    private boolean riskyJumpBlocked;

    /** Called when {@link GroundMoves} discards a move because of the risky-jump avoidance setting. */
    void markRiskyJumpBlocked() {
        riskyJumpBlocked = true;
    }

    /** How long (ticks) the head may stay submerged. 0 means unlimited. {@link CellSource#maxSubmergedTicks()}. */
    private final int maxSubmergedTicks;

    /** Whether this search discarded even one move because of {@link #maxSubmergedTicks}. */
    private boolean submergedRunCapBlocked;

    /** Cumulative values carried over from the previous leg (consecutive bridge length, placement count). */
    private Carryover carried = Carryover.NONE;

    /** Radius (blocks) within which the goal is treated as a region. 0 means an exact coordinate match. */
    /**
     * Vertical tolerance (blocks) of a region goal. Fixed wider, separately from the horizontal {@code goalRadius}.
     *
     * <p>Region goals are all points placed by the coarse layers, and their Y is only a <b>chunk representative height</b>, a linear interpolation,
     * or a raw estimate made when Xaero's detailed data couldn't be read. Constraining Y to the same width as horizontally makes
     * an intermediate target with a wrong estimate <b>unreachable in principle</b>, and discovering that uses up the node cap
     * every time (in-game log: the same relay point (920,584) came out in two variants, Y=66 and Y=81;
     * the 66 one burned 200k nodes all three times without reaching it, while the 81 one reached it in 28k nodes).
     *
     * <p>The width matches the vertical spacing at which layer 1 places intermediate targets ({@code CoarseRouter#WAYPOINT_VERTICAL_SPACING_BLOCKS});
     * Y differences finer than that aren't represented by layer 1 in the first place.
     *
     * <p><b>{@link #node}'s {@code radiusAllowance} discounts only by the horizontal radius, so the heuristic is not admissible
     * with respect to this vertical tolerance</b>: {@code h} remains even for nodes inside the goal region
     * (measured: 84% of leg ends had a Y offset, with {@code h} up to 137 ticks there, while the true remainder was 0).
     * <b>A fix that also discounts vertically was measured and rejected.</b> Path quality moved only ±0.01x on all 6 terrains
     * (terrains that improved and ones that got worse cancelled out), while the number of legs ending below the surface
     * doubled (3 → 8 out of 10 wide-area routes), and one more Nether route became unreachable. Aiming at the center Y
     * is itself what keeps leg ends on the surface so the next leg doesn't have to climb back up.
     */
    private static final int GOAL_VERTICAL_TOLERANCE_BLOCKS = 24;

    private int goalRadius;

    /** {@link CellSource#minDescentTicksPerBlock()}. Constant during the search, so it is read only once. */
    private final double minDescentPerBlock;

    /** Move candidate generation. Only one is created per search; see the {@link GroundMoves} class Javadoc. */
    private final GroundMoves groundMoves = new GroundMoves(this);
    private final WaterMoves waterMoves = new WaterMoves(this);
    private final BuildMoves buildMoves = new BuildMoves(this);
    private final MountMoves mountMoves = new MountMoves(this);

    /** The animal being ridden ({@link CellSource#mount()}), or {@code null}. Read once; constant for the search. */
    private final @Nullable Mount mount;

    private final NodeTable nodes = new NodeTable();

    /**
     * Nodes in the boating state. {@link PathNode#boating} is part of identity, so even at the same coordinates
     * riding and not riding are different nodes. {@link BlockPos#asLong} uses all 64 bits,
     * so no bit can be added to the key, and the table itself is split instead. Stays empty if no boat is held.
     */
    private final NodeTable boatNodes = new NodeTable();

    /** Nodes in the riding state ({@link PathNode#mounted}); split off for the same reason. Empty on foot. */
    private final NodeTable mountNodes = new NodeTable();

    /** Total number of nodes created (including those around expanded nodes). */
    private int createdNodes;
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();
    /**
     * Endpoint candidates. <b>Two sets of the same ladder</b>: the first half is the set limited to {@link #FALLBACK_BUDGET_TICKS},
     * the second half is the unrestricted set (= the same as before this change).
     *
     * <p>Keeping two sets is the point. With only the within-budget set, the <b>distant candidates in other directions</b>
     * held by the back of the unrestricted ladder (c=5, c=10) disappear. Those are the only escape when all the near candidates
     * are on half-built bridges, and dropping them brought back "not a single path comes out" in 3 of the guard tests.
     */
    private final PathNode[] bestSoFar = new PathNode[2 * COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[bestSoFar.length];

    int goalX;
    private int goalY;
    int goalZ;
    // If true, searches treating "any cell with y >= surfaceY" as the goal (for surface-first navigation).
    // Instead of digging straight up from directly below the goal, the condition is height alone rather than
    // a fixed single point, to allow paths that reach the surface from anywhere around.
    private boolean surfaceGoal;
    private int surfaceY;

    /**
     * Step width (ticks) used to shift the dequeue order "only on ties".
     *
     * <p>The nav line on flat ground becomes L-shaped or staircased because on flat, open terrain the octile {@link Heuristic} is
     * <b>exact</b>, so {@code g + h} is constant along the path, and
     * {@code f = g + weight*h = constant + (weight-1)*h}, i.e. <b>the move that reduces h fastest always wins</b>.
     * One diagonal move reduces h by {@code DIAGONAL} (5.040) while a straight move reduces it by only {@code STRAIGHT} (3.564), so
     * the search tips toward "use up all the diagonals, then go straight". The difference is {@code (1.5-1)*(5.040-3.564)=0.738 ticks/move}.
     *
     * <p><b>Never add the deviation from the straight line to f</b> (hit in-game on 2026-08-30). Adding it
     * keeps a "pull back to the line" force acting across the whole path, so every time terrain blocks the line it becomes a
     * <b>rectangular staircase that keeps going out and coming back</b>. In an in-game End leg the number of turns rose from 4 to 21.
     *
     * <p>Instead, f is <b>quantized</b> into steps of {@code LINE_TIE_BREAK_TICKS}, and only nodes falling into
     * the same step are dequeued in order of smallest deviation. The step (2.0) is larger than the 0.738 above, so the flat-ground bias
     * disappears, while genuine cost differences from detouring around terrain (3.564 or more per move) cross steps and <b>don't interfere at all</b>.
     */
    private static final double LINE_TIE_BREAK_TICKS = 2.0;

    /** Reordering width within a tie. Always kept smaller than {@link #LINE_TIE_BREAK_TICKS} so it never crosses a step. */
    private static final double LINE_TIE_BREAK_FRACTION = 0.9;

    /** Deviation at which the reordering is exactly half its width (where saturation starts to take effect, blocks). */
    private static final double LINE_TIE_BREAK_HALF_BLOCKS = 8.0;

    /** Straight line from start to goal (XZ plane). Used by {@link #orderingCost}. Disabled if its length is 0. */
    private int lineStartX;
    private int lineStartZ;
    private double lineDirX;
    private double lineDirZ;
    private boolean lineTieBreak;

    public AStarPathfinder(CellSource view) {
        this(view, SearchLimits.DEFAULT);
    }

    public AStarPathfinder(CellSource view, SearchLimits limits) {
        this(view, limits, null);
    }

    /**
     * Constructor that explicitly specifies {@code costToGo}. If {@code null}, it behaves exactly like
     * the existing behavior using {@link Heuristic} (the default geometric lower bound).
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo) {
        this(view, limits, costToGo, Tolerances.of(view));
    }

    /**
     * Constructor with explicit risk tolerances. Used for the anti-deadlock re-search when the caps leave
     * not a single path within range ("lava bridges, drowning risk and painful falls are last resorts, but better
     * than being stuck" as the order of preference).
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo, Tolerances tolerances) {
        this(view, limits, costToGo, tolerances, 1.0);
    }

    /**
     * Constructor that explicitly sets the factor applied to the price of placing footing. {@code 1.0} is the default
     * (exactly {@link ActionCosts#PLACE_BLOCK_AIM_TICKS}).
     *
     * <p>For searching again for "a path with fewer placements" when inventory is scarce
     * (the {@code PathfindingExecutor} frugal re-plan). <b>Its role differs from the cap ({@link #placedBudget})</b>:
     * the cap draws the line on what's feasible, while this expresses a preference within what's feasible.
     *
     * @param placementCostScale factor applied to {@link #placementCostTicks}. Don't pass less than 1.0
     *                           (making it cheaper would stop the {@link CostToGo} guide from being a lower bound)
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo, Tolerances tolerances,
                            double placementCostScale) {
        RunCaps caps = tolerances.caps();
        this.profile = view.routeProfile();
        this.mount = view.mount();
        this.swimmingEnabled = view.swimmingEnabled();
        this.digSurchargeTicks = profile.digSurchargeTicks();
        this.placementCostTicks = ActionCosts.PLACE_BLOCK_AIM_TICKS * placementCostScale
                * profile.placementCostScale();
        this.maxBridgeRun = caps.maxBridgeRunBlocks();
        this.maxLavaBridgeRun = caps.effectiveLavaBridgeRun();
        this.maxVoidBridgeRun = caps.effectiveVoidBridgeRun();
        this.maxSubmergedTicks = caps.maxSubmergedTicks();
        this.placedBudget = tolerances.placedBlockBudget();
        this.placeWithoutBlocks = tolerances.placeWithoutBlocks();
        this.avoidRiskyJumps = !tolerances.allowRiskyJumps();
        this.maxFallDamagePoints = tolerances.maxFallDamagePoints();
        // The generator rereads the same cell many times (197-413 reads per node, while the columns touched
        // over the whole search number about 20k). Wrapping here lets the second and later reads skip the hash table
        this.view = new MemoCells(view);
        // If the fall damage tolerance is relaxed, relax the descent lower bound with it. The longer the allowed drop,
        // the closer the per-block real cost gets to terminal velocity and the cheaper it becomes, so with the original lower bound
        // the heuristic could exceed the real cost (= inadmissible)
        this.minDescentPerBlock = view.minDescentTicksPerBlock(this.maxFallDamagePoints);
        this.maxExpandedNodes = limits.maxExpandedNodes();
        this.timeLimitMillis = limits.timeLimitMillis();
        this.heuristicWeight = limits.heuristicWeight();
        this.costToGo = costToGo;
        this.scans = new ColumnScans(this.view);
    }

    /**
     * Whether this search discarded a move because of the consecutive bridge length cap. If not,
     * removing the cap and searching again won't change the result.
     */
    public boolean bridgeRunCapBlocked() {
        return bridgeRunCapBlocked;
    }

    /**
     * Whether this search discarded a placement move because of the inventory block budget. If not,
     * removing the budget and searching again won't change the result.
     */
    public boolean placedBudgetBlocked() {
        return placedBudgetBlocked;
    }

    /**
     * Placement count carried over from the previous leg ({@link Carryover#placedBlocks()}).
     *
     * <p>The caller needs it to report "how much of the inventory this path uses": the placement count of
     * the path this search returns alone would <b>always make it look like there's plenty in hand</b> when solved in legs.
     */
    public int carriedPlacedBlocks() {
        return carried.placedBlocks();
    }

    /**
     * Whether this search discarded a placement move because "no placeable blocks are held". If not,
     * opening up the no-blocks assumption and searching again won't change the result.
     */
    public boolean placementBlockedByEmptyInventory() {
        return placementBlockedByEmptyInventory;
    }

    /**
     * Whether this search discarded a move because of the consecutive submersion length cap. If not,
     * removing the cap and searching again won't change the result.
     */
    public boolean submergedRunCapBlocked() {
        return submergedRunCapBlocked;
    }

    /**
     * Whether this search discarded a move by avoiding "jumps that kill on a miss". If not,
     * allowing them and searching again won't change the result.
     */
    public boolean riskyJumpBlocked() {
        return riskyJumpBlocked;
    }

    /**
     * Number of placement steps {@link #trimUnfinishedPlacements} dropped from the end of the path.
     *
     * <p>Exists only for diagnostics. Looking at the trimmed path, "laid no bridge at all" and
     * "laid a bridge but couldn't get across" both look like the same <b>0 placements</b>, even though the causes are opposite,
     * and they can't be told apart.
     */
    public int trimmedPlacements() {
        return trimmedPlacements;
    }

    /**
     * Once any cutoff condition (expansion cap, time cap, cancelled) is reached, returns the most promising
     * provisional path at that point.
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled) {
        return search(start, goal, cancelled, Carryover.NONE, 0);
    }

    /**
     * Searches treating the goal not as a "point" but as a <b>region of radius {@code goalRadius}</b>.
     *
     * <p>The intermediate targets of a long-distance route are merely representative points built from chunk averages (layer 1) or
     * linearly interpolated points along the route ({@code pointAlong}). They are artificial points unrelated to the terrain, so snapping to the exact coordinates
     * creates detours that aren't really needed: a relay point is a <b>direction to head in</b>, not a <b>place to pass through</b>,
     * which is the very definition of layer 1's role.
     *
     * <p>{@link #searchToSurface} already takes this form as "anywhere with y &gt;= surfaceY is the goal".
     * This is its generalization. Pass 0 for the real destination (the point the user picked can't be moved).
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled, int goalRadius) {
        return search(start, goal, cancelled, Carryover.NONE, goalRadius);
    }

    /**
     * Searches carrying over the cumulative values from the previous leg ({@link Carryover}).
     *
     * <p>Each leg of a path is solved by a separate pathfinder, so without carrying over <b>the caps reset once per leg</b>:
     * the consecutive bridge length goes back to 0 at the boundary, and the inventory budget is full again for each leg.
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled, Carryover carried,
                              int goalRadius) {
        this.surfaceGoal = false;
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.carried = carried;
        this.goalRadius = goalRadius;
        this.waterEntryBlocked = !swimmingEnabled
                && !(goalRadius <= 0 && CellData.water(view.cell(goalX, goalY, goalZ)));
        return runSearch(start, cancelled);
    }

    /**
     * Searches treating "any cell with y &gt;= surfaceY" as the goal. This is for finding the move from underground to the surface
     * not as a single-point goal that digs straight up above the start, but as a path that can reach the surface from anywhere
     * around (for surface-first navigation; see {@link net.prason.xaeronav.client.PathfindingState}).
     *
     * <p>The heuristic treats each node's own (x, z) as the goal's (x, z) (horizontal distance 0),
     * making it a lower bound on "how many more blocks to climb" only. The real remaining cost may include horizontal movement, so
     * it stays a lower bound and A* optimality holds (horizontally it's effectively Dijkstra, so the search tends to spread out).
     * Nodes already at or above {@code surfaceY} are goals themselves, so they get 0 ({@link #node}).
     */
    public PathResult searchToSurface(BlockPos start, int surfaceY, BooleanSupplier cancelled) {
        this.surfaceGoal = true;
        this.surfaceY = surfaceY;
        this.waterEntryBlocked = !swimmingEnabled;
        return runSearch(start, cancelled);
    }

    /**
     * Value that decides the dequeue order. Quantizes {@code f} into steps of {@link #LINE_TIE_BREAK_TICKS} and,
     * only within the same step, orders by "closest to the start-to-goal line".
     *
     * <p>Quantizing rather than adding is the point. Cost differences that cross steps (= genuine reasons to detour around terrain)
     * are left completely untouched; only ties within a step are broken.
     */
    private double orderingCost(double totalCost, int x, int z) {
        if (!lineTieBreak || LINE_TIE_BREAK_FRACTION <= 0.0) {
            return totalCost;
        }
        double dx = x - lineStartX;
        double dz = z - lineStartZ;
        // The direction vector has unit length, so the absolute value of the cross product is the perpendicular distance as is
        double deviation = Math.abs(dx * lineDirZ - dz * lineDirX);
        double tie = LINE_TIE_BREAK_FRACTION * LINE_TIE_BREAK_TICKS
                * (deviation / (deviation + LINE_TIE_BREAK_HALF_BLOCKS));
        return Math.floor(totalCost / LINE_TIE_BREAK_TICKS) * LINE_TIE_BREAK_TICKS + tie;
    }

    /**
     * Prepares the start-to-goal line (for {@link #LINE_TIE_BREAK_TICKS}).
     * Disabled when the goal is a surface ({@link #searchToSurface}) or the start and goal are in the same column.
     */
    private void prepareDeviationLine(BlockPos start) {
        lineStartX = start.getX();
        lineStartZ = start.getZ();
        double dx = goalX - start.getX();
        double dz = goalZ - start.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        lineTieBreak = !surfaceGoal && length > 0.0;
        if (lineTieBreak) {
            lineDirX = dx / length;
            lineDirZ = dz / length;
        }
    }

    private PathResult runSearch(BlockPos start, BooleanSupplier cancelled) {
        prepareDeviationLine(start);
        // If already riding a boat, start in the riding state. Charging the one-move cost of boarding again
        // would make the guidance say "getting off and swimming is cheaper" when little water surface remains.
        // It also checks that this is a water-surface cell to exclude the case of a boat beached on land while riding
        boolean startBoating = view.ridingBoat()
                && isBoatSurface(start.getX(), start.getY(), start.getZ());
        // Riding at the start: plan for the animal until the route gets off it. It starts mounted even where its
        // footprint check would fail (it is standing there after all); only the moves away are checked
        boolean startMounted = mount != null && !startBoating;
        PathNode startNode = node(start.getX(), start.getY(), start.getZ(), startBoating, startMounted);
        startNode.bridgeRun = carried.bridgeRun();
        // Charge up front the count the previous leg has committed to using. Without this, the budget is full
        // for each leg and paths come out that place many times what's in hand in total
        startNode.placedTotal = carried.placedBlocks();
        startNode.cost = 0.0;
        startNode.combinedCost = orderingCost(heuristicWeight * startNode.estimatedCostToGoal,
                startNode.x, startNode.z);
        open.insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, startNode.estimatedCostToGoal);

        long deadline = MonotonicTime.millis() + timeLimitMillis;
        int expanded = 0;

        // If the loop ran until open was exhausted, there was no way to reach the goal within the search range.
        // Without distinguishing this from running out of budget, pointless retries would be set up endlessly
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;
        while (!open.isEmpty()) {
            if (expanded >= maxExpandedNodes) {
                termination = PathResult.Termination.NODE_BUDGET;
                break;
            }
            if ((expanded & CHECK_INTERVAL_MASK) == 0) {
                if (cancelled.getAsBoolean()) {
                    termination = PathResult.Termination.CANCELLED;
                    break;
                }
                if (MonotonicTime.millis() >= deadline) {
                    termination = PathResult.Termination.TIME_LIMIT;
                    break;
                }
            }

            PathNode current = open.removeLowest();
            current.closed = true;
            expanded++;
            if (reachedGoal(current)) {
                return buildResult(startNode, current, PathResult.Termination.REACHED_GOAL, expanded);
            }
            if (expandFilter == null || expandFilter.expandable(current.x, current.y, current.z)) {
                expand(current);
            }
        }

        return buildResult(startNode, selectFallback(startNode), termination, expanded);
    }

    /** Vertical tolerance shared by the goal check and the reachability check against the snapshot. */
    public static int goalVerticalRadius(int goalRadius) {
        return goalRadius <= 0 ? 0 : Math.max(goalRadius, GOAL_VERTICAL_TOLERANCE_BLOCKS);
    }

    private boolean reachedGoal(PathNode node) {
        // Height alone also counts areas under a ceiling as surface. Deep cave tunnels run long horizontally
        // and often pass above the default surface height. Ending the relay there brings back the path
        // that heads straight from inside the cave to the goal = the straight-line digging we wanted to avoid
        if (surfaceGoal) {
            return node.y >= surfaceY && node.y >= view.surfacedY(node.x, node.z);
        }
        if (goalRadius <= 0) {
            return node.x == goalX && node.y == goalY && node.z == goalZ;
        }
        // Look at a "horizontal cylinder" rather than a sphere. The Y of an intermediate target is only fixed by the chunk
        // representative height or linear interpolation and is far less reliable than the horizontal coordinates; constraining Y with the same radius
        // would reject correct paths that merely go up or down a few blocks following the terrain
        int dx = node.x - goalX;
        int dz = node.z - goalZ;
        return dx * dx + dz * dz <= goalRadius * goalRadius
                && Math.abs(node.y - goalY) <= goalVerticalRadius(goalRadius);
    }

    /**
     * Picks the endpoint when the goal wasn't reached. First, via {@link #selectByLadder}, takes the first candidate,
     * starting from the smallest coefficient (= weighting distance actually travelled), that is at least {@link #MIN_DIST_PATH}
     * from the start. If none qualifies, returns the start itself, treated as an empty path = "no path to present".
     *
     * <p>Only when that choice gambles more than {@link #FALLBACK_BUDGET_TICKS} is it swapped for
     * a within-budget candidate. This is where <b>the score {@code h + g/c} becoming more tolerant of gambling as the guide gets more accurate</b>
     * is stopped; the swap conditions are described under {@link #FALLBACK_BUDGET_TICKS}.
     *
     * <p><b>Distance is measured after trimming with {@link #trimUnfinishedPlacements}.</b> A candidate itself
     * may stand on a half-built bridge, and since that bridge isn't proven to be crossable it can't be presented;
     * choosing by the untrimmed distance grabs <b>a candidate with nothing left after trimming</b> and returns an empty path.
     * Measured ({@code nether_wide}, the shore of a lava sea): all 7 candidates were on 30 moves' worth of bridge, and
     * after using 100k nodes <b>not a single line came out</b>; that's the in-game "470k expanded, 0 steps".
     * Measuring after moving back to the shore lets it move on to the next candidate (a direction that can be walked).
     */
    private PathNode selectFallback(PathNode startNode) {
        PathNode ladder = selectByLadder(startNode);
        // If the current choice is within budget, it isn't over-gambling = no reason to touch it
        if (!fallbackBudgetEnabled || ladder.cost <= FALLBACK_BUDGET_TICKS) {
            return ladder;
        }
        // Apply the cap only when over-gambling. Swap to the first candidate in the within-budget ladder that is
        // far enough ahead for an extension to succeed and doesn't throw away much of the original choice's progress
        double ladderProgress = horizontalFrom(startNode, ladder);
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            PathNode landed = backOffUnfinishedBridge(bestSoFar[i]);
            if (closerOnGuide(startNode, landed) && movedForward(startNode, landed)
                    && horizontalFrom(startNode, landed)
                            >= MIN_CAPPED_PROGRESS_SHARE * ladderProgress) {
                return landed;
            }
        }
        return ladder;
    }

    /** The current way of choosing. In order starting from the smallest coefficient (= weighting distance actually travelled). */
    private PathNode selectByLadder(PathNode startNode) {
        for (int i = COEFFICIENTS.length; i < bestSoFar.length; i++) {
            PathNode landed = backOffUnfinishedBridge(bestSoFar[i]);
            if (closerOnGuide(startNode, landed) && farEnough(startNode, landed, MIN_DIST_PATH)) {
                return landed;
            }
        }
        return startNode;
    }

    /**
     * Whether the point after moving back is closer to the goal than the start, as measured by the guide. Candidates are chosen with {@code h + g/c < h(start)}, so this always holds before moving back,
     * but moving back from a half-built bridge to the shore may break it. Taking a point that fails it makes two shores pick each other as endpoints
     * and the extension oscillates ({@code NetherWideRouteTest}, back and forth 5 moves at a time on a Nether lava shore).
     */
    private static boolean closerOnGuide(PathNode startNode, PathNode landed) {
        return landed.estimatedCostToGoal < startNode.estimatedCostToGoal;
    }

    private static double horizontalFrom(PathNode from, PathNode to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether it got far enough ahead for an extension to succeed. <b>Measured horizontally</b>:
     * {@code PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS} looks at horizontal distance, so
     * mixing in vertical here would count a leg that only moved vertically as "got ahead" and create a tail that won't be joined.
     */
    private static boolean movedForward(PathNode startNode, PathNode landed) {
        double dx = landed.x - startNode.x;
        double dz = landed.z - startNode.z;
        return dx * dx + dz * dz > MIN_USEFUL_PROGRESS * MIN_USEFUL_PROGRESS;
    }

    private static boolean farEnough(PathNode startNode, PathNode landed, double blocks) {
        double dx = landed.x - startNode.x;
        double dy = landed.y - startNode.y;
        double dz = landed.z - startNode.z;
        return dx * dx + dy * dy + dz * dz > blocks * blocks;
    }

    /** Moves back while standing on footing it placed itself at the end (same range as {@link #trimUnfinishedPlacements}). */
    private static PathNode backOffUnfinishedBridge(PathNode node) {
        PathNode cursor = node;
        while (cursor.previous != null
                && cursor.kind.placedBlockPos(cursor.x, cursor.y, cursor.z) != null) {
            cursor = cursor.previous;
        }
        return cursor;
    }

    private PathResult buildResult(PathNode startNode, PathNode end, PathResult.Termination termination,
                                   int expanded) {
        List<PathStep> steps = new ArrayList<>();
        for (PathNode cursor = end; cursor != startNode && cursor.previous != null; cursor = cursor.previous) {
            PathNode from = cursor.previous;
            int x = cursor.x;
            int y = cursor.y;
            int z = cursor.z;
            steps.add(new PathStep(new BlockPos(x, y, z), cursor.kind.movementType(),
                    cursor.cost - from.cost, cursor.kind.bodyCells(from.x, from.y, from.z, x, y, z),
                    digCells(from, cursor), PathRisk.NONE, cursor.kind.placedBlockPos(x, y, z)));
        }
        Collections.reverse(steps);
        if (trimCapViolations(steps)) {
            // Even if candidates reaching the same coordinates with different resource states are merged, a completed path
            // exceeding the safety cap is never released. The higher-level runner can pick a relaxation stage from the blocked flags.
            termination = PathResult.Termination.EXHAUSTED;
        }
        if (termination != PathResult.Termination.REACHED_GOAL) {
            trimUnfinishedPlacements(steps);
        }
        return new PathResult(steps, termination, expanded, createdNodes);
    }

    /** Always enforces the placement cap of the published path at the end, even if the approximate state during the search missed it. */
    private boolean trimCapViolations(List<PathStep> steps) {
        int bridgeRun = carried.bridgeRun();
        int placed = carried.placedBlocks();
        int bridgeStart = bridgeRun > 0 ? 0 : -1;
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            if (!step.bridging()) {
                bridgeRun = 0;
                bridgeStart = -1;
                continue;
            }
            if (bridgeStart < 0) {
                bridgeStart = i;
            }
            bridgeRun++;
            placed++;
            if (maxBridgeRun > 0 && bridgeRun > maxBridgeRun) {
                bridgeRunCapBlocked = true;
                steps.subList(bridgeStart, steps.size()).clear();
                return true;
            }
            if (placedBudget > 0 && placed > placedBudget) {
                placedBudgetBlocked = true;
                steps.subList(bridgeStart, steps.size()).clear();
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the steps at the end of a cut-off path that stand on footing it placed itself.
     *
     * <p>A path that didn't reach the goal only means "you can get this far", but if it ends mid-bridge
     * the meaning changes: <b>you spend blocks and end up stranded at a dead end you may not be able to cross</b>.
     * If it ends on the shore, the extension after new chunks are read takes over from there.
     * This is what makes "only guide across bridges proven to be crossable" hold.
     *
     * <p>Cutting at the search's exit rather than on the presentation side is the point. Cutting here means line drawing, the end-reached check,
     * the extension's starting point and the carry-over of consecutive length across legs <b>all see the same path</b>.
     * Cutting only the drawing makes the guidance arrow point where there's no line.
     */
    private void trimUnfinishedPlacements(List<PathStep> steps) {
        int end = steps.size();
        while (end > 0 && steps.get(end - 1).bridging()) {
            end--;
        }
        trimmedPlacements = steps.size() - end;
        steps.subList(end, steps.size()).clear();
    }

    /**
     * Cells this move actually breaks. Found by passing a collecting list into exactly the same function as the cost calculation.
     * Separately re-deciding "cells that need digging" would produce cells whose cost was paid but that aren't shown (such as
     * a chain of falling blocks overhead), or the reverse.
     */
    private List<BlockPos> digCells(PathNode from, PathNode to) {
        List<BlockPos> cells = new ArrayList<>();
        switch (to.kind) {
            case DESCEND, SWIM_DESCEND -> descendingBodyCost(to.x, from.y, to.z, cells);
            case ASCEND -> {
                columnCost(from.x, from.y + 2, from.y + 2, from.z, cells);
                standingBodyCost(to.x, to.y, to.z, cells);
            }
            // Diagonal ascend/descend doesn't allow digging (addDiagonalAscend/addDiagonalDescend already checked
            // with clearWithoutDigging). Falling into the default branch could pick up a falling-block chain overhead and show
            // "digging cost that wasn't paid"
            case DIAGONAL_ASCEND, DIAGONAL_DESCEND -> {
            }
            // Riding never digs; getting off happens where the animal stands, which is clear for a person
            case RIDE, RIDE_ASCEND, RIDE_DESCEND, DISMOUNT -> {
            }
            // Climbing only digs the cell that becomes the new head. Counting the two body cells at the arrival point
            // would also show the old head (already confirmed passable) as a dug cell
            case PILLAR -> columnCost(from.x, from.y + 2, from.y + 2, from.z, cells);
            default -> standingBodyCost(to.x, to.y, to.z, cells);
        }
        return List.copyOf(cells);
    }

    private PathNode node(int x, int y, int z) {
        return node(x, y, z, false);
    }

    private PathNode node(int x, int y, int z, boolean boating) {
        return node(x, y, z, boating, false);
    }

    private PathNode node(int x, int y, int z, boolean boating, boolean mounted) {
        PathNode[] page = (mounted ? mountNodes : boating ? boatNodes : nodes).page(x, y, z);
        int index = NodeTable.index(x, y, z);
        PathNode existing = page[index];
        if (existing != null) {
            return existing;
        }
        // With a surface goal, a cell already at or above surfaceY is itself a goal (remaining cost 0).
        // Passing surfaceY straight through would count descending from there as remaining cost, overestimating.
        // costToGo is a table tied to a specific goal coordinate, so it isn't used in surfaceGoal mode,
        // where the goal isn't fixed to a single point
        double heuristic;
        boolean guideHole = false;
        if (surfaceGoal) {
            heuristic = Heuristic.estimate(x, y, z, x, Math.max(y, surfaceY), z);
        } else {
            // For a node riding a boat, the horizontal lower bound drops to paddling speed. Estimating at sprint speed
            // would be inadmissible for the boat branch, and together with the one-time boarding cost it would never be expanded
            // Riding is faster still: estimating at sprint speed would overestimate every mounted node
            double horizontalTicks = mounted ? Math.min(mount.ticksPerBlock(), ActionCosts.SPRINT_ONE_BLOCK)
                    : boating ? ActionCosts.PADDLE_ONE_BLOCK : ActionCosts.SPRINT_ONE_BLOCK;
            heuristic = Heuristic.estimate(x, y, z, goalX, goalY, goalZ, minDescentPerBlock, horizontalTicks);
            // With a region goal, the estimate to the center overestimates by the radius = inadmissible.
            // Subtract it, assuming the radius can be closed with the cheapest horizontal movement (same idea as
            // searchToSurface rewriting it into a lower bound on "how many more blocks to climb" only)
            double radiusAllowance = goalRadius * ActionCosts.SPRINT_ONE_BLOCK;
            heuristic = Math.max(0.0, heuristic - radiusAllowance);
            if (costToGo != null) {
                // Use the larger of the two. Heuristic is a geometric lower bound; costToGo is an estimate closer to reality
                // by the amount layer 1 detoured around walls and lava seas.
                //
                // <b>Subtract the radius on the guide side too.</b> Overwriting the lower bound reduced for the region goal
                // with a guide measuring to the center would undo it.
                //
                // The guide includes "invented" weights such as cliff penalties, so it isn't a strict lower bound,
                // and the path's shape changes the moment it exceeds the real cost. Overshoot caused by layer 1's resolution
                // is removed by {@code CoarseRouter#centerOffsetCost}; without that,
                // h gets a 16-block-period sawtooth, and paths get pulled toward chunk boundaries and turn at right angles
                double guide = costToGo.searchEstimate(x, y, z);
                guideHole = Double.isNaN(guide);
                // The guide is priced on foot; scale it down to the animal's pace for mounted nodes
                double pace = mounted ? horizontalTicks / ActionCosts.SPRINT_ONE_BLOCK : 1.0;
                heuristic = Math.max(heuristic,
                        (guideHole ? costToGo.estimate(x, y, z) : guide) * pace - radiusAllowance);
            }
        }
        PathNode created = new PathNode(x, y, z, boating, mounted, heuristic, guideHole);
        page[index] = created;
        createdNodes++;
        return created;
    }

    private void expand(PathNode current) {
        if (current.mounted) {
            mountMoves.expand(current, mount);
            return;
        }
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            groundMoves.addTraverse(current, dx, dz);
            groundMoves.addAscend(current, dx, dz);
            groundMoves.addDescend(current, dx, dz);
            waterMoves.addSwim(current, dx, dz);
            waterMoves.addBoatPaddle(current, dx, dz, false);
            waterMoves.addBoatEnter(current, dx, dz);
            groundMoves.addClimb(current, dx, dz);
            groundMoves.addJumpGap(current, dx, dz);
        }
        for (int i = 0; i < DIAGONAL_DX.length; i++) {
            groundMoves.addDiagonalTraverse(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            waterMoves.addDiagonalSwim(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            waterMoves.addBoatPaddle(current, DIAGONAL_DX[i], DIAGONAL_DZ[i], true);
            groundMoves.addDiagonalAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            groundMoves.addDiagonalDescend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
        }
        // Vertical swimming and climbing only start when currently in water or on a ladder. Otherwise the checks are skipped entirely
        long standingCell = view.cell(current.x, current.y, current.z);
        if (CellData.water(standingCell)) {
            waterMoves.addSwimUp(current);
            waterMoves.addSwimDown(current);
            for (int i = 0; i < CARDINAL_DX.length; i++) {
                waterMoves.addSwimAscend(current, CARDINAL_DX[i], CARDINAL_DZ[i]);
            }
            for (int i = 0; i < DIAGONAL_DX.length; i++) {
                waterMoves.addDiagonalSwimAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            }
        }
        if (CellData.climbable(standingCell)) {
            groundMoves.addClimbUp(current);
            groundMoves.addClimbDown(current);
        }
        buildMoves.addPillar(current);
        // What lies below the cell stepped into is shared by falling and placing, so it's traced only once per direction.
        // Block placement is evaluated last so that, at equal cost, moves that use the terrain as is are preferred
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            int obstacleY = scans.firstNonAirBelow(current.x + dx, current.y - 1, current.z + dz);
            groundMoves.addFall(current, dx, dz, obstacleY);
            buildMoves.addBridge(current, dx, dz, obstacleY);
        }
    }

    /**
     * Time it takes to pass through one block of the entered cell. Water and cobwebs both have no collision, so
     * judging only by "passable" makes them look like they can be run through, but in reality they're slower by orders of magnitude.
     * Cobwebs slow you down if they catch either the feet or the head.
     */
    double stepCost(int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (CellData.water(feet)) {
            // Even with feet on the ground, moves at water speed (see {@link ActionCosts#SWIM_ONE_BLOCK})
            return ActionCosts.SWIM_ONE_BLOCK;
        }
        if (CellData.cobweb(feet) || CellData.cobweb(view.cell(x, y + 1, z))) {
            return ActionCosts.SPRINT_ONE_IN_COBWEB;
        }
        // Soul sand and honey are slow, ice is fast. As in vanilla, if the feet cell has no multiplier,
        // look at the block one below that is actually being stood on ({@code Entity#getBlockSpeedFactor})
        double speedFactor = CellData.speedFactor(feet);
        if (speedFactor == 1.0) {
            speedFactor = CellData.speedFactor(view.cell(x, y - 1, z));
        }
        return ActionCosts.SPRINT_ONE_BLOCK / speedFactor;
    }

    /**
     * Whether a boat can float in the cell. Water surface = "the cell is water, and the one directly above isn't water and can hold a body".
     * Boats don't float at heights partway down in the water, so this check is the boat's height itself.
     */
    boolean isBoatSurface(int x, int y, int z) {
        long here = view.cell(x, y, z);
        long above = view.cell(x, y + 1, z);
        return CellData.water(here) && !CellData.water(above)
                && CellData.occupiableWithoutDigging(above);
    }

    /**
     * Whether a boat can be paddled into this cell: it is water surface, and part of an open stretch at least 2x2 blocks
     * of it. A boat is 1.375 blocks wide, so it sticks out of a single cell on both sides; in a 1-wide channel (swamps:
     * water between roots, lily pads and mud) it jams against the banks, whereas in a 2-wide one it runs along the seam.
     *
     * <p>Only for moves into the boating state. Starting already afloat ({@link #runSearch}) keeps the plain surface
     * check: the boat is there, and paddling out of a narrow spot is still possible.
     */
    boolean boatFits(int x, int y, int z) {
        if (!isBoatSurface(x, y, z)) {
            return false;
        }
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                if (isBoatSurface(x + ox, y, z + oz) && isBoatSurface(x + ox + 1, y, z + oz)
                        && isBoatSurface(x + ox, y, z + oz + 1) && isBoatSurface(x + ox + 1, y, z + oz + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the two cells occupied by a standing pose can be passed through as is, without digging. */
    boolean clearWithoutDigging(int x, int y, int z) {
        return CellData.occupiableWithoutDigging(view.cell(x, y, z))
                && CellData.occupiableWithoutDigging(view.cell(x, y + 1, z));
    }

    /**
     * Horizontal speed multiplier when taking off from this spot. Found the same way as vanilla's {@code Entity#getBlockSpeedFactor}:
     * if the feet cell has no multiplier, look at the block one below that is actually being stood on.
     *
     * <p><b>Values above 1.0 (ice) are not returned.</b> {@link Heuristic} uses
     * {@code ASCEND_ONE_BLOCK} as the lower bound for climbing and {@code SPRINT_ONE_BLOCK} as the horizontal lower bound,
     * so going below those would be inadmissible. The benefit of the faster side is expressed only by {@link #stepCost}, for horizontal moves.
     */
    double takeoffSpeedFactor(int x, int y, int z) {
        double speedFactor = CellData.speedFactor(view.cell(x, y, z));
        if (speedFactor == 1.0) {
            speedFactor = CellData.speedFactor(view.cell(x, y - 1, z));
        }
        speedFactor = Math.min(1.0, speedFactor);
        // For a step away from a spot gripping vines or a ladder, jump-type moves are already forbidden by the caller (onGround() is
        // false, so it can't take off), so only addDescend, addDiagonalDescend and addFall get here.
        // At their sprint-based price they'd be estimated faster than reality; see ActionCosts#CLIMBABLE_TAKEOFF_SPEED_FACTOR
        if (CellData.climbable(view.cell(x, y, z))) {
            speedFactor = Math.min(speedFactor, ActionCosts.CLIMBABLE_TAKEOFF_SPEED_FACTOR);
        }
        return speedFactor;
    }

    /**
     * Whether this search discarded a landing because of the fall damage tolerance. If not, relaxing the tolerance
     * and searching again won't change the result.
     */
    public boolean fallDamageCapBlocked() {
        return fallDamageCapBlocked;
    }

    /**
     * Whether the head is submerged once the move is finished (= whether air goes down).
     *
     * <p>If the head cell is water, that's it. The exception is <b>solid cells passed by digging</b>: even if solid now,
     * digging underwater lets water flow in, so if it touches water it's treated as submerged. Without this,
     * a path digging through underwater would slip past the air cap as "the head cell is stone, so it's not underwater".
     *
     * <p>Cells passable without digging (air) are excluded. Otherwise the one move climbing from the sea onto the beach
     * would be counted as "still diving because the sea is next to it", and it could never get ashore.
     */
    private boolean headSubmerged(PathNode from, int x, int headY, int z) {
        long head = view.cell(x, headY, z);
        if (CellData.water(head)) {
            return true;
        }
        return from.submergedTicks > 0.0 && !CellData.occupiableWithoutDigging(head)
                && hasAdjacentWater(x, headY, z);
    }

    /**
     * Whether there is water around the cell where a block is placed (the 5 faces other than straight up). Used by both {@link #headSubmerged} and
     * {@link BuildMoves#addBridge} (the latter to avoid placing where it touches water).
     *
     * <p><b>Re-reading every time is fine.</b> The reads hit {@code MemoCells}' page array, so adding
     * per-cell notes here wouldn't make it faster; looking up the notes would cost more.
     */
    boolean hasAdjacentWater(int x, int y, int z) {
        return hasAdjacentCell(x, y, z, CellData::water);
    }

    /**
     * Whether any of the 5 faces other than straight up (down, east, west, south, north) satisfies {@code test}. A common form so that
     * adjacency checks for water, lava and climbables don't need three implementations differing only in shape; {@link BuildMoves} uses it too.
     */
    boolean hasAdjacentCell(int x, int y, int z, LongPredicate test) {
        return test.test(view.cell(x, y - 1, z))
                || test.test(view.cell(x + 1, y, z)) || test.test(view.cell(x - 1, y, z))
                || test.test(view.cell(x, y, z + 1)) || test.test(view.cell(x, y, z - 1));
    }

    /**
     * Surcharge when, beside (in 4 directions) the block reached on foot, there's a place where a misstep is fatal.
     *
     * <p>Looking in just 4 directions also catches diagonal corner-cutting: the two corner blocks a diagonal move crosses are also 4-direction neighbors of the landing spot.
     *
     * <p>Called after {@link #relax}'s "discard candidates that don't improve" check. The surcharge is non-negative, so
     * there's no need to read the surroundings for candidates already losing before the surcharge. It isn't included in the value passed to {@link EdgeSink},
     * so the nav graph adds it itself in {@link SectionMoves}; without that, the guide would be cheaper by the surcharge, and on long legs along an edge
     * {@code RouteReview} would mistake the difference for a detour.
     */
    double edgeHazardPenalty(MoveKind kind, int x, int y, int z) {
        switch (kind) {
            case TRAVERSE, DIAGONAL, ASCEND, DESCEND, DIAGONAL_ASCEND, DIAGONAL_DESCEND -> { }
            default -> {
                return 0.0;
            }
        }
        PathNode arrival = node(x, y, z, false);
        if (arrival.edgeHazard == 0) {
            arrival.edgeHazard = 1;
            for (int i = 0; i < CARDINAL_DX.length; i++) {
                if (deadlyBeside(x + CARDINAL_DX[i], y, z + CARDINAL_DZ[i])) {
                    arrival.edgeHazard = 2;
                    break;
                }
            }
        }
        return arrival.edgeHazard == 2 ? profile.edgeHazardPenaltyTicks() : 0.0;
    }

    /** Whether slipping to {@code (x, y, z)} at foot height is fatal (entering lava, or falling into the void or a lethal drop). */
    private boolean deadlyBeside(int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (CellData.lava(feet) || CellData.lava(view.cell(x, y + 1, z))) {
            return true;
        }
        if (!CellData.passableEmpty(feet)) {
            return false;
        }
        int obstacleY = scans.firstNonAirBelow(x, y - 1, z);
        if (obstacleY == ColumnScans.NOTHING_BELOW) {
            return true;
        }
        // Unloaded terrain can't be declared dangerous. Applying the surcharge here would make paths wobble along the loading edge
        if (obstacleY == ColumnScans.UNREADABLE_BELOW) {
            return false;
        }
        long obstacle = view.cell(x, obstacleY, z);
        if (CellData.lava(obstacle)) {
            return true;
        }
        // Landing in water resets the fall distance
        if (CellData.water(obstacle)) {
            return false;
        }
        return y - obstacleY - 1 >= view.fatalFallBlocks();
    }

    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0);
    }

    /** Relaxes to the node in the riding state. Only for {@link MountMoves}. */
    void relaxMounted(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0, false, true);
    }

    /** Relaxes to the node in the boating state. Only for {@link #addBoatEnter}/{@link #addBoatPaddle}. */
    void relaxBoating(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0, true);
    }

    /**
     * Variant that passes {@code bridgeRun} explicitly. Nonzero is passed only for moves landing on footing it placed itself
     * ({@link #addBridge}, {@link #addPillar}); all others land on a real floor, so it's 0.
     */
    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun) {
        relax(from, x, y, z, edgeCost, kind, bridgeRun, false);
    }

    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun,
               boolean boating) {
        relax(from, x, y, z, edgeCost, kind, bridgeRun, boating, false);
    }

    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun,
               boolean boating, boolean mounted) {
        // Cut before the edge is reported, so the nav graph doesn't learn water entries the search never makes
        if (waterEntryBlocked && !boating && !mounted && entersWater(from, x, y, z)) {
            return;
        }
        if (from.boating && !boating) {
            edgeCost += ActionCosts.BOAT_STOW_TICKS;
        }
        // The nav graph is built on foot; riding edges would be priced for one particular animal
        if (edgeSink != null && !from.mounted && !mounted) {
            edgeSink.edge(from.x, from.y, from.z, from.boating, x, y, z, boating, edgeCost, kind);
        }
        // Before the air accounting, discard "candidates that won't get cheaper anyway". The surcharge (SUBMERGED_TRAVEL_PENALTY)
        // never goes below 1x, so if it can't improve at the pre-surcharge cost, it can't improve after the surcharge either.
        // Postponing this would mean reading overhead and the 5 surrounding faces for candidates known to be discarded.
        //
        // This misses setting {@code submergedRunCapBlocked} further down, and that's fine:
        // an edge that doesn't improve vanishing because of the cap doesn't change the answer, so removing the cap for that reason
        // and searching again gives the same path
        PathNode neighbor = node(x, y, z, boating, mounted);
        if (neighbor.closed || neighbor.cost - (from.cost + edgeCost) <= MIN_IMPROVEMENT) {
            return;
        }
        // Holes in the guide (such as the void outside the nav graph's shell) only have the geometric lower bound, thousands of ticks cheaper than the value over the neighboring island.
        // Left as is, the search dives from the island's tip into the void in every direction and burns the budget (in-game End: 50-97% of nodes created at the island's tip,
        // and the path became 0 moves after trimming half-built bridges). Only allow it to drop by one move from the parent's value (pathmax)
        if (neighbor.guideHole) {
            neighbor.estimatedCostToGoal = Math.max(neighbor.estimatedCostToGoal,
                    from.estimatedCostToGoal - edgeCost);
        }

        // Regardless of the move type, if the head is submerged at the landing spot, air goes down by the time the move took.
        // It's checked all at once here because the same holds for moves other than swimming (walking underwater, sinking, digging, falling into water);
        // mining in particular takes dozens of ticks per move, so counting by blocks would slip past the air cap
        double submergedTicks = 0.0;
        boolean submerged = headSubmerged(from, x, y + 1, z);
        if (submerged) {
            submergedTicks = from.submergedTicks + edgeCost;
            if (maxSubmergedTicks > 0.0 && submergedTicks > maxSubmergedTicks) {
                submergedRunCapBlocked = true;
                return;
            }
        }

        // Don't cross while submerged; surface first and then cross. Only surfacing is exempt;
        // it applies to both horizontal moves and diving. Applying it only to horizontal moves would let the path dodge the surcharge
        // by bobbing up and down, alternating diagonal surfacing and diagonal descent.
        //
        // <b>The exemption is limited to surfacing within 1 horizontal block.</b> Exempting moves that rise while advancing diagonally too would,
        // on legs that should go diagonally, make the "rise diagonally, sink diagonally" round trip (√3 + √2·P)
        // cheaper than two diagonal horizontal moves (2·√2·P), and the same bobbing returns in diagonal form
        // (measured with {@code doesNotBobDiagonallyToDodgeTheSubmergedPenalty}).
        // On legs that can go cardinally, two horizontal moves are already cheaper, so this hole only appears diagonally.
        //
        // The surcharge is for choosing paths and isn't mixed into the air accounting (submergedTicks); that one
        // is meaningless unless it's the time actually taken
        boolean surfacing = y > from.y && Math.abs(x - from.x) + Math.abs(z - from.z) <= 1;
        double tentativeCost = from.cost
                + (submerged && !surfacing ? edgeCost * profile.submergedTravelPenalty() : edgeCost)
                + edgeHazardPenalty(kind, x, y, z);
        if (neighbor.cost - tentativeCost <= MIN_IMPROVEMENT) {
            return;
        }

        neighbor.previous = from;
        neighbor.cost = tentativeCost;
        neighbor.combinedCost = orderingCost(
                tentativeCost + heuristicWeight * neighbor.estimatedCostToGoal, neighbor.x, neighbor.z);
        neighbor.kind = kind;
        neighbor.bridgeRun = bridgeRun;
        // The number of blocks placed can be derived from the type (adding a parameter would mean adding 0 to every call)
        neighbor.placedTotal = from.placedTotal + (kind == MoveKind.BRIDGE || kind == MoveKind.PILLAR ? 1 : 0);
        neighbor.submergedTicks = submergedTicks;
        if (neighbor.isOpen()) {
            open.update(neighbor);
        } else {
            open.insert(neighbor);
        }

        boolean withinBudget = neighbor.cost <= FALLBACK_BUDGET_TICKS;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
            if (withinBudget && bestHeuristic[i] - heuristic > MIN_IMPROVEMENT) {
                bestHeuristic[i] = heuristic;
                bestSoFar[i] = neighbor;
            }
            int free = COEFFICIENTS.length + i;
            if (bestHeuristic[free] - heuristic > MIN_IMPROVEMENT) {
                bestHeuristic[free] = heuristic;
                bestSoFar[free] = neighbor;
            }
        }
    }

    /**
     * Whether this move takes the player from a dry cell (or a boat) into water. Refused while
     * {@link #waterEntryBlocked}.
     *
     * <p>Only <b>entering</b> water is refused: stepping, falling or swimming into a water cell from a cell that isn't
     * water. Moves from water to water stay allowed, otherwise a player who starts in a lake (or is dropped there by
     * an earlier leg) could never get out, and the search would fail outright instead of guiding to the shore.
     * Boating moves never get here, and getting out of a boat onto land isn't entering water; only dropping from a
     * boat into the water to swim on counts.
     */
    private boolean entersWater(PathNode from, int x, int y, int z) {
        if (!CellData.water(view.cell(x, y, z))) {
            return false;
        }
        return from.boating || !CellData.water(view.cell(from.x, from.y, from.z));
    }

    /**
     * Mining underwater is 5x slower without the Aqua Affinity enchantment. It depends not on each dug cell but on "whether the player's head
     * is in water while digging", so it's applied to the total digging cost per move rather than to the cost of individual cells.
     *
     * <p>Whether underwater and whether the feet are on the ground are measured at <b>{@code from}, where it stands while digging</b>. The head at the destination
     * is a solid still to be dug, so measuring there would price digging dirt while swimming the same as on land, and looking at the destination floor
     * would turn swim-digging's 25x into 5x. Even if {@code from}'s head cell is solid in the terrain, if it came there by digging underwater
     * water has flowed in, so the on-arrival check ({@code submergedTicks}) is consulted too.
     */
    double submerged(PathNode from, double digCost) {
        boolean eyeInWater = CellData.water(view.cell(from.x, from.y + 1, from.z)) || from.submergedTicks > 0.0;
        if (digCost <= 0.0 || !eyeInWater) {
            return digCost;
        }
        // The !onGround() branch of Player#getDigSpeed
        boolean onGround = CellData.standable(view.cell(from.x, from.y - 1, from.z));
        return digCost * (onGround ? ActionCosts.SUBMERGED_DIG_PENALTY : ActionCosts.SWIMMING_DIG_PENALTY);
    }

    /**
     * Break cost of the 2 cells occupied in a standing pose (feet and head).
     */
    double standingBodyCost(int x, int y, int z, @Nullable List<BlockPos> cells) {
        return columnCost(x, y, y + 1, z, cells);
    }

    /**
     * The 3 cells the body passes through in a one-step descent. {@code y} is the height before descending (feet at {@code y}, head at {@code y+1},
     * the destination at {@code y-1}).
     */
    double descendingBodyCost(int x, int y, int z, @Nullable List<BlockPos> cells) {
        return columnCost(x, y - 1, y + 1, z, cells);
    }

    /**
     * Break cost of one vertical column ({@code bottomY} to {@code topY}). Additionally adds, once only, any falling blocks (sand, gravel, etc.)
     * stacked directly above. The required cells themselves are just counted individually, so
     * chain costs aren't double-counted between adjacent required cells.
     *
     * <p>If {@code cells} is non-null, the cells actually broken are collected into it. This keeps the decision to pay the cost and the enumeration of broken cells
     * on the same code path; writing them separately makes the display and the search disagree.
     */
    double columnCost(int x, int bottomY, int topY, int z, @Nullable List<BlockPos> cells) {
        double total = 0.0;
        boolean doorCharged = false;
        for (int y = bottomY; y <= topY; y++) {
            // A door is split into an upper and lower cell, but opening it is a single action. Paying the open/close cost for both
            // would make one door weigh as much as two, and correct passages with doors would be avoided
            long cell = view.cell(x, y, z);
            boolean openable = CellData.openable(cell);
            if (openable && doorCharged) {
                continue;
            }
            double cost = occupyCost(cell, x, y, z, cells);
            if (Double.isInfinite(cost)) {
                return ActionCosts.INFEASIBLE;
            }
            total += cost;
            doorCharged |= openable;
        }
        return total + fallingChainCost(x, topY + 1, z, cells);
    }

    private double fallingChainCost(int x, int startY, int z, @Nullable List<BlockPos> cells) {
        double total = 0.0;
        for (int i = 0; i < MAX_FALLING_CHAIN_SCAN; i++) {
            int y = startY + i;
            long cell = view.cell(x, y, z);
            if (!CellData.fallingBlock(cell)) {
                break;
            }
            double cost = occupyCost(cell, x, y, z, cells);
            if (Double.isInfinite(cost)) {
                break;
            }
            total += cost;
        }
        return total;
    }

    private double occupyCost(long cell, int x, int y, int z, @Nullable List<BlockPos> cells) {
        if (!CellData.present(cell)) {
            return ActionCosts.INFEASIBLE;
        }
        if (CellData.occupiableWithoutDigging(cell)) {
            return 0.0;
        }
        if (CellData.openable(cell)) {
            // Doors are opened, not broken. They aren't counted as dug cells either
            return ActionCosts.OPEN_DOOR_OVERHEAD_TICKS;
        }
        // The profile's surcharge rides on every dug cell, like DIG_OVERHEAD_TICKS itself (0 unless RESOURCE_SAVING)
        double ticks = CellData.digTicks(cell) + digSurchargeTicks;
        // Undiggable cells (digging forbidden, negative hardness) are also used to cut off falling-block chains, so they aren't collected
        if (cells != null && !Double.isInfinite(ticks)) {
            // Ascend's ceiling digging may point at the same cell as the falling-block chain when the overhead is sand or gravel
            BlockPos pos = new BlockPos(x, y, z);
            if (!cells.contains(pos)) {
                cells.add(pos);
            }
        }
        return ticks;
    }
}
