package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGridBuilder;

/**
 * Behavior of long-range route layer 2 ({@link SurfaceCellSource}). Checks whether the existing {@link AStarPathfinder} generates
 * correct moves from just the surface height per column (x,z). There's no new search logic, so all
 * this verifies is that the composition from {@link SurfaceGrid} into {@code CellData} bits is correct.
 */
class SurfaceCellSourceTest {

    private static final int RADIUS = 20;

    private static PathResult search(SurfaceGrid grid, BlockPos start, BlockPos goal) {
        SearchBounds bounds = new SearchBounds(-RADIUS, 0, -RADIUS, RADIUS, 200, RADIUS);
        return new AStarPathfinder(new SurfaceCellSource(grid, bounds, true, 0)).search(start, goal, () -> false);
    }

    private static List<MovementType> movements(PathResult result) {
        return result.steps().stream().map(PathStep::movement).toList();
    }

    @Test
    void walksStraightAcrossFlatLand() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAND, 64);
            }
        }

        PathResult result = search(builder.build(), new BlockPos(0, 65, 0), new BlockPos(10, 65, 0));

        assertTrue(result.complete(), "flat land is always reachable");
        assertTrue(result.steps().stream().noneMatch(PathStep::digging), "layer 2 does not handle digging");
    }

    @Test
    void cannotCrossASheerCliffWithoutADetour() {
        // A strip only 5 wide. At x=5 the height drops 44 blocks from 64 to 20. Falls connect only up to 3 blocks,
        // so with no detour outside the strip the path can't continue
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-2, -2, 20, 5);
        for (int x = -2; x < 18; x++) {
            for (int z = -2; z < 3; z++) {
                builder.put(x, z, CoarseMap.LAND, x < 5 ? 64 : 20);
            }
        }
        SearchBounds bounds = new SearchBounds(-2, 0, -2, 18, 200, 3);
        PathResult result = new AStarPathfinder(new SurfaceCellSource(builder.build(), bounds, true, 0))
                .search(new BlockPos(0, 65, 0), new BlockPos(15, 21, 0), () -> false);

        assertFalse(result.complete(), "a 44-block cliff can't be crossed without a detour");
    }

    @Test
    void swimsAcrossAWaterGap() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                if (x >= 5 && x <= 9) {
                    // Water bottom 55, surface 64. The surface is at the same Y as the adjacent land's ground (64): addDescend/addAscend
                    // are both one-step moves, and "land -> water -> land" only connects when 64, one below the land's
                    // feet (65), matches the water surface (with a lower surface, the landing spot going up to land is buried)
                    builder.put(x, z, CoarseMap.WATER, 55, 64);
                } else {
                    builder.put(x, z, CoarseMap.LAND, 64);
                }
            }
        }

        PathResult result = search(builder.build(), new BlockPos(0, 65, 0), new BlockPos(15, 65, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.SWIM), "water legs come out as swimming: " + movements(result));
    }

    @Test
    void neverEntersLava() {
        // A strip only 5 wide. Lava can't be crossed without a detour (layer 2 handles neither digging nor placing)
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-2, -2, 20, 5);
        for (int x = -2; x < 18; x++) {
            for (int z = -2; z < 3; z++) {
                byte kind = x >= 5 && x <= 9 ? CoarseMap.LAVA : CoarseMap.LAND;
                builder.put(x, z, kind, 64);
            }
        }
        SearchBounds bounds = new SearchBounds(-2, 0, -2, 18, 200, 3);
        PathResult result = new AStarPathfinder(new SurfaceCellSource(builder.build(), bounds, true, 0))
                .search(new BlockPos(0, 65, 0), new BlockPos(15, 65, 0), () -> false);

        assertFalse(result.complete(), "lava can't be crossed without a detour");
        assertTrue(result.steps().stream().noneMatch(step -> step.pos().getX() >= 5 && step.pos().getX() <= 9),
                "stepped into the lava strip: " + result.steps());
    }

    @Test
    void outOfRangeColumnsAreDiscarded() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(0, 0, CoarseMap.LAND, 64);
        builder.put(100, 100, CoarseMap.LAND, 64);
        SurfaceGrid grid = builder.build();

        assertEquals(CoarseMap.NO_DATA, grid.kindAt(100, 100));
        assertEquals(SurfaceGrid.UNKNOWN_HEIGHT, grid.groundHeightAt(100, 100));
    }
}
