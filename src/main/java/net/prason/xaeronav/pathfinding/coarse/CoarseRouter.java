package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * Draws a rough route to the destination over the {@link CoarseMap}, one chunk at a time.
 *
 * <p>The result is used not as the path itself but as <b>a sequence of intermediate targets</b>. All
 * this decides is the big picture: "which way around the sea", "which valley to go through", and
 * "(in dimensions with a ceiling) which layer to use". The path actually followed is re-drawn per
 * intermediate target by the detail search, which looks at loaded chunks. Since the coarse side has
 * no per-block passability, the line drawn here is not guaranteed to be walkable as-is.
 *
 * <p>The search state is {@code (chunkX, chunkZ, floor)}: a per-cell floor of the {@link CoarseMap}.
 * In dimensions where several independent layers stack at the same XZ (the Nether), moving between
 * layers is not fudged as a "cheap step" but treated as a separate edge with its own cost
 * ({@link #LAYER_TRANSITION_PENALTY}). In dimensions where the floor count is always 1 (Overworld,
 * The End), this state space is exactly the same as the old {@code (chunkX, chunkZ)}.
 *
 * <p>Costs are estimated in ticks, the same unit as the detail search. Without matching units, when
 * they disagree ("shortest on the coarse side but an obvious detour on the detail side"), there is
 * no way to tell which estimate is off.
 */
public final class CoarseRouter {

    /** Side length of one cell (blocks). 16, since {@link CoarseMap} is per chunk. */
    private static final int CELL_BLOCKS = 16;

    private static final double STRAIGHT_COST = CELL_BLOCKS * ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL_COST = STRAIGHT_COST * ActionCosts.DIAGONAL_DISTANCE;

    /**
     * Multiplier for crossing water surface. Exactly the speed ratio of sprinting to swimming (5.612 / 3.6).
     *
     * <p>What layer 1 sees in a water cell is the cost of <b>crossing the surface</b>, so it uses {@link ActionCosts#SWIM_ONE_BLOCK}.
     *
     * <p>The denominator is {@link ActionCosts#SPRINT_ONE_BLOCK} because the multiplier is applied to
     * {@link #STRAIGHT_COST}, which is based on sprinting. Dividing by walking would estimate land at
     * sprint speed while measuring only the water ratio at walking speed, skewing the ratio systematically.
     */
    private static final double WATER_MULTIPLIER =
            ActionCosts.SWIM_ONE_BLOCK / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * Water-surface multiplier when travelling by boat. Because {@link ActionCosts#PADDLE_ONE_BLOCK} is
     * smaller than {@link ActionCosts#SPRINT_ONE_BLOCK}, unlike {@link #WATER_MULTIPLIER} this is
     * below 1, i.e. not a cost for avoiding water but a shortcut to choose actively.
     */
    private static final double BOAT_MULTIPLIER =
            ActionCosts.PADDLE_ONE_BLOCK / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * Multiplier for passing through cells missing from the map. Treating them as impassable means no
     * route ever reaches a destination beyond unvisited land. Treating them like land, on the other
     * hand, abandons known detours to charge straight into the unknown. Make it heavy enough that "a
     * known road is preferred even if somewhat longer".
     */
    private static final double UNKNOWN_MULTIPLIER = 1.6;

    /**
     * Multiplier for passing through cells containing lava. Unlike the other multipliers this is not a
     * measured speed ratio: lava doesn't slow you down, it forces detours, so this is the estimate "going
     * around lava within a chunk roughly doubles the actual distance", plus a margin for layer 1 not
     * knowing whether the cell can be crossed safely.
     *
     * <p>The Nether always has cells like this, so making them impassable would disconnect routes.
     * But treating them like land would guide straight through lava fields. A weight that makes it pick "the other way, if there is one".
     */
    private static final double LAVA_MIXED_MULTIPLIER = 2.5;

    /**
     * Multiplier for crossing lava cells with {@link BridgePolicy#BRIDGE}. Layers 1 and 3 share tick
     * units for cost, so this is derived from layer 3's actual cost rather than a guess: per block
     * {@code SPRINT_ONE_BLOCK + PLACE_BLOCK_AIM_TICKS + LAVA_BRIDGE_PENALTY_TICKS ≒ 35.6} ticks,
     * against 3.564 for normal land, a ratio of about 10x.
     *
     * <p>It adds {@code PLACE_BLOCK_AIM_TICKS} rather than {@code PLACE_BLOCK_OVERHEAD_TICKS} because
     * layer 3 does not add the surcharge for interrupting the sprint on lava and void bridges
     * ({@code ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}). Adding it only in layer 1 would make the
     * two layers price the same bridge differently.
     */
    private static final double LAVA_BRIDGE_MULTIPLIER =
            (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                    + ActionCosts.LAVA_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * Multiplier for crossing void cells with {@link BridgePolicy#BRIDGE}. Like
     * {@link #LAVA_BRIDGE_MULTIPLIER}, derived from layer 3's actual cost ({@link ActionCosts#VOID_BRIDGE_PENALTY_TICKS}).
     *
     * <p><b>This multiplier effectively decides "how much void is worth crossing".</b> Layer 1 is A*,
     * so it can't keep the bridge's run length in its state (k times the states means 1/√k the reach),
     * and has no way to express {@code maxVoidBridgeRunBlocks}. Instead, with 1 cell = 16 blocks under
     * this multiplier, crossing 2 void cells (32 blocks) costs about as much as walking 320 blocks, so
     * any shorter way around always wins. Void too long for the detail search to bridge within its
     * limit is simply never chosen by layer 1.
     */
    private static final double VOID_BRIDGE_MULTIPLIER =
            (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                    + ActionCosts.VOID_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * {@link #UNKNOWN_MULTIPLIER} reinterpreted as a void ratio: {@code 1.6 = (1-r) + r·}
     * {@link #VOID_BRIDGE_MULTIPLIER} solved for {@code r} (about 0.067).
     * In other words, the original 1.6x really meant "just under 7% of unknown cells are void".
     */
    private static final double UNKNOWN_PRIOR_VOID_RATIO =
            (UNKNOWN_MULTIPLIER - 1.0) / (VOID_BRIDGE_MULTIPLIER - 1.0);

    /**
     * Weight (in equivalent cell count) of {@link #UNKNOWN_PRIOR_VOID_RATIO} pitted against the measured
     * known cells in {@link #calibratedUnknownMultiplier}.
     *
     * <p><b>The key point is that this is a weight, not a threshold.</b> Cutting off with "don't
     * calibrate below N known cells" creates a step where the multiplier jumps from 1.6 to nearly 10
     * depending only on whether the Nth cell is land or void. Layer 1 is re-drawn on every move, so
     * that step surfaces as the route flip-flopping ("straight through the unknown going out, a wide
     * detour through the known coming back"). Mixing in this many pretend-seen known cells at void
     * ratio {@link #UNKNOWN_PRIOR_VOID_RATIO} makes it shift smoothly toward the measurement as known
     * cells increase, with no step.
     *
     * <p>The weight of 50 cells keeps the standard error around 7 points assuming a void ratio near 40%
     * (not a rigorous statistical threshold based on measurement).
     */
    private static final double UNKNOWN_PRIOR_WEIGHT_CELLS = 50.0;

    /**
     * Extra cost per block of height difference, applied equally up and down.
     * Coarse cells can't tell cliffs from gentle slopes, so this is a noncommittal middle weight,
     * used only to make it pick "the flatter one at about the same distance".
     */
    private static final double HEIGHT_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * Extra cost the guide puts on one block of ascent. <b>Not {@link #HEIGHT_COST_PER_BLOCK}</b>:
     * climbing piggybacks on horizontal movement, so all that actually increases is the difference
     * between {@code Ascend} and sprinting (same idea as the piggyback in {@code Heuristic}). Using
     * {@link #HEIGHT_COST_PER_BLOCK} as-is overestimates about 4x, pushing routes on hilly terrain
     * toward flat ground needlessly.
     */
    private static final double GUIDE_ASCEND_COST_PER_BLOCK =
            ActionCosts.ASCEND_ONE_BLOCK - ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * Relief within a cell ({@code maxHeight - minHeight}) beyond this is treated as a cliff.
     * Uses vanilla's {@code SAFE_FALL_DISTANCE} default ({@link ActionCosts#SAFE_FALL_BLOCKS}) as-is.
     * Gentler relief is fine to treat as an ordinary slope, expressed by differences in average height.
     */
    private static final int CLIFF_THRESHOLD_BLOCKS = ActionCosts.SAFE_FALL_BLOCKS;

    /**
     * Extra cost for stepping into a cliff cell. Even with the same average height as its surroundings,
     * a chunk with large internal relief is likely "a flat patch partway up a cliff", where the detail
     * search tends to end up detouring or digging a lot. This compensates for
     * {@link #HEIGHT_COST_PER_BLOCK}, which only looks at averages and so treats steep and gentle
     * hillside chunks the same.
     */
    private static final double CLIFF_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * Cap on the cliff penalty. Left linear, on rugged terrain (the norm in the Nether's 3D maze) it
     * easily exceeds the extra lava cost from {@link #LAVA_MIXED_MULTIPLIER}, making "a flat sea of lava"
     * look cheaper than "real terrain with relief" (measured: LAND flips at about 30 blocks of relief).
     *
     * <p>Keeps the invariant "cliff penalty cap &lt; extra cost of a lava-mixed cell". It is based on the
     * extra cost of one straight cell ({@code STRAIGHT_COST * (LAVA_MIXED_MULTIPLIER - 1)}): straight has
     * a smaller base cost than diagonal, making it the stricter case, so matching it here holds for both.
     * Held to 90% as a safety margin.
     */
    private static final double CLIFF_PENALTY_CAP = STRAIGHT_COST * (LAVA_MIXED_MULTIPLIER - 1.0) * 0.9;

    /**
     * Surcharge multiplier for moving between layers within the same cell (floor i ↔ floor i+1). The
     * coarse map doesn't know whether there is actually a passable shaft between the layers, so
     * {@code Δheight × HEIGHT_COST_PER_BLOCK} is multiplied by a surcharge for "not knowing whether it's
     * really connected". Same design idea as {@link #UNKNOWN_MULTIPLIER} and
     * {@link #LAVA_MIXED_MULTIPLIER} ("not impassable, but pricier than a sure road"); not a measured
     * coefficient (needs tuning).
     */
    private static final double LAYER_TRANSITION_PENALTY = 2.0;

    /**
     * A landmass of at least this many cells counts as a "large island" (3×3 chunks = 48×48 blocks).
     * No surcharge when crossing to a landmass at least this large.
     */
    private static final int LARGE_ISLAND_CELLS = 9;

    /**
     * Surcharge for crossing to a landmass of only 1 cell (16×16 blocks). Answers the user request
     * "In The End I'd like to avoid hopping between islands as much as possible. I want <b>a route that
     * travels across large islands</b>".
     *
     * <p><b>It does not charge for "the number of crossings" itself.</b> Choosing short stepping stones
     * is a <b>feasibility</b> requirement from layer 3's bridge limit ({@code maxVoidBridgeRunBlocks}),
     * and is correct behaviour, as pinned down by
     * {@code CoarseRouterTest#prefersSteppingStoneIslandsOverTheShortestVoidCrossing}; breaking it
     * makes the route pick void too long to cross. It charges only for "which islands to land on",
     * making it prefer large islands over small ones.
     *
     * <p>Equivalent to walking 4 chunks (64 blocks): the weight of "worth detouring this far to go via
     * a large island". Well below one void cell ({@link #VOID_BRIDGE_MULTIPLIER} ≒ 10x = walking 160
     * blocks), so it never <b>adds an extra void cell to reach a large island</b>.
     */
    private static final double SMALL_ISLAND_PENALTY = STRAIGHT_COST * 4.0;

    /**
     * Horizontal spacing (cells = chunks) at which intermediate targets are placed.
     *
     * <p><b>Always keep this shorter than the distance the detail search aims at once
     * ({@code detailHorizonBlocks}, default 96).</b> Thinning triggers when "the largest-axis difference
     * reaches this value", so on diagonal routes the actual spacing is up to
     * {@code spacing * √2 * 16} blocks: with 6 cells that is up to 135.8 blocks, and anything past 96
     * can't be aimed at in one detail search. The target then has to be taken <b>on the straight line
     * toward the waypoint</b> ({@code PathfindingState#pointAlongRoute}), and where the route bends that
     * line cuts the corner, dropping the target into the middle of a lava sea layer 1 avoided. With 4
     * cells it's at most 90.5 blocks, always inside 96, so "aim at the next intermediate target itself"
     * is enough.
     *
     * <p>Tighter spacing doesn't increase the number of detail searches. {@code reachableWaypointTarget}
     * aims at the <b>farthest reachable</b> intermediate target, so the extra ones are skipped and only resolution improves.
     */
    private static final int WAYPOINT_SPACING_CELLS = 4;

    /**
     * Vertical spacing (blocks) at which intermediate targets are placed. Thinning only by the
     * horizontal spacing ({@link #WAYPOINT_SPACING_CELLS}) collapses a stretch climbing many layers at the
     * same XZ (zero horizontal movement) into one segment with no waypoints, so the detail search
     * suddenly has to aim at a single target "far above the current position".
     * Matches {@code PathfindingState#REFINED_WAYPOINT_MIN_SPACING_BLOCKS}.
     */
    private static final int WAYPOINT_VERTICAL_SPACING_BLOCKS = 24;

    /**
     * When the goal isn't reached, tracks endpoint candidates by several metrics {@code h + g / coefficient}
     * at once. Same idea and same coefficient list as
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder}. Choosing by the heuristic alone
     * (= the cell closest to the goal) grabs "a dead end that cost an enormous amount to reach", like the
     * tip of a peninsula jutting into the sea. Smaller coefficients weigh actual distance travelled more.
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /**
     * A provisional route that progresses less than this isn't worth presenting (cells = chunks,
     * distance on the XZ plane). Same role as
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#MIN_DIST_PATH}, but the unit is
     * chunks rather than blocks, so this one is 1 cell.
     */
    private static final double MIN_DIST_CELLS = 1.0;

    private CoarseRouter() {
    }

    /**
     * Sequence of intermediate targets. If {@code reachedGoal} is false, it ends without reaching the
     * destination, at "the point that got closest to the goal at that time".
     */
    public record Route(List<BlockPos> waypoints, boolean reachedGoal) {

        public Route {
            waypoints = List.copyOf(waypoints);
        }

        public boolean isEmpty() {
            return waypoints.isEmpty();
        }
    }

    /**
     * How to treat <b>cells that can't be crossed without placing blocks</b> (lava, void). Callers try
     * from {@link #AVOID} in order and relax only when the goal isn't reached.
     *
     * <p>If layer 1 decides to cut through a lava field or the void, the detail search fundamentally
     * cannot reach that waypoint (you can't walk on lava or void). Staging ensures "a road that avoids
     * it, even with a big detour" is always searched first; A* finds detours and backtracking on its
     * own, so all this needs to express is allowed/not allowed.
     *
     * <p>Lava and void share one knob because both have the same property: "passable if you bridge
     * them". However, <b>they relax at different stages</b>: lava bridging has a config switch, while
     * void bridging doesn't (layer 3 decides only by {@code canPlaceBlocks}), so void opens up at
     * {@link #ALLOW}.
     */
    public enum BridgePolicy {
        /** Never passes lava-mixed cells or void. Finds a road that avoids them, even a long detour. */
        AVOID,
        /** Lava-mixed cells and void are passable but expensive. Only majority-lava cells are impassable. */
        ALLOW,
        /** Passes everything, including majority-lava cells, assuming they are bridged. Last resort. */
        BRIDGE
    }

    public static Route findRoute(CoarseMap map, BlockPos start, BlockPos goal, boolean boatAvailable,
                                   BridgePolicy bridgePolicy) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new Route(List.of(), false);
        }
        double waterMultiplier = boatAvailable ? BOAT_MULTIPLIER : WATER_MULTIPLIER;
        double unknownMultiplier = calibratedUnknownMultiplier(map, bridgePolicy);

        int cells = map.chunksX() * map.chunksZ();
        int states = cells * CoarseMap.MAX_FLOORS;
        double[] cost = new double[states];
        int[] previous = new int[states];
        boolean[] closed = new boolean[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        int startFloor = resolveFloor(map, startX, startZ, start.getY());
        int goalFloor = resolveFloor(map, goalX, goalZ, goal.getY());
        int startIndex = stateIndex(map, startX, startZ, startFloor);
        int goalIndex = stateIndex(map, goalX, goalZ, goalFloor);
        cost[startIndex] = 0.0;

        PriorityQueue<Candidate> open =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimatedTotal));
        open.add(new Candidate(startIndex, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier)));

        int[] bestSoFar = new int[COEFFICIENTS.length];
        double[] bestHeuristic = new double[COEFFICIENTS.length];
        Arrays.fill(bestSoFar, startIndex);
        Arrays.fill(bestHeuristic, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            // The same state is pushed multiple times instead of decrease-key, so drop stale ones here
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            if (current.index() == goalIndex) {
                return buildRoute(map, previous, goalIndex, startIndex, true, start.getY());
            }

            int x = stateChunkX(map, current.index());
            int z = stateChunkZ(map, current.index());
            int floor = stateFloor(current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relaxHorizontal(map, cost, previous, closed, open, x, z, floor, dx, dz, goalX, goalZ,
                            bestSoFar, bestHeuristic, waterMultiplier, unknownMultiplier, bridgePolicy);
                }
            }
            relaxVertical(map, cost, previous, closed, open, x, z, floor, goalX, goalZ,
                    bestSoFar, bestHeuristic, waterMultiplier);
        }

        return buildRoute(map, previous, selectFallback(map, bestSoFar, startIndex), startIndex, false,
                start.getY());
    }

    /**
     * Computes a lower bound on actual cost from the goal backwards to every cell and floor. This is
     * the substance of the guide ({@link net.prason.xaeronav.pathfinding.astar.CostToGo}) used alongside
     * layer 3's heuristic, letting it use estimates that route around walls and lava seas instead of
     * geometric straight-line distance.
     *
     * <p>Unlike {@link #findRoute}, this runs plain Dijkstra without a heuristic until open is
     * exhausted (= until shortest distances to all reachable states are settled).
     * The {@link #stepCost} family is asymmetric (determined by the properties of the entered cell), so
     * when running backwards from the goal the call's {@code from}/{@code to} are swapped: "the cost of
     * entering B from cell A" is counted standing on B, facing A.
     *
     * <p><b>Caveat on approximation</b>: horizontal moves in {@link #findRoute} connect <b>only</b> to
     * the floor of the neighbouring cell closest in height (so moves between layers don't bypass the
     * vertical transition surcharge). Reversing that exactly would require working out "which floors
     * could be chosen as 'nearest' from which floors", which is complicated, so this simplifies by
     * taking all floors of neighbouring cells as candidates. The result can only err toward
     * "estimating wider than what's really connected". It's designed to be used as the max with the
     * admissible {@link net.prason.xaeronav.pathfinding.astar.Heuristic}
     * (see {@code AStarPathfinder#node}), so this approximation never breaks the search
     * (at worst it naturally falls back to the geometric lower bound).
     *
     * <p><b>The contract: the guide (this table) must be a lower bound on actual cost.</b>
     *
     * <p>{@code AStarPathfinder#node} uses the <b>larger</b> of the guide and the geometric {@code Heuristic} as h.
     * The moment the guide exceeds actual cost, A* overestimates that direction and <b>changes the
     * shape of the path</b>; and since layer 1 is per chunk, the change follows the chunk grid, not the terrain.
     *
     * <p>So <b>the same map is priced two ways</b>. {@link #findRoute} (planning which valley to take)
     * uses values including preferences; this table (the detail search's guide) uses lower bounds only.
     * The lower-bound side drops the following five, none of which are <b>actual time spent</b>:
     *
     * <ul>
     * <li>{@link #cliffPenalty}: large relief within a cell doesn't rule out a flat ledge to pass along</li>
     * <li>{@link #SMALL_ISLAND_PENALTY}: the human preference "I want to cross large islands"</li>
     * <li>The {@code NO_DATA} multiplier ({@link #calibratedUnknownMultiplier}): not knowing is not
     *     a reason for something to be expensive</li>
     * <li>{@link #LAYER_TRANSITION_PENALTY}: the surcharge for not knowing whether a shaft exists</li>
     * <li>Descending {@link #HEIGHT_COST_PER_BLOCK}: you can run down, so actual cost doesn't increase</li>
     * <li>The water and {@link CoarseMap#LAVA_MIXED} multipliers: even when most of a cell is water,
     *     it's common for a row to be crossable for 16 blocks along a dry strip. Diluting to the
     *     threshold fraction still isn't a lower bound</li>
     * <li>Climbs measured by representative height (chunk average): a ridge cell whose saddle can be
     *     crossed is made to climb by its average height. Re-measured with <b>the cell's min and max</b></li>
     * <li>Offset between floor height and actual Y ({@link #floorOffsetCost}): standing at a height not
     *     recorded as a floor, like a beach below a cliff, yields the value "after climbing to that floor"</li>
     * </ul>
     *
     * <p>Only the void ({@link CoarseMap#VOID}) multiplier is not diluted. It's what steers toward island
     * edges in The End; removing it makes island hopping unsolvable.
     *
     * <p><b>Being a lower bound is measured directly by {@code GuideAdmissibilityTest}.</b> Measured on
     * optimal paths: 0.65-0.98x. The last three of the five above were found there as overshoots of 1.22-2.02x.
     *
     * <p><b>The price is search breadth.</b> For island hopping in a real End (the segment in
     * {@code RealEndTerrainTest}): 69,159 → 260,176 nodes, about 1.2 s → about 3.2 s in wall time
     * including the parallel deep budget. Restoring preferences makes it faster but paths worse
     * (restoring the cliff penalty gives 147,073 nodes and about 1.4 s, but Overworld paths beyond
     * tolerance go from 7 to 34). This choice <b>favours path quality over guidance speed</b>.
     *
     * <p>Path cost ratio against the baseline (weight 1.0, no guide, unlimited budget). 40-90 blocks from
     * {@code PathOptimalityTest}, 200-450 blocks from {@code LongRouteOptimalityTest}:
     *
     * <pre>
     * 40-90 blocks    Overworld avg 0.96-1.01 worst 1.067 / Nether avg 0.82-1.00 / End avg 1.011
     * 200-450 blocks  Overworld avg 1.046 worst 1.078
     * </pre>
     */
    public static CostToGo costToGo(CoarseMap map, BlockPos goal, boolean boatAvailable, BridgePolicy bridgePolicy) {
        return costToGo(map, goal, boatAvailable, bridgePolicy, false);
    }

    /**
     * Estimate outside the nav graph's window. Looks up the same table as {@link #costToGo}, but for
     * coordinates below the floor the climb to the floor is <b>added rather than subtracted</b>.
     *
     * <p>The subtraction in {@link #costToGo} is there to keep the lower bound; as a window-edge value
     * it makes edges deeper underground look cheaper. Climbs inside the window are counted exactly, so
     * subtracting lets an exit win that "stays deep to the edge without climbing and pretends to climb
     * outside the window" (on coastal terrain, a route from depth -23 came out 1.19x optimal, heading
     * east while staying deep instead of using a water shaft inside the window).
     * The estimate outside the window needn't be a lower bound (the Nether's 3D coarse layer is also used at 1.3x).
     */
    public static CostToGo farEstimate(CoarseMap map, BlockPos goal, boolean boatAvailable, BridgePolicy bridgePolicy) {
        CoarseCostToGo table = costToGo(map, goal, boatAvailable, bridgePolicy, true);
        return new CoarseCostToGo(table.map(), table.cost(), table.goalOffset(), true, true);
    }

    private static CoarseCostToGo costToGo(CoarseMap map, BlockPos goal, boolean boatAvailable,
                                           BridgePolicy bridgePolicy, boolean chargeClimbFromBelow) {
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        int states = map.chunksX() * map.chunksZ() * CoarseMap.MAX_FLOORS;
        double[] cost = new double[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        if (!map.containsChunk(goalX, goalZ)) {
            return new CoarseCostToGo(map, cost,
                    centerOffsetCost(goal.getX(), goal.getZ(), goalX, goalZ), chargeClimbFromBelow, false);
        }
        double waterMultiplier = boatAvailable ? BOAT_MULTIPLIER : WATER_MULTIPLIER;
        int goalFloor = resolveFloor(map, goalX, goalZ, goal.getY());
        double goalOffset = centerOffsetCost(goal.getX(), goal.getZ(), goalX, goalZ)
                + floorOffsetCost(map, goalX, goalZ, goalFloor, goal.getY());
        int goalIndex = stateIndex(map, goalX, goalZ, goalFloor);
        cost[goalIndex] = 0.0;
        boolean[] closed = new boolean[states];

        PriorityQueue<Candidate> open =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimatedTotal));
        open.add(new Candidate(goalIndex, 0.0));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            int x = stateChunkX(map, current.index());
            int z = stateChunkZ(map, current.index());
            int floor = stateFloor(current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relaxBackwardHorizontal(map, cost, closed, open, x, z, floor, dx, dz, waterMultiplier,
                            bridgePolicy);
                }
            }
            relaxBackwardVertical(map, cost, closed, open, x, z, floor);
        }
        return new CoarseCostToGo(map, cost, goalOffset, chargeClimbFromBelow, false);
    }

    /**
     * Amount (ticks) to subtract when bringing a cell-center value down to block coordinates.
     *
     * <p>The table only holds values <b>from cell center to the destination cell's center</b>, so a raw
     * lookup gives the same value anywhere in the cell. In reality, both the looked-up coordinate and
     * the destination are offset from center by up to half a cell diagonal, and the table value
     * exceeds actual cost by that much.
     *
     * <p>Without subtracting, the max in {@code AStarPathfinder#node} picks a value larger than the
     * geometric lower bound ({@code Heuristic}), and <b>h gets a sawtooth with a 16-block period</b>.
     * Combined with weight 1.5 and a search that doesn't reopen closed nodes, the detail path gets
     * pulled to chunk borders and becomes <b>only long straights and right angles</b>.
     * Measured with the same start and end (left: path with the sawtooth; right: the current path,
     * matching the guide-less optimal path):
     *
     * <pre>
     * Synthetic flat     60 moves (22 diag / 38 straight)    → 40 moves (all diagonal)
     * Synthetic hills    150 moves (30 diag / 120 straight)  → 100 moves (80 diag)
     * Real End island    148 moves (32 diag / 116 straight)  → 101 moves (79 diag)
     * </pre>
     *
     * <p><b>Subtracting a flat one-cell diagonal ({@link #DIAGONAL_COST}) is too much.</b> It is an upper
     * bound on the overshoot, but it weakens the whole guide so the nodes expanded to cross the void
     * exceed the default budget (100k). The offset is known per coordinate, so only that measured value
     * is subtracted.
     */
    private static double centerOffsetCost(int x, int z, int chunkX, int chunkZ) {
        int dx = Math.abs(x - (chunkX * CELL_BLOCKS + CELL_BLOCKS / 2));
        int dz = Math.abs(z - (chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2));
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return (diagonal * ActionCosts.DIAGONAL_DISTANCE + straight) * ActionCosts.SPRINT_ONE_BLOCK;
    }

    /**
     * How far the coordinate is vertically offset from the height of its assigned floor.
     *
     * <p>Layer 1 only has <b>floors = horizontal surfaces</b>, so standing at a height not recorded as a
     * floor, like under an overhang or on a beach below a cliff, makes the estimate the value "after
     * climbing to that floor", exceeding actual cost. Measured (the coast in {@code GuideAdmissibilityTest}):
     * a route whose goal was y=48 below a cliff while layer 1's floor was only y=63 overshot by
     * <b>1.84x</b>. Subtracting the offset cancels this out.
     *
     * <p>The ascent rate is used for descent too (descent doesn't increase actual cost, so this
     * subtracts too much, but <b>subtracting too much doesn't break the lower bound</b>).
     */
    private static double floorOffsetCost(CoarseMap map, int chunkX, int chunkZ, int floor, int y) {
        short height = stateHeight(map, chunkX, chunkZ, floor);
        if (height == CoarseMap.UNKNOWN_HEIGHT) {
            return 0.0;
        }
        return Math.abs(y - height) * GUIDE_ASCEND_COST_PER_BLOCK;
    }

    /**
     * Thin wrapper making {@link #costToGo}'s result look-up-able by block coordinate. Out-of-range or
     * no-data coordinates return 0 (layer 1 just has no information, and {@code AStarPathfinder} takes
     * the max with the geometric {@link net.prason.xaeronav.pathfinding.astar.Heuristic}, so returning 0
     * does no harm beyond "contributing nothing for lack of information"; returning
     * {@link Double#POSITIVE_INFINITY} would poison layer 3 with infinity just because its search range
     * is wider than this map's read range).
     *
     * @param goalOffset How far the destination is offset from the center of its cell ({@link #centerOffsetCost}).
     *                   Unlike the per-coordinate offset it doesn't change during the search, so it is computed once when building the table
     */
    /**
     * @param valueUnknownCells Return the unknown-cell table value even for cells missing from Xaero's map (estimate outside the window).
     *                          Returning 0 drops window-edge cells as "unknown" from the seeds, and where the map has nothing
     *                          (right after a teleport, unexplored caves) the window edge has no seeds at all and the guide is entirely empty
     */
    private record CoarseCostToGo(CoarseMap map, double[] cost, double goalOffset, boolean chargeClimbFromBelow,
                                  boolean valueUnknownCells) implements CostToGo {
        @Override
        public double estimate(int x, int y, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (!map.containsChunk(chunkX, chunkZ)) {
                return 0.0;
            }
            int floor = valueUnknownCells ? resolveFloor(map, chunkX, chunkZ, y) : map.nearestFloor(chunkX, chunkZ, y);
            if (floor < 0) {
                return 0.0;
            }
            double value = cost[stateIndex(map, chunkX, chunkZ, floor)];
            if (Double.isInfinite(value)) {
                return 0.0;
            }
            short height = stateHeight(map, chunkX, chunkZ, floor);
            double floorOffset = chargeClimbFromBelow && height != CoarseMap.UNKNOWN_HEIGHT && y < height
                    ? -(height - y) * GUIDE_ASCEND_COST_PER_BLOCK
                    : floorOffsetCost(map, chunkX, chunkZ, floor, y);
            return Math.max(0.0, value - centerOffsetCost(x, z, chunkX, chunkZ) - floorOffset - goalOffset);
        }
    }

    private static void relaxBackwardHorizontal(CoarseMap map, double[] cost, boolean[] closed,
                                                PriorityQueue<Candidate> open, int x, int z, int floor,
                                                int dx, int dz, double waterMultiplier, BridgePolicy bridgePolicy) {
        int neighborX = x + dx;
        int neighborZ = z + dz;
        if (!map.containsChunk(neighborX, neighborZ)) {
            return;
        }
        boolean diagonal = dx != 0 && dz != 0;
        int neighborFloorCount = Math.max(map.floorCount(neighborX, neighborZ), 1);
        for (int neighborFloor = 0; neighborFloor < neighborFloorCount; neighborFloor++) {
            // Swap from/to: compute "the cost of entering x from neighbor" (since we're running backwards).
            // lowerBound=true, so the passed value is discarded (the lower bound is always 1.0). Passing a
            // constant rather than the calibrated value documents the "guide is not calibrated" contract at the call site too
            double step = horizontalStepCost(map, neighborX, neighborZ, neighborFloor, x, z, floor, diagonal,
                    waterMultiplier, UNKNOWN_MULTIPLIER, bridgePolicy, true);
            if (Double.isInfinite(step)) {
                continue;
            }
            offerBackward(map, cost, closed, open, x, z, floor, neighborX, neighborZ, neighborFloor, step);
        }
    }

    private static void relaxBackwardVertical(CoarseMap map, double[] cost, boolean[] closed,
                                              PriorityQueue<Candidate> open, int x, int z, int floor) {
        int floorCount = map.floorCount(x, z);
        if (floorCount == 0) {
            return;
        }
        for (int neighborFloor : new int[] {floor - 1, floor + 1}) {
            if (neighborFloor < 0 || neighborFloor >= floorCount) {
                continue;
            }
            // Running backwards, so this is "climbing from neighborFloor to floor". Not pricing descent
            // and not applying LAYER_TRANSITION_PENALTY is per costToGo's javadoc
            double climb = Math.max(0,
                    map.heightAtFloor(x, z, floor) - map.heightAtFloor(x, z, neighborFloor));
            offerBackward(map, cost, closed, open, x, z, floor, x, z, neighborFloor,
                    climb * GUIDE_ASCEND_COST_PER_BLOCK);
        }
    }

    private static void offerBackward(CoarseMap map, double[] cost, boolean[] closed,
                                      PriorityQueue<Candidate> open, int fromX, int fromZ, int fromFloor,
                                      int toX, int toZ, int toFloor, double step) {
        int nextIndex = stateIndex(map, toX, toZ, toFloor);
        if (closed[nextIndex]) {
            return;
        }
        double tentative = cost[stateIndex(map, fromX, fromZ, fromFloor)] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        open.add(new Candidate(nextIndex, tentative));
    }

    /**
     * Resolves which floor the start and end coordinates actually refer to. For a known cell, the floor
     * closest to the actual Y; for an unknown cell, the only state ({@code floor=0}, for which {@link #stateKind} returns {@code NO_DATA}).
     */
    private static int resolveFloor(CoarseMap map, int chunkX, int chunkZ, int y) {
        int floor = map.nearestFloor(chunkX, chunkZ, y);
        return floor < 0 ? 0 : floor;
    }

    /**
     * Horizontal neighbours connect <b>only</b> to the floor closest in height to the current floor (not to all floors of the neighbour).
     *
     * <p>Connecting to all floors would let a move that should cross layers reach a distant layer in the
     * neighbouring cell "disguised as a horizontal move", paying only the same {@code heightPenalty} as an
     * ordinary slope and skipping {@link #LAYER_TRANSITION_PENALTY}. That becomes a loophole where
     * horizontal moves bypass the "not knowing whether it's really connected" surcharge that
     * {@link #relaxVertical} is supposed to charge explicitly. Restricting to the nearest floor keeps
     * gentle slopes traversable as-is, while abrupt layer changes always go through a vertical transition.
     */
    private static void relaxHorizontal(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                                        PriorityQueue<Candidate> open, int x, int z, int floor, int dx, int dz,
                                        int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic,
                                        double waterMultiplier, double unknownMultiplier,
                                        BridgePolicy bridgePolicy) {
        int nextX = x + dx;
        int nextZ = z + dz;
        if (!map.containsChunk(nextX, nextZ)) {
            return;
        }
        boolean diagonal = dx != 0 && dz != 0;
        int nextFloor = nearestConnectableFloor(map, x, z, floor, nextX, nextZ);
        double step = horizontalStepCost(map, x, z, floor, nextX, nextZ, nextFloor, diagonal, waterMultiplier,
                unknownMultiplier, bridgePolicy, false);
        if (Double.isInfinite(step)) {
            return;
        }
        offer(map, cost, previous, closed, open, x, z, floor, nextX, nextZ, nextFloor, step, goalX, goalZ,
                bestSoFar, bestHeuristic, waterMultiplier);
    }

    /** The floor of the neighbouring cell closest to the current floor's height. If it is unknown, its only state (floor=0). */
    private static int nearestConnectableFloor(CoarseMap map, int fromX, int fromZ, int fromFloor,
                                               int toX, int toZ) {
        if (map.floorCount(toX, toZ) == 0) {
            return 0;
        }
        short fromHeight = stateHeight(map, fromX, fromZ, fromFloor);
        if (fromHeight == CoarseMap.UNKNOWN_HEIGHT) {
            return 0;
        }
        return map.nearestFloor(toX, toZ, fromHeight);
    }

    /**
     * Moves to the floor one above or one below within the same cell. Floors are sorted by ascending
     * height, so the adjacent index is the "next closest layer". Unknown cells (0 floors) have no floors, so this doesn't occur.
     */
    private static void relaxVertical(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                                      PriorityQueue<Candidate> open, int x, int z, int floor,
                                      int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic,
                                      double waterMultiplier) {
        int floorCount = map.floorCount(x, z);
        if (floorCount == 0) {
            return;
        }
        for (int nextFloor : new int[] {floor - 1, floor + 1}) {
            if (nextFloor < 0 || nextFloor >= floorCount) {
                continue;
            }
            double deltaHeight =
                    Math.abs(map.heightAtFloor(x, z, nextFloor) - map.heightAtFloor(x, z, floor));
            double step = deltaHeight * HEIGHT_COST_PER_BLOCK * LAYER_TRANSITION_PENALTY;
            offer(map, cost, previous, closed, open, x, z, floor, x, z, nextFloor, step, goalX, goalZ,
                    bestSoFar, bestHeuristic, waterMultiplier);
        }
    }

    private static void offer(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                              PriorityQueue<Candidate> open, int fromX, int fromZ, int fromFloor,
                              int toX, int toZ, int toFloor, double step, int goalX, int goalZ,
                              int[] bestSoFar, double[] bestHeuristic, double waterMultiplier) {
        int nextIndex = stateIndex(map, toX, toZ, toFloor);
        if (closed[nextIndex]) {
            return;
        }
        int fromIndex = stateIndex(map, fromX, fromZ, fromFloor);
        double tentative = cost[fromIndex] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        previous[nextIndex] = fromIndex;
        double remaining = heuristic(map, toX, toZ, goalX, goalZ, waterMultiplier);
        open.add(new Candidate(nextIndex, tentative + remaining));

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double candidateHeuristic = remaining + tentative / COEFFICIENTS[i];
            if (candidateHeuristic < bestHeuristic[i]) {
                bestHeuristic[i] = candidateHeuristic;
                bestSoFar[i] = nextIndex;
            }
        }
    }

    /**
     * Chooses the endpoint when the goal isn't reached. In order from the smallest coefficient (= weighing
     * actual distance travelled most), takes the candidate at least {@link #MIN_DIST_CELLS} from the
     * start on the XZ plane. If none qualifies, returns the start itself, treated as an empty route =
     * "no route to present".
     */
    private static int selectFallback(CoarseMap map, int[] bestSoFar, int startIndex) {
        int startX = stateChunkX(map, startIndex);
        int startZ = stateChunkZ(map, startIndex);
        double thresholdSquared = MIN_DIST_CELLS * MIN_DIST_CELLS;
        for (int candidate : bestSoFar) {
            double dx = stateChunkX(map, candidate) - startX;
            double dz = stateChunkZ(map, candidate) - startZ;
            if (dx * dx + dz * dz > thresholdSquared) {
                return candidate;
            }
        }
        return startIndex;
    }

    /** Multiplier the long-distance route ({@link #findRoute}) applies to unknown cells. Void is assumed bridgeable ({@link BridgePolicy#ALLOW}). */
    public static double unknownMultiplier(CoarseMap map) {
        return calibratedUnknownMultiplier(map, BridgePolicy.ALLOW);
    }

    /**
     * Calibrates {@link #UNKNOWN_MULTIPLIER} from the land:void ratio <b>already known</b> in this
     * {@code map}. Applies only to routes drawn by {@code findRoute} (values including preferences); the
     * guide of {@link #costToGo} is left alone to keep its lower-bound contract (callers don't use this
     * return value when {@code lowerBound}).
     *
     * <p><b>Derived from the live map's measurements, not a fixed per-dimension constant.</b>
     * Aggregating three real End terrain dumps ({@code EndUnknownVoidRatioBenchTest}), the void ratio of
     * known cells is 35-53% (about 42% overall): the 1.6x set on the premise "unknown is mostly land"
     * is systematically too optimistic. But hard-coding "raise the multiplier only in The End" can't
     * reflect that dense island clusters and open void areas differ even within The End (this test's
     * samples alone range from 35% to 53%). <b>Using the known breakdown as-is self-calibrates in any dimension.</b>
     *
     * <p>While known cells are few, {@link #UNKNOWN_PRIOR_WEIGHT_CELLS} worth of "the original 1.6x"
     * keeps it from fully shifting to the measurement (see there for why this curbs overfitting without
     * a step). The calibrated value never drops below 1.6, preserving the original design intent "treat
     * the unknown as somewhat costly" (without assuming it's impassable). It also tops out at
     * {@link #VOID_BRIDGE_MULTIPLIER} (when all known cells are void).
     *
     * <p><b>The price is search breadth.</b> {@link #heuristic} is a geometric lower bound assuming a
     * multiplier of 1.0, so the closer unknown gets to 10x, the further h drifts from actual cost and
     * the more cells layer 1 expands. Layer 1 is per chunk and at worst degrades to Dijkstra over the
     * whole map, so there's no risk of burning the budget.
     */
    private static double calibratedUnknownMultiplier(CoarseMap map, BridgePolicy bridgePolicy) {
        double voidMultiplier = bridgeMultiplier(CoarseMap.VOID, bridgePolicy);
        if (Double.isInfinite(voidMultiplier)) {
            // In AVOID the void itself is impassable. Nothing to mix with, so pass through
            return UNKNOWN_MULTIPLIER;
        }
        int[] counts = map.kindCounts();
        int sample = counts[CoarseMap.LAND] + counts[CoarseMap.VOID];
        double voidRatio = (counts[CoarseMap.VOID] + UNKNOWN_PRIOR_WEIGHT_CELLS * UNKNOWN_PRIOR_VOID_RATIO)
                / (sample + UNKNOWN_PRIOR_WEIGHT_CELLS);
        return Math.max(UNKNOWN_MULTIPLIER, (1.0 - voidRatio) + voidRatio * voidMultiplier);
    }

    /**
     * Cost of advancing one cell.
     *
     * @param lowerBound Compute a value used as a <b>lower bound</b> on actual cost (for {@link #costToGo}'s guide).
     *                   If {@code false}, the planning value including preferences (for {@link #findRoute}).
     *                   See {@link #costToGo}'s javadoc for the difference
     * @param unknownMultiplier Price of {@code NO_DATA}, used only when {@code lowerBound} is
     *                          {@code false} ({@link #calibratedUnknownMultiplier}). Ignored when
     *                          {@code lowerBound} is {@code true} (the lower bound is always 1.0), so
     *                          {@link #costToGo}'s callers may pass any value
     */
    private static double horizontalStepCost(CoarseMap map, int fromX, int fromZ, int fromFloor,
                                             int toX, int toZ, int toFloor, boolean diagonal,
                                             double waterMultiplier, double unknownMultiplier,
                                             BridgePolicy bridgePolicy, boolean lowerBound) {
        byte kind = stateKind(map, toX, toZ, toFloor);
        double bridgeMultiplier = bridgeMultiplier(kind, bridgePolicy);
        if (Double.isInfinite(bridgeMultiplier)) {
            return ActionCosts.INFEASIBLE;
        }
        double base = diagonal ? DIAGONAL_COST : STRAIGHT_COST;
        double multiplier = switch (kind) {
            // Even when "half the cell is water", it's common for a row to be crossable for 16 blocks
            // along a dry strip. Diluting by the fraction still isn't a lower bound; measured, the coast's
            // guide overshot the actual remaining cost by up to 2.02x (GuideAdmissibilityTest). Same for the 25% of lava-mixed
            case CoarseMap.WATER -> lowerBound ? 1.0 : waterMultiplier;
            case CoarseMap.LAVA_MIXED -> lowerBound ? 1.0 : bridgeMultiplier;
            // "Unknown" isn't a reason to raise the lower bound. Using the planning multiplier as the lower
            // bound makes everything outside the read range look uniformly expensive, pulling routes inside it
            case CoarseMap.NO_DATA -> lowerBound ? 1.0 : unknownMultiplier;
            case CoarseMap.LAVA, CoarseMap.VOID -> bridgeMultiplier;
            default -> 1.0;
        };

        double heightPenalty = 0.0;
        if (lowerBound) {
            // Since this is a lower bound, measure as entering <b>the lowest point in the cell from the highest</b>.
            // Measuring by the difference of representative heights (chunk average) makes a ridge cell whose
            // saddle can be crossed climb by its average height, breaking the lower bound
            short fromTop = stateMaxHeight(map, fromX, fromZ, fromFloor);
            short toBottom = stateMinHeight(map, toX, toZ, toFloor);
            if (fromTop != CoarseMap.UNKNOWN_HEIGHT && toBottom != CoarseMap.UNKNOWN_HEIGHT) {
                heightPenalty = Math.max(0, toBottom - fromTop) * GUIDE_ASCEND_COST_PER_BLOCK;
            }
        } else {
            short fromHeight = stateHeight(map, fromX, fromZ, fromFloor);
            short toHeight = stateHeight(map, toX, toZ, toFloor);
            // If either height is unknown, the step can't be measured. Treating unknown as a 0 step would
            // make unknown regions look like "flat shortcuts"
            if (fromHeight != CoarseMap.UNKNOWN_HEIGHT && toHeight != CoarseMap.UNKNOWN_HEIGHT) {
                heightPenalty = Math.abs(toHeight - fromHeight) * HEIGHT_COST_PER_BLOCK;
            }
        }
        if (lowerBound) {
            return base * multiplier + heightPenalty;
        }
        return base * multiplier + heightPenalty + cliffPenalty(map, toX, toZ, toFloor)
                + smallIslandPenalty(map, fromX, fromZ, toX, toZ);
    }

    /**
     * Surcharge applied only when moving to a different landmass, based on how small that island is.
     *
     * <p>The key point is <b>charging only on the step into the island</b>. Charging per cell would mean
     * paying repeatedly while crossing a small island, creating a different distortion: "small islands
     * are expensive even to pass through". All we want is "which island to land on", so it's checked
     * once on the edge where the landmass ID changes.
     */
    private static double smallIslandPenalty(CoarseMap map, int fromX, int fromZ, int toX, int toZ) {
        int toIsland = map.islandIdAt(toX, toZ);
        if (toIsland == CoarseMap.NO_ISLAND || toIsland == map.islandIdAt(fromX, fromZ)) {
            return 0.0;
        }
        int size = map.islandSizeAt(toX, toZ);
        if (size >= LARGE_ISLAND_CELLS) {
            return 0.0;
        }
        return SMALL_ISLAND_PENALTY * (LARGE_ISLAND_CELLS - size) / (double) (LARGE_ISLAND_CELLS - 1);
    }

    /**
     * Multiplier for cells that can't be crossed without placing blocks (lava, void). Returns 1.0 for
     * other cells (callers use other multipliers). {@link ActionCosts#INFEASIBLE} means impassable.
     */
    private static double bridgeMultiplier(byte kind, BridgePolicy bridgePolicy) {
        if (kind == CoarseMap.LAVA) {
            return bridgePolicy == BridgePolicy.BRIDGE ? LAVA_BRIDGE_MULTIPLIER : ActionCosts.INFEASIBLE;
        }
        if (kind == CoarseMap.LAVA_MIXED) {
            return bridgePolicy == BridgePolicy.AVOID ? ActionCosts.INFEASIBLE : LAVA_MIXED_MULTIPLIER;
        }
        if (kind == CoarseMap.VOID) {
            // Only {@link BridgePolicy#AVOID} makes the void impassable. ALLOW bridges it too.
            //
            // It's asymmetric with lava because <b>lava bridging has a config switch but void doesn't</b>
            // (layer 3's {@code addBridge} decides void only by {@code canPlaceBlocks}).
            // Making void impassable at ALLOW leaves layer 3's segmentation ({@code solveCoarseGuided})
            // unable to create any segment in The End, so it tries to cross between islands in one search
            // and burns the budget. This is the void version of what {@code PathfindingExecutor} documents,
            // with real-game records, as "a blanket ALLOW causes the same thing at the edge of a lava sea".
            return bridgePolicy == BridgePolicy.AVOID ? ActionCosts.INFEASIBLE : VOID_BRIDGE_MULTIPLIER;
        }
        return 1.0;
    }

    /** Extra cost when the entered floor has large relief. Treated as flat (0) if relief is unknown. */
    private static double cliffPenalty(CoarseMap map, int chunkX, int chunkZ, int floor) {
        short min = stateMinHeight(map, chunkX, chunkZ, floor);
        short max = stateMaxHeight(map, chunkX, chunkZ, floor);
        if (min == CoarseMap.UNKNOWN_HEIGHT || max == CoarseMap.UNKNOWN_HEIGHT) {
            return 0.0;
        }
        int relief = max - min;
        if (relief <= CLIFF_THRESHOLD_BLOCKS) {
            return 0.0;
        }
        return Math.min((relief - CLIFF_THRESHOLD_BLOCKS) * CLIFF_COST_PER_BLOCK, CLIFF_PENALTY_CAP);
    }

    /**
     * Lower bound on remaining cost. Looks only at distance on the XZ plane; ignoring the vertical
     * direction (cost of crossing layers) can only underestimate, so correctness as a lower bound
     * (admissibility) is preserved. Without applying {@code waterMultiplier} ({@link #BOAT_MULTIPLIER} < 1.0
     * when a boat is available), the actual cost of a route entirely in boat water falls below this bound, making it inadmissible.
     */
    private static double heuristic(CoarseMap map, int x, int z, int goalX, int goalZ, double waterMultiplier) {
        int dx = Math.abs(goalX - x);
        int dz = Math.abs(goalZ - z);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        double multiplier = Math.min(1.0, waterMultiplier);
        return (diagonal * DIAGONAL_COST + straight * STRAIGHT_COST) * multiplier;
    }

    /**
     * Thins the route into intermediate targets. Returning every cell would call the detail search
     * every few chunks, reducing guidance to tracing a coarse line. Splits when either the horizontal or
     * vertical spacing is exceeded: a stretch climbing many layers doesn't advance horizontally, so
     * without vertical spacing it would collapse into a single segment.
     */
    private static Route buildRoute(CoarseMap map, int[] previous, int endIndex, int startIndex,
                                    boolean reachedGoal, int startY) {
        List<Integer> states = new ArrayList<>();
        for (int cursor = endIndex; cursor != -1; cursor = previous[cursor]) {
            states.add(cursor);
            if (cursor == startIndex) {
                break;
            }
        }
        Collections.reverse(states);
        if (states.size() <= 1) {
            return new Route(List.of(), reachedGoal);
        }

        List<BlockPos> waypoints = new ArrayList<>();
        int lastX = stateChunkX(map, states.get(0));
        int lastZ = stateChunkZ(map, states.get(0));
        // Fallback for states with unknown height inherits the last known height (or the start if none).
        // A fixed 0 would, in dimensions like the Nether whose main terrain height band is far from 0,
        // make the detail search try to route to the bottom of the void and burn through the node limit
        int fallbackHeight = startY;
        int lastWaypointHeight = startY;
        for (int i = 1; i < states.size(); i++) {
            int state = states.get(i);
            int x = stateChunkX(map, state);
            int z = stateChunkZ(map, state);
            int floor = stateFloor(state);
            boolean last = i == states.size() - 1;
            int spanX = Math.abs(x - lastX);
            int spanZ = Math.abs(z - lastZ);
            short height = stateHeight(map, x, z, floor);
            int spanY = height == CoarseMap.UNKNOWN_HEIGHT ? 0 : Math.abs(height - lastWaypointHeight);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS
                    || spanY >= WAYPOINT_VERTICAL_SPACING_BLOCKS) {
                BlockPos waypoint = toBlockPos(x, z, height, fallbackHeight);
                waypoints.add(waypoint);
                fallbackHeight = waypoint.getY();
                lastWaypointHeight = waypoint.getY();
                lastX = x;
                lastZ = z;
            }
        }
        return new Route(List.copyOf(waypoints), reachedGoal);
    }

    /** Center of the cell. States with unknown height use {@code fallbackHeight}. */
    private static BlockPos toBlockPos(int chunkX, int chunkZ, short height, int fallbackHeight) {
        return new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2,
                height == CoarseMap.UNKNOWN_HEIGHT ? fallbackHeight : height,
                chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2);
    }

    /**
     * Distinguishes an unknown cell's "unknown" state from a known floor by whether {@code floor} exists
     * ({@code floorCount>0}). Unknown cells always have exactly one state, {@code floor==0}.
     */
    private static boolean isUnknownState(CoarseMap map, int chunkX, int chunkZ) {
        return map.floorCount(chunkX, chunkZ) == 0;
    }

    private static byte stateKind(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.NO_DATA : map.kindAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.heightAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateMinHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.minHeightAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateMaxHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.maxHeightAtFloor(chunkX, chunkZ, floor);
    }

    private static int stateIndex(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return cellIndex(map, chunkX, chunkZ) * CoarseMap.MAX_FLOORS + floor;
    }

    private static int cellIndex(CoarseMap map, int chunkX, int chunkZ) {
        return (chunkZ - map.minChunkZ()) * map.chunksX() + (chunkX - map.minChunkX());
    }

    private static int stateChunkX(CoarseMap map, int stateIndex) {
        int cellIndex = stateIndex / CoarseMap.MAX_FLOORS;
        return map.minChunkX() + cellIndex % map.chunksX();
    }

    private static int stateChunkZ(CoarseMap map, int stateIndex) {
        int cellIndex = stateIndex / CoarseMap.MAX_FLOORS;
        return map.minChunkZ() + cellIndex / map.chunksX();
    }

    private static int stateFloor(int stateIndex) {
        return stateIndex % CoarseMap.MAX_FLOORS;
    }

    private record Candidate(int index, double estimatedTotal) {
    }
}
