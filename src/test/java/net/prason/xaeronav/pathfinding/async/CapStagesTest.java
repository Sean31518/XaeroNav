package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.Tolerances;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * The <b>order of the rungs</b> of the ladder relaxed when stuck ({@code PathfindingExecutor#capStages}).
 *
 * <p>The order itself is the requirement, and it's determined by a pure function. Checking it with real-game End island hopping
 * takes 8 seconds per case, but this needs neither terrain nor search: <b>what the real-game search must guard against
 * is the hole in the approximation where "placement branches vanish at large scale"</b>, not which rung is tried first.
 */
class CapStagesTest {

    private static FakeCells view() {
        return FakeCells.empty(new SearchBounds(0, 0, 0, 16, 16, 16))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(30)
                .maxVoidBridgeRunBlocks(30)
                .maxLavaBridgeRunBlocks(10)
                .maxSubmergedTicks(40)
                .maxFallDamagePoints(6)
                .placedBlockBudget(20);
    }

    private static List<Tolerances> stages(boolean budgetBlocked, boolean emptyInventoryBlocked) {
        return PathfindingExecutor.capStages(view(), true, budgetBlocked, emptyInventoryBlocked);
    }

    /**
     * <b>The core.</b> If placement was dropped because of the budget, the rung removing the budget comes before relaxing the caps.
     *
     * <p>Other caps merely "don't produce that move", but the budget kills placement branches the further the frontier advances
     * (an approximation where {@code PathNode.placedTotal} isn't part of node identity). If the order were reversed,
     * on terrain where the budget is the cause we'd keep paying for searches with only the caps doubled and quadrupled, to no avail.
     */
    @Test
    void liftsThePlacedBlockBudgetBeforeLooseningAnyRunCap() {
        List<Tolerances> stages = stages(true, false);

        assertEquals(0, stages.get(0).placedBlockBudget(), "the rung removing the budget isn't first");
        assertEquals(RunCaps.of(view()), stages.get(0).caps(),
                "the rung removing the budget also relaxes the caps = can't tell what helped");
    }

    /** If placement was dropped because the inventory is empty, that is also opened before relaxing caps (walls where no bridge is generated at all don't budge). */
    @Test
    void liftsTheEmptyInventoryBlockBeforeLooseningAnyRunCap() {
        List<Tolerances> stages = stages(false, true);

        assertTrue(stages.get(0).placeWithoutBlocks(), "the empty-inventory rung isn't first");
        assertEquals(RunCaps.of(view()), stages.get(0).caps(), "relaxes the caps at the same time");
    }

    /** If both are the cause, the budget goes first. The budget distorts the search's shape, so it's removed before the empty inventory. */
    @Test
    void theBudgetComesBeforeTheEmptyInventoryWhenBothBlocked() {
        List<Tolerances> stages = stages(true, true);

        assertEquals(0, stages.get(0).placedBlockBudget());
        assertFalse(stages.get(0).placeWithoutBlocks(), "opens both at once in the first rung");
        assertTrue(stages.get(1).placeWithoutBlocks(), "the empty-inventory rung isn't second");
    }

    /**
     * If placement isn't the cause, don't add the budget rung. Adding it pays for one whole
     * "same search with just the budget removed".
     */
    @Test
    void doesNotAddABudgetStageWhenNothingWasBlockedByIt() {
        List<Tolerances> stages = stages(false, false);

        assertEquals(view().placedBlockBudget(), stages.get(0).placedBlockBudget(),
                "a budget-removing rung is added even though placement isn't stuck");
    }

    /**
     * The last rung of the ladder is always "no caps, no budget". Without it, on terrain with no path at all within the
     * search range because of the caps, being stuck becomes final.
     */
    @Test
    void theLastStageLiftsEveryRunCapAndTheBudget() {
        List<Tolerances> stages = stages(false, false);
        Tolerances last = stages.get(stages.size() - 1);

        assertEquals(RunCaps.NONE, last.caps(), "caps remain in the last rung");
        assertEquals(0, last.placedBlockBudget(), "budget remains in the last rung");
    }

    /**
     * <b>Fall damage alone never gets an unlimited rung.</b> Unlike other caps, removing it puts instantly fatal falls
     * straight into the guidance. It must stop at the health-derived cap (1.5x the default).
     */
    @Test
    void neverOffersAnUnlimitedFallDamageStage() {
        int loosened = 6 * 3 / 2;
        for (Tolerances stage : stages(true, true)) {
            assertEquals(loosened, stage.maxFallDamagePoints(),
                    "fall damage tolerance moved away from the health-derived cap");
        }
    }

    /** If fall damage is declined in the settings, it stays 0 and isn't opened even to escape being stuck. */
    @Test
    void keepsFallDamageAtZeroWhenTheSettingForbidsIt() {
        FakeCells noFallDamage = view().maxFallDamagePoints(0);

        for (Tolerances stage : PathfindingExecutor.capStages(noFallDamage, true, true, true)) {
            assertEquals(0, stage.maxFallDamagePoints(), "offers a painful fall even though it was declined");
        }
    }
}
