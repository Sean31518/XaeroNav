package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * Layer 1's cost-to-go guide ({@link CoarseRouter#costToGo}) must not distort the detail search's paths.
 *
 * <p>The guide is used as a max with the geometric {@code Heuristic}, so <b>the moment it exceeds actual cost,
 * the path's shape changes</b>. The table only holds values per chunk (16 blocks), so a raw lookup gives the
 * same value anywhere in the cell, putting a 16-block-period sawtooth on h: the real cause of the user reports
 * "lots of jerky right-angle behaviour" and "the path isn't intuitive".
 */
class CostToGoGuideTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 60;
    private static final int STAND_Y = 61;
    private static final int RADIUS = 96;

    private static FakeCells flatGround() {
        FakeCells cells = FakeCells.empty(
                new SearchBounds(-RADIUS, GROUND_Y - 8, -RADIUS, RADIUS, GROUND_Y + 32, RADIUS));
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                cells.set(x, GROUND_Y, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static PathResult search(FakeCells cells, BlockPos start, BlockPos goal, CostToGo guide) {
        return new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT), guide)
                .search(start, goal, NEVER);
    }

    private static CostToGo guideFor(FakeCells cells, BlockPos start, BlockPos goal) {
        CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), NEVER);
        return CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
    }

    private static int diagonalSteps(BlockPos start, PathResult result) {
        int diagonals = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            if (step.pos().getX() != previous.getX() && step.pos().getZ() != previous.getZ()) {
                diagonals++;
            }
            previous = step.pos();
        }
        return diagonals;
    }

    /**
     * <b>On open flat ground, the path doesn't change by a single move with or without the guide.</b> The terrain
     * gives no reason, so layer 1 has no business shaping the path.
     *
     * <p>Back when the guide exceeded actual cost at cell borders, a stretch toward a 45-degree destination that
     * <b>needs 40 diagonal moves took 60 (22 diagonal, 38 straight)</b>: it was pulled to chunk borders and
     * advanced only by straights of 10+ blocks and right angles.
     */
    @Test
    void theGuideDoesNotBendThePathOnOpenGround() {
        FakeCells cells = flatGround();
        BlockPos start = new BlockPos(0, STAND_Y, 0);
        for (BlockPos goal : new BlockPos[] {
                new BlockPos(40, STAND_Y, 40), new BlockPos(40, STAND_Y, 20),
                new BlockPos(60, STAND_Y, 15), new BlockPos(37, STAND_Y, 43)}) {
            PathResult plain = search(cells, start, goal, null);
            PathResult guided = search(cells, start, goal, guideFor(cells, start, goal));
            assertEquals(plain.steps().size(), guided.steps().size(),
                    "the guide lengthens the path: goal=" + goal.toShortString());
            assertEquals(diagonalSteps(start, plain), diagonalSteps(start, guided),
                    "the guide replaces diagonals with right angles: goal=" + goal.toShortString());
        }
    }

    /**
     * The guide must not exceed the geometric lower bound ({@link Heuristic}). On open flat ground
     * {@code Heuristic} is the actual cost itself, so exceeding it makes A* inadmissible.
     *
     * <p>Checks directly a property upstream of {@link #theGuideDoesNotBendThePathOnOpenGround}, which looks at path shape.
     * If only this one fails, it isolates the case where the lower bound is broken but not yet enough to cause distortion.
     */
    @Test
    void theGuideStaysUnderTheGeometricLowerBound() {
        FakeCells cells = flatGround();
        BlockPos start = new BlockPos(0, STAND_Y, 0);
        BlockPos goal = new BlockPos(40, STAND_Y, 40);
        CostToGo guide = guideFor(cells, start, goal);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 40; z++) {
                double lowerBound = Heuristic.estimate(x, STAND_Y, z, goal.getX(), goal.getY(), goal.getZ());
                assertTrue(guide.estimate(x, STAND_Y, z) <= lowerBound + 1.0e-9,
                        "the guide exceeds actual cost: " + x + "," + z
                                + " guide=" + guide.estimate(x, STAND_Y, z) + " lowerBound=" + lowerBound);
            }
        }
    }
}
