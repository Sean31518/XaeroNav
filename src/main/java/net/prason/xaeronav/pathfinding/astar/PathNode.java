package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * A single node in the search. Exactly one is created per coordinate, and it holds the cost, path and heap position.
 *
 * <p>Splitting gScore, cameFrom and closed into separate Maps would cost several hash computations and
 * entry allocations per node. Consolidating them into one object means the only hash lookup for a node is
 * {@link AStarPathfinder}'s single coordinate→node Map.
 */
final class PathNode {

    final int x;
    final int y;
    final int z;

    /**
     * Whether riding a boat. <b>Part of the node's identity alongside its coordinates</b>;
     * {@link AStarPathfinder} keeps the riding and non-riding states as separate nodes.
     *
     * <p>This can't be a non-key approximation like {@link #bridgeRun} or {@link #submergedTicks}. Boarding is a large
     * one-off cost concentrated in a single move, and A* expands cheap edges first, so if both were merged into one node
     * <b>the swimming side would always be finalized first and become {@link #closed}, and the boat branch could never improve</b>;
     * it would never be chosen, however much better its total cost.
     */
    final boolean boating;

    /**
     * Estimated cost to the goal. Computed once at creation. Only {@link #guideHole} nodes inherit it from the parent and rise with each relaxation.
     */
    double estimatedCostToGoal;

    /** The guide has no value for this cell ({@link CostToGo#searchEstimate} is {@link Double#NaN}). */
    final boolean guideHole;

    double cost = ActionCosts.INFEASIBLE;
    double combinedCost;

    PathNode previous;
    MoveKind kind;

    /**
     * The number of blocks travelled consecutively up to here on footing you placed yourself ({@link MoveKind#BRIDGE} and
     * {@link MoveKind#PILLAR}). Used by {@link AStarPathfinder#addBridge} for the cap check.
     *
     * <p>It resets to 0 <b>when standing on a floor that actually exists as terrain</b>. Traverse, Ascend, Descend, Fall and Jump
     * all require the destination's footing to be {@code standable} in the terrain data, so putting one of them in between
     * naturally resets it to 0. Only Pillar requires no footing (you stand on the block you just placed), so it carries the
     * count over without resetting; back when this reset to 0, bridging up to the cap and then stacking one block was enough to
     * break the cap.
     *
     * <p><b>Not part of this node's identity</b> (the key is the coordinates only). A path arriving at the same cell over a short
     * bridge and one arriving over a long bridge are merged into the same node, and the run length of whichever is finalized
     * cheapest first is kept. Edge costs don't depend on the run length (bridges over the cap are simply not created), so path
     * costs aren't distorted, but the cap check becomes an approximation based on "the run length of the cheapest path to arrive",
     * which can miss the possibility of going via stepping stones on terrain where they'd allow a cheap crossing. If that miss
     * results in "no path within range", the search is retried without the cap by checking {@link AStarPathfinder#bridgeRunCapBlocked()}.
     */
    int bridgeRun;

    /**
     * The total number of blocks placed along the path so far ({@link MoveKind#BRIDGE} and {@link MoveKind#PILLAR}).
     * Used by {@link AStarPathfinder#addBridge} to compare against the inventory budget. The start isn't necessarily 0:
     * the number already committed by the preceding segment is received via {@link Carryover#placedBlocks()}.
     *
     * <p><b>Unlike {@link #bridgeRun}, it doesn't reset to 0 on standing on a floor.</b> That one measures the danger of a miss by
     * "how many blocks one bridge continues", whereas this is the cumulative resources consumed; the blocks don't come back once
     * you've crossed and stepped down onto the ground.
     *
     * <p>Like {@link #bridgeRun}, <b>it's not part of this node's identity</b> (the key is the coordinates only).
     * When paths with fewer and more placements reach the same cell, it's an approximation keeping the value of whichever is finalized cheapest first.
     * Edge costs don't depend on this value (bridges over the budget are simply not created), so path costs aren't distorted.
     * If a miss results in "no path within range",
     * the search is retried without the budget by checking {@link AStarPathfinder#placedBudgetBlocked()}.
     */
    int placedTotal;

    /**
     * The number of ticks elapsed up to here with the head underwater. Resets to 0 on surfacing or reaching land.
     * Used by {@link AStarPathfinder#relax} for the cap check.
     *
     * <p>Air runs out after {@code AIR_SUPPLY_TICKS}, so this is literally "whether your breath lasts".
     * <b>Counting in ticks rather than blocks</b> is the key: mining underwater takes tens of ticks per block, and 25 times
     * as long while swimming, yet counting by blocks turned 40 ticks of mining into just "one block", letting routes that
     * dig through underwater slip past the breath cap.
     *
     * <p>It counts by the <b>head cell</b> to match vanilla: {@code LivingEntity#baseTick} decreases air via
     * {@code isEyeInFluid(WATER)}, so air doesn't drop while your face is out, even when submerged to the waist.
     *
     * <p>Like {@link #bridgeRun}, <b>it's not part of the node's identity</b> (the key is the coordinates only).
     * A path arriving at the same cell after a short dive and one after a long dive are merged into the same node, and
     * whichever is finalized cheapest first is kept. If the cap leaves no path within range,
     * the search is retried without the cap by checking {@link AStarPathfinder#submergedRunCapBlocked()}.
     *
     * <p>The start is always counted from 0. The possibility that air isn't full when the dive begins is absorbed by
     * leaving headroom on the cap side.
     */
    double submergedTicks;

    /** Position within {@link BinaryHeapOpenSet}. Needed for decrease-key. -1 means outside the open set. */
    int heapPosition = -1;

    /**
     * Whether this node has been expanded. {@link #heapPosition} only tells whether it's "in the open set now", so
     * expanded nodes are indistinguishable from undiscovered ones.
     *
     * <p>Weighting the heuristic breaks consistency, so the cost of an already-expanded node can improve later.
     * Putting it back into the open set each time re-expands the same cell over and over (measured at over 6 times per cell,
     * with the expansions needed to reach the goal ballooning to 6 times the number of distinct cells). In exchange for not
     * putting it back, the path cost stays within {@code heuristicWeight} times the optimum (the weighted A* guarantee).
     */
    boolean closed;

    /**
     * Whether there is a fatal spot to step off sideways (a memo for {@code AStarPathfinder#edgeHazardPenalty}). 0 = unchecked, 1 = none, 2 = present.
     * A cell is reached from many surrounding moves, and re-reading the surroundings per move made the Nether random-route check about 10% slower.
     */
    byte edgeHazard;

    PathNode(int x, int y, int z, boolean boating, double estimatedCostToGoal) {
        this(x, y, z, boating, estimatedCostToGoal, false);
    }

    PathNode(int x, int y, int z, boolean boating, double estimatedCostToGoal, boolean guideHole) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.boating = boating;
        this.estimatedCostToGoal = estimatedCostToGoal;
        this.guideHole = guideHole;
    }

    boolean isOpen() {
        return heapPosition != -1;
    }
}
