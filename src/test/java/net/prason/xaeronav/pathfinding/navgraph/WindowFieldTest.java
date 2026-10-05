package net.prason.xaeronav.pathfinding.navgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class WindowFieldTest {

    private static final int FLOOR_Y = 64;

    /** A flat 64x64 stone floor. With {@code wall}, a bedrock wall stands at x=32 with a passable gap only at z=8..9. */
    private static FakeCells world(boolean wall) {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 48, 0, 63, 96, 63));
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
                for (int y = 48; y < FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
                if (wall && x == 32 && (z < 8 || z > 9)) {
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
        }
        return cells;
    }

    private static double optimalCost(FakeCells cells, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(cells, new SearchLimits(2_000_000, 60_000, 1.0))
                .search(start, goal, () -> false);
        assertTrue(result.complete(), "the reference search doesn't reach");
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    private static NavGraph built(FakeCells cells, BlockPos goal, int centerX, int centerZ, int radius) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(centerX, centerZ, 1 << 20);
        long[] keys = graph.missingSections(centerX, centerZ, radius, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, () -> false));
        return graph;
    }

    @Test
    void matchesTheOptimalSearchAroundAWall() {
        FakeCells cells = world(true);
        BlockPos start = new BlockPos(10, FLOOR_Y + 1, 50);
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        NavGraph graph = built(cells, goal, 32, 32, 40);
        WindowField field = graph.field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);

        double optimal = optimalCost(cells, start, goal);
        // It goes around to the gap in the wall, so it's much higher than the straight line (geometric lower bound)
        assertTrue(optimal > 1.5 * Heuristic.estimate(start.getX(), start.getY(), start.getZ(), goal.getX(),
                goal.getY(), goal.getZ()));
        assertEquals(optimal, field.estimate(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
        assertEquals(0.0, field.estimate(goal.getX(), goal.getY(), goal.getZ()), 1e-9);
    }

    @Test
    void retargetingTheGoalHeightMatchesAFreshGraph() {
        // Real-game Nether: a destination whose map Y fell inside rock is re-snapped to a standable height once the column is loaded
        FakeCells cells = world(true);
        BlockPos inRock = new BlockPos(54, FLOOR_Y - 8, 50);
        BlockPos standable = new BlockPos(54, FLOOR_Y + 1, 50);
        NavGraph retargeted = built(cells, inRock, 32, 32, 40);
        retargeted.retarget(standable);
        WindowField reused = retargeted.field(32, 32, 40, FarField.UNKNOWN, () -> false);
        WindowField fresh = built(cells, standable, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(reused);
        assertNotNull(fresh);
        for (int x = 0; x < 64; x += 3) {
            for (int z = 0; z < 64; z += 3) {
                assertEquals(fresh.estimate(x, FLOOR_Y + 1, z), reused.estimate(x, FLOOR_Y + 1, z), 1e-9,
                        "(" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void refusesToRetargetToAnotherColumn() {
        NavGraph graph = new NavGraph(new BlockPos(54, FLOOR_Y + 1, 50), 48, 96);
        assertThrows(IllegalArgumentException.class, () -> graph.retarget(new BlockPos(55, FLOOR_Y + 1, 50)));
    }

    @Test
    void neverReturnsZeroOffTheGraph() {
        FakeCells cells = world(true);
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        // Midair, above the shell (2 above/below standable heights). Not a graph node
        double high = field.estimate(10, FLOOR_Y + 12, 50);
        assertTrue(high >= Heuristic.estimate(10, FLOOR_Y + 12, 50, goal.getX(), goal.getY(), goal.getZ()),
                "a point not in the graph went below the geometric lower bound: " + high);
        // One block directly above a standing spot (standing on a placed block). Extends from a nearby node's value and knows about going around the wall
        double onPlacedBlock = field.estimate(10, FLOOR_Y + 2, 50);
        assertTrue(onPlacedBlock > field.estimate(10, FLOOR_Y + 1, 50) * 0.9, "doesn't extend from nearby values: " + onPlacedBlock);
    }

    @Test
    void descendsToTheGoalInsideTheWindow() {
        FakeCells cells = world(true);
        BlockPos start = new BlockPos(10, FLOOR_Y + 1, 50);
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        WindowField.Descent descent = field.descend(start.getX(), start.getY(), start.getZ());
        assertNotNull(descent);
        assertTrue(descent.reachedGoal());
        assertEquals(field.estimate(start.getX(), start.getY(), start.getZ()), descent.inside(), 1e-6);
    }

    @Test
    void descendsToTheWindowEdgeWhenTheGoalIsOutside() {
        FakeCells cells = world(false);
        BlockPos start = new BlockPos(8, FLOOR_Y + 1, 32);
        BlockPos goal = new BlockPos(60, FLOOR_Y + 1, 32);
        FarField far = (x, y, z) -> Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        WindowField field = built(cells, goal, 12, 32, 24).field(12, 32, 24, far, () -> false);
        assertNotNull(field);
        WindowField.Descent descent = field.descend(start.getX(), start.getY(), start.getZ());
        assertNotNull(descent);
        assertFalse(descent.reachedGoal());
        // The window is x=-12..36. The exit is the edge on the destination's side
        assertTrue(descent.exit().getX() >= 12 + 24 - 3, "didn't stop at the edge: " + descent.exit());
        assertEquals(field.estimate(start.getX(), start.getY(), start.getZ()), descent.inside() + descent.outside(), 1e-6);
    }

    @Test
    void seedsTheWindowEdgeFromTheFarField() {
        FakeCells cells = world(false);
        BlockPos start = new BlockPos(8, FLOOR_Y + 1, 32);
        BlockPos goal = new BlockPos(60, FLOOR_Y + 1, 32);
        // The window extends to around x=-16..40. The destination is outside
        NavGraph graph = built(cells, goal, 12, 32, 24);
        // On a flat floor the straight line (geometric lower bound) is exactly optimal, so it's accurate as the outside value
        FarField far = (x, y, z) -> Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        WindowField field = graph.field(12, 32, 24, far, () -> false);
        assertNotNull(field);
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.estimate(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
    }

    @Test
    void tellsASealedPocketApart() {
        // A small room 16 blocks below the ground that can't be left without digging. The shell only reaches 2 above/below standable points, so it doesn't connect to the surface
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 63, 96, 63));
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                for (int y = 40; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        int pocketY = FLOOR_Y - 16;
        for (int x = 9; x <= 11; x++) {
            for (int z = 49; z <= 51; z++) {
                cells.set(x, pocketY, z, FakeCells.AIR);
                cells.set(x, pocketY + 1, z, FakeCells.AIR);
            }
        }
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(field.connects(10, FLOOR_Y + 1, 50), "a surface point isn't connected");
        assertFalse(field.connects(10, pocketY, 50), "treated a small room not connected to the surface as connected");
        // Midair outside the shell (e.g. on top of a placed block) isn't refused, since extending nearby values is enough
        assertTrue(field.connects(10, FLOOR_Y + 12, 50));
    }

    @Test
    void bridgesAVoidWiderThanTheShell() {
        // The End's outer islands. The void between them (40 blocks) is wider than twice the shell's horizontal width, so the middle of the bridge isn't in the shell
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 79, 96, 31)).canPlaceBlocks(true)
                .maxVoidBridgeRunBlocks(96);
        for (int x = 0; x < 80; x++) {
            if (x >= 16 && x < 56) {
                continue;
            }
            for (int z = 0; z < 32; z++) {
                for (int y = FLOOR_Y - 4; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        BlockPos start = new BlockPos(4, FLOOR_Y + 1, 16);
        BlockPos goal = new BlockPos(72, FLOOR_Y + 1, 16);
        WindowField field = built(cells, goal, 40, 16, 40).field(40, 16, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(field.connects(start.getX(), start.getY(), start.getZ()), "the island on the far side isn't connected to the destination");
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.exact(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
    }

    @Test
    void bridgesALavaSeaWiderThanTheShell() {
        // Islands across a Nether lava sea. The lava between them (20 blocks) is wider than twice the shell's horizontal width and narrower than the bridge cap (30)
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 63, 96, 31)).canPlaceBlocks(true)
                .maxBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30);
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 32; z++) {
                boolean sea = x >= 22 && x < 42;
                for (int y = 40; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, sea && y > FLOOR_Y - 8 ? FakeCells.LAVA : FakeCells.STONE);
                }
                if (!sea) {
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
        }
        // The islands' top surface is 7 blocks above the lava surface
        BlockPos start = new BlockPos(4, FLOOR_Y + 7, 16);
        BlockPos goal = new BlockPos(58, FLOOR_Y + 7, 16);
        WindowField field = built(cells, goal, 32, 16, 40).field(32, 16, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(Double.isFinite(field.exact(start.getX(), start.getY(), start.getZ())),
                "the island beyond the lava sea isn't connected to the destination");
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.exact(start.getX(), start.getY(), start.getZ()), optimal * 0.02);
    }

    @Test
    void refusesToGuideWhenTheGoalIsCutOff() {
        FakeCells cells = world(false);
        // The destination is inside the window, but buried in bedrock and unreachable from anywhere in the shell
        BlockPos goal = new BlockPos(40, FLOOR_Y - 10, 32);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertFalse(field.reachesGoal());
    }
}
