package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * The balance between terrain-editing moves (digging, placing) and detouring around to the side.
 *
 * <p><b>If this is too low, natural terrain gets broken/built up at every step.</b> 2-block steps and 1-wide walls
 * occur every few blocks, so a value that tips toward editing at a few blocks of detour turns 10-30% of the path into digging/placing
 * (user report: "when walking on the surface there's a lot of pointless block digging and block placing").
 *
 * <p>The tipping point is exactly what {@link ActionCosts#DIG_OVERHEAD_TICKS} and
 * {@link ActionCosts#PLACE_BLOCK_OVERHEAD_TICKS} mean, so if only one of them moves,
 * one of them is broken. <b>It tips slightly before the arithmetic ratio (overhead / {@link ActionCosts#SIDESTEP_ONE_BLOCK})</b>
 * because weighted A* dislikes moves away from the destination;
 * when changing the constants, look at the position measured here, not the ratio.
 *
 * <p>Digging uses {@link FakeCells#SOFT} (dirt/grass with an iron shovel). {@link FakeCells#STONE} is
 * the value for digging stone <b>bare-handed</b>, so in normal play walking around with tools the detour would always win,
 * and this balance couldn't be measured.
 */
class TerrainEditVersusDetourTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** X distance of the trip. Needs to be long enough for diagonals to absorb the sideways offset (too short makes the detour look expensive). */
    private static final int SPAN = 40;

    /**
     * X of the obstacle. <b>The X distance up to here caps the sideways offset diagonals can absorb</b>: if the obstacle is close,
     * the detour is priced as "1 move straight sideways" instead of "2 diagonal moves", changing the balance being measured.
     */
    private static final int OBSTACLE_X = 20;

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells).search(start, goal, NEVER);
    }

    /** Flat ground. Obstacles are only placed at {@code z < detour}, so from {@code z = detour} onward is the detour route. */
    private static FakeCells flatGround(int detour) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-40, 20, -40, 90, 120, 90));
        for (int x = -4; x <= SPAN + 4; x++) {
            for (int z = -6; z <= detour + 8; z++) {
                cells.set(x, 60, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static long digs(PathResult result) {
        return result.steps().stream().filter(PathStep::digging).count();
    }

    private static double totalCost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    private static long places(PathResult result) {
        return result.steps().stream().filter(PathStep::bridging).count();
    }

    /** A 2-block wall can't be climbed, so the only options are digging or going around. Placing is disabled to ask only about digging. */
    private static PathResult acrossWall(int detour) {
        FakeCells cells = flatGround(detour).canPlaceBlocks(false);
        for (int z = -6; z < detour; z++) {
            cells.set(OBSTACLE_X, 61, z, FakeCells.SOFT);
            cells.set(OBSTACLE_X, 62, z, FakeCells.SOFT);
        }
        return search(cells, new BlockPos(0, 61, 0), new BlockPos(SPAN, 61, 0));
    }

    /**
     * A 2-block step. It can't be jumped up, so it's either building one pillar or going around. The plateau is bedrock
     * to remove digging as a third option (if left, with a long detour, collapsing the ceiling would be chosen over a pillar).
     */
    private static PathResult upOntoLedge(int detour) {
        FakeCells cells = flatGround(detour).canPlaceBlocks(true);
        for (int x = OBSTACLE_X; x <= SPAN + 4; x++) {
            for (int z = -6; z <= detour + 8; z++) {
                cells.set(x, 61, z, FakeCells.BEDROCK);
                if (z < detour) {
                    cells.set(x, 62, z, FakeCells.BEDROCK);
                }
            }
        }
        return search(cells, new BlockPos(0, 61, 0), new BlockPos(SPAN, 63, 0));
    }

    /**
     * <b>The premise of what the two below measure.</b> A detour shifting 1 block sideways costs only
     * {@link ActionCosts#SIDESTEP_ONE_BLOCK} (= 2 diagonal moves replace 2 straight moves).
     * If diagonal moves stop appearing, the detour jumps to 1 move straight sideways (2x {@link ActionCosts#SPRINT_ONE_BLOCK}),
     * and the tipping point changes <b>without the constants moving</b>.
     */
    @Test
    void aSidestepCostsTwoDiagonalStepsWorthOfExtraTravel() {
        double oneBlock = totalCost(acrossWall(1));
        double twoBlocks = totalCost(acrossWall(2));

        assertEquals(ActionCosts.SIDESTEP_ONE_BLOCK, twoBlocks - oneBlock, 1e-6);
    }

    /**
     * <p>The tipping point is further than "price of one dig / price of one block of detour" because the dig actually chosen
     * <b>digs only the upper block and goes over the wall</b>: climbing up and down that one step adds
     * {@link ActionCosts#STEP_TRANSITION_TICKS} twice.
     */
    @Test
    void digsThroughAWallOnlyWhenTheDetourExceedsEightBlocks() {
        assertEquals(0, digs(acrossWall(7)), "with a 7-block detour, go around");
        assertEquals(1, digs(acrossWall(8)), "from an 8-block detour, dig through");

        assertTrue(acrossWall(7).complete() && acrossWall(8).complete());
    }

    /** The end of the detour also has a one-step climb, so the pillar breaks even that much sooner. */
    @Test
    void pillarsOntoALedgeOnlyWhenTheDetourExceedsTenBlocks() {
        assertEquals(0, places(upOntoLedge(9)), "with a 9-block detour, go around");
        assertEquals(1, places(upOntoLedge(10)), "from a 10-block detour, build a pillar");

        assertTrue(upOntoLedge(9).complete() && upOntoLedge(10).complete());
    }
}
