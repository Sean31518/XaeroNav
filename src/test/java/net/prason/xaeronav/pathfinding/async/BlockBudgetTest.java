package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * Uses the number of blocks in the inventory as the cap on placements along the route ({@code CellSource#placedBlockBudget}).
 *
 * <p>User report: "Too many placements; I run out of blocks halfway and end up digging anyway. Digging would have been faster."
 * The bridge length cap ({@code maxBridgeRunBlocks}) is a <b>run length</b>, so routes that build many short bridges
 * slipped past it.
 *
 * <p><b>Always include a control.</b> "Bridges disappeared when the budget was tightened" alone can't reveal that you're
 * testing terrain that never produced bridges in the first place (a false pass actually hit in {@code RiskyJumpTest}).
 */
class BlockBudgetTest {

    private static final BooleanSupplier NEVER = () -> false;
    private static final SearchLimits LIMITS = new SearchLimits(200_000, 20_000, 1.5);

    /** Width of the chasm. Wider than the jumpable maximum (3), so placing blocks is the only way across. */
    private static final int GAP = 4;

    /**
     * A ledge running east-west. From x=20, a bottomless chasm {@link #GAP} wide runs south to {@code gapReachZ},
     * and beyond that it's connected (you can walk around).
     *
     * <p><b>The length of the detour decides whether the control works.</b> A void bridge costs about 35.6 ticks per block
     * (32 of which are placement and the danger surcharge), so a width of 4 adds 128 ticks. The detour is horizontal travel
     * there and back; going 26 blocks south and returning is about 185 ticks. Unless <b>the bridge is cheaper</b>, the route
     * detours even before the budget is tightened.
     */
    private static FakeCells ledgeWithGap(int gapReachZ) {
        SearchBounds bounds = new SearchBounds(-8, 0, -8, 48, 96, 44);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR)
                .canPlaceBlocks(true).maxFallDamagePoints(0);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 32; z++) {
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

    private static int maxZ(PathResult result) {
        return result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);
    }

    private static PathResult solve(FakeCells cells) {
        return new AStarPathfinder(cells, LIMITS)
                .search(new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), NEVER, 0);
    }

    /**
     * <b>Control.</b> Without a budget (the previous behavior), a chasm shorter than the detour gets bridged.
     * On terrain where this doesn't hold, the tests below pass vacuously.
     */
    @Test
    void bridgesTheGapWhenNoBudgetIsSet() {
        PathResult result = solve(ledgeWithGap(26));

        assertTrue(result.complete(), "Walking around always gets there: " + result.termination());
        assertTrue(placements(result) > 0, "Crossed without bridging, i.e. the control doesn't hold");
        assertTrue(maxZ(result) < 10, "Should cross straight without detouring: maxZ=" + maxZ(result));
    }

    /** With enough budget, the same route as the control above comes out (having a budget alone doesn't narrow it). */
    @Test
    void keepsBridgingWhenTheBudgetCoversIt() {
        PathResult withoutBudget = solve(ledgeWithGap(26));
        PathResult withBudget = solve(ledgeWithGap(26).placedBlockBudget(64));

        assertTrue(withBudget.complete());
        assertEquals(placements(withoutBudget), placements(withBudget),
                "Placement count changed even though the budget is sufficient");
    }

    /**
     * <b>The main case.</b> If the budget is smaller than the chasm, the route walks around without bridging.
     */
    @Test
    void walksAroundWhenTheBudgetIsTooSmall() {
        PathResult result = solve(ledgeWithGap(26).placedBlockBudget(2));

        assertTrue(result.complete(), "There's a way around, so it should arrive: " + result.termination());
        assertTrue(placements(result) <= 2, "Placing beyond the budget: " + placements(result));
        assertTrue(maxZ(result) > 26, "Didn't walk around: maxZ=" + maxZ(result));
    }

    /**
     * When the budget is short and there's no way around, <b>the relaxation ladder opens up and produces a route</b>;
     * showing "not enough, but there is a way" beats a dead end (the guidance side reports the shortfall).
     */
    @Test
    void loosensTheBudgetWhenThereIsNoOtherWay() throws Exception {
        // Terrain where the chasm runs all the way to the edge of the search range, so it can't be walked around
        FakeCells cells = ledgeWithGap(Integer.MAX_VALUE).placedBlockBudget(2);

        AStarPathfinder strict = new AStarPathfinder(cells, LIMITS);
        PathResult blocked = strict.search(new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), NEVER, 0);
        assertTrue(strict.placedBudgetBlocked(), "Placements were dropped due to the budget but the flag isn't set");
        assertTrue(!blocked.complete(), "Crossable within budget, i.e. the terrain isn't a valid control");

        PathResult loosened = new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();
        assertTrue(loosened.complete(), "The relaxation ladder didn't open: " + loosened.termination());
        assertTrue(placements(loosened) > 2, "Relaxed, yet the route still stays within budget");
    }

    /**
     * <b>The budget carries over across segments.</b> Long-distance routes submit a search per segment, so
     * without carrying it over <b>the budget refills to full for every segment</b>; even with 6 blocks on hand, splitting
     * into 3 segments would assemble a route that places 18 in total.
     *
     * <p>The key is putting a control (no carryover) alongside. With only one, you can't tell whether you're looking at
     * "terrain that can't be crossed with a budget of 6 anyway".
     */
    @Test
    void carriesThePlacedBlocksAcrossSegments() {
        FakeCells cells = ledgeWithGap(26).placedBlockBudget(6);
        BlockPos start = new BlockPos(0, 65, 0);
        BlockPos goal = new BlockPos(40, 65, 0);

        PathResult fresh = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER, Carryover.NONE, 0);
        assertEquals(GAP, placements(fresh), "With a budget of 6, a chasm 4 wide should be crossable");

        // The preceding segment is committed to using 3, i.e. 3 remain, which isn't enough for a chasm 4 wide
        PathResult continued = new AStarPathfinder(cells, LIMITS)
                .search(start, goal, NEVER, new Carryover(0, 3), 0);
        assertTrue(continued.complete(), "There's a way around, so it should arrive: " + continued.termination());
        assertTrue(placements(continued) <= 3, "Placing beyond the remaining budget: " + placements(continued));
        assertTrue(maxZ(continued) > 26, "Didn't walk around: maxZ=" + maxZ(continued));
    }

    /**
     * <b>Even without a single placeable block, bridges are guided when there's no other way.</b>
     *
     * <p>The cause behind the in-game report "only End island hopping doesn't work". With an empty inventory
     * {@code canPlaceBlocks} is false and <b>not a single bridge is generated</b>; the route just wanders the island without
     * reaching the destination, and with zero placements not even the shortage warning appears (the guidance shows nothing).
     *
     * <p>Showing it makes clear that "a bridge is needed here", so the player can decide whether to go gather blocks or turn back.
     * The HUD reports how many are needed.
     */
    @Test
    void offersABridgeWithNoBlocksWhenThereIsNoOtherWay() throws Exception {
        FakeCells cells = ledgeWithGap(Integer.MAX_VALUE)
                .canPlaceBlocks(false)
                .bridgingAllowedBySettings(true);

        PathResult result = new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();

        assertTrue(result.complete(), "Guidance should appear even without blocks: " + result.termination());
        assertTrue(placements(result) > 0, "Not a single bridge was produced");
    }

    /**
     * <b>Control.</b> When placement is turned off in settings, it isn't opened up; "not carrying any" and "refused"
     * are distinguished. If this breaks, players who turned off placement would get bridge guidance too.
     */
    @Test
    void refusesToBridgeWhenTheSettingForbidsIt() throws Exception {
        FakeCells cells = ledgeWithGap(Integer.MAX_VALUE)
                .canPlaceBlocks(false)
                .bridgingAllowedBySettings(false);

        PathResult result = new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();

        assertEquals(0, placements(result), "Produced a bridge even though settings forbid it");
    }
}
