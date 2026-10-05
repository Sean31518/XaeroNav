package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Safety check right before presenting a path. Checks that sections the cost can't fully express, "walkable but
 * with conditions", get marked.
 */
class PathSafetyCheckerTest {

    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    /**
     * Magma blocks are passable as footing (harmless if you sneak), but stepping on them while running burns you.
     * Since they're passable, not conveying the condition leads to "I followed the guidance and got burned".
     */
    @Test
    void marksStepsOverMagmaAsNeedingASneak() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #MMM#""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "Magma blocks are passable");
        assertEquals(3, annotated.steps().stream()
                        .filter(step -> step.risk() == PathRisk.SNEAK_OVER_MAGMA).count(),
                "All 3 steps over magma should be marked: " + annotated.steps());
    }

    /**
     * A bridge built over the void. Missing one footing block means a fatal fall, so it must be drawn differently from a bridge
     * over a crack with a bottom (unmarked = cyan).
     */
    @Test
    void marksBridgesOverABottomlessGap() {
        CellSource cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 12, 93, 0))
                .canPlaceBlocks(true)
                .set(0, 60, 0, FakeCells.BEDROCK)
                .set(4, 60, 0, FakeCells.BEDROCK);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "Bridges can be built over the void too: " + annotated.steps());
        assertEquals(3, annotated.steps().stream()
                        .filter(step -> step.risk() == PathRisk.VOID_BELOW).count(),
                "Every footing block over the void should be marked: " + annotated.steps());
    }

    /**
     * Bridges over a crack with a bottom aren't warned about. Missing a footing block just means falling, and you can climb back from there.
     *
     * <p>The crack is 4 blocks deep to rule out the climb-down, walk, climb-up path and <b>force a bridge</b>
     * (safe falls go up to 3 blocks). With a 1-block step it would become an empty test that only confirms
     * "no warning" without a single bridge appearing.
     */
    @Test
    void leavesBridgesOverAFlooredGapUnmarked() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #...#
                #...#
                #...#
                #...#
                #####""")
                .canPlaceBlocks(true)
                .jumpGapEnabled(false)
                .fillWith(FakeCells.BEDROCK);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 65, 0), new BlockPos(4, 65, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "A crack with a bottom can be crossed: " + annotated.steps());
        assertEquals(3, annotated.steps().stream().filter(PathStep::bridging).count(),
                "This crack can only be crossed by bridge: " + annotated.steps());
        assertTrue(annotated.steps().stream().allMatch(step -> step.risk() == PathRisk.NONE),
                "A bridge over a crack whose floor is visible must not be warned about: " + annotated.steps());
    }

    @Test
    void leavesOrdinaryGroundUnmarked() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #####""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.steps().stream().allMatch(step -> step.risk() == PathRisk.NONE),
                "Ordinary ground must not be marked: " + annotated.steps());
    }
}
