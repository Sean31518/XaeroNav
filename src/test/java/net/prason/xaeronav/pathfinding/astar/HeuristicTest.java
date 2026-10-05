package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * Verifies that {@link Heuristic}'s per-axis lower bounds accumulate according to the formula.
 * A* relies on the heuristic being a lower bound on actual cost (admissible) for optimality, so if this
 * breaks toward exceeding actual cost, nobody notices paths quietly drifting from optimal.
 */
class HeuristicTest {

    private static final double STRAIGHT = ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL = STRAIGHT * ActionCosts.DIAGONAL_DISTANCE;

    @Test
    void sameCellCostsNothing() {
        assertEquals(0.0, Heuristic.estimate(5, 60, -3, 5, 60, -3));
    }

    @Test
    void axisAlignedHorizontalMovementIsPureStraightCost() {
        assertEquals(7 * STRAIGHT, Heuristic.estimate(0, 64, 0, 7, 64, 0), 1e-9);
        assertEquals(7 * STRAIGHT, Heuristic.estimate(0, 64, 0, 0, 64, 7), 1e-9);
    }

    @Test
    void pureDiagonalMovementUsesOctileDistance() {
        // When dx == dz it can be covered by diagonal moves alone, so it should equal n steps of DIAGONAL
        assertEquals(4 * DIAGONAL, Heuristic.estimate(0, 64, 0, 4, 64, 4), 1e-9);
    }

    @Test
    void horizontalDistanceIsSymmetricUnderAxisSwap() {
        double dxLarger = Heuristic.estimate(0, 64, 0, 9, 64, 2);
        double dzLarger = Heuristic.estimate(0, 64, 0, 2, 64, 9);
        assertEquals(dxLarger, dzLarger, 1e-9);
    }

    @Test
    void mixedHorizontalMatchesOctileFormula() {
        int dx = 9;
        int dz = 2;
        double diagonalSaving = DIAGONAL - 2 * STRAIGHT;
        double expected = STRAIGHT * (dx + dz) + diagonalSaving * Math.min(dx, dz);
        assertEquals(expected, Heuristic.estimate(0, 64, 0, dx, 64, dz), 1e-9);
    }

    /**
     * A pure climb with no horizontal movement is estimated by the cheapest of all moves that gain one level of height.
     *
     * <p>A land {@code Ascend} involves one horizontal step, but that step can be undone by turning back, so it
     * can be used even with zero net horizontal displacement. Even so the cheapest is {@code SwimUp}: land
     * ascents and descents carry {@link ActionCosts#STEP_TRANSITION_TICKS}, while swimming up isn't a jump and
     * doesn't.
     */
    @Test
    void pureVerticalAscendUsesTheCheapestMoveThatGainsHeight() {
        double cheapest = Math.min(ActionCosts.ASCEND_ONE_BLOCK, ActionCosts.SWIM_UP_ONE_BLOCK);
        assertEquals(ActionCosts.SWIM_UP_ONE_BLOCK, cheapest, 1e-9,
                "if land Ascend is cheaper, the basis of this estimate has changed");
        assertEquals(5 * cheapest, Heuristic.estimate(0, 64, 0, 0, 69, 0), 1e-9);
    }

