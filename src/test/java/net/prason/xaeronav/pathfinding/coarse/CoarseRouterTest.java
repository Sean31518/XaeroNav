package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;

class CoarseRouterTest {

    private static final int RADIUS = 40;

    /** A map that's flat land everywhere. Seas and cliffs are drawn onto it. */
    private static CoarseMapBuilder flatLand() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        return builder;
    }

    private static BlockPos atChunk(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
    }

    @Test
    void routesStraightAcrossOpenLand() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // It just crosses flat land, so Z doesn't leave the start's band
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getZ() >= -16 && waypoint.getZ() <= 32,
                    "veered off despite flat land: " + waypoint);
        }
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void detoursAroundWaterInsteadOfSwimming() {
        CoarseMapBuilder builder = flatLand();
        // A shallow bay blocking the way to the destination. The north side (Z<-2) opens right away, so a short detour avoids it
        for (int x = 4; x <= 16; x++) {
            for (int z = -2; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // If it detours, the legs spanning the bay must be out to the north
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() < -2 * 16),
                "cut straight across the bay instead of going around: " + route.waypoints());
    }

    /**
     * If the detour is too long, swim across. Prone swimming is only about 1/1.56 of sprint speed, so
     * treating "water is to be avoided" as absolute would mean walking 488 blocks around a bay that a 208-block swim would cross.
     */
    @Test
    void swimsAcrossWhenTheDetourIsLongerThanTheCrossing() {
        CoarseMapBuilder builder = flatLand();
        // A bay whose northern opening is far. A detour must go 112 blocks north and back
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().allMatch(waypoint -> waypoint.getZ() >= -16),
                "went around a bay that's faster to swim: " + route.waypoints());
    }

    @Test
    void crossesWaterDirectlyWhenBoatIsAvailable() {
        CoarseMapBuilder builder = flatLand();
        // A bay that can be detoured (same terrain as detoursAroundWaterInsteadOfSwimming). Without a boat it detours, but
        // boats are faster than walking, so with a boat cutting straight across should be cheaper
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), true,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        assertTrue(route.waypoints().stream().noneMatch(waypoint -> waypoint.getZ() < -6 * 16),
                "detoured despite having a boat: " + route.waypoints());
    }

    @Test
    void swimsWhenDetourIsFarLonger() {
        CoarseMapBuilder builder = flatLand();
        // A strait blocking from edge to edge. There's no detour, so swimming is cheaper than going the long way
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void neverRoutesThroughLava() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        // It's completely cut off by lava, so the destination can't be reached
        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "stepped into the lava belt: " + waypoint);
        }
    }

    /**
     * Cells that merely contain some lava are passable. In the Nether the majority of known cells are like this, so
     * making them impassable would leave routes completely disconnected.
     */
    @Test
    void crossesMixedLavaWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** But if it can detour, it does: "passable" and "chosen" are different things. */
    @Test
    void detoursAroundMixedLavaWhenCleanGroundExists() {
        CoarseMapBuilder builder = flatLand();
        // A lava-mixed band is placed on the path, but veering a little in Z lets it go around on plain land
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideMixedLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideMixedLava, "cut through lava-mixed cells despite a detour: " + waypoint);
        }
    }

    /**
     * {@link CoarseRouter.BridgePolicy#AVOID} makes lava-mixed cells impassable too. In the Nether this often leaves routes
     * disconnected, but that's the signal for the caller to move on to the next stage.
     */
    @Test
    void avoidPolicyRefusesMixedLavaEvenWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "stepped into lava-mixed cells: " + waypoint);
        }
    }

    /** If there's a detour, even {@code AVOID} naturally takes it and arrives. */
    @Test
    void avoidPolicyStillReachesGoalByDetouring() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** {@code BRIDGE} crosses lava bands no other policy can pass, on the premise of bridging them. */
    @Test
    void bridgePolicyCrossesFullLavaThatBlocksEveryOtherPolicy() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        assertFalse(CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal());

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(bridged.reachedGoal());
        assertEquals(20 * 16 + 8, last(bridged).getX());
    }

    /** Even with {@code BRIDGE}, if lava can be avoided it takes that way: a last resort, not a shortcut. */
    @Test
    void bridgePolicyStillPrefersCleanGround() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideLava, "crossed lava despite a detour: " + waypoint);
        }
    }

    @Test
    void prefersKnownGroundOverUnmappedShortcut() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        // A single band of known land crosses an area missing from the map. It's placed where veering a little reaches it,
        // because the unknown penalty isn't heavy enough to "avoid even with a long detour" (for a distant band, going straight is correct)
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = 2; z <= 4; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(20, 0, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        // Rather than a straight line through the unknown, it veers to the known band of land
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() >= 2 * 16),
                "cut through the unknown instead of using known land: " + route.waypoints());
    }

    @Test
    void reportsFailureWhenGoalIsOutsideTheMap() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(RADIUS + 10, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertFalse(route.reachedGoal());
        assertTrue(route.isEmpty());
    }

    @Test
    void prefersFlatGroundOverClimbingWhenDistanceIsSimilar() {
        CoarseMapBuilder builder = flatLand();
        // Only the band straight toward the destination is a high ridge. Stepping one cell north is flat
        for (int x = 1; x <= 19; x++) {
            builder.replaceCell(x, 0, CoarseMap.LAND, 140);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getY() < 140, "went over the ridge: " + waypoint);
        }
    }

    @Test
    void avoidsCliffyCellsEvenWhenAverageHeightMatchesSurroundings() {
        CoarseMapBuilder builder = flatLand();
        // Average height is 64, same as the surroundings, but the in-cell relief (0-128) is large = a cliff chunk.
        // The old logic that looked only at averages couldn't detect it and was indifferent between it and the flat detour one cell north
        for (int x = 1; x <= 19; x++) {
            builder.putFloor(x, 0, CoarseMap.LAND, 64, 0, 128);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() != 8),
                "passed straight through a high-relief cell instead of avoiding it: " + route.waypoints());
    }

    /**
     * Without a cap on the cliff penalty, making a wide detour around a wall would always be cheaper than passing through
     * one extremely rugged cell (a value that can't occur in practice, deliberately large to test the boundary).
     * In the Nether, even about 30 blocks of relief costs more than a lava-mixed cell, so
     * this cap guarantees that "no matter how much relief there is, it never costs more than a detour of a few cells".
     */
    @Test
    void cliffPenaltyCapLetsARuggedShortcutBeatALongDetour() {
        CoarseMapBuilder builder = flatLand();
        // Only the x=0 column is a north-south lava wall, open only at z=0. The open cell is an extreme cliff
        // with relief 10000 (highMax=10000 is within short range; note that exceeding 32767 overflows in the 6-argument put's
        // cast and gets rounded to "relief 0", the opposite of the intent).
        // Going around the wall needs at least 6 cells of back-and-forth in z with diagonal moves, and that (12 diagonal cells,
        // about 283 ticks more than going straight) clearly exceeds the cliff penalty cap (about 77 ticks);
        // if the cap weren't working, going around the wall would be cheaper
        for (int z = -5; z <= 5; z++) {
            if (z == 0) {
                continue;
            }
            builder.putFloor(0, z, CoarseMap.LAVA, 64);
        }
        builder.putFloor(0, 0, CoarseMap.LAND, 64, 0, 10_000);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(-20, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        // A detour would temporarily leave z=8. If it passed straight through the cliff cell, it stays at z=8 throughout
        assertTrue(route.waypoints().stream().allMatch(waypoint -> waypoint.getZ() == 8),
                "went around the wall = the cliff penalty cap isn't working: " + route.waypoints());
    }

    /**
     * The crux of the Nether's 3D maze: when a cell has two independent floors stacked vertically, they can be reached by
     * connecting them with a vertical transition. The start and end Y being near their respective floors must also be
     * resolved correctly by {@link CoarseMap#nearestFloor}.
     */
    @Test
    void connectsTwoStackedFloorsInTheSameCellViaAVerticalTransition() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(0, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos start = new BlockPos(8, 41, 8);
        BlockPos goal = new BlockPos(8, 91, 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        assertEquals(90, last(route).getY(), "should end at the height of the floor climbed to (90)");
    }

    /**
     * Horizontal moves connect not to every floor of the adjacent cell, but only to the floor closest to the current one.
     * Without this, moves crossing levels would bypass, disguised as horizontal moves, the rule that "moves between levels
     * not known to really be connected always pay the vertical transition surcharge"
     * (it could cross to a distant floor of an adjacent cell for just the ordinary slope {@code heightPenalty},
     * becoming a loophole around the surcharge imposed by
     * {@link #connectsTwoStackedFloorsInTheSameCellViaAVerticalTransition}).
     *
     * <p>The start cell has only one floor at height 40. Its neighbor (the destination cell) has two floors, at height 42
     * (near) and height 90 (far). The destination Y=90 is still reachable: it just becomes a two-stage route via the nearest
     * floor (42) and then up by vertical transition; 90 doesn't disappear as an "unconnected floor".
     */
    @Test
    void horizontalStepReachesTheFarFloorOnlyThroughTheNearFloorAndAVerticalTransition() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(1, 0, CoarseMap.LAND, 42);
        builder.putFloor(1, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos start = new BlockPos(8, 41, 8);
        BlockPos goal = new BlockPos(24, 91, 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(90, last(route).getY());
    }

    /**
     * {@link CoarseRouter#costToGo}: the guide itself, combined with layer 3's heuristic in stage 4.
     * Computes costs to every state backward from the goal and returns a wrapper that can be looked up by block coordinates.
     */
    @Test
    void costToGoIsZeroAtTheGoalItself() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(5, 5);

        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertEquals(0.0, guide.estimate(goal.getX(), goal.getY(), goal.getZ()), 1e-9);
    }

    @Test
    void costToGoIncreasesWithDistanceOnFlatLand() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(0, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        double near = guide.estimate(atChunk(2, 0).getX(), 64, atChunk(2, 0).getZ());
        double far = guide.estimate(atChunk(10, 0).getX(), 64, atChunk(10, 0).getZ());

        assertTrue(near > 0.0);
        assertTrue(far > near, "the farther cell must have a higher cost: near=" + near + " far=" + far);
    }

    /**
     * Looking up outside the search range (coordinates this map doesn't know) returns 0, not infinity.
     * {@code AStarPathfinder} takes the max with the geometric Heuristic, so returning 0 just means
     * "no information, no contribution"; returning infinity would contaminate the heuristic of every out-of-range node
     * merely because layer 3's search range is wider than this map's read range.
     */
    @Test
    void costToGoReturnsZeroOutsideTheMap() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(0, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        BlockPos farOutside = atChunk(RADIUS + 100, 0);
        assertEquals(0.0, guide.estimate(farOutside.getX(), 64, farOutside.getZ()));
    }

    /**
     * A cell completely cut off from the goal (beyond a lava wall) also returns 0, not infinity.
     * Same safe-side reason as {@link #costToGoReturnsZeroOutsideTheMap}: representing unreachable as infinity
     * could let that cell's heuristic break the entire search via the max.
     */
    @Test
    void costToGoReturnsZeroForCellsUnreachableFromTheGoal() {
        CoarseMapBuilder builder = flatLand();
        for (int z = -RADIUS; z < RADIUS; z++) {
            builder.replaceCell(0, z, CoarseMap.LAVA, 64);
        }
        CoarseMap map = builder.build();
        BlockPos goal = atChunk(20, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.AVOID);

        BlockPos cutOff = atChunk(-20, 0);
        assertEquals(0.0, guide.estimate(cutOff.getX(), 64, cutOff.getZ()));
    }

    /** cost-to-go across levels within the same cell reflects the vertical transition cost (including the surcharge). */
    @Test
    void costToGoAccountsForVerticalTransitionsWithinTheSameCell() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(0, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos goal = new BlockPos(8, 91, 8);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        double atLowerFloor = guide.estimate(8, 41, 8);
        assertTrue(atLowerFloor > 0.0, "a 50-block level difference shouldn't cost 0");
    }

    /**
     * An archipelago in the End. Between it and the destination island is void, and it can go around via islands at the same height.
     * Islands are 4 chunks square, with 2 chunks (32 blocks) of void between them.
     */
    private static CoarseMapBuilder archipelago() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        // Writing nothing means floor 0 = unknown. The void is filled explicitly with VOID
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                        CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
            }
        }
        return builder;
    }

    private static void island(CoarseMapBuilder builder, int minChunkX, int minChunkZ, int height) {
        for (int x = minChunkX; x < minChunkX + 4; x++) {
            for (int z = minChunkZ; z < minChunkZ + 4; z++) {
                builder.replaceCell(x, z, CoarseMap.LAND, height);
            }
        }
    }

    /**
     * The void isn't "not known yet" but "known to have no floor". If it were as cheap to pass as unknown cells,
     * layer 1 would line up intermediate targets cutting straight between the End's islands, and the detailed search would burn its budget every time.
     */
    @Test
    void doesNotRouteThroughVoidWhenAvoiding() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        island(builder, 10, 0, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertFalse(route.reachedGoal(), "AVOID must not reach an island across the void");
    }

    /**
     * <b>The void opens up at {@link CoarseRouter.BridgePolicy#ALLOW} (one stage earlier than lava).</b>
     *
     * <p>Lava bridges have a config switch, but void bridges don't (layer 3 decides by {@code canPlaceBlocks}
     * alone). If ALLOW made the void impassable too, layer 3's leg splitting couldn't create a single leg in the End,
     * and it would try to cross between islands in one search and burn the budget.
     */
    @Test
    void voidOpensOneStepEarlierThanLava() {
        CoarseMapBuilder voidBuilder = archipelago();
        island(voidBuilder, 0, 0, 64);
        island(voidBuilder, 10, 0, 64);
        assertTrue(CoarseRouter.findRoute(voidBuilder.build(), atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal(),
                "if the void is impassable under ALLOW, layer 3's leg splitting doesn't work in the End");

        // Lava is unchanged. ALLOW doesn't cross cells that are "mostly lava"
        CoarseMapBuilder lavaBuilder = flatLand();
        for (int x = 4; x <= 8; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                lavaBuilder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        assertFalse(CoarseRouter.findRoute(lavaBuilder.build(), atChunk(0, 0), atChunk(12, 0), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal(),
                "lava bridges can be turned off in the config, so ALLOW must not cross them on its own");
    }

    /** On the premise of building bridges (the last resort), it reaches on the same terrain. */
    @Test
    void bridgesAcrossVoidWhenNothingElseWorks() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        island(builder, 10, 0, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal(), "if even BRIDGE doesn't reach, there's no way to cross the void");
    }

    /**
     * <b>This is the crux of "going around".</b> The detailed search can't descend to a lower island (no downward bridges can be built).
     * If there's a way via islands at the same height, it <b>chooses that even if it's a detour</b> over cutting straight
     * across the void; the void multiplier decides this.
     */
    @Test
    void prefersSteppingStoneIslandsOverTheShortestVoidCrossing() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        // The destination island. Heading straight there crosses 6 chunks (96 blocks) of void
        island(builder, 12, 0, 64);
        // Stepping stones. A detour, but it only spans one chunk of void at a time
        island(builder, 5, 6, 64);
        island(builder, 10, 5, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(13, 1), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        // If it cut straight across, the route wouldn't leave the Z=0 band. If it goes via the stepping stones, Z drops
        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        assertTrue(maxZ > 32, "cut straight across the void (not via the stepping stones): maxZ=" + maxZ);

        // Control: turning the void into land on the same terrain removes the reason to detour, so it goes straight.
        // Without this, it couldn't be told apart from a test where "only detouring routes ever come out"
        CoarseMapBuilder allLand = archipelago();
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                allLand.replaceCell(x, z, CoarseMap.LAND, 64);
            }
        }
        CoarseRouter.Route control = CoarseRouter.findRoute(allLand.build(), atChunk(1, 1), atChunk(13, 1),
                false, CoarseRouter.BridgePolicy.BRIDGE);
        int controlMaxZ = control.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        assertTrue(controlMaxZ <= 32, "on land there's no reason to detour: maxZ=" + controlMaxZ);
    }

    /**
     * <b>Void waypoints don't drop to Y=0.</b> {@link CoarseMap#VOID} has no height, so if the fallback in
     * {@code toBlockPos} (the previous known height) doesn't kick in, it becomes 0.
     *
     * <p>There's a precedent of hitting the same kind of "misdirection to Y=0" in the Nether: the detailed search tried to draw
     * a path to the bottom of the world and burned through the node cap. Footing over the void is placed at <b>the height of the
     * island departed from</b>, so carrying that over is correct.
     */
    @Test
    void voidWaypointsInheritTheHeightOfTheIslandTheyLeftFrom() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        island(builder, 10, 0, 64);

        CoarseRouter.Route route = CoarseRouter.findRoute(builder.build(), atChunk(1, 1), atChunk(11, 1),
                false, CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getY() > 32,
                    "void waypoint too low (misdirection to Y=0 has recurred): " + waypoint);
        }
    }

    /**
     * Void and unvisited are different things. Cells that merely lack data stay passable as before; making unexplored
     * areas impassable would wipe out detours along with them and leave it stuck.
     */
    @Test
    void unvisitedCellsStayPassableUnlikeVoid() {
        CoarseMapBuilder builder = flatLand();
        // Block the way to the destination with "unvisited" (not by erasing floors, but by making an area that's never written)
        CoarseMapBuilder sparse = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                if (x >= 4 && x <= 8) {
                    continue;
                }
                sparse.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }

        CoarseRouter.Route route = CoarseRouter.findRoute(sparse.build(), atChunk(0, 0), atChunk(12, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertTrue(route.reachedGoal(), "making unvisited cells impassable makes unexplored directions unreachable");
        assertFalse(builder.build().containsChunk(999, 999));
    }

    /** Places a square island of the given size. */
    private static void squareIsland(CoarseMapBuilder builder, int minChunkX, int minChunkZ, int size) {
        for (int x = minChunkX; x < minChunkX + size; x++) {
            for (int z = minChunkZ; z < minChunkZ + size; z++) {
                builder.replaceCell(x, z, CoarseMap.LAND, 64);
            }
        }
    }

    /**
     * Terrain with "a small stepping stone on the straight line" and "a large island a little to the south" between the start and destination islands.
     * Changing only {@code southSize} gives the same terrain apart from the southern island's size.
     */
    private static CoarseMap twoBranches(int southSize) {
        CoarseMapBuilder builder = archipelago();
        squareIsland(builder, 0, -1, 3);
        squareIsland(builder, 14, -1, 3);
        // A single-cell stepping stone on the straight line. Geometrically this is closer
        builder.replaceCell(8, 0, CoarseMap.LAND, 64);
        // An island a little to the south (4 cells). Size varies with size
        squareIsland(builder, 7, 3, southSize);
        return builder.build();
    }

    private static int maxWaypointZ(CoarseRouter.Route route) {
        return route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
    }

    /**
     * A user request (2026-08-30) to <b>travel "across large islands" in the End</b>.
     * It chooses going via the 3x3 island a little to the south over the single-cell rock on the straight line.
     */
    @Test
    void prefersALargeIslandOverATinySteppingStoneOnTheDirectLine() {
        CoarseRouter.Route route = CoarseRouter.findRoute(twoBranches(3), atChunk(1, 0), atChunk(15, 0),
                false, CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        assertTrue(maxWaypointZ(route) >= 3 * 16,
                "stepped on the single-cell rock on the straight line (didn't go around to the large island): " + route.waypoints());
    }

    /**
     * <b>Control.</b> Shrinking the southern island to one cell as well removes the reason to detour, so it steps on the rock on the straight line.
     * Without this, it couldn't be told apart from terrain where "it just always goes around to the south".
     */
    @Test
    void takesTheDirectSteppingStoneWhenTheSouthernIslandIsJustAsTiny() {
        CoarseRouter.Route route = CoarseRouter.findRoute(twoBranches(1), atChunk(1, 0), atChunk(15, 0),
                false, CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        assertTrue(maxWaypointZ(route) < 3 * 16,
                "detoured even though the south is the same size = turning for a reason other than island size: " + route.waypoints());
    }

    private static BlockPos last(CoarseRouter.Route route) {
        List<BlockPos> waypoints = route.waypoints();
        return waypoints.get(waypoints.size() - 1);
    }
}
