package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the ordering relations that {@link ActionCosts} constants must satisfy.
 * The values themselves are computed directly from vanilla measurements, so no test pins them,
 * but relations like "sprinting is cheaper than walking", whose breakage silently degrades path quality, are enforced here.
 */
class ActionCostsTest {

    @Test
    void sprintingIsCheaperThanWalking() {
        assertTrue(ActionCosts.SPRINT_ONE_BLOCK < ActionCosts.WALK_ONE_BLOCK);
    }

    @Test
    void waterAndCobwebAreSlowerThanOpenGround() {
        assertTrue(ActionCosts.SWIM_ONE_BLOCK > ActionCosts.SPRINT_ONE_BLOCK);
        assertTrue(ActionCosts.SPRINT_ONE_IN_COBWEB > ActionCosts.SPRINT_ONE_BLOCK);
    }

    @Test
    void paddlingIsFasterThanSprinting() {
        assertTrue(ActionCosts.PADDLE_ONE_BLOCK < ActionCosts.SPRINT_ONE_BLOCK);
    }

    @Test
    void ascendIsAtLeastAsExpensiveAsWalkingOrJumping() {
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.WALK_ONE_BLOCK);
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.JUMP_ONE_BLOCK);
    }

    /**
     * A diagonal ascent is pointless unless it's cheaper than splitting 1 horizontal + 1 vertical block into two cardinal
     * moves (climb + straight). If reversed, the search never picks a diagonal move (requires it to be clearly cheaper,
     * not by less than {@code MIN_IMPROVEMENT}).
     */
    @Test
    void diagonalAscendIsCheaperThanTwoCardinalHops() {
        assertTrue(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK
                < ActionCosts.ASCEND_ONE_BLOCK + ActionCosts.SPRINT_ONE_BLOCK);
    }

    /** The descending counterpart of {@link #diagonalAscendIsCheaperThanTwoCardinalHops}. */
    @Test
    void diagonalDescendIsCheaperThanTwoCardinalHops() {
        assertTrue(ActionCosts.DIAGONAL_DESCEND_ONE_BLOCK
                < ActionCosts.DESCEND_ONE_BLOCK + ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * Climbing one step diagonally costs more than the same distance moving diagonally on flat ground. If they become equal,
     * height is "free", {@code Heuristic}'s ascent term becomes 0 entirely, and
     * the search spreads unnecessarily on routes heading up mountains (the cardinal side already satisfies this relation).
     */
    @Test
    void climbingDiagonallyCostsMoreThanMovingDiagonallyOnFlatGround() {
        double diagonalOnFlat = ActionCosts.SPRINT_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE;
        assertTrue(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK > diagonalOnFlat,
                "diagonal ascent penalty has vanished: " + ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK + " vs " + diagonalOnFlat);
    }

    @Test
    void fallCostIsMonotonicWithDistance() {
        double previous = 0.0;
        for (int blocks = 1; blocks <= ActionCosts.SAFE_FALL_BLOCKS + 5; blocks++) {
            double cost = ActionCosts.fallCost(blocks);
            assertTrue(cost > previous, "a " + blocks + "-block fall should cost more than " + (blocks - 1) + " blocks");
            previous = cost;
        }
    }

    /**
     * A 1-block gap jump costs more than sprinting the same 2 blocks (mid-air, the cost can't be cut short before landing).
     * If this reverses, jumping is always cheaper even on flat ground, giving paths full of pointless jumps.
     */
    @Test
    void jumpingAcrossAGapCostsMoreThanSprintingTheSameDistance() {
        assertTrue(ActionCosts.JUMP_ACROSS_GAP > 2 * ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * Keep walking cheaper than jumping at every gap width. If this reverses, a jumping path is chosen even when
     * there is a detour on flat ground, recommending guidance that falls if the landing is missed.
     */
    @Test
    void jumpingAnyGapCostsMoreThanSprintingAroundIt() {
        for (int gap = 1; gap <= 3; gap++) {
            // The landing spot is 1 block past the gap. Compare with sprinting the same distance on flat ground
            double sprintSameDistance = (gap + 1) * ActionCosts.SPRINT_ONE_BLOCK;
            assertTrue(ActionCosts.jumpAcrossGap(gap) > sprintSameDistance,
                    "a " + gap + "-block gap jump is cheaper than sprinting the same distance");
        }
    }

    @Test
    void widerGapsCostMore() {
        assertEquals(ActionCosts.JUMP_ACROSS_GAP, ActionCosts.jumpAcrossGap(1),
                "a 1-block gap is the air time itself, as before");
        double previous = ActionCosts.jumpAcrossGap(1);
        for (int gap = 2; gap <= 3; gap++) {
            double cost = ActionCosts.jumpAcrossGap(gap);
            assertTrue(cost > previous, "a " + gap + "-block gap should cost more than " + (gap - 1) + " blocks");
            previous = cost;
        }
    }

    @Test
    void safeFallBlocksMatchesVanillaFallDamageThreshold() {
        assertEquals(3, ActionCosts.SAFE_FALL_BLOCKS);
    }

    /**
     * Condition for the retry that relaxes fall damage tolerance to hold. When the allowed drop grows, the descent lower bound
     * <b>must go down</b>. If this isn't monotonic, passing the old lower bound to the relaxed search would make
     * the heuristic exceed the actual cost (= inadmissible) without anyone noticing.
     */
    @Test
    void descentBoundNeverRisesAsTheAllowedDropGrows() {
        double previous = Double.MAX_VALUE;
        for (int maxDrop = 1; maxDrop <= 40; maxDrop++) {
            double bound = ActionCosts.descentBoundForMaxDrop(maxDrop);
            assertTrue(bound <= previous + 1e-12,
                    "lower bound went up even though falls up to " + maxDrop + " blocks are allowed: " + previous + " -> " + bound);
            previous = bound;
        }
    }

    /** The lower bound must be, as named, a lower bound. It must not exceed the actual cost per block. */
    @Test
    void descentBoundStaysBelowTheRealPerBlockCost() {
        for (int maxDrop = 1; maxDrop <= 40; maxDrop++) {
            double bound = ActionCosts.descentBoundForMaxDrop(maxDrop);
            for (int drop = 1; drop <= maxDrop; drop++) {
                assertTrue(bound <= ActionCosts.fallCost(drop) / drop + 1e-12,
                        "lower bound exceeds the actual cost of a " + drop + "-block drop (maxDrop=" + maxDrop + ")");
            }
        }
    }

    /**
     * The risk penalty for missing a foothold rises monotonically with drop height and caps at the fatal drop.
     * <b>Both ends matching the previous binary values</b> is why adding this slope doesn't change existing behavior.
     */
    @Test
    void dropRiskGrowsWithTheDropAndStopsAtTheFatalOne() {
        int fatal = 23;
        assertEquals(0.0, ActionCosts.dropRiskPenalty(0, fatal));
        assertEquals(0.0, ActionCosts.dropRiskPenalty(ActionCosts.SAFE_FALL_BLOCKS, fatal),
                "no risk penalty at heights you can descend safely");
        assertEquals(ActionCosts.VOID_BRIDGE_PENALTY_TICKS, ActionCosts.dropRiskPenalty(fatal, fatal),
                "a fatal drop costs the same as the void");
        assertEquals(ActionCosts.VOID_BRIDGE_PENALTY_TICKS,
                ActionCosts.dropRiskPenalty(fatal + 100, fatal), "does not increase past the cap");

        double previous = -1;
        for (int drop = 0; drop <= fatal + 5; drop++) {
            double penalty = ActionCosts.dropRiskPenalty(drop, fatal);
            assertTrue(penalty >= previous - 1e-12,
                    "risk penalty dropped at a " + drop + "-block drop: " + previous + " -> " + penalty);
            assertTrue(penalty <= ActionCosts.VOID_BRIDGE_PENALTY_TICKS + 1e-12);
            previous = penalty;
        }
    }

    /**
     * Digging costs less effort than placing. This ordering is the intent of {@link ActionCosts#PLACE_BLOCK_OVERHEAD_TICKS}:
     * "when digging and stacking look like about the same number of moves, prefer digging".
     */
    @Test
    void diggingOverheadStaysLighterThanPlacing() {
        assertTrue(ActionCosts.DIG_OVERHEAD_TICKS < ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS);
    }
}
