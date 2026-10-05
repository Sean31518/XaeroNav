package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>After jumping off a cliff, don't make the player climb back up it.</b>
 *
 * <p>User report: "The destination is to the upper left, but after dropping off a cliff I get sent back up
 * the cliff." A join after deviating aims at "the nearest step", so right after jumping down <b>the path
 * directly above</b> remains nearest. If joining there is allowed, guidance shows a way to climb back up.
 *
 * <p>Joining itself is needed (throwing away an expensive path like island hopping on every deviation gives
 * no guarantee of drawing the same path again). What we want to stop is <b>only joins that backtrack</b>.
 */
class CliffSpliceTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int TOP = 80;
    private static final int BOTTOM = 60;

    /**
     * North (z≦20) is a plateau, south (z≧26) lowland. <b>Only the slope at the west end (x≦20)</b> connects them.
     * The destination is west on the plateau, the start east on it. Jumping off the cliff partway puts you on the lowland.
     */
    private static FakeCells terrain() {
        SearchBounds bounds = new SearchBounds(-8, 40, -8, 208, 120, 68);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6);
        for (int x = 0; x <= 200; x++) {
            for (int z = 0; z <= 60; z++) {
                int top;
                if (z <= 20) {
                    top = TOP;
                } else if (z >= 26) {
                    top = BOTTOM;
                } else if (x <= 20) {
                    top = TOP - (z - 20) * 4;
                } else {
                    continue;
                }
                for (int y = BOTTOM - 6; y <= top; y++) {
                    cells.set(x, y, z, FakeCells.SOFT);
                }
            }
        }
        return cells;
    }

    private static PathResult solve(FakeCells cells, BlockPos from, BlockPos to) {
        return new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT))
                .search(from, to, NEVER);
    }

    private static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void doesNotClimbBackUpAfterDroppingOffACliff() {
        FakeCells cells = terrain();
        BlockPos goal = new BlockPos(10, TOP + 1, 10);
        PathResult onTheCliff = solve(cells, new BlockPos(190, TOP + 1, 10), goal);
        assertTrue(onTheCliff.complete(), "a path heading west along the plateau should come out");

        BlockPos player = new BlockPos(140, BOTTOM + 1, 34);
        int join = Splice.joinableStepIndex(onTheCliff.steps(),
                new net.minecraft.world.phys.Vec3(player.getX() + 0.5, player.getY() + 0.5,
                        player.getZ() + 0.5), 0, i -> true);
        BlockPos joinPos = onTheCliff.steps().get(join).pos();
        assertTrue(joinPos.getY() >= TOP, "the join target should be on top of the cliff (no other path on this terrain)");

        PathResult toJoin = solve(cells, player, joinPos);
        assertTrue(toJoin.complete(), "the way to climb back up does exist (which is why it gets silently adopted)");

        assertFalse(Splice.spliceWorthTaking(cost(toJoin.steps()), player, joinPos, goal, null),
                "a join climbing back up the cliff is adopted: join segment=" + Math.round(cost(toJoin.steps())) + "tick");
    }

    /** If only a few blocks to the side of the path, take the join as-is (joining must not be killed). */
    @Test
    void ordinaryDeviationStillSplices() {
        FakeCells cells = terrain();
        BlockPos goal = new BlockPos(10, TOP + 1, 10);
        PathResult path = solve(cells, new BlockPos(190, TOP + 1, 10), goal);

        BlockPos player = new BlockPos(140, TOP + 1, 16);
        int join = Splice.joinableStepIndex(path.steps(),
                new net.minecraft.world.phys.Vec3(player.getX() + 0.5, player.getY() + 0.5,
                        player.getZ() + 0.5), 0, i -> true);
        BlockPos joinPos = path.steps().get(join).pos();
        PathResult toJoin = solve(cells, player, joinPos);

        assertTrue(Splice.spliceWorthTaking(cost(toJoin.steps()), player, joinPos, goal, null),
                "join refused on an ordinary deviation: join segment=" + Math.round(cost(toJoin.steps())) + "tick");
    }
}
