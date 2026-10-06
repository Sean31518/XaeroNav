package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.Mount;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Routes planned while riding ({@link MountMoves}): the animal needs more room than the player, is faster, and the route
 * only gets off where it can't go on.
 */
class MountRouteTest {

    private static final int FLOOR = 60;
    private static final int FEET = FLOOR + 1;

    /** An average horse: 1.4 wide, 1.6 tall, steps one block, speed 0.225. */
    private static final Mount HORSE = Mount.of(1.3965, 1.6, 1.0, 0.225);

    private static FakeCells field() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-6, FLOOR - 4, -14, 40, FLOOR + 12, 14));
        for (int x = -6; x <= 40; x++) {
            for (int z = -14; z <= 14; z++) {
                cells.set(x, FLOOR, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /** A bedrock wall across x = {@code wallX}, three blocks high, with openings at the given z. */
    private static FakeCells wall(int wallX, int... openZ) {
        FakeCells cells = field();
        for (int z = -14; z <= 14; z++) {
            boolean open = false;
            for (int o : openZ) {
                open |= o == z;
            }
            if (!open) {
                for (int y = FEET; y <= FEET + 3; y++) {
                    cells.set(wallX, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    private static PathResult search(FakeCells cells, BlockPos goal) {
        return new AStarPathfinder(cells).search(new BlockPos(0, FEET, 0), goal, () -> false);
    }

    private static boolean dismounts(PathResult result) {
        return result.steps().stream().anyMatch(step -> step.movement() == MovementType.DISMOUNT);
    }

    private static double cost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void readsTheHorseAsTheSizeItIs() {
        assertEquals(1, HORSE.footprintRadius(), "1.4 wide overhangs its cell");
        assertEquals(2, HORSE.bodyHeight());
        assertEquals(3, HORSE.riderHeight(), "the rider sits on top");
        assertEquals(1, HORSE.stepHeight());
        assertTrue(HORSE.ticksPerBlock() < 3.0, "faster than sprinting: " + HORSE.ticksPerBlock());
    }

    @Test
    void ridesAcrossOpenGroundFasterThanWalking() {
        BlockPos goal = new BlockPos(20, FEET, 0);

        PathResult riding = search(field().mount(HORSE), goal);
        PathResult walking = search(field(), goal);

        assertTrue(riding.complete());
        assertTrue(riding.steps().stream().allMatch(PathStep::riding), "never gets off on open ground");
        assertTrue(cost(riding) < cost(walking) * 0.7, cost(riding) + " vs " + cost(walking));
        assertTrue(walking.steps().stream().noneMatch(PathStep::riding), "on foot there is nothing to ride");
    }

    @Test
    void takesTheWideOpeningInsteadOfTheOneBlockGap() {
        // A 1-wide gap straight ahead, a 3-wide opening further to the side
        PathResult result = search(wall(10, 0, 6, 7, 8).mount(HORSE), new BlockPos(20, FEET, 0));

        assertTrue(result.complete());
        assertFalse(dismounts(result), "a horse fits the wide opening, so it stays on");
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getX() == 10 && step.pos().getZ() == 7),
                "goes through the middle of the wide opening: " + result.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void getsOffWhereOnlyAPersonFits() {
        PathResult result = search(wall(10, 0).mount(HORSE), new BlockPos(20, FEET, 0));

        assertTrue(result.complete());
        assertTrue(dismounts(result), "the 1-wide gap is the only way through");
        List<PathStep> steps = result.steps();
        int off = 0;
        while (steps.get(off).movement() != MovementType.DISMOUNT) {
            assertTrue(steps.get(off).riding());
            off++;
        }
        assertTrue(steps.get(off).pos().getX() < 10, "gets off before the wall");
        assertTrue(steps.subList(off + 1, steps.size()).stream().noneMatch(PathStep::riding), "no getting back on");
    }

    @Test
    void stepsUpOneBlockWithoutGettingOff() {
        FakeCells cells = field().mount(HORSE);
        for (int x = 10; x <= 40; x++) {
            for (int z = -14; z <= 14; z++) {
                cells.set(x, FEET, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells, new BlockPos(20, FEET + 1, 0));

        assertTrue(result.complete());
        assertFalse(dismounts(result));
    }

    @Test
    void keepsTheRiderOutOfLowTunnels() {
        // A tunnel two high: the horse fits, the rider on top doesn't; the long way round is open
        FakeCells cells = field().mount(HORSE);
        for (int x = 8; x <= 12; x++) {
            for (int z = -14; z <= 14; z++) {
                boolean tunnel = z >= -2 && z <= 2;
                for (int y = FEET; y <= FEET + 4; y++) {
                    if (!tunnel || y >= FEET + 2) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
        }
        // ...and a way round past the end of the wall
        for (int x = 8; x <= 12; x++) {
            for (int y = FEET; y <= FEET + 4; y++) {
                cells.set(x, y, 13, FakeCells.AIR);
                cells.set(x, y, 14, FakeCells.AIR);
                cells.set(x, y, 12, FakeCells.AIR);
            }
        }

        PathResult result = search(cells, new BlockPos(20, FEET, 0));

        assertTrue(result.complete());
        for (PathStep step : result.steps()) {
            if (step.riding() && step.pos().getX() >= 8 && step.pos().getX() <= 12) {
                assertTrue(step.pos().getZ() >= 12, "never rides into the low tunnel: " + step.pos());
            }
        }
    }

    @Test
    void neverRidesIntoWater() {
        FakeCells cells = field().mount(HORSE);
        for (int x = 5; x <= 15; x++) {
            for (int z = -14; z <= 14; z++) {
                cells.set(x, FLOOR, z, FakeCells.WATER);
                cells.set(x, FLOOR - 1, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells, new BlockPos(20, FEET, 0));

        for (PathStep step : result.steps()) {
            if (step.riding()) {
                assertTrue(step.pos().getX() < 5 || step.pos().getX() > 15, "rides into the water at " + step.pos());
            }
        }
    }
}
