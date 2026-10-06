package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.GameCompat;

/**
 * Planning a whole route at once, leg after leg ({@link RoutePreview}), and the corridor it reads
 * ({@link FullRoutePlanner#corridor}).
 */
class RoutePreviewTest {

    private static final int FLOOR = 60;
    private static final int FEET = FLOOR + 1;

    /** Flat ground from x=-8 to x={@code length}; nothing at all (unknown) beyond. */
    private static FakeCells flat(int length) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, FLOOR - 4, -24, length + 40, FLOOR + 12, 24))
                .fillWith(FakeCells.ABSENT);
        for (int x = -8; x <= length; x++) {
            for (int z = -24; z <= 24; z++) {
                cells.set(x, FLOOR, z, FakeCells.BEDROCK);
                for (int y = FEET; y <= FLOOR + 12; y++) {
                    cells.set(x, y, z, FakeCells.AIR);
                }
            }
        }
        return cells;
    }

    @Test
    void splitsTheCorridorIntoLegs() {
        FakeCells cells = flat(400);
        List<BlockPos> polyline = List.of(new BlockPos(0, FEET, 0), new BlockPos(200, FEET, 0),
                new BlockPos(300, FEET, 0));

        List<BlockPos> targets = RoutePreview.legTargets(cells, polyline, 80);

        assertEquals(List.of(new BlockPos(80, FEET, 0), new BlockPos(160, FEET, 0), new BlockPos(240, FEET, 0),
                new BlockPos(300, FEET, 0)), targets, "every 80 blocks along the bends, then the end itself");
    }

    @Test
    void plansAllTheWayToTheDestination() {
        FakeCells cells = flat(400);
        BlockPos goal = new BlockPos(350, FEET, 5);
        List<BlockPos> targets = RoutePreview.legTargets(cells, List.of(new BlockPos(0, FEET, 0), goal), 80);
        List<RoutePreview> progress = new ArrayList<>();

        RoutePreview preview = RoutePreview.plan(() -> cells, new BlockPos(0, FEET, 0), targets, () -> false,
                progress::add);

        assertTrue(preview.complete());
        BlockPos end = preview.points().get(preview.points().size() - 1);
        assertTrue(end.distSqr(goal) <= RoutePreview.GOAL_RADIUS * RoutePreview.GOAL_RADIUS * 2, "ends at " + end);
        assertTrue(progress.size() >= targets.size(), "reports every leg as it goes");
        for (int i = 1; i < preview.points().size(); i++) {
            assertTrue(preview.points().get(i - 1).distSqr(preview.points().get(i)) <= 3, "no gaps between legs");
            assertTrue(preview.ticks()[i] > preview.ticks()[i - 1]);
        }
        assertEquals(preview.ticks()[preview.ticks().length - 1], preview.ticksFrom(0, 0), 1.0e-9);
        assertEquals(0.0, preview.ticksFrom(end.getX(), end.getZ()), 1.0e-9);
    }

    @Test
    void stopsWhereTheKnownTerrainEnds() {
        FakeCells cells = flat(150);
        BlockPos goal = new BlockPos(350, FEET, 0);
        List<BlockPos> targets = RoutePreview.legTargets(cells, List.of(new BlockPos(0, FEET, 0), goal), 80);

        RoutePreview preview = RoutePreview.plan(() -> cells, new BlockPos(0, FEET, 0), targets, () -> false,
                ignored -> { });

        assertFalse(preview.complete());
        BlockPos end = preview.points().get(preview.points().size() - 1);
        assertTrue(end.getX() >= 140 && end.getX() <= 150, "gets as far as the ground goes: " + end);
    }

    @Test
    void stopsWhenCancelled() {
        FakeCells cells = flat(400);
        List<BlockPos> targets = RoutePreview.legTargets(cells,
                List.of(new BlockPos(0, FEET, 0), new BlockPos(350, FEET, 0)), 80);

        RoutePreview preview = RoutePreview.plan(() -> cells, new BlockPos(0, FEET, 0), targets, () -> true,
                ignored -> { });

        assertFalse(preview.complete());
        assertEquals(1, preview.points().size(), "only the start");
    }

    @Test
    void corridorCoversTheLineAndItsSides() {
        Set<Long> corridor = FullRoutePlanner.corridor(List.of(new BlockPos(0, FEET, 0), new BlockPos(320, FEET, 0)));

        int side = FullRoutePlanner.CORRIDOR_CHUNKS;
        assertTrue(corridor.contains(GameCompat.chunkKey(0, 0)));
        assertTrue(corridor.contains(GameCompat.chunkKey(20, 0)), "the end");
        assertTrue(corridor.contains(GameCompat.chunkKey(10, side)));
        assertTrue(corridor.contains(GameCompat.chunkKey(10, -side)));
        assertFalse(corridor.contains(GameCompat.chunkKey(10, side + 1)));
        assertEquals((21 + 2 * side) * (2 * side + 1), corridor.size(), "chunks 0..20 along, widened on all sides");
    }

    @Test
    void corridorStaysWithinItsCap() {
        Set<Long> corridor = FullRoutePlanner.corridor(
                List.of(new BlockPos(0, FEET, 0), new BlockPos(1_000_000, FEET, 0)));

        int square = (2 * FullRoutePlanner.CORRIDOR_CHUNKS + 1) * (2 * FullRoutePlanner.CORRIDOR_CHUNKS + 1);
        assertTrue(corridor.size() < FullRoutePlanner.MAX_CORRIDOR_CHUNKS + square, "size " + corridor.size());
        assertTrue(corridor.contains(GameCompat.chunkKey(0, 0)), "keeps the part nearest the start");
    }
}
