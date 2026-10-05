package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>Offline reproduction of "the Nether route is wrong".</b> Facts captured in the real game (2026-09-07,
 * {@code 0.1.3+6404f17}):
 *
 * <ul>
 * <li>{@code /xaeronav debug mapdata} reported <b>3561 cells only in cave layer 3 (heights 26-55),
 *     and 0 cells in every other layer</b></li>
 * <li>Layer 1's route had 5 intermediate targets and didn't reach the destination; their Y was 33-50</li>
 * <li>Yet looking at the same columns in the save data, <b>the standable places were the lava surface at y32 and the
 *     crimson forest 30 blocks above it (y65-97)</b>. The intermediate targets layer 1 laid out were over the lava sea</li>
 * <li>Layer 2 couldn't solve legs 3, 4, and 5 ({@code leg 3/5: did not reach})</li>
 * </ul>
 *
 * <p><b>The key to reproducing it is that "the map is only visible as a Y slab".</b> Xaero keeps the map of dimensions
 * with a ceiling split into cave layers ({@code CAVE_MODE_DEPTH} = 30 blocks), so only the layers in the height band the
 * player walked get filled. If walkable terrain is in a different layer, layer 1 sees that floor as
 * <b>nonexistent</b>: not even {@link CoarseMap#NO_DATA} (unknown = nearly the cheapest), but
 * <b>a "definite map" saying that cell has only a lava floor</b>.
 *
 * <p>Here the slab is made by cutting the Y of the box passed to {@link LiveCoarseSampler}. Whether layer 1 reads
 * Xaero's map or real data doesn't matter for this symptom (it's only about what {@link CoarseRouter} can see),
 * so the same map can be made without bringing in Xaero.
 */
class NetherCaveLayerSlabReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    /**
     * The height band of the visible layer. Exactly the real game's {@code layer 3: 3561 cells known, height 26-55}.
     * On this terrain only the lava surface (around y31) and the caves below fall in; the walkable crimson forest (y61 and up) is excluded.
     */
    private static final int SLAB_MIN_Y = 26;
    private static final int SLAB_MAX_Y = 55;

    /** Two points on the upper level (the crimson forest), across the lava sea. */
    private static final BlockPos START_COLUMN = new BlockPos(-400, 0, 456);
    private static final BlockPos GOAL_COLUMN = new BlockPos(-216, 0, 520);

    /** Height to start searching for the upper level. A value that doesn't land on top of the bedrock ceiling (y123-127). */
    private static final int UPPER_FLOOR_SEARCH_TOP = 90;

    /** Search budget per leg. Same as the real game's default (100,000 nodes, 2 seconds). */
    private static final SearchLimits LEG_LIMITS =
            new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** Intermediate targets are at chunk resolution, so aim at them as area goals, as in the real game. */
    private static final int LEG_GOAL_RADIUS = 16;

    /**
     * Lower bound on path inflation. Measured at <b>1.52x</b> (1352 ticks/200 steps -> 2057 ticks/258 steps).
     * The real game was even worse: there were 19 legs instead of 5, and the HUD showed 655 blocks remaining
     * against a straight line of 294 blocks.
     */
    private static final double COST_RATIO_FLOOR = 1.35;

    /** If an intermediate target's Y is farther than this from the real terrain's floor, it can't be descended to. */
    private static final int STANDABLE_TOLERANCE_BLOCKS = 8;

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    /**
     * The highest "standable Y" at or below {@code y}.
     *
     * <p>{@code TerrainFixture#standableY} returns the top of the column, so in dimensions with a ceiling it returns
     * <b>the top of the bedrock ceiling</b>. What's needed here is "is there a floor near this height", so cut from above.
     */
    private static int standableAtOrBelow(CellSource cells, SearchBounds bounds, int x, int z, int y) {
        for (int at = Math.min(y, bounds.maxY() - 2); at > bounds.minY(); at--) {
            if (CellData.standable(cells.cell(x, at - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, at, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, at + 1, z))) {
                return at;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Whether there's a standable place in the real terrain near that intermediate target's Y.
     *
     * <p><b>Looks at the whole cell (chunk).</b> An intermediate target is only a chunk-resolution representative point, and
     * the real game also snaps it to a standable place within the chunk via {@code PathfindingState#resolveOnSurface}
     * before use. Looking only at the given column would judge it "unstandable" even if the chunk has a floor.
     */
    private static boolean standableThere(FakeCells terrain, BlockPos waypoint) {
        int baseX = (waypoint.getX() >> 4) << 4;
        int baseZ = (waypoint.getZ() >> 4) << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                int ground = standableAtOrBelow(terrain, terrain.bounds(), x, z,
                        waypoint.getY() + STANDABLE_TOLERANCE_BLOCKS);
                if (ground != Integer.MIN_VALUE
                        && Math.abs(ground - waypoint.getY()) <= STANDABLE_TOLERANCE_BLOCKS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CoarseMap sample(FakeCells terrain, SearchBounds bounds, int referenceY) {
        return LiveCoarseSampler.sample(terrain, bounds, referenceY, NEVER);
    }

    /** The same ladder as the real game ({@code computeCoarseRoute}). Returns the stage reached and the result. */
    private static Attempt ladder(CoarseMap map, BlockPos start, BlockPos goal) {
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, policy);
            if (route.reachedGoal()) {
                return new Attempt(policy, route);
            }
        }
        return new Attempt(null,
                CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.BRIDGE));
    }

    private record Attempt(CoarseRouter.BridgePolicy reachedWith, CoarseRouter.Route route) {
    }

    private static List<BlockPos> unstandableWaypoints(FakeCells terrain, CoarseRouter.Route route) {
        List<BlockPos> bad = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            if (!standableThere(terrain, waypoint)) {
                bad.add(waypoint);
            }
        }
        return bad;
    }

    @Test
    void theVisibleSlabDecidesWhetherTheRouteWalksOrCrossesLava() throws IOException {
        FakeCells terrain = terrain();
        SearchBounds full = terrain.bounds();
        BlockPos start = new BlockPos(START_COLUMN.getX(),
                standableAtOrBelow(terrain, full, START_COLUMN.getX(), START_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                START_COLUMN.getZ());
        BlockPos goal = new BlockPos(GOAL_COLUMN.getX(),
                standableAtOrBelow(terrain, full, GOAL_COLUMN.getX(), GOAL_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                GOAL_COLUMN.getZ());

        CoarseMap seen = sample(terrain, full, start.getY());
        SearchBounds slabBox = new SearchBounds(full.minX(), SLAB_MIN_Y, full.minZ(),
                full.maxX(), SLAB_MAX_Y, full.maxZ());
        CoarseMap slab = sample(terrain, slabBox, (SLAB_MIN_Y + SLAB_MAX_Y) / 2);

        // 1. Floors outside the slab aren't unknown; they "never existed". In the start cell, the floor the
        //    player is actually standing on disappears, and layer 1 solves with the floor 30 blocks below as the "start"
        int startCellX = start.getX() >> 4;
        int startCellZ = start.getZ() >> 4;
        int seenFloor = seen.nearestFloor(startCellX, startCellZ, start.getY());
        int slabFloor = slab.nearestFloor(startCellX, startCellZ, start.getY());
        assertTrue(Math.abs(seen.heightAtFloor(startCellX, startCellZ, seenFloor) - start.getY()) <= 8,
                "with everything visible, the start cell's floor is at the player's feet");
        assertTrue(slab.heightAtFloor(startCellX, startCellZ, slabFloor) < start.getY() - 8,
                "with only the slab visible, the start floor is well below the player's feet (real game: standing at y51, "
                        + "the only map floor at y46)");

        // 2. With everything visible, a path that avoids lava entirely is found, and every intermediate target is standable
        Attempt whenSeen = ladder(seen, start, goal);
        assertEquals(CoarseRouter.BridgePolicy.AVOID, whenSeen.reachedWith(),
                "with the upper level visible, it can walk around the lava");
        assertEquals(List.of(), unstandableWaypoints(terrain, whenSeen.route()),
                "every intermediate target of the avoiding route is standable in the real terrain");

        // 3. With only the slab visible, on the same terrain the ladder falls all the way to ALLOW (real-game log: "No path
        //    avoiding the void or lava-mixed areas was found, so switched to a long-distance route through them"),
        //    and intermediate targets pointing at unstandable places appear. This is the source of the symptom: the detailed
        //    search can't reach them, and makes up for what it can't reach with wide detours
        Attempt whenSlab = ladder(slab, start, goal);
        assertNotEquals(CoarseRouter.BridgePolicy.AVOID, whenSlab.reachedWith(),
                "with only the slab visible, no lava-avoiding path exists on the map");
        assertTrue(whenSlab.route().waypoints().size() > whenSeen.route().waypoints().size(),
                "crawling along the lower level adds intermediate targets (real game: 19 legs)");
        assertNotEquals(List.of(), unstandableWaypoints(terrain, whenSlab.route()),
                "as in the real game, intermediate targets pointing at unstandable places (lava sea, void) appear");
    }

    /**
     * <b>The symptom itself: how many times longer the path gets with the same terrain and endpoints.</b> The real game's HUD
     * showed 655 blocks remaining against a straight line of 294 blocks. Measured as the total cost when assembled by following the intermediate targets in order.
     */
    @Test
    void followingTheSlabRouteCostsFarMoreThanWalkingTheUpperFloor() throws Exception {
        FakeCells terrain = terrain();
        SearchBounds full = terrain.bounds();
        BlockPos start = new BlockPos(START_COLUMN.getX(),
                standableAtOrBelow(terrain, full, START_COLUMN.getX(), START_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                START_COLUMN.getZ());
        BlockPos goal = new BlockPos(GOAL_COLUMN.getX(),
                standableAtOrBelow(terrain, full, GOAL_COLUMN.getX(), GOAL_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                GOAL_COLUMN.getZ());
        SearchBounds slabBox = new SearchBounds(full.minX(), SLAB_MIN_Y, full.minZ(),
                full.maxX(), SLAB_MAX_Y, full.maxZ());

        Walk seen = walk(terrain, start,
                ladder(sample(terrain, full, start.getY()), start, goal).route());
        Walk slab = walk(terrain, start,
                ladder(sample(terrain, slabBox, (SLAB_MIN_Y + SLAB_MAX_Y) / 2), start, goal).route());
        double ratio = slab.cost() / seen.cost();
        String measured = "all visible=" + Math.round(seen.cost()) + "tick/" + seen.steps() + " steps, "
                + "slab=" + Math.round(slab.cost()) + "tick/" + slab.steps() + " steps ("
                + Math.round(ratio * 100) / 100.0 + "x)";

        assertTrue(ratio > COST_RATIO_FLOOR, "with only the slab visible, the path inflates greatly (" + measured + ")");
    }

    /** Measurements from {@link #walk}. */
    private record Walk(double cost, int steps, int reachedLegs) {
    }

    /** Assembles by following intermediate targets in order (the same shape as the real game's leg splitting and extension). */
    private static Walk walk(FakeCells terrain, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        int steps = 0;
        int reached = 0;
        for (BlockPos waypoint : route.waypoints()) {
            PathResult result = executor.submit(terrain, from, waypoint, LEG_LIMITS, true, LEG_GOAL_RADIUS)
                    .get();
            if (result.steps().isEmpty()) {
                break;
            }
            for (PathStep step : result.steps()) {
                cost += step.cost();
            }
            steps += result.steps().size();
            if (!result.complete()) {
                break;
            }
            reached++;
            from = result.steps().get(result.steps().size() - 1).pos();
        }
        return new Walk(cost, steps, reached);
    }
}
