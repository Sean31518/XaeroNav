package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>If a path would use up most of the player's inventory, reduce placements even at the cost of a small detour</b>
 * (the thrift stage of {@code PathfindingExecutor#refineQuality}).
 *
 * <p>The budget ({@code CellSource#placedBlockBudget}, {@link BlockBudgetTest}) only draws the line of <b>whether it can
 * be executed</b>. A path that places 4 out of 6 blocks on hand can be executed, but leaves your hands empty once across;
 * the path cache key is only the destination, so it won't be replanned just because blocks ran low.
 *
 * <p><b>Two controls are included.</b> "It now takes the detour" alone can't show that it's neither (a) always taking the
 * detour regardless of scarcity, nor (b) taking the detour no matter how expensive.
 */
class BlockThriftTest {

    private static final SearchLimits LIMITS = new SearchLimits(200_000, 20_000, 1.5);

    /** Width of the gap = placements needed to cross. Wider than the jumpable maximum (3). */
    private static final int GAP = 4;

    /**
     * A ledge running east-west. From x=20, a bottomless gap of width {@link #GAP} extends south to {@code gapReachZ},
     * and it connects beyond that. Same shape as {@link BlockBudgetTest}, but the ledge extends far enough south to
     * create <b>a detour that exceeds the 10% gate</b>.
     *
     * <p>Measured (the chosen path and its total cost at the true cost, when varying the placement cost multiplier):
     *
     * <pre>
     * gapReachZ  1x             1.5x           2x             3x
     * 24         bridge4/270.6  around0/243.5  around0/243.5  around0/243.5
     * 30         bridge4/270.6  bridge4/270.6  around0/286.3  around0/286.3
     * 34         bridge4/270.6  bridge4/270.6  around0/314.8  around0/314.8
     * </pre>
     *
     * <p>For 24, a bridge comes out even though <b>going around is cheaper even at 1x</b> = a matter of weighted A*'s
     * greediness, picked up by a different trigger than thrift (replanning paths that cross the void). Thrift takes effect
     * at 30 and 34, with +5.8% and +16.3% worse cost; {@code THRIFT_MAX_COST_INCREASE} (10%) separates those two.
     */
    private static FakeCells ledgeWithGap(int gapReachZ) {
        SearchBounds bounds = new SearchBounds(-8, 0, -8, 48, 96, 64);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR)
                .canPlaceBlocks(true).maxFallDamagePoints(0);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 52; z++) {
                if (x >= 20 && x < 20 + GAP && z <= gapReachZ) {
                    continue;
                }
                cells.set(x, 64, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static long placements(PathResult result) {
        return result.steps().stream().filter(PathStep::bridging).count();
    }

    private static PathResult solve(FakeCells cells) throws Exception {
        return new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();
    }

    /** <b>The main case.</b> The path uses 4 of the 6 blocks on hand, so it buys a +5.8% detour to place 0. */
    @Test
    void walksAroundWhenTheBridgeWouldUseUpMostOfTheInventory() throws Exception {
        PathResult result = solve(ledgeWithGap(30).placedBlockBudget(6));

        assertTrue(result.complete(), "there's a way around, so it should arrive: " + result.termination());
        assertEquals(0, placements(result), "bridge remains despite thrift");
    }

    /**
     * <b>Control 1.</b> Same terrain, same detour, but with plenty of blocks on hand it takes the shorter way.
     * Pins down that the thrift trigger is <b>scarcity</b>, not a preference for detours.
     */
    @Test
    void keepsTheShortPathWhenBlocksAreNotScarce() throws Exception {
        PathResult result = solve(ledgeWithGap(30).placedBlockBudget(64));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "took the detour despite having plenty");
    }

    /** <b>Control 2.</b> With no budget at all (creative, setting off), there's no notion of scarcity. */
    @Test
    void keepsTheShortPathWithoutABudget() throws Exception {
        PathResult result = solve(ledgeWithGap(30));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "economized despite having no budget");
    }

    /**
     * <b>Control 3.</b> Thrift <b>buys blocks with time</b>, so the price has a cap. Even for the same 4 blocks, it doesn't
     * buy on terrain where the detour costs +16.3%. Without this, merely having few blocks would make the guidance
     * detour without limit.
     */
    @Test
    void refusesToBuyBlocksAtAnyPrice() throws Exception {
        PathResult result = solve(ledgeWithGap(34).placedBlockBudget(6));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "took a detour that isn't worth it");
    }
}
