package net.prason.xaeronav.pathfinding.cost;

/**
 * What a route should be optimised for, expressed as multipliers on the <b>preference</b> parts of the cost model.
 * The physical parts (walking, swimming, falling, digging and placing time) are never touched: every multiplier
 * applies to a surcharge that is added on top of the time a move really takes, so no move becomes cheaper than its
 * pure travel time.
 *
 * <p>Concrete values:
 *
 * <table>
 * <caption>Multipliers per profile</caption>
 * <tr><th></th><th>BALANCED</th><th>FASTEST</th><th>SAFEST</th><th>RESOURCE_SAVING</th></tr>
 * <tr><td>hazard ({@link ActionCosts#JUMP_REACH_PENALTY}, {@link ActionCosts#FALL_DAMAGE_PENALTY_PER_POINT},
 *     {@link ActionCosts#EDGE_HAZARD_PENALTY_TICKS})</td><td>1</td><td>0.5</td><td>4</td><td>1</td></tr>
 * <tr><td>fall risk ({@link ActionCosts#dropRiskPenalty}, which also prices bridges over the void)</td>
 *     <td>1</td><td>0.5</td><td>2</td><td>1</td></tr>
 * <tr><td>lava bridge ({@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS})</td><td>1</td><td>1</td><td>2</td><td>1</td></tr>
 * <tr><td>submerged travel ({@link ActionCosts#SUBMERGED_TRAVEL_PENALTY}, absolute value)</td>
 *     <td>1.3</td><td>1.3</td><td>1.4</td><td>1.3</td></tr>
 * <tr><td>placing action ({@link ActionCosts#PLACE_BLOCK_AIM_TICKS})</td><td>1</td><td>1</td><td>1</td><td>3</td></tr>
 * <tr><td>dig overhead per cell ({@link ActionCosts#DIG_OVERHEAD_TICKS})</td><td>1</td><td>1</td><td>1</td><td>3</td></tr>
 * </table>
 *
 * <p><b>Why SAFEST stops at 2 for the bridge-related prices.</b> Their upper bound is set by the search, not by taste:
 * A* exhausts "n blocks of walking" per bridge block before it reaches a bridge. At 5x today's lava price the
 * Nether search never reached the bridge ({@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}), and adding only the
 * 20-tick interruption surcharge to void bridges already made End island hopping unsolvable
 * ({@code BuildMoves#addBridge}).
 * "Do not bridge long stretches" is better expressed with the run caps ({@code maxLavaBridgeRunBlocks} /
 * {@code maxVoidBridgeRunBlocks}).
 *
 * <p><b>Why SAFEST stops at 1.4 for submerged travel.</b> Above {@code SWIM_ASCEND_ONE_BLOCK / SWIM_ONE_BLOCK}
 * (= √2) a route bobs up and down to dodge the surcharge ({@link ActionCosts#SUBMERGED_TRAVEL_PENALTY}); 1.4 is the
 * highest measured value without that artefact.
 *
 * <p><b>Placement and dig multipliers are never below 1.</b> The nav graph guide is built with the same moves, but
 * the geometric {@code Heuristic} and the layer 1 guides assume today's prices, and only raising a price keeps them
 * lower bounds (see {@code AStarPathfinder#placementCostTicks}).
 *
 * <p>SAFEST additionally forces {@code avoidRiskyJumps} on and turns fall damage tolerance off; that is applied
 * where the options are assembled ({@code MovementOptions}), not here, so the relaxation ladder sees the same
 * values as for a user who set them by hand.
 */
public enum RouteProfile {

    /** Today's cost model, unchanged. Every multiplier is exactly 1, so results are bit-identical to before. */
    BALANCED(1.0, 1.0, 1.0, ActionCosts.SUBMERGED_TRAVEL_PENALTY, 1.0, 1.0, false),

    /** Minimise travel time: the risk surcharges are halved, everything else as {@link #BALANCED}. */
    FASTEST(0.5, 0.5, 1.0, ActionCosts.SUBMERGED_TRAVEL_PENALTY, 1.0, 1.0, false),

