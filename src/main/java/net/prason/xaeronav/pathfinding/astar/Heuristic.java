package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * Per-axis heuristic (version supporting diagonal ascend/descend).
 * Sums lower bounds that never exceed the real cost for horizontal movement, ascent and descent separately.
 * Horizontal distance uses the octile distance, which accounts for diagonal moves (same height only).
 *
 * <p>The exception is ice: only on ice does the real cost of one block fall below this lower bound (plain sprinting)
 * ({@code CellData}'s speed multiplier). Paths containing ice may therefore deviate slightly from optimal, but
 * lowering the bound to match ice would uniformly weaken the heuristic even where there's no ice, and
 * the search would spread and hit the expanded node cap first (= the path gets cut short). The latter harm, which applies
 * regardless of whether there's ice, is larger, so the bound stays at sprinting.
 *
 * <p>Ascent can be carried at no extra cost up to the amount that can "ride along" with each diagonal and cardinal
 * move ({@code Ascend}/{@code DiagonalAscend} do horizontal movement and climbing in one move).
 * Adding them independently would estimate a single {@code Ascend} of 1 horizontal + 1 up (real cost {@code ASCEND_ONE_BLOCK})
 * as two moves, "1 horizontal + 1 climb", exceeding the real cost (inadmissible).
 * For descent, {@code FALL_ASYMPTOTIC_MIN_PER_BLOCK} is already a sufficiently small bound, so it's simply added without considering ride-along.
 */
public final class Heuristic {

    /**
     * Lower bound for one step of climbing that can ride along with a diagonal move. Takes <b>the cheaper of land and water</b>: swimming up
     * isn't a jump, so {@link ActionCosts#STEP_TRANSITION_TICKS} doesn't apply, and it's
     * cheaper than {@code DIAGONAL_ASCEND_ONE_BLOCK}. Using only the land value as the bound would be inadmissible underwater.
     */
    private static final double MIN_DIAGONAL_ASCEND = Math.min(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK,
            ActionCosts.DIAGONAL_SWIM_ASCEND_ONE_BLOCK);

    /** Lower bound for one step of climbing that can ride along with a cardinal move. Water is considered for the same reason as {@link #MIN_DIAGONAL_ASCEND}. */
    private static final double MIN_CARDINAL_ASCEND =
            Math.min(ActionCosts.ASCEND_ONE_BLOCK, ActionCosts.SWIM_ASCEND_ONE_BLOCK);

    /**
     * Lower bound for one step of pure climbing ({@code pureAscends}) that can't ride along with horizontal movement.
     *
     * <p>"No horizontal displacement is needed, so the ladder ({@link ActionCosts#LADDER_UP_ONE_BLOCK}) is the bound" is wrong.
     * <b>Going back and forth</b> horizontally with {@code Ascend} gains height with zero net horizontal displacement;
     * a switchback staircase has exactly that shape, and its real cost is only {@link ActionCosts#ASCEND_ONE_BLOCK} per step.
     * Using the ladder (8.511) as the bound makes the estimate exceed the real cost on such terrain, which is inadmissible
     * (example: {@code (0,64,0)→(1,67,0)} is Ascend×3 = 13.90, but the estimate was 21.65).
     *
     * <p>{@code Ascend} moves always come with 1 horizontal step, but the point is that step <b>can be undone</b>. Doubling back
     * gains height with zero net horizontal displacement, so this remains the bound even after the horizontal ride-along slots are used up.
     *
     * <p><b>Considering water too is the point.</b> {@code SwimUp} (7.407), which doesn't incur {@link ActionCosts#STEP_TRANSITION_TICKS},
     * is cheaper than land {@code Ascend} (7.633), which does. {@code ClimbUp} (8.511) and
     * {@code Pillar} (which incurs the placement cost) are more expensive than either, so they needn't be considered.
     */
    private static final double MIN_PURE_ASCEND =
            Math.min(ActionCosts.ASCEND_ONE_BLOCK, ActionCosts.SWIM_UP_ONE_BLOCK);

    private Heuristic() {
    }

    /** Variant without a specified lower bound. Uses values that are safe in any dimension or setting. */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return estimate(fromX, fromY, fromZ, toX, toY, toZ, ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK,
                ActionCosts.SPRINT_ONE_BLOCK);
    }

    /** Variant without a specified horizontal lower bound. Correct for searches that assume travel on foot (the fastest horizontal move is sprinting). */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                                   double minDescentTicksPerBlock) {
        return estimate(fromX, fromY, fromZ, toX, toY, toZ, minDescentTicksPerBlock,
                ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * @param minDescentTicksPerBlock the cheapest per-block cost among descent moves this search can generate
     *                               ({@link net.prason.xaeronav.pathfinding.world.CellSource#minDescentTicksPerBlock}).
     *                               The bound from terminal velocity (0.2551) assumes <b>falls of any depth can occur</b>,
     *                               and can be tightened a lot if the largest drop actually generated is known:
     *                               in the Nether with fall damage tolerance off, 3 blocks is the max, giving 4.392, a 17x difference.
     *                               If this is loose, the real cost of recovering one climbed block (9.321) looks almost free,
     *                               and weighted A* systematically favors uphill branches and settles them as {@code closed}
     * @param minHorizontalTicksPerBlock the cheapest per-block cost among horizontal moves that can be generated
     *                               from that node. Normally sprinting ({@link ActionCosts#SPRINT_ONE_BLOCK}), but
     *                               <b>only for nodes riding a boat</b> does it drop to {@link ActionCosts#PADDLE_ONE_BLOCK}.
     *                               Estimating at sprint speed would be inadmissible for boat nodes, and
     *                               together with the large one-time cost of boarding, <b>the boat branch would never be expanded</b>:
     *                               the swimming frontier reaches the goal first, and it isn't chosen even when much better on total cost
     */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                                   double minDescentTicksPerBlock, double minHorizontalTicksPerBlock) {
        double straight = minHorizontalTicksPerBlock;
        double diagonalStep = straight * ActionCosts.DIAGONAL_DISTANCE;
        int dx = Math.abs(toX - fromX);
        int dz = Math.abs(toZ - fromZ);
        int dy = toY - fromY;

        int diagonalSteps = Math.min(dx, dz);
        int cardinalSteps = Math.abs(dx - dz);
        int up = Math.max(0, dy);
        int down = Math.max(0, -dy);

        // Let ascent ride along with diagonal moves first (bigger savings), then let the rest ride along with cardinal moves.
        // Only what's still left over is charged as pure climbing without horizontal movement (ladders, digging up, etc.).
        int diagonalAscends = Math.min(up, diagonalSteps);
        int cardinalAscends = Math.min(up - diagonalAscends, cardinalSteps);
        int pureAscends = up - diagonalAscends - cardinalAscends;

        // Descent rides along in the same horizontal slots. `up` and `down` are mutually exclusive, so they don't compete for slots.
        // The portion that rides along gets zero extra cost. In reality each step incurs
        // {@code ActionCosts#STEP_TRANSITION_TICKS}, so this underestimates, but it's correct as a lower bound
        // (only overestimating is inadmissible). Back when they were simply added, with a Nether-equivalent bound (4.392),
        // the estimate for one diagonal descent was 9.432, exceeding the real cost 9.321, which was inadmissible.
        int ridableDescends = Math.min(down, diagonalSteps + cardinalSteps);
        int pureDescends = down - ridableDescends;

        double horizontalAndAscend = diagonalAscends * MIN_DIAGONAL_ASCEND
                + (diagonalSteps - diagonalAscends) * diagonalStep
                + cardinalAscends * MIN_CARDINAL_ASCEND
                + (cardinalSteps - cardinalAscends) * straight
                + pureAscends * MIN_PURE_ASCEND;
        double descend = pureDescends * minDescentTicksPerBlock;
        return horizontalAndAscend + descend;
    }
}
