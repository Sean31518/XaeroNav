package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class FarScaleCalibrationTest {

    private static final int FLOOR_Y = 64;
    private static final int RADIUS = 64;
    private static final LoadedArea EVERYTHING = (minX, maxX, minZ, maxZ) -> (maxX - minX + 1) * (maxZ - minZ + 1);

    /** A long east-west flat floor. You can walk straight, so the true remainder is nearly equal to the geometric lower bound. */
    private static FakeCells corridor() {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 56, 0, 511, 80, 31));
        for (int x = 0; x < 512; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 56; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    private static WindowField fieldAt(FakeCells cells, BlockPos goal, int centerX, FarField far) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        long[] keys = graph.missingSections(centerX, 16, RADIUS, EVERYTHING);
        assertTrue(graph.build(cells, keys, 0, keys.length, EVERYTHING, () -> false));
        WindowField field = graph.field(centerX, 16, RADIUS, far, () -> false);
        assertNotNull(field);
        return field;
    }

    @Test
    void learnsHowMuchTheFarEstimateFallsShort() {
        FakeCells cells = corridor();
        BlockPos goal = new BlockPos(500, FLOOR_Y + 1, 16);
        // An outside estimate that yields only half the true value, like the Nether's 3D coarse layer
        FarField halved = (x, y, z) -> 0.5 * Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        FarScaleCalibration calibration = new FarScaleCalibration();

        for (int x = 20; x <= 300; x += 8) {
            calibration.observe(fieldAt(cells, goal, x, halved), new BlockPos(x, FLOOR_Y + 1, 16));
        }

        assertEquals(2.0, calibration.scale(), 0.2);
    }

    @Test
    void keepsTheEstimateWhenItIsAlreadyRight() {
        FakeCells cells = corridor();
        BlockPos goal = new BlockPos(500, FLOOR_Y + 1, 16);
        FarScaleCalibration calibration = new FarScaleCalibration();

        for (int x = 20; x <= 300; x += 8) {
            calibration.observe(fieldAt(cells, goal, x, FarField.straightLineTo(goal)), new BlockPos(x, FLOOR_Y + 1, 16));
        }

        assertEquals(1.0, calibration.scale(), 0.1);
    }
}