    /** Prefer safe routes even when they are noticeably longer. */
    SAFEST(4.0, 2.0, 2.0, 1.4, 1.0, 1.0, true),

    /** Place and break as few blocks as possible; walks noticeably farther around obstacles instead. */
    RESOURCE_SAVING(1.0, 1.0, 1.0, ActionCosts.SUBMERGED_TRAVEL_PENALTY, 3.0, 3.0, false);

    private final double jumpReachPenalty;
    private final double fallDamagePenaltyPerPoint;
    private final double edgeHazardPenaltyTicks;
    private final double fallRiskScale;
    private final double lavaBridgePenaltyTicks;
    private final double submergedTravelPenalty;
    private final double placementCostScale;
    private final double digSurchargeTicks;
    private final boolean forcesSafeLimits;

    RouteProfile(double hazardScale, double fallRiskScale, double lavaBridgeScale, double submergedTravelPenalty,
                 double placementCostScale, double digOverheadScale, boolean forcesSafeLimits) {
        this.jumpReachPenalty = ActionCosts.JUMP_REACH_PENALTY * hazardScale;
        this.fallDamagePenaltyPerPoint = ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT * hazardScale;
        this.edgeHazardPenaltyTicks = ActionCosts.EDGE_HAZARD_PENALTY_TICKS * hazardScale;
        this.fallRiskScale = fallRiskScale;
        this.lavaBridgePenaltyTicks = ActionCosts.LAVA_BRIDGE_PENALTY_TICKS * lavaBridgeScale;
        this.submergedTravelPenalty = submergedTravelPenalty;
        this.placementCostScale = placementCostScale;
        // Only the part above today's price is added on top of the dig ticks already stored per cell
        this.digSurchargeTicks = ActionCosts.DIG_OVERHEAD_TICKS * (digOverheadScale - 1.0);
        this.forcesSafeLimits = forcesSafeLimits;
    }

    /** {@link ActionCosts#jumpAcrossGap} with this profile's reach penalty. */
    public double jumpAcrossGap(int gapBlocks) {
        return ActionCosts.JUMP_ACROSS_GAP + (gapBlocks - 1) * jumpReachPenalty;
    }

    /** {@link ActionCosts#FALL_DAMAGE_PENALTY_PER_POINT} for this profile. */
    public double fallDamagePenaltyPerPoint() {
        return fallDamagePenaltyPerPoint;
    }

    /** {@link ActionCosts#EDGE_HAZARD_PENALTY_TICKS} for this profile. */
    public double edgeHazardPenaltyTicks() {
        return edgeHazardPenaltyTicks;
    }

    /** {@link ActionCosts#dropRiskPenalty} for this profile. */
    public double dropRiskPenalty(int dropBlocks, int fatalFallBlocks) {
        return ActionCosts.dropRiskPenalty(dropBlocks, fatalFallBlocks) * fallRiskScale;
    }

    /** {@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS} for this profile. */
    public double lavaBridgePenaltyTicks() {
        return lavaBridgePenaltyTicks;
    }

    /** Replaces {@link ActionCosts#SUBMERGED_TRAVEL_PENALTY}. Always within [1, √2) (see the class Javadoc). */
    public double submergedTravelPenalty() {
        return submergedTravelPenalty;
    }

    /** Factor on the placing action ({@link ActionCosts#PLACE_BLOCK_AIM_TICKS}). Never below 1. */
    public double placementCostScale() {
        return placementCostScale;
    }

    /** Extra ticks per dug cell on top of the stored dig time. 0 unless the profile raises the dig overhead. */
    public double digSurchargeTicks() {
        return digSurchargeTicks;
    }

    /** Whether this profile forces {@code avoidRiskyJumps} on and fall damage tolerance off. */
    public boolean forcesSafeLimits() {
        return forcesSafeLimits;
    }
}