    /**
     * The lower bound for a pure climb must never exceed any means of achieving it: the core of admissibility.
     * Not only ladders, swimming and Pillar but also <b>switchback stairs</b> (back-and-forth {@code Ascend}) count as means.
     */
    @Test
    void pureVerticalAscendNeverExceedsAnyRealMoveThatAchievesIt() {
        double estimate = Heuristic.estimate(0, 64, 0, 0, 65, 0);
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + 1e-9,
                "must not estimate higher than switchback stairs (back-and-forth Ascend)");
        assertTrue(estimate <= ActionCosts.LADDER_UP_ONE_BLOCK + 1e-9);
        assertTrue(estimate <= ActionCosts.SWIM_UP_ONE_BLOCK + 1e-9,
                "must not estimate higher than SwimUp");
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + 1e-9,
                "must not estimate higher than Pillar (Ascend equivalent + placement overhead)");
    }

    /**
     * Switchback stairs. Horizontal displacement 1, height 3 can be climbed with 3 {@code Ascend} moves (one of
     * which undoes the horizontal), so the estimate must not exceed 3 moves. Back when ladders were used as the
     * lower bound, this was 21.65 &gt; 13.90 and inadmissible.
     */
    @Test
    void switchbackStaircaseIsNotOverEstimated() {
        double threeAscends = 3 * ActionCosts.ASCEND_ONE_BLOCK;
        assertTrue(Heuristic.estimate(0, 64, 0, 1, 67, 0) <= threeAscends + 1e-9,
                "must not exceed the actual cost of switchback stairs");
    }

    /**
     * The tightened descent lower bound must never exceed any descent move that can actually be generated.
     * In the Nether with fall damage tolerance off, you can only fall up to the safe height (3 blocks), so the
     * lower bound is {@code fallCost(3)/3}. This is below a 1-block fall (9.321), ladders (6.667) and swimming (9.091).
     *
     * <p>It's compared against {@code fallCost(1)} rather than {@code DESCEND_ONE_BLOCK} because this is an
     * estimate with <b>zero horizontal displacement</b>. {@code Descend} moves down to the neighbouring block, so
     * it can't be used straight down; the only ways one block straight down are falling, ladders and diving.
     */
    @Test
    void aTightenedDescentBoundStaysUnderEveryRealDescent() {
        double tightened = ActionCosts.fallCost(ActionCosts.SAFE_FALL_BLOCKS) / ActionCosts.SAFE_FALL_BLOCKS;
        double estimate = Heuristic.estimate(0, 64, 0, 0, 63, 0, tightened);

        assertTrue(estimate <= ActionCosts.fallCost(1) + 1e-9, "must not estimate higher than a 1-block fall");
        assertTrue(estimate <= ActionCosts.LADDER_DOWN_ONE_BLOCK + 1e-9, "must not estimate higher than a ladder");
        assertTrue(estimate <= ActionCosts.SWIM_DOWN_ONE_BLOCK + 1e-9, "must not estimate higher than swimming");
        for (int drop = 2; drop <= ActionCosts.SAFE_FALL_BLOCKS; drop++) {
            assertTrue(Heuristic.estimate(0, 64, 0, 0, 64 - drop, 0, tightened)
                            <= ActionCosts.fallCost(drop) + 1e-9,
                    drop + "-block fall: must not estimate higher than that");
        }
    }

    /** The tightened lower bound is actually larger than the loose default (confirming it has effect). */
    @Test
    void theTightenedBoundIsActuallyTighter() {
        double tightened = ActionCosts.fallCost(ActionCosts.SAFE_FALL_BLOCKS) / ActionCosts.SAFE_FALL_BLOCKS;

        assertTrue(Heuristic.estimate(0, 74, 0, 0, 64, 0, tightened)
                        > 10 * Heuristic.estimate(0, 74, 0, 0, 64, 0),
                "the tightened lower bound should be at least 10x the default");
    }

    @Test
    void descendingUsesTheAsymptoticFallLowerBound() {
        assertEquals(5 * ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK,
                Heuristic.estimate(0, 64, 0, 0, 59, 0), 1e-9);
    }

    /**
     * The diagonal shortcut ({@code DIAGONAL_SAVING}) must not take so much effect that the horizontal heuristic
     * drops below the minimum cost of the actual octile distance (the lower bound when moving only cardinally).
     */
    @Test
    void horizontalEstimateNeverExceedsWalkingEachAxisSeparately() {
        for (int dx = 0; dx <= 20; dx += 3) {
            for (int dz = 0; dz <= 20; dz += 3) {
                double estimate = Heuristic.estimate(0, 64, 0, dx, 64, dz);
                assertTrue(estimate <= STRAIGHT * (dx + dz) + 1e-9,
                        "dx=" + dx + " dz=" + dz + ": must not exceed the total cost of cardinal moves");
            }
        }
    }

    /**
     * The actual cost of {@code Ascend}, doing one block horizontal + one block up in a single move, is
     * {@code ASCEND_ONE_BLOCK} (the larger of horizontal travel time and jump time), so the heuristic must not
     * exceed it. The current implementation adds the horizontal and vertical components independently, so this
     * check currently fails (regression test; passes once Heuristic is fixed to a per-axis piggyback calculation).
     */
    @Test
    void cardinalAscendEstimateDoesNotExceedItsRealCost() {
        double estimate = Heuristic.estimate(0, 64, 0, 1, 65, 0);
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + 1e-9,
                "must not exceed the actual cost of a single Ascend: estimate=" + estimate);
    }

    /**
     * Likewise for {@code DiagonalAscend}, climbing one level over one diagonal block. The current implementation,
     * adding the cardinal part independently, overestimates even more.
     */
    @Test
    void diagonalAscendEstimateDoesNotExceedItsRealCost() {
        double estimate = Heuristic.estimate(0, 64, 0, 1, 65, 1);
        assertTrue(estimate <= ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK + 1e-9,
                "must not exceed the actual cost of a single DiagonalAscend: estimate=" + estimate);
    }
}
