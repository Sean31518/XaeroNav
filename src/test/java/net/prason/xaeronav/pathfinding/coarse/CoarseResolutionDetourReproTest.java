package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * Offline reproduction of "wasteful routes (detours) / mysteriously makes you cross". User report:
 * "it mysteriously makes you cross things", "lots of wasteful routes (detours). <b>Doesn't happen at short range</b>".
 *
 * <p>Hypothesis ([[xaeronav-next-plans]]): the chunk-resolution layer 1 ({@link CoarseMap}) can't represent land
 * crossings narrower than 16 blocks. A narrow isthmus tips over to {@code waterSamples*2 >= samples} in
 * {@link LiveCoarseSampler}'s aggregation and becomes a {@code WATER} chunk, so the isthmus disappears from the map.
 * Layer 1 can then only output intermediate targets that "swim across the water / take a big detour", and the detailed
 * search (layer 3) faithfully builds bridges or swims to those targets = "mysteriously makes you cross".
 *
 * <p>This test observes rather than asserts. It prints side by side what the detailed search does on block-resolution
 * terrain and what layer 1 outputs on the coarse map of the same terrain squashed by {@link LiveCoarseSampler}.
 */
class CoarseResolutionDetourReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 63;
    private static final int STAND_Y = 64;
    private static final int WATER_SURFACE_Y = 63;

    private static final int MIN_X = -16;
    private static final int MAX_X = 208;
    private static final int MIN_Z = -16;
    private static final int MAX_Z = 96;

    /**
     * The start-side land (x<64) and destination-side land (x>=144) are connected only by an isthmus {@code isthmusWidth}
     * blocks wide. Outside the isthmus, x∈[64,144) is a channel. North of the isthmus (if {@code northBridge} is true),
     * a 16-block-wide land bridge = visible even at chunk resolution, is placed at z∈[64,80).
     */
    private static FakeCells terrain(int isthmusWidth, boolean northBridge) {
        SearchBounds bounds = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X, 110, MAX_Z);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(0);
        for (int x = MIN_X; x < MAX_X; x++) {
            for (int z = MIN_Z; z < MAX_Z; z++) {
                boolean inChannel = x >= 64 && x < 144;
                boolean onIsthmus = z >= 0 && z < isthmusWidth;
                boolean onNorthBridge = northBridge && z >= 64 && z < 80;
                if (inChannel && !onIsthmus && !onNorthBridge) {
                    // Channel: stone floor + 2 blocks of water
                    cells.set(x, GROUND_Y - 2, z, FakeCells.STONE);
                    cells.set(x, WATER_SURFACE_Y - 1, z, FakeCells.WATER);
                    cells.set(x, WATER_SURFACE_Y, z, FakeCells.WATER);
                } else {
                    cells.set(x, GROUND_Y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    private static CoarseMap coarseOf(FakeCells cells) {
        SearchBounds b = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X - 1, 110, MAX_Z - 1);
        return LiveCoarseSampler.sample(cells, b);
    }

    private static int chunkKind(CoarseMap map, int chunkX, int chunkZ) {
        if (map.floorCount(chunkX, chunkZ) == 0) {
            return -1;
        }
        return map.kindAtFloor(chunkX, chunkZ, 0);
    }

    private static String kindName(int k) {
        return switch (k) {
            case -1 -> "----";
            case CoarseMap.NO_DATA -> "no  ";
            case CoarseMap.LAND -> "land";
            case CoarseMap.WATER -> "WATR";
            case CoarseMap.LAVA -> "lava";
            case CoarseMap.LAVA_MIXED -> "lavm";
            case CoarseMap.VOID -> "void";
            default -> "?" + k;
        };
    }

    /** Prints the band the isthmus runs along (chunkZ=0..) as one row in the x direction. */
    private static void dumpIsthmusRow(String label, CoarseMap map) {
        StringBuilder sb = new StringBuilder(label).append("  chunkZ=0: ");
        for (int cx = MIN_X >> 4; cx <= (MAX_X - 1) >> 4; cx++) {
            sb.append(kindName(chunkKind(map, cx, 0))).append(' ');
        }
        System.out.println(sb);
    }

    private static int waterSteps(FakeCells cells, PathResult result) {
        int n = 0;
        for (PathStep step : result.steps()) {
            BlockPos p = step.pos();
            if (CellData.water(cells.cell(p.getX(), p.getY(), p.getZ()))
                    || CellData.water(cells.cell(p.getX(), p.getY() - 1, p.getZ()))) {
                n++;
            }
        }
        return n;
    }

    private static int bridgeSteps(PathResult result) {
        int n = 0;
        for (PathStep step : result.steps()) {
            if (step.bridging()) {
                n++;
            }
        }
        return n;
    }

    private static void reportDetail(String label, FakeCells cells, BlockPos start, BlockPos goal,
                                      int goalRadius) {
        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult r = new AStarPathfinder(cells, limits).search(start, goal, NEVER, goalRadius);
        int maxZ = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (PathStep s : r.steps()) {
            maxZ = Math.max(maxZ, s.pos().getZ());
            minZ = Math.min(minZ, s.pos().getZ());
        }
        System.out.printf("%-40s complete=%-5s term=%-13s steps=%-3d water=%-3d bridge=%-3d zRange=[%d,%d]%n",
                label, r.complete(), r.termination(), r.steps().size(),
                waterSteps(cells, r), bridgeSteps(r), minZ, maxZ);
    }

    private static int throughWaterCells(CoarseMap map, CoarseRouter.Route route) {
        int n = 0;
        BlockPos prev = null;
        for (BlockPos w : route.waypoints()) {
            if (prev != null) {
                int steps = Math.max(Math.abs(w.getX() - prev.getX()), Math.abs(w.getZ() - prev.getZ())) / 16;
                for (int i = 0; i <= steps; i++) {
                    int x = steps == 0 ? w.getX() : prev.getX() + (w.getX() - prev.getX()) * i / steps;
                    int z = steps == 0 ? w.getZ() : prev.getZ() + (w.getZ() - prev.getZ()) * i / steps;
                    if (chunkKind(map, x >> 4, z >> 4) == CoarseMap.WATER) {
                        n++;
                    }
                }
            }
            prev = w;
        }
        return n;
    }

    private static CoarseRouter.Route reportCoarse(String label, CoarseMap map, BlockPos start, BlockPos goal) {
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                CoarseRouter.BridgePolicy.AVOID);
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos w : route.waypoints()) {
            maxZ = Math.max(maxZ, w.getZ());
        }
        System.out.printf("%-40s reached=%-5s waypoints=%-2d throughWaterCells=%-2d maxWaypointZ=%d%n",
                label, route.reachedGoal(), route.waypoints().size(), throughWaterCells(map, route), maxZ);
        System.out.println("   " + route.waypoints());
        return route;
    }

    /**
     * Main scenario: a 4-block-wide isthmus is the only land route. The detailed search walks the isthmus. Layer 1
     * loses the isthmus and outputs intermediate targets that swim the channel / bridge it.
     */
    @Test
    void narrowIsthmusVanishesFromCoarseMapAndForcesAWaterCrossing() {
        FakeCells narrow = terrain(4, false);
        FakeCells wide = terrain(16, false);

        BlockPos start = new BlockPos(8, STAND_Y, 2);
        BlockPos goal = new BlockPos(200, STAND_Y, 2);

        System.out.println("--- detail search (block resolution) ---");
        reportDetail("narrow isthmus (4 wide) -> goal", narrow, start, goal, 0);
        reportDetail("wide isthmus  (16 wide) -> goal", wide, start, goal, 0);

        CoarseMap narrowCoarse = coarseOf(narrow);
        CoarseMap wideCoarse = coarseOf(wide);

        System.out.println("--- coarse map (chunk resolution) ---");
        System.out.println("narrow: " + narrowCoarse.kindBreakdown());
        dumpIsthmusRow("narrow", narrowCoarse);
        System.out.println("wide:   " + wideCoarse.kindBreakdown());
        dumpIsthmusRow("wide  ", wideCoarse);

        System.out.println("--- coarse route (layer 1 waypoints) ---");
        CoarseRouter.Route narrowRoute = reportCoarse("narrow isthmus", narrowCoarse, start, goal);
        CoarseRouter.Route wideRoute = reportCoarse("wide isthmus", wideCoarse, start, goal);

        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult detail = new AStarPathfinder(narrow, limits).search(start, goal, NEVER, 0);

        // The detailed search walks the full 4-wide isthmus without entering water = the land route really exists
        assertTrue(detail.complete(), "the detailed search can reach by walking the isthmus: " + detail.termination());
        assertEquals(0, waterSteps(narrow, detail), "the detailed search entered water despite a land route");

        // Even so, layer 1 loses the isthmus and outputs intermediate targets that cross the channel
        assertTrue(throughWaterCells(narrowCoarse, narrowRoute) > 0,
                "layer 1 doesn't cross the channel = not reproduced: " + narrowRoute.waypoints());
        // If the isthmus is wide enough to be seen at chunk resolution, layer 1 doesn't enter water on the same terrain
        assertEquals(0, throughWaterCells(wideCoarse, wideRoute),
                "with a 16-wide isthmus, layer 1 should take the land route: " + wideRoute.waypoints());
    }

    /**
     * Detour scenario: north of a 4-wide isthmus (z∈[0,4), invisible), there's land that opens up at z≥{@code openFrom}
     * (visible at chunk resolution). Since the isthmus is invisible, layer 1 can only detour north or swim the
     * channel. The detailed search walks the isthmus.
     */
    private static FakeCells terrainWithNorthGap(int isthmusWidth, int channelMaxX, int openFrom) {
        SearchBounds bounds = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X, 110, MAX_Z);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(0);
        for (int x = MIN_X; x < MAX_X; x++) {
            for (int z = MIN_Z; z < MAX_Z; z++) {
                boolean inChannel = x >= 64 && x < channelMaxX;
                boolean onIsthmus = z >= 0 && z < isthmusWidth;
                boolean northOfGap = z >= openFrom;
                if (inChannel && !onIsthmus && !northOfGap) {
                    cells.set(x, GROUND_Y - 2, z, FakeCells.STONE);
                    cells.set(x, WATER_SURFACE_Y - 1, z, FakeCells.WATER);
                    cells.set(x, WATER_SURFACE_Y, z, FakeCells.WATER);
                } else {
                    cells.set(x, GROUND_Y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    @Test
    void coarseMapDetoursOrSwimsBecauseTheShortcutIsSubChunk() {
        FakeCells cells = terrainWithNorthGap(4, 112, 32);
        BlockPos start = new BlockPos(8, STAND_Y, 2);
        BlockPos goal = new BlockPos(200, STAND_Y, 2);

        System.out.println("--- detail vs coarse (short isthmus + near north gap) ---");
        reportDetail("detail -> goal", cells, start, goal, 0);

        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult detail = new AStarPathfinder(cells, limits).search(start, goal, NEVER, 0);
        int detailMaxZ = detail.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);

        CoarseMap coarse = coarseOf(cells);
        System.out.println(coarse.kindBreakdown());
        dumpIsthmusRow("isthmus row", coarse);
        CoarseRouter.Route route = reportCoarse("coarse route", coarse, start, goal);
        int coarseMaxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);

        assertTrue(detail.complete() && detailMaxZ <= 8, "the detailed search walks along the isthmus (z≈2): maxZ=" + detailMaxZ);
        assertTrue(coarseMaxZ >= 24,
                "layer 1 should lose the isthmus and detour north (the long way round): coarseMaxZ=" + coarseMaxZ);
    }
}
