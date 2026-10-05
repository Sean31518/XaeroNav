package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.util.MathSupport;

/**
 * Derives "remaining distance", "time required" and "whether the end of the route is near" from the route being shown. The time
 * can include the estimate from the end of the route to the destination ({@link GoalEta}).
 *
 * <p>The cumulative totals up to each point depend only on the route, so they're built once per route and reused
 * ({@link Route}). The only thing that changes as the player moves is "where on the route they are now" ({@link PathProgress}),
 * and queries from there on are answered by subtracting cumulative totals.
 */
final class NavGuidance {

    /** Show the end-of-route guidance once within this distance. */
    private static final int ARRIVAL_BLOCKS = 3;

    /**
     * Range of the factor used when rescaling the time by the measured speed. Applying the slowness just before stopping,
     * or a momentary burst of speed in a vehicle, as-is would change the order of magnitude.
     */
    private static final double PACE_FACTOR_MIN = 0.5;
    private static final double PACE_FACTOR_MAX = 4.0;

    /**
     * Granularity of the displayed seconds. The measured speed always fluctuates, so showing it to the second makes the
     * number restless, and the display ends up less trustworthy.
     */
    private static final int SECONDS_GRANULARITY = 5;
    /**
     * Granularity of seconds that include an estimate (over 1 minute, over 10 minutes). The estimate moves by tens of seconds
     * each time the window advances, so 5-second steps would suggest a precision it doesn't have.
     */
    private static final int ESTIMATE_GRANULARITY_MINUTE = 10;
    private static final int ESTIMATE_GRANULARITY_LONG = 30;

    private static final PathCache<Route> ROUTES = new PathCache<>();

    final int remainingBlocks;
    final int remainingSeconds;
    final boolean nearEnd;
    final boolean complete;

    private NavGuidance(int remainingBlocks, int remainingSeconds, boolean nearEnd, boolean complete) {
        this.remainingBlocks = remainingBlocks;
        this.remainingSeconds = remainingSeconds;
        this.nearEnd = nearEnd;
        this.complete = complete;
    }

    /**
     * @param beyondTicks estimate (ticks) from the end of the route to the destination. 0 if the route reaches the destination
     */
    static NavGuidance forPath(PathResult result, BlockPos playerPos, double beyondTicks) {
        return ROUTES.get(result, Route::new).guidanceAt(playerPos, beyondTicks);
    }

    /** Time required (seconds) when there is no route yet. {@code ticks} is the {@link GoalEta} estimate. */
    static int estimateSeconds(double ticks) {
        return roundSeconds(ticks * paceFactor(1.0 / ActionCosts.SPRINT_ONE_BLOCK) / 20.0, true);
    }

    private static NavGuidance build(Route route, double beyondTicks) {
        int from = PathProgress.INSTANCE.indexFor(route.source);
        int last = route.source.steps().size() - 1;

        double blocks = route.blocks[last] - route.blocks[from];
        double seconds = route.remainingTicks(from, beyondTicks) / 20.0;
        // Don't show "about 0 seconds" while there's still distance remaining
        int rounded = Math.max(blocks > 0.0 ? SECONDS_GRANULARITY : 0, roundSeconds(seconds, beyondTicks > 0.0));
        return new NavGuidance((int) Math.round(blocks), rounded,
                blocks <= ARRIVAL_BLOCKS, route.source.complete());
    }

    private static int roundSeconds(double seconds, boolean estimated) {
        int granularity = !estimated || seconds < 60.0 ? SECONDS_GRANULARITY
                : seconds < 600.0 ? ESTIMATE_GRANULARITY_MINUTE : ESTIMATE_GRANULARITY_LONG;
        return (int) Math.round(seconds / granularity) * granularity;
    }

    /**
     * Factor for rescaling the time required by the measured speed.
     *
     * @param assumed the speed the estimate assumes (blocks/tick)
     */
    private static double paceFactor(double assumed) {
        double actual = NavPace.INSTANCE.blocksPerTick();
        return actual <= 0.0 ? 1.0 : MathSupport.clamp(assumed / actual, PACE_FACTOR_MIN, PACE_FACTOR_MAX);
    }

    /**
     * Per-route preparation. Holds the cumulative totals up to each step (distance, movement cost, work cost).
     */
    private static final class Route {

        private final PathResult source;
        /** Distance up to each step. */
        private final double[] blocks;
        /** The cost of movement itself, and the cost of digging, placing and opening. Treated differently when correcting the time. */
        private final double[] movementTicks;
        private final double[] movementBlocks;
        private final double[] actionTicks;

        // Guidance doesn't change until the player moves one block. The HUD is drawn every frame, so
        // queries while on the same block don't rebuild it
        private BlockPos cachedPos;
        private double cachedBeyondTicks;
        private NavGuidance cached;

        private Route(PathResult source) {
            this.source = source;
            List<PathStep> steps = source.steps();
            int size = steps.size();
            this.blocks = new double[size];
            this.movementTicks = new double[size];
            this.movementBlocks = new double[size];
            this.actionTicks = new double[size];

            for (int i = 1; i < size; i++) {
                PathStep step = steps.get(i);
                double distance = Math.sqrt(steps.get(i - 1).pos().distSqr(step.pos()));
                // The cost of segments that dig, place or deploy a boat has nothing to do with walking speed,
                // so exclude it from rescaling by measured speed
                boolean action = step.digging() || step.bridging() || step.boating();
                blocks[i] = blocks[i - 1] + distance;
                movementBlocks[i] = movementBlocks[i - 1] + (action ? 0.0 : distance);
                movementTicks[i] = movementTicks[i - 1] + (action ? 0.0 : step.cost());
                actionTicks[i] = actionTicks[i - 1] + (action ? step.cost() : 0.0);
            }
        }

        private NavGuidance guidanceAt(BlockPos playerPos, double beyondTicks) {
            if (cached == null || !playerPos.equals(cachedPos) || beyondTicks != cachedBeyondTicks) {
                cached = build(this, beyondTicks);
                cachedPos = playerPos;
                cachedBeyondTicks = beyondTicks;
            }
            return cached;
        }

        /**
         * Time required (ticks) from {@code from} on. The movement share and the estimate beyond the route are rescaled by the player's measured speed.
         *
         * <p>The reference speed is the speed the route assumes (the average over movement segments). Comparing against a fixed sprint
         * would doubly underestimate routes that are slow to begin with, such as swimming or walking underwater. The estimate beyond the
         * route is built from the same movement costs, so it's rescaled by the same factor.
         */
        private double remainingTicks(int from, double beyondTicks) {
            int last = source.steps().size() - 1;
            double movement = movementTicks[last] - movementTicks[from];
            double action = actionTicks[last] - actionTicks[from];
            double moved = movementBlocks[last] - movementBlocks[from];
            double assumed = movement > 0.0 && moved > 0.0 ? moved / movement : 1.0 / ActionCosts.SPRINT_ONE_BLOCK;
            return (movement + beyondTicks) * paceFactor(assumed) + action;
        }
    }
}
