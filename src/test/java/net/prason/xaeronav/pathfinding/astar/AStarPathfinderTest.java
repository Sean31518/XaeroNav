package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Behavior of the pathfinding core. Terrain is written as characters with {@link FakeCells}.
 *
 * <p>What this pins down is "which moves are generated and which are not". Rather than fine-grained cost
 * constants, it checks whether the path a human would expect for the terrain comes back. Guidance breaks
 * down when the shape of the path is wrong, not over a cost difference of a few ticks.
 */
class AStarPathfinderTest {

    private static final BooleanSupplierNever NOT_CANCELLED = new BooleanSupplierNever();

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED);
    }

    private static List<MovementType> movements(PathResult result) {
        return result.steps().stream().map(PathStep::movement).toList();
    }

    /**
     * The largest number of levels dropped in a single step of the path. Beyond {@link ActionCosts#SAFE_FALL_BLOCKS},
     * that fall deals damage. {@code PathStep} carries no {@code MoveKind}
     * (it is folded down to {@code MovementType}), so we look at <b>how far the guidance actually drops</b>, not the kind.
     */
    private static int biggestDrop(BlockPos start, PathResult result) {
        int biggest = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            biggest = Math.max(biggest, previous.getY() - step.pos().getY());
            previous = step.pos();
        }
        return biggest;
    }

    @Test
    void walksStraightAcrossFlatGround() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "a straight line on flat ground must always be reachable");
        assertEquals(5, result.steps().size());
        assertEquals(List.of(MovementType.TRAVERSE, MovementType.TRAVERSE, MovementType.TRAVERSE,
                MovementType.TRAVERSE, MovementType.TRAVERSE), movements(result));
        assertTrue(result.steps().stream().noneMatch(PathStep::digging), "no need to dig");
        assertEquals(new BlockPos(5, 61, 0), last(result).pos());
    }

    @Test
    void climbsAndDescendsAOneBlockStep() {
        // a one-block step at x=2,3
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ..##..
                ######""");

        PathResult up = search(cells, new BlockPos(0, 61, 0), new BlockPos(3, 62, 0));
        assertTrue(up.complete());
        assertTrue(movements(up).contains(MovementType.ASCEND), "climbs over the step: " + movements(up));
        assertTrue(up.steps().stream().noneMatch(PathStep::digging), "must not dig a climbable step");

        PathResult down = search(cells, new BlockPos(3, 62, 0), new BlockPos(0, 61, 0));
        assertTrue(down.complete());
        assertTrue(movements(down).contains(MovementType.DESCEND), "the way down is treated as a step too: " + movements(down));
    }

    @Test
    void climbsDiagonallyUpAStaircase() {
        // Lay only a staircase floor rising one level in both X and Z: (0,61,0)→(1,62,1)→(2,63,2)→(3,64,3).
        // No cardinal floor (e.g. (1,60,0)) is placed at all, so a cardinal decomposition can't climb it
        CellSource cells = diagonalStaircase();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(3, 64, 3));

        assertTrue(result.complete());
        // A cardinal decomposition takes 2 steps per level (ascend + straight) = 6 steps. Diagonally it's 1 per level = 3
        assertEquals(3, result.steps().size(),
                "diagonal ascent should take one step per level: " + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(List.of(MovementType.ASCEND, MovementType.ASCEND, MovementType.ASCEND), movements(result));
        assertEquals(new BlockPos(3, 64, 3), last(result).pos());
    }

    @Test
    void descendsDiagonally() {
        // descend the same staircase as the test above, in reverse
        CellSource cells = diagonalStaircase();

        PathResult result = search(cells, new BlockPos(3, 64, 3), new BlockPos(0, 61, 0));

        assertTrue(result.complete());
        assertEquals(3, result.steps().size(),
                "diagonal descent should take one step per level: " + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(List.of(MovementType.DESCEND, MovementType.DESCEND, MovementType.DESCEND), movements(result));
        assertEquals(new BlockPos(0, 61, 0), last(result).pos());
    }

    /**
     * <b>Crosses two blocks that touch only corner to corner in one diagonal step.</b> This is the user-reported
     * "terrain where block corners touch and it looks like you could just walk across".
     *
     * <p>Vanilla can cross it too: the player's hitbox is 0.6 blocks wide, so as it passes the corner the body fits
     * as long as the two columns it overhangs are empty, and the feet rest on both blocks.
     * As {@link #doesNotCutThroughABlockedCorner} shows, it can't be crossed <b>only when those two columns</b>
     * <b>are blocked</b>.
     */
    @Test
    void walksAcrossBlocksThatTouchOnlyAtACorner() {
        // nothing (no floor, no wall) in the two corner columns ((1,60,0) and (0,60,1)) = void
        CellSource cells = FakeCells.empty(new SearchBounds(-2, 55, -2, 8, 75, 8))
                .set(0, 60, 0, FakeCells.STONE)
                .set(1, 60, 1, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 61, 1));

        assertTrue(result.complete(), "crossable when corners touch");
        assertEquals(1, result.steps().size(), "should cross in one diagonal step: "
                + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(List.of(MovementType.TRAVERSE), movements(result));
    }

    /** Crosses a run of stepping stones connected only at the corners. Crossing one is not the same as chaining them. */
    @Test
    void walksAlongAChainOfCornerTouchingBlocks() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-2, 55, -2, 10, 75, 10));
        for (int i = 0; i <= 4; i++) {
            cells.set(i, 60, i, FakeCells.STONE);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(4, 61, 4));

        assertTrue(result.complete());
        assertEquals(4, result.steps().size(), "should stay diagonal all four times: "
                + result.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void doesNotCutThroughABlockedCorner() {
        // Block one corner (1,62,0) of the diagonal ascent with stone. The landing floor (1,61,1) itself is free,
        // so it can't go diagonally but can in two cardinal steps (straight in z, then ascend)
        CellSource cells = diagonalAscendWithCardinalDetour().set(1, 62, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 62, 1));

        assertTrue(result.complete(), "reachable by a detour even with a blocked corner");
        assertEquals(2, result.steps().size(), "the diagonal is blocked, so it detours in two cardinal steps: "
                + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(new BlockPos(0, 61, 1), result.steps().get(0).pos());
        assertEquals(new BlockPos(1, 62, 1), last(result).pos());
    }

    @Test
    void doesNotJumpDiagonallyUnderALowCeiling() {
        // Block the headroom (0,63,0) above the takeoff point. The two-cardinal-step side has its headroom elsewhere, so it is unaffected
        CellSource cells = diagonalAscendWithCardinalDetour().set(0, 63, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 62, 1));

        assertTrue(result.complete(), "reachable by a detour even with blocked headroom");
        assertEquals(2, result.steps().size(), "headroom blocks the jump, so it detours in two cardinal steps: "
                + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(new BlockPos(0, 61, 1), result.steps().get(0).pos());
        assertEquals(new BlockPos(1, 62, 1), last(result).pos());
    }

    /** A staircase from (0,61,0) to (3,64,3) with only floors rising one level in both X and Z. No cardinal floors. */
    private static CellSource diagonalStaircase() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        return FakeCells.empty(bounds)
                .set(0, 60, 0, FakeCells.STONE)
                .set(1, 61, 1, FakeCells.STONE)
                .set(2, 62, 2, FakeCells.STONE)
                .set(3, 63, 3, FakeCells.STONE);
    }

    /**
     * Terrain with only the floors needed for both a one-level diagonal ascent (0,61,0)→(1,62,1) and a cardinal route
     * around it ((0,61,0)→(0,61,1)→(1,62,1), straight in z then ascend).
     */
    private static FakeCells diagonalAscendWithCardinalDetour() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        return FakeCells.empty(bounds)
                .set(0, 60, 0, FakeCells.STONE)
                .set(0, 60, 1, FakeCells.STONE)
                .set(1, 61, 1, FakeCells.STONE);
    }

    @Test
    void digsThroughAWallWhenThereIsNoWayAround() {
        // the ceiling is bedrock, so it can't be climbed over. The only way is to dig through the 2-high wall
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBB
                ..##..
                ..##..
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        List<BlockPos> dug = result.steps().stream().flatMap(step -> step.digCells().stream()).toList();
        // The head height is a dig target too, not just the feet. Looking only at the one landing block would
        // emit a path the head can't actually fit through as "passable if dug"
        assertTrue(dug.contains(new BlockPos(2, 61, 0)), "digs the base of the wall: " + dug);
        assertTrue(dug.contains(new BlockPos(2, 62, 0)), "also digs the wall at head height: " + dug);
        assertTrue(dug.contains(new BlockPos(3, 61, 0)), "digs the base of the wall: " + dug);
        assertTrue(dug.contains(new BlockPos(3, 62, 0)), "also digs the wall at head height: " + dug);
    }

    @Test
    void doesNotDigThroughUndiggableBlocks() {
        // An undiggable wall. Equivalent to how ChunkView marks every solid when diggingEnabled=false
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBB
                ..BB..
                ..BB..
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertFalse(result.complete(), "cannot reach past an undiggable wall");
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getX() < 2),
                "there must be no step past the wall: " + result.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void returnsNoRouteRatherThanAUselesslyShortOne() {
        // a sealed room whose movable area is under MIN_DIST_PATH (5 blocks)
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBB
                ..BB
                BBBB""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(20, 61, 0));

        assertFalse(result.complete());
        assertTrue(result.steps().isEmpty(),
                "does not offer a path that only advances a few blocks (useless as guidance): " + result.steps().size());
    }

    @Test
    void offersAPartialRouteWhenTheGoalIsOutOfReach() {
        // The end of a long corridor is blocked. The goal is out of reach, but the reachable part is worth guiding
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBBBBBBBBBBBBBB
                ................B#
                ................B#
                ##################""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(40, 61, 0));

        assertFalse(result.complete(), "has not reached the goal");
        assertFalse(result.steps().isEmpty(), "guides as far as it can reach (provisional path)");
        assertTrue(last(result).pos().getX() >= 5,
                "returns a point at least MIN_DIST_PATH from the start: " + last(result).pos());
    }

    @Test
    void swimsAcrossWaterWithoutAFloor() {
        // A stretch of water surface. With no footing no Traverse is generated, so it can only be crossed by Swim
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                .~~~~.
                #~~~~#""")
                .fillWith(FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.SWIM), "water stretches come out as swimming: " + movements(result));
    }

    @Test
    void climbsALadderInsteadOfDigging() {
        // A ladder in a shaft (y=61-63 at x=3). The ladder is cheaper than digging, so the path should use it
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...H##
                ###H##
                ###H##
                ######""");

        PathResult result = search(cells, new BlockPos(3, 61, 0), new BlockPos(1, 63, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.CLIMB), "emits a ladder-climbing move: " + movements(result));
        assertTrue(result.steps().stream().noneMatch(PathStep::digging),
                "does not dig when there is a ladder: " + result.steps().stream().flatMap(s -> s.digCells().stream()).toList());
    }

    @Test
    void surfaceSearchStopsAtTheFirstCellAtOrAboveSurfaceLevel() {
        // terrain rising like stairs toward the east. y=64 is the surface
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                ....#
                ...##
                ..###
                .####
                #####""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(2, 61, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "there is a way up to the surface");
        assertTrue(last(result).pos().getY() >= 64,
                "stops at or above surfaceY: " + last(result).pos());
        assertTrue(result.steps().stream().filter(step -> step.pos().getY() >= 64).count() == 1,
                "cuts off as soon as it reaches surfaceY (from there the route to the real destination is recomputed)");
    }

    @Test
    void surfaceSearchWalksOutFromUnderARoofInsteadOfStoppingAtHeight() {
        // A tunnel at y=65-66. The west side (x=0,1) is under a rock ceiling, the east side (x=2,3) is open to the sky
        CellSource cells = FakeCells.of(0, 64, 0, """
                ....
                ##..
                ....
                ....
                ####""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(0, 65, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "walking to the opening gets it to the surface");
        assertTrue(last(result).pos().getX() >= 2,
                "under a ceiling is not the surface even with enough height. Advance to a column open to the sky: " + last(result).pos());
        assertTrue(result.steps().stream().noneMatch(PathStep::digging),
                "does not dig when it can walk out through the existing tunnel: " + movements(result));
    }

    /**
     * In the sea, "got its head above the water" counts as reaching the surface. The MOTION_BLOCKING heightmap
     * used by {@code openSkyY} includes fluids, so it points <b>one above</b> the water surface, but that is air with
     * no footing and never a node a swimming player can stand on. Using it as the condition as-is meant the
     * relay search to the surface could, in principle, never succeed in the open ocean.
     */
    @Test
    void surfaceSearchReachesTheWaterLineWhenTheColumnIsSea() {
        // y=62 is the seabed, y=63-66 water, y=67 and up sky (fillWith is not used, so outside the drawing is air)
        CellSource cells = FakeCells.of(0, 62, 0, """
                ......
                ~~~~~~
                ~~~~~~
                ~~~~~~
                ~~~~~~
                ######""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(0, 63, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "swimming up reaches the water surface");
        assertEquals(66, last(result).pos().getY(),
                "counts a water-surface cell as reached (the one above is out of the water = can't stand): " + last(result).pos());
    }

    /**
     * A flooded side tunnel and a longer detour where it can catch its breath. A dive longer than one breath
     * <b>doesn't create the move at all</b>, so the search only ever sees the detour.
     *
     * <p>y=63 is the water surface (head can be above water), y=61-62 the underwater tunnel. The north (z=1) side is open to the surface.
     */
    private static FakeCells floodedTunnel() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8))
                .fillWith(FakeCells.BEDROCK);
        for (int x = -1; x <= 13; x++) {
            // z=0: a flooded tunnel capped by a ceiling (y=64). The head stays submerged the whole swim
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 0, FakeCells.WATER);
            }
            // z=2: a channel with its water surface under the sky. Swimming at y=63 keeps the head out, so no breath is lost
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 2, FakeCells.WATER);
            }
            cells.set(x, 64, 2, FakeCells.AIR);
        }
        // Only the ends (x=-1, x=13) connect the two. z=1 in between is a bedrock wall, so it can't
        // break off mid-tunnel to surface
        for (int x : new int[] {-1, 13}) {
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 1, FakeCells.WATER);
            }
            cells.set(x, 64, 1, FakeCells.AIR);
        }
        return cells;
    }

    @Test
    void doesNotRouteThroughADiveLongerThanOneBreath() {
        CellSource cells = floodedTunnel().maxSubmergedTicks(22);

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(12, 62, 0));

        assertTrue(result.complete(), "reachable by going around via the channel where the head can surface");
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getZ() != 0),
                "does not push through a flooded tunnel longer than one breath; veers to the channel where it can surface: "
                        + result.steps().stream().map(PathStep::pos).toList());
    }

    /**
     * Confirms {@link #doesNotRouteThroughADiveLongerThanOneBreath} isn't passing vacuously.
     * Without the limit it goes straight through the flooded tunnel on the same terrain = breath is what makes it veer.
     */
    @Test
    void divesStraightThroughWhenTheBreathLimitIsOff() {
        CellSource cells = floodedTunnel().maxSubmergedTicks(0);

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(12, 62, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getZ() == 0),
                "without a limit it goes straight through the shortest flooded tunnel: "
                        + result.steps().stream().map(PathStep::pos).toList());
    }

    /**
     * Swims diagonally underwater. A diagonal move with footing ({@code addDiagonalTraverse}) doesn't hold underwater,
     * so without a swim-specific diagonal it gets decomposed into two cardinal steps.
     *
     * <p>It has a ceiling so it can't surface, to look only at whether it can swim diagonally. In open sea it would
     * first rise to the surface ({@link #surfacesBeforeCrossingOpenWater}), mixing in that behavior.
     */
    @Test
    void swimsDiagonallyThroughWater() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 12));
        // A body of water sandwiched between a floor (y=60) and ceiling (y=64). Swimming at y=62 there is no footing
        for (int x = -1; x <= 6; x++) {
            for (int z = -1; z <= 6; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                cells.set(x, 64, z, FakeCells.BEDROCK);
                for (int y = 61; y <= 63; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(4, 62, 4));

        assertTrue(result.complete());
        assertEquals(4, result.steps().size(),
                "swimming diagonally advances one block in both X and Z per step: " + result.steps().stream().map(PathStep::pos).toList());
        assertTrue(result.steps().stream().allMatch(step -> step.movement() == MovementType.SWIM),
                "underwater diagonals are guided as swimming too: " + movements(result));
    }

    /**
     * From the shore (x=0) across the water surface (x=1..width) to the far shore (x=width+1). Water surface at y=62,
     * shore at y=63: an ordinary coastline with the water one block lower.
     */
    private static FakeCells strait(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, width + 12, 76, 8));
        for (int x = -1; x <= width + 2; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean water = x >= 1 && x <= width;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    @Test
    void takesTheBoatAcrossAWideStrait() {
        CellSource cells = strait(40).boatAvailable(true);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(PathStep::boating),
                "40 blocks of water are crossed by boat: " + movements(result));
        assertEquals(1, result.steps().stream().filter(step -> step.movement() == MovementType.BOAT
                        && step.cost() > ActionCosts.BOAT_LAUNCH_TICKS).count(),
                "the cost of placing and boarding is paid only once, on launch: " + movements(result));
    }

    /**
     * Same shape but a 20-block channel doesn't get a boat. The cost of placing and boarding, plus exiting, breaking
     * and picking it up ({@code BOAT_LAUNCH_TICKS}, {@code BOAT_STOW_TICKS}) outweighs the difference from swimming.
     */
    @Test
    void swimsAcrossANarrowChannelInsteadOfLaunchingABoat() {
        CellSource cells = strait(20).boatAvailable(true);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(21, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().noneMatch(PathStep::boating),
                "a 20-block channel is just swum across: " + movements(result));
    }

    /**
     * If already aboard, don't charge the boarding cost again. Charging it would, when little water remains,
     * produce guidance saying "getting out and swimming is cheaper".
     */
    @Test
    void doesNotChargeBoardingAgainWhileAlreadyRiding() {
        CellSource cells = strait(40).boatAvailable(true).ridingBoat(true);

        // the start is on the water surface (where it's aboard). The destination is the far shore
        PathResult result = search(cells, new BlockPos(1, 62, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> !step.boating()
                        || step.cost() < ActionCosts.BOAT_LAUNCH_TICKS),
                "a segment still pays the boarding cost: "
                        + result.steps().stream().filter(PathStep::boating)
                                .map(PathStep::cost).toList());
    }

    @Test
    void doesNotOfferABoatWithoutOneInTheInventory() {
        CellSource cells = strait(40).boatAvailable(false);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete(), "can swim across without a boat");
        assertTrue(result.steps().stream().noneMatch(PathStep::boating),
                "doesn't ask to place a boat it doesn't have: " + movements(result));
    }

    /**
     * Mining underwater uses up breath accordingly. A block takes tens of ticks, so counting remaining breath in
     * <b>blocks</b> made a 40-tick mine count as just "1 block", letting underwater digging routes slip past the limit.
     */
    @Test
    void countsUnderwaterDiggingAgainstTheBreathLimit() {
        // A submerged stone wall. No way but to dig through (bedrock above and below)
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 8));
        for (int x = -1; x <= 4; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 65, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, x == 2 ? FakeCells.STONE : FakeCells.WATER);
            }
        }

        // Limit 45 ticks = about 8 blocks of swimming. Paying 5x for mining one stone block (40 ticks) underwater exceeds it
        PathResult limited = search(cells.maxSubmergedTicks(45), new BlockPos(0, 61, 0),
                new BlockPos(3, 61, 0));

        assertFalse(limited.complete(),
                "out of breath, so it can't dig through the underwater wall: " + movements(limited));
    }

    /** Confirms {@link #countsUnderwaterDiggingAgainstTheBreathLimit} isn't passing vacuously. */
    @Test
    void diggingUnderwaterIsStillAllowedWithinTheBreathLimit() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 8));
        for (int x = -1; x <= 4; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 65, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, x == 2 ? FakeCells.STONE : FakeCells.WATER);
            }
        }

        PathResult unlimited = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(3, 61, 0));

        assertTrue(unlimited.complete(), "without a limit it can dig through");
        assertTrue(unlimited.steps().stream().anyMatch(PathStep::digging),
                "becomes a path that digs through: " + movements(unlimited));
    }

    /**
     * An underwater dig move gets the surcharge even if the landing head cell is a solid about to be dug, since the head is in water while digging.
     * When the start was next to the wall, a check looking only at the landing cell priced it like land (seen at a real channel exit).
     */
    @Test
    void chargesTheUnderwaterDigPenaltyWhenDiggingFromTheStart() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 8));
        for (int x = -1; x <= 3; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 65, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, x == 1 ? FakeCells.STONE : FakeCells.WATER);
            }
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0), new BlockPos(2, 61, 0));

        assertTrue(result.complete(), "unreachable: " + movements(result));
        List<PathStep> digs = result.steps().stream().filter(PathStep::digging).toList();
        assertFalse(digs.isEmpty(), "ended up as a path that doesn't dig: " + movements(result));
        for (PathStep step : digs) {
            double raw = step.digCells().stream()
                    .mapToDouble(pos -> CellData.digTicks(cells.cell(pos.getX(), pos.getY(), pos.getZ())))
                    .sum();
            assertTrue(step.cost() >= raw * ActionCosts.SUBMERGED_DIG_PENALTY,
                    "underwater mining has no surcharge: raw dig=" + raw + ", with move=" + step.cost());
        }
    }

    /** A deep sea open from the seabed (y=54) to the surface (y=70). Long in x, with nothing in the way. */
    private static FakeCells openSea(int length) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 44, -8, length + 12, 86, 8));
        for (int x = -1; x <= length + 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 54, z, FakeCells.BEDROCK);
                for (int y = 55; y <= 70; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    /**
     * Out in deep sea, <b>rise to the surface first</b> instead of crossing submerged. The horizontal travel is what would drain breath,
     * so it's safer to get out from under the water first and then cross, and on the surface a boat can be used too.
     */
    @Test
    void surfacesBeforeCrossingOpenWater() {
        CellSource cells = openSea(60);

        PathResult result = search(cells, new BlockPos(0, 55, 0), new BlockPos(58, 70, 0));

        assertTrue(result.complete());
        int surfacedAt = -1;
        for (int i = 0; i < result.steps().size(); i++) {
            if (result.steps().get(i).pos().getY() == 70) {
                surfacedAt = i;
                break;
            }
        }
        assertTrue(surfacedAt >= 0, "surfaces: " + result.steps().stream().map(PathStep::pos).toList());
        // 15 blocks from the seabed (55) to the surface (70). One block up per step, so rising without waste takes 15 steps
        assertTrue(surfacedAt <= 16, "surfaces without detours (steps until surfacing): " + (surfacedAt + 1));
        // keeps moving toward the destination the whole time = not an L shape of straight up and then sideways
        int advancedWhileRising = result.steps().get(surfacedAt).pos().getX();
        assertTrue(advancedWhileRising >= 10,
                "rises diagonally while heading to the destination (horizontal distance covered while surfacing): " + advancedWhileRising);
    }

    /**
     * While crossing open sea, the path <b>sticks to the water-surface layer</b>.
     *
     * <p>The height while crossing carries no guidance content (every layer of water is equally passable), so any
     * bending up or down becomes pure noise in the line. If this wobbles, both the assumption that rendering places
     * the line relative to the water surface ({@code PathGeometry}) and the assumption that vertical offset underwater
     * isn't counted as deviation ({@code PathfindingState#offPathDistance}) break at once.
     *
     * <p>It sticks because of {@link ActionCosts#SUBMERGED_TRAVEL_PENALTY}: a water-surface cell has the head out of
     * the water, so no surcharge applies, while dipping even one block applies it.
     */
    @Test
    void staysOnTheSurfaceLayerWhileCrossingOpenWater() {
        // 58 blocks from the surface (y=70) to the far shore. Submerged or afloat it's all water, so only cost picks the layer
        CellSource cells = openSea(60);

        PathResult result = search(cells, new BlockPos(0, 70, 0), new BlockPos(58, 70, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 70),
                "does not leave the water-surface layer: " + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(58, result.steps().size(),
                "crosses straight at one block per step: " + result.steps().size() + " steps");
    }

    /**
     * Does not dip back underwater right before climbing onto the shore.
     *
     * <p>Back when shallows (water where the feet touch bottom) were priced as "walking on the bed", that was 1.64x
     * swimming, so <b>avoiding the shallows, sinking into deeper water and swimming before climbing out</b> was
     * cheaper. That is what showed up in-game as "a trajectory that dips back into the water before going ashore".
     *
     * <p>Vanilla's {@code LivingEntity#travel} picks the water branch on {@code isInWater()} alone, and whether
     * the feet touch bottom only feeds an equipment factor. Speed is the same standing or swimming, so there is no
     * reason to make shallows more expensive.
     */
    @Test
    void staysAtTheSurfaceInsteadOfDivingBackBeforeComingAshore() {
        // A sloping beach: the seabed rises one block at a time from y=60 at x=8 to y=65 at x=15. Water surface at y=65
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 50, -8, 30, 86, 8));
        for (int x = -1; x <= 25; x++) {
            int floorTop = x < 8 ? 60 : Math.min(60 + (x - 8), 65);
            for (int z = -1; z <= 1; z++) {
                for (int y = 60; y <= floorTop; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
                for (int y = floorTop + 1; y <= 65; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }

        PathResult result = new AStarPathfinder(cells.maxSubmergedTicks(0))
                .search(new BlockPos(2, 62, 0), new BlockPos(18, 66, 0), NOT_CANCELLED);

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        for (int i = 1; i < steps.size(); i++) {
            assertTrue(steps.get(i).pos().getY() >= steps.get(i - 1).pos().getY(),
                    "sinks on the way to the shore: " + steps.stream().map(PathStep::pos).toList());
        }
    }

    /**
     * When heading for the water surface, it can rise while advancing in both X and Z.
     *
     * <p>Back when surfacing had only the four cardinal directions, it got decomposed into "go straight, then rise" or
     * "rise, then go diagonally", and the path bent at a right angle just on the stretch toward the surface (in-game
     * report: "there's no diagonal option at the water surface").
     */
    @Test
    void risesDiagonallyTowardsASurfaceGoalOffTheAxis() {
        // A sufficiently large body of water. The destination is diagonally above (X, Z and the Z direction all need to change)
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 30, -8, 40, 86, 40));
        for (int x = -1; x <= 30; x++) {
            for (int z = -1; z <= 30; z++) {
                cells.set(x, 40, z, FakeCells.BEDROCK);
                for (int y = 41; y <= 55; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 41, 0),
                new BlockPos(20, 52, 20));

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        boolean roseDiagonally = false;
        for (int i = 1; i < steps.size(); i++) {
            BlockPos previous = steps.get(i - 1).pos();
            BlockPos current = steps.get(i).pos();
            if (current.getY() > previous.getY()
                    && current.getX() != previous.getX() && current.getZ() != previous.getZ()) {
                roseDiagonally = true;
                break;
            }
        }
        assertTrue(roseDiagonally,
                "surfacing is tied to the four cardinal directions, bending at a right angle only on the rising stretch: "
                        + steps.stream().map(PathStep::pos).toList());
    }

    /**
     * The <b>diagonal version</b> of the same "bounce to dodge the surcharge" check above. The existing guard is a 1-wide
     * corridor, so diagonal moves are never generated and it can't catch bouncing once diagonal surfacing is added. This one checks open water.
     */
    @Test
    void doesNotBobDiagonallyToDodgeTheSubmergedPenalty() {
        // A flooded room with a ceiling and floor. <b>The destination is diagonal, not straight ahead</b>: bouncing only pays off
        // on stretches that "pay the diagonal price even when moving honestly"; where it can move cardinally,
        // two plain horizontal steps (2·P) are already cheaper than a diagonal bounce (√3+√2·P), so it wouldn't guard anything
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 24));
        for (int x = -1; x <= 13; x++) {
            for (int z = -1; z <= 13; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                for (int y = 61; y <= 64; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
                cells.set(x, 65, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 12));

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        int climbs = 0;
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).pos().getY() > steps.get(i - 1).pos().getY()) {
                climbs++;
            }
        }
        assertEquals(0, climbs,
                "rises even though it isn't heading out of the water = bouncing to dodge the surcharge: "
                        + steps.stream().map(PathStep::pos).toList());
    }

    /**
     * Does not bounce up and down to exploit the surfacing surcharge exemption. Only diagonal surfacing is exempt,
     * so if the value is too large, repeatedly "rising diagonally and sinking diagonally" becomes cheaper than moving
     * horizontally, giving an endlessly undulating path underwater. The cap on {@code SUBMERGED_TRAVEL_PENALTY} comes from this.
     */
    @Test
    void doesNotBobUpAndDownToDodgeTheSubmergedPenalty() {
        // A flooded corridor with a ceiling. It can only move cardinally, so bouncing up and down is the only loophole
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8));
        for (int x = -1; x <= 13; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, FakeCells.WATER);
            }
            cells.set(x, 65, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 0));

        assertTrue(result.complete());
        int climbs = 0;
        List<PathStep> steps = result.steps();
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).pos().getY() > steps.get(i - 1).pos().getY()) {
                climbs++;
            }
        }
        assertTrue(climbs <= 1, "undulating up and down underwater: "
                + steps.stream().map(step -> step.pos().getY()).toList());
    }

    /**
     * Where it can't surface, the shape doesn't change. In flooded caves or roofed channels the surcharge just applies
     * uniformly, and there is no option other than staying submerged anyway.
     */
    @Test
    void stillSwimsThroughAFloodedTunnelWithNoSurfaceAbove() {
        // Only y=61-62 is water, with a bedrock ceiling at y=63. A flooded tunnel with no way to surface
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8));
        for (int x = -1; x <= 13; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.WATER);
            cells.set(x, 62, 0, FakeCells.WATER);
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 0));

        assertTrue(result.complete(), "a flooded tunnel is passable even without surfacing");
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() <= 62),
                "the ceiling keeps the height unchanged: " + result.steps().stream().map(PathStep::pos).toList());
    }

    /** A deep sea: bed at 40, water at 41-70, sky above. */
    private static FakeCells deepSea(int length) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 30, -8, length + 12, 86, 8));
        for (int x = -1; x <= length + 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 40, z, FakeCells.BEDROCK);
                for (int y = 41; y <= 70; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    /**
     * For a distant underwater destination, it first surfaces for air and then heads there.
     *
     * <p>Back when the breath limit was held in <b>blocks</b>, this was unreachable: just surfacing from the bed
     * exceeded the limit, so it couldn't even go up for air and the path got cut off midway. Now that the limit is
     * in ticks, it correctly measures whether surfacing, crossing and diving each fit within one breath.
     */
    @Test
    void surfacesToBreatheOnTheWayToADistantUnderwaterGoal() {
        CellSource cells = deepSea(64).maxSubmergedTicks(250);

        PathResult result = search(cells, new BlockPos(0, 45, 0), new BlockPos(60, 45, 0));

        assertTrue(result.complete(), "with breathing stops it reaches even a deep underwater destination: " + result.termination());
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getY() == 70),
                "goes up to the surface along the way: " + result.steps().stream().mapToInt(step -> step.pos().getY()).max());
    }

    /**
     * For an underwater destination within breath range, it heads straight there without detouring to the surface. The
     * safety surcharge ({@code SUBMERGED_TRAVEL_PENALTY}) must not turn short distances with breath to spare into detours.
     */
    @Test
    void goesStraightToANearbyUnderwaterGoal() {
        CellSource cells = deepSea(16).maxSubmergedTicks(250);

        PathResult result = search(cells, new BlockPos(0, 45, 0), new BlockPos(12, 45, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 45),
                "goes straight without surfacing if breath lasts: " + result.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void samePathIsReturnedForTheSameTerrain() {
        // Cutting off by expanded-node count makes the same input return the same path.
        // Cutting off by time would change the line with the machine load at the moment, and guidance wouldn't settle
        CellSource cells = FakeCells.of(0, 60, 0, """
                ..........
                ....##....
                ##########""");
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(9, 61, 0);

        List<BlockPos> first = search(cells, start, goal).steps().stream().map(PathStep::pos).toList();
        List<BlockPos> second = search(cells, start, goal).steps().stream().map(PathStep::pos).toList();

        assertEquals(first, second);
        assertNotEquals(0, first.size());
    }

    /**
     * A gap {@code gapBlocks} wide. Both banks are bedrock, so it can neither dig down nor go around.
     * Outside the cross-section (z≠0) is filled with bedrock too, leaving no way but jumping.
     */
    private static FakeCells chasm(int gapBlocks) {
        FakeCells cells = FakeCells.of(0, 60, 0, "B".repeat(gapBlocks + 4))
                .fillWith(FakeCells.BEDROCK);
        for (int x = 0; x < gapBlocks + 4; x++) {
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        // The gap is gapBlocks wide starting at x=2. Remove the floor and hollow it out deep so a fall finds no footing
        for (int x = 2; x < 2 + gapBlocks; x++) {
            for (int y = 40; y <= 60; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        return cells;
    }

    @Test
    void jumpsGapsUpToThreeBlocksWide() {
        for (int gap = 1; gap <= 3; gap++) {
            CellSource cells = chasm(gap);

            PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(2 + gap, 61, 0));

            assertTrue(result.complete(), gap + "-block gap should be jumpable");
            assertTrue(movements(result).contains(MovementType.JUMP),
                    gap + "-block gap crossed without jumping: " + movements(result));
        }
    }

    @Test
    void doesNotJumpOffSoulSand() {
        // A one-block gap. Only the takeoff point (x=1) is soul sand
        CellSource cells = chasm(1).set(1, 60, 0, FakeCells.SOUL_SAND);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "taking off while slowed falls short. Must not say to jump: " + movements(result));
    }

    @Test
    void doesNotJumpOverLava() {
        // Fill the bottom of a one-block gap with lava. It's a jumpable width, but missing is death
        FakeCells cells = chasm(1);
        cells.set(2, 60, 0, FakeCells.LAVA);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "doesn't jump over lava: " + movements(result));
    }

    /**
     * Cobweb multiplies the movement itself by 0.25 in {@code WebBlock#entityInside}, so with the takeoff point
     * inside a web, both the sprint speed and the jump's initial speed are cut sharply the moment it steps off. It may
     * reach in theory, but the chance of missing and falling is too high, so it doesn't guide a jump.
     */
    @Test
    void doesNotJumpFromACobweb() {
        // A one-block gap. The takeoff point (x=1) itself is a cobweb
        CellSource cells = chasm(1).set(1, 61, 0, FakeCells.COBWEB);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "doesn't jump from on top of a cobweb: " + movements(result));
    }

    /**
     * Cobweb has no collision box, so {@code clearWithoutDigging} passes through it, but if the body grazes it
     * mid-air the speed gets cut for the same reason. Looking only at the takeoff doesn't prevent that.
     */
    @Test
    void doesNotJumpThroughACobwebInTheGap() {
        // In a 3-block gap, fill the near side (x=2) of the space jumped over with cobweb
        CellSource cells = chasm(3).set(2, 61, 0, FakeCells.COBWEB);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(5, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "does not generate a jump that grazes a cobweb: " + movements(result));
    }

    @Test
    void stillJumpsWhenThereIsAFloorAboveTheLava() {
        // A gap with lava below but a floor above it. Falling wouldn't touch the lava, so jumping is fine
        FakeCells cells = chasm(1);
        cells.set(2, 55, 0, FakeCells.LAVA).set(2, 56, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.JUMP),
                "can jump if there's a floor between it and the lava: " + movements(result));
    }

    @Test
    void doesNotJumpGapsBeyondSprintJumpRange() {
        // A 4-block gap exceeds the reach of a sprint jump
        CellSource cells = chasm(4);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(6, 61, 0));

        assertFalse(result.complete(), "must not say to jump an unreachable distance");
        assertFalse(movements(result).contains(MovementType.JUMP), "no jump is generated: " + movements(result));
    }

    @Test
    void bridgesInsteadOfJumpingWhenJumpingIsDisabled() {
        CellSource cells = chasm(2).jumpGapEnabled(false).canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete(), "even without jumping, it can cross by placing blocks");
        assertFalse(movements(result).contains(MovementType.JUMP),
                "jumped even though jumping is disabled: " + movements(result));
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "crosses by placing footing instead of jumping: " + movements(result));
    }

    @Test
    void doesNotCrossAtAllWhenJumpingAndBridgingAreBothDisabled() {
        CellSource cells = chasm(2).jumpGapEnabled(false).canPlaceBlocks(false);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(result.complete(), "a gap it can neither jump nor bridge is uncrossable");
    }

    @Test
    void landsOnTheNearestBankRatherThanJumpingFarther() {
        // Past the far bank (x=4) of a 2-block gap there is another gap (x=5). It should land on the near bank
        FakeCells cells = chasm(2);
        for (int y = 40; y <= 60; y++) {
            cells.set(5, y, 0, FakeCells.AIR);
        }
        cells.set(6, 61, 0, FakeCells.AIR).set(6, 62, 0, FakeCells.AIR).set(6, 63, 0, FakeCells.AIR);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete());
        assertEquals(new BlockPos(4, 61, 0), last(result).pos(), "lands on the near bank");
    }

    /**
     * A cliff it can neither dig up nor go around. Only Pillar gets it up.
     * Outside the cross-section (z≠0) is filled with bedrock because if it stayed air, it could bridge around.
     */
    private static FakeCells bedrockCliff() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                .BBB..
                .BBB..
                .BBB..
                BBBBBB""")
                .fillWith(FakeCells.BEDROCK);
    }

    @Test
    void pillarsUpACliffThatCannotBeDugOrWalkedAround() {
        CellSource cells = bedrockCliff().canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertTrue(result.complete(), "pillaring up blocks gets it on top of the cliff");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "a stretch places blocks to climb: " + movements(result));
        assertTrue(result.steps().stream().filter(PathStep::bridging)
                        .allMatch(step -> step.movement() == MovementType.ASCEND),
                "a pillaring stretch is guided as ascending: " + movements(result));
    }

    @Test
    void doesNotPillarWithoutBlocksInTheHotbar() {
        CellSource cells = bedrockCliff().canPlaceBlocks(false);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertFalse(result.complete(), "without blocks to place, the cliff can't be climbed");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "doesn't ask to place blocks it doesn't have: " + result.steps());
    }

    @Test
    void doesNotPillarThroughAnUnbreakableCeiling() {
        // Same terrain as the cliff, but the headroom above the pillaring column (x=0) is blocked by bedrock
        CellSource cells = bedrockCliff().set(0, 63, 0, FakeCells.BEDROCK).canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertFalse(result.complete(), "can't pillar up under an undiggable ceiling");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging), "no pillaring stretch: " + result.steps());
    }

    /**
     * A lava channel enclosed in bedrock. The 4 blocks underfoot (y=60) are lava: too wide to walk, too far to jump.
     * The surroundings are filled with bedrock, so it can't detour, dig, or place footing in mid-air; the only way
     * across is to place footing on the lava itself.
     */
    private static FakeCells lavaPond() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                #LLLL#""")
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
    }

    @Test
    void bridgesOverLavaWhenThereIsNoOtherWay() {
        CellSource cells = lavaPond();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "rather than getting stuck, it places footing on the lava to cross");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "lava is crossed by placing: " + movements(result));
    }

    @Test
    void doesNotBridgeOverLavaWhenDisabled() {
        CellSource cells = lavaPond().lavaBridgingEnabled(false);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertFalse(result.complete(), "with it disabled, lava may stay uncrossable");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "does not guide placing footing on lava: " + result.steps());
    }

    /** A lava bridge is a last resort. If there's a dry detour, it takes that even if it's somewhat longer. */
    @Test
    void prefersADryDetourOverBridgingLava() {
        // Same terrain as the lava channel, with a bare-ground detour carved only on the z=1 side
        FakeCells cells = lavaPond();
        for (int x = 0; x <= 5; x++) {
            cells.set(x, 60, 1, FakeCells.STONE);
            cells.set(x, 61, 1, FakeCells.AIR);
            cells.set(x, 62, 1, FakeCells.AIR);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "reachable thanks to the detour");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "placed footing on lava even though it could detour: " + movements(result));
    }

    /**
     * Nether weeping and twisting vines can be walked through, but in vanilla they are <b>not replaceable</b>, so
     * blocks can't be placed there (aiming at them sends it to the neighboring cell). Don't judge placeability by collision alone.
     */
    @Test
    void neverPlacesABlockWhereVanillaWouldRefuseIt() {
        FakeCells cells = chasm(2).canPlaceBlocks(true).jumpGapEnabled(false);
        // Fill the gap at floor height with weeping vines. The body passes through, but footing can't be placed there
        cells.set(2, 60, 0, FakeCells.NETHER_VINE);
        cells.set(3, 60, 0, FakeCells.NETHER_VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.steps().stream()
                        .map(PathStep::placedBlockPos)
                        .filter(pos -> pos != null)
                        .noneMatch(pos -> pos.getY() == 60 && pos.getX() >= 2 && pos.getX() < 4),
                "places footing where weeping vines prevent placement: " + result.steps());
    }

    /**
     * While holding onto a ladder or vine, {@code onGround()} is false and {@code jumpFromGround()} isn't called.
     * Even if grounded while holding on, {@code handleOnClimbable} clamps horizontal speed to ±0.15.
     */
    @Test
    void doesNotJumpWhileHangingOnAClimbable() {
        FakeCells cells = chasm(2).jumpGapEnabled(true).canPlaceBlocks(false);
        for (int z = -1; z <= 1; z++) {
            cells.set(1, 61, z, FakeCells.LADDER);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "can't jump while holding onto a ladder: " + movements(result));
    }

    /**
     * For the same reason as addPillar ({@code onGround()} is false, {@code handleOnClimbable} clamps speed),
     * addBridge can't take off while holding a ladder or vine either. When the full width of the passage is covered
     * by ladders, the only move is the impossible one of bridging while holding on, so unreachable is correct (issue #46).
     */
    @Test
    void doesNotBridgeWhileHangingOnAClimbable() {
        FakeCells cells = chasm(2).jumpGapEnabled(false).canPlaceBlocks(true);
        for (int z = -1; z <= 1; z++) {
            cells.set(1, 61, z, FakeCells.LADDER);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(result.complete(),
                "unreachable, since the only way across is bridging while holding a ladder: " + result.steps());
    }

    /**
     * For the same reason as addJumpGap (onGround() is false, so jumpFromGround() isn't called),
     * addAscend can't take off while holding a ladder or vine either.
     */
    @Test
    void doesNotAscendWhileHangingOnAClimbable() {
        // Terrain with a one-block step at x=2,3 (built from bedrock so it can't dig around it).
        // Cover the takeoff position (x=1) with a ladder
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                .HBB..
                BBBBBB""");

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 62, 0));

        assertFalse(result.complete(), "can't jump up the step while holding a ladder: " + result.steps());
    }

    /**
     * The diagonal ascent (addDiagonalAscend) is subject to the same constraint as addAscend for the same reason.
     */
    @Test
    void doesNotDiagonalAscendWhileHangingOnAClimbable() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        CellSource cells = FakeCells.empty(bounds)
                .set(0, 61, 0, FakeCells.LADDER)
                .set(1, 61, 1, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 62, 1));

        assertFalse(result.complete(), "can't jump up diagonally either while holding a ladder: " + result.steps());
    }

    /**
     * Descending or falling needs no jump, so generation itself isn't forbidden (unlike the addAscend family,
     * {@code onGround()} is irrelevant), but since {@code handleOnClimbable} clamps horizontal speed to ±0.15 blocks/tick,
     * it must reliably cost more than the sprint-based price
     * ({@link ActionCosts#CLIMBABLE_TAKEOFF_SPEED_FACTOR}).
     */
    @Test
    void costsMoreToDescendOffAClimbableThanOffOrdinaryGround() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        CellSource ordinaryGround = FakeCells.empty(bounds)
                .set(0, 61, 0, FakeCells.STONE)
                .set(1, 60, 0, FakeCells.STONE);
        CellSource climbable = FakeCells.empty(bounds)
                .set(0, 62, 0, FakeCells.LADDER)
                .set(1, 60, 0, FakeCells.STONE);

        PathResult ordinaryResult = search(ordinaryGround, new BlockPos(0, 62, 0), new BlockPos(1, 61, 0));
        PathResult climbableResult = search(climbable, new BlockPos(0, 62, 0), new BlockPos(1, 61, 0));

        assertTrue(ordinaryResult.complete());
        assertTrue(climbableResult.complete());
        assertEquals(List.of(MovementType.DESCEND), movements(ordinaryResult));
        assertEquals(List.of(MovementType.DESCEND), movements(climbableResult));
        assertTrue(climbableResult.steps().get(0).cost() > ordinaryResult.steps().get(0).cost(),
                "descending from a ladder-holding point should cost more than the sprint-based price: "
                        + climbableResult.steps().get(0).cost() + " vs " + ordinaryResult.steps().get(0).cost());
    }

    /**
     * It needs a run-up. Top sprint speed takes about 5 ticks (about 1 block) to reach from standstill, and there's
     * almost no acceleration in mid-air, so reach is set directly by the takeoff speed. From a 1-block-wide ledge it
     * can only run up within its own block.
     */
    @Test
    void doesNotJumpFromAOneBlockPerchWithNoRunUp() {
        FakeCells cells = chasm(2).jumpGapEnabled(true).canPlaceBlocks(false);
        // Block off the space before the takeoff (x=1), making a 1-block-wide ledge with no run-up
        for (int z = -1; z <= 1; z++) {
            cells.set(0, 61, z, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "must not make it jump from a ledge with no run-up: " + movements(result));
    }

    /**
     * Next to flat soul sand, place a 1-block ridge of the same soul sand. Going up gives the same ground and
     * nothing gets any faster, so climbing up and down is a pure loss.
     */
    private static FakeCells soulSandFlatWithRidge(char ridgeTop) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 16, 76, 8));
        for (int x = -1; x <= 6; x++) {
            cells.set(x, 60, 0, FakeCells.SOUL_SAND);
            cells.set(x, 60, 1, FakeCells.SOUL_SAND);
            cells.set(x, 61, 1, ridgeTop);
        }
        return cells;
    }

    /**
     * The speed factor was applied only to horizontal movement, so on soul sand "climb one block" (4.633) was
     * cheaper than "walk one block" (8.909), and sawtoothing up and down became the cheapest path.
     */
    @Test
    void crossesSoulSandFlatInsteadOfHoppingOntoTheRidgeBeside() {
        CellSource cells = soulSandFlatWithRidge(FakeCells.SOUL_SAND);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 61),
                "with the same soul sand there's no point climbing the ridge: " + movements(result));
    }

    /**
     * Must not be implemented as "uniformly dislike vertical movement". If the ridge top really is faster ground, climbing is worth it.
     */
    @Test
    void stillClimbsOntoARidgeThatIsGenuinelyFaster() {
        CellSource cells = soulSandFlatWithRidge(FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getY() > 61),
                "the stone ridge really is 2.5x faster, so it should climb: " + movements(result));
    }

    /** A flat corridor with no obstacles. Terrain for looking only at goal handling (exact coordinate or region). */
    private static FakeCells flatCorridor() {
        return FakeCells.of(0, 60, 0, """
                ..........
                ..........
                ##########""")
                .extrudeZ(-1, 1);
    }

    /**
     * An intermediate target is only "a direction to head", not "a place to pass through", so it must not detour
     * to land exactly on its coordinate. Making the goal a region with a radius lets it finish on first touch.
     */
    @Test
    void aRadiusGoalStopsAsSoonAsTheRegionIsTouched() {
        FakeCells cells = flatCorridor();
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(9, 61, 0);

        PathResult exact = new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED);
        PathResult region = new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED, 4);

        assertTrue(exact.complete() && region.complete());
        assertTrue(region.steps().size() < exact.steps().size(),
                "should finish a radius short: " + region.steps().size() + " vs " + exact.steps().size());
        assertEquals(5, region.steps().size(), "with radius 4 it touches the region at x=5");
    }

    /** Radius 0 (the real destination) is an exact coordinate match, as before. */
    @Test
    void aZeroRadiusGoalStillRequiresAnExactMatch() {
        PathResult result = new AStarPathfinder(flatCorridor())
                .search(new BlockPos(0, 61, 0), new BlockPos(9, 61, 0), NOT_CANCELLED, 0);

        assertTrue(result.complete());
        assertEquals(new BlockPos(9, 61, 0), last(result).pos());
    }

    /**
     * Even when an intermediate target is unreachable, e.g. inside a wall, a region lets it just pass nearby.
     * Layer 1 only looks at chunk averages, so a waypoint landing on an unreachable point can't be avoided in itself.
     */
    @Test
    void aRadiusGoalSucceedsEvenWhenItsCentreIsUnreachable() {
        FakeCells cells = flatCorridor();
        // Fill the target coordinate itself with bedrock. An exact-coordinate goal never reaches it
        BlockPos unreachable = new BlockPos(5, 61, 0);
        for (int z = -1; z <= 1; z++) {
            cells.set(5, 61, z, FakeCells.BEDROCK);
            cells.set(5, 62, z, FakeCells.BEDROCK);
        }

        PathResult exact = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), unreachable, NOT_CANCELLED, 0);
        PathResult region = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), unreachable, NOT_CANCELLED, 4);

        assertFalse(exact.complete(), "an exact-coordinate goal can't get inside the bedrock");
        assertTrue(region.complete(), "a region is satisfied by touching it from just before");
    }

    /**
     * A lava channel {@code width} wide. Both banks are bedrock, and the only way across is to keep placing footing on the lava.
     * A variant of {@link #lavaPond} with arbitrary width, used to test the cap on consecutive bridge length.
     */
    private static FakeCells lavaChannel(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 40, -8, width + 12, 80, 8))
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
        for (int x = -1; x <= width + 1; x++) {
            cells.set(x, 60, 0, x >= 1 && x <= width ? FakeCells.LAVA : FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.AIR);
            cells.set(x, 62, 0, FakeCells.AIR);
        }
        return cells;
    }

    /**
     * The cap works by "not creating the move at all", not by "making it costly". Holding it back with weight,
     * A* expands from cheap edges, so it would exhaust the surroundings and burn the budget before reaching for the bridge.
     */
    @Test
    void refusesToBridgeBeyondTheConfiguredRun() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "doesn't cross if the only bridges exceed the cap");
        assertTrue(result.steps().stream().filter(PathStep::bridging).count() <= 6,
                "must not extend a bridge past the cap: " + movements(result));
    }

    @Test
    void stillBridgesWhenTheRunStaysUnderTheCap() {
        CellSource cells = lavaChannel(4).maxBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "bridges within the cap are crossed as before");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging), "" + movements(result));
    }

    /** A cap of 0 is unlimited. Confirms that disabling it in the config restores the previous behavior. */
    @Test
    void aZeroCapMeansNoLimit() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertTrue(result.complete(), "with a cap of 0 it crosses regardless of length");
    }

    /**
     * Bridges over lava alone can be cut off with a separate cap. Missing a bridge over a void just means a fall,
     * but over lava it's instant death, so the acceptable range differs even for the same length.
     */
    @Test
    void refusesToBridgeOverLavaBeyondTheLavaRunEvenWhenTheGeneralCapIsOff() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(0).maxLavaBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "doesn't cross if the only bridges exceed the lava cap");
    }

    /** The lava cap applies only over lava. Bridges over a void are governed by maxBridgeRunBlocks as before. */
    @Test
    void theLavaRunCapLeavesBridgesOverEmptySpaceAlone() {
        CellSource cells = chasm(6).jumpGapEnabled(false).canPlaceBlocks(true)
                .maxBridgeRunBlocks(0).maxLavaBridgeRunBlocks(2);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "a gap without lava isn't bound by the lava cap: " + movements(result));
    }

    /** Over lava both caps apply. The stricter one wins. */
    @Test
    void theStricterOfTheTwoCapsWinsOverLava() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(6).maxLavaBridgeRunBlocks(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "even with the lava cap unlimited, the bridge's own cap remains");
    }

    /**
     * Terrain with just a low shelf below a drop. The {@code drop} can't be crossed without fall-damage tolerance.
     * A minimized version of "descending to a lower island" in the End, without the void.
     */
    private static FakeCells ledgeBelow(int drop) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 12, 90, 4))
                .canPlaceBlocks(false);
        for (int x = -2; x <= 1; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.AIR);
            cells.set(x, 62, 0, FakeCells.AIR);
        }
        for (int x = 2; x <= 8; x++) {
            cells.set(x, 60 - drop, 0, FakeCells.BEDROCK);
            cells.set(x, 61 - drop, 0, FakeCells.AIR);
            cells.set(x, 62 - drop, 0, FakeCells.AIR);
        }
        return cells.extrudeZ(-1, 1);
    }

    /**
     * Reports that it discarded a landing <b>only</b> because the fall-damage tolerance fell short. This triggers the
     * re-search that gradually loosens the tolerance when stuck ({@code PathfindingExecutor}).
     *
     * <p>Must not be set when discarded because of the void or unloaded chunks: loosening won't make a landing appear there.
     */
    @Test
    void reportsWhenOnlyTheFallDamageAllowanceBlockedALanding() {
        AStarPathfinder blocked = new AStarPathfinder(ledgeBelow(8).maxFallDamagePoints(0));
        PathResult result = blocked.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED);
        assertFalse(result.complete(), "with tolerance 0 it doesn't offer an 8-block fall");
        assertTrue(blocked.fallDamageCapBlocked(), "the floor is readable and only the drop is the problem, so loosening is worth it");

        AStarPathfinder allowed = new AStarPathfinder(ledgeBelow(8).maxFallDamagePoints(8));
        assertTrue(allowed.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED).complete(),
                "with tolerance opened it can descend on the same terrain");
    }

    /**
     * With no bottom (the void), no amount of loosening fall damage makes a landing appear. Setting the flag here
     * would make the relaxation ladder spin uselessly to the end.
     */
    @Test
    void doesNotBlameTheFallAllowanceForABottomlessDrop() {
        AStarPathfinder pathfinder =
                new AStarPathfinder(bottomlessGap(6).canPlaceBlocks(false).maxFallDamagePoints(0));
        pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(9, 61, 0), NOT_CANCELLED);
        assertFalse(pathfinder.fallDamageCapBlocked(), "the void isn't a tolerance problem");
    }

    /**
     * Overriding the tolerance with {@link Tolerances} generates the fall even if {@link CellSource#maxFallDamagePoints()} is 0.
     * Relaxation when stuck works through this path.
     */
    @Test
    void tolerancesOverrideTheViewsFallAllowance() {
        FakeCells cells = ledgeBelow(8).maxFallDamagePoints(0);
        AStarPathfinder loosened = new AStarPathfinder(cells, SearchLimits.DEFAULT, null,
                new Tolerances(RunCaps.of(cells), 8, true, cells.placedBlockBudget(), false));

        assertTrue(loosened.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED).complete(),
                "if overriding the tolerance doesn't generate the fall, the relaxation ladder spins uselessly");
    }

    /** Whether a move was discarded by the cap decides whether re-searching without the cap is worth it. */
    @Test
    void reportsWhetherTheCapActuallyBlockedAnything() {
        AStarPathfinder blocked = new AStarPathfinder(lavaChannel(12).maxBridgeRunBlocks(6));
        blocked.search(new BlockPos(0, 61, 0), new BlockPos(13, 61, 0), NOT_CANCELLED);
        assertTrue(blocked.bridgeRunCapBlocked());

        AStarPathfinder untouched = new AStarPathfinder(lavaChannel(4).maxBridgeRunBlocks(6));
        untouched.search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);
        assertFalse(untouched.bridgeRunCapBlocked());
    }

    /**
     * A gap in a cavern with no visible bottom, with lava far below (20 blocks). One block below the feet is air, so
     * the adjacency check ({@code hasAdjacentLava}) alone doesn't notice the lava. Without the lavaFarBelow check in
     * {@code addBridge}, it would slip past the lava-bridge ban as "no danger".
     */
    private static FakeCells voidWithLavaFarBelow(int gapBlocks) {
        FakeCells cells = chasm(gapBlocks);
        for (int x = 2; x < 2 + gapBlocks; x++) {
            cells.set(x, 40, 0, FakeCells.LAVA);
        }
        return cells;
    }

    @Test
    void doesNotBridgeOverAVoidWithLavaFarBelowWhenLavaBridgingDisabled() {
        CellSource cells = voidWithLavaFarBelow(2)
                .jumpGapEnabled(false)
                .canPlaceBlocks(true)
                .lavaBridgingEnabled(false);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(result.complete(), "even with lava far below, it must not cross while lava bridges are disabled");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "must not miss the lava far below and bridge just because the feet look like air: " + result.steps());
    }

    @Test
    void prefersADryDetourOverBridgingAVoidWithLavaFarBelow() {
        // Same terrain as the gap with lava far below, with a bare-ground detour carved only on the z=1 side
        FakeCells cells = voidWithLavaFarBelow(2);
        for (int x = 0; x <= 5; x++) {
            cells.set(x, 60, 1, FakeCells.STONE);
            cells.set(x, 61, 1, FakeCells.AIR);
            cells.set(x, 62, 1, FakeCells.AIR);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete(), "reachable thanks to the detour");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "bridged without noticing lava far below even though it could detour: " + movements(result));
    }

    /**
     * A 1-block gap with a low ceiling. This is to <b>pin the crossing height at y=61 (placement at y=60)</b>.
     * Without the ceiling, a path "pillar one block and cross one level higher" appears, placing footing in a
     * different cell away from the vine, so the vine check can't be tested.
     */
    private static FakeCells vinedChasm() {
        FakeCells cells = chasm(1).jumpGapEnabled(false).canPlaceBlocks(true);
        for (int x = 0; x <= 4; x++) {
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }
        return cells;
    }

    /**
     * Vines are {@code replaceable}, so they pass the "placeable" check, but aiming hits the vine with the line of sight,
     * and the block goes into the vine's cell. It doesn't get placed where guided.
     */
    @Test
    void doesNotBridgeIntoVines() {
        // Make the gap 1 block to narrow the placement target down to (2,60,0). Putting the vine above the feet
        // produces a path climbing the vine instead of bridging (addClimb), muddying what's measured
        FakeCells cells = vinedChasm();
        cells.set(2, 60, 0, FakeCells.VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(result.complete(), "must not cross using a vine cell as footing");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "must not pick a vine cell as the placement target: " + movements(result));
    }

    /** The same goes for next to a vine. Even one block away, the line of sight to the placement target passes through the vine. */
    @Test
    void doesNotBridgeNextToVines() {
        FakeCells cells = vinedChasm();
        cells.set(2, 59, 0, FakeCells.VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(result.complete(), "must not cross by placing footing next to a vine");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "must not pick a cell adjacent to a vine as the placement target: " + movements(result));
    }

    /** Without the vine it bridges as before. Confirms the two above didn't just "remove bridging altogether". */
    @Test
    void stillBridgesTheSameGapWithoutVines() {
        CellSource cells = vinedChasm();

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertTrue(result.complete(), "crossable without the vine: " + movements(result));
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging), "" + movements(result));
    }

    /**
     * A bottomless gap (between End islands). {@code fillWith} isn't called, so unwritten coordinates stay air,
     * with air continuing down to the bottom of the search bounds and out of bounds beyond. {@code ChunkView} returns the same
     * {@code ABSENT} for out-of-bounds and unloaded, so this is exactly the terrain misread as "can't read what's below" unless distinguished.
     */
    private static FakeCells bottomlessGap(int gapBlocks) {
        // Only one column in z. If a path sidesteps around the void, the bridge itself can't be tested
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, gapBlocks + 12, 93, 0))
                .canPlaceBlocks(true);
        for (int x = -1; x <= gapBlocks + 2; x++) {
            if (x < 1 || x > gapBlocks) {
                cells.set(x, 60, 0, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /**
     * The End island hop itself. Departure island → {@code gapBlocks} blocks of void → arrival island {@code dropBlocks}
     * lower. Bridges can only be built horizontally, so the only way onto the arrival island is to <b>fall</b>.
     */
    private static FakeCells islandsAcrossVoid(int gapBlocks, int dropBlocks) {
        int landingY = 60 - dropBlocks;
        // Take the bottom well below the landing island. If it equaled the landing island, the void scan would stop out of bounds
        // and no longer be NOTHING_BELOW, muddying what's measured
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, landingY - 40, 0, gapBlocks + 12, 93, 0))
                .canPlaceBlocks(true);
        for (int x = -1; x <= 0; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        for (int x = gapBlocks + 1; x <= gapBlocks + 4; x++) {
            cells.set(x, landingY, 0, FakeCells.BEDROCK);
        }
        return cells;
    }

    /**
     * <b>End-to-end check of the exact shape the user was stuck on.</b> "Can someone without an elytra walk to a
     * lower island across the void?"
     *
     * <p>Downward bridges can't be built in survival (no face to click on the void side), so the only procedure is
     * "bridge horizontally across the void, then fall off the edge and land". While the fall-damage tolerance is
     * insufficient, <b>the path doesn't exist at all</b>. This is the structural reason hop2 failed under every condition
     * in-game, and the ladder that loosens the tolerance when stuck was added to open this up.
     */
    @Test
    void walksAcrossVoidAndDropsOntoALowerIsland() {
        int drop = 8;
        FakeCells terrain = islandsAcrossVoid(6, drop);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(8, 61 - drop, 0);

        AStarPathfinder strict = new AStarPathfinder(islandsAcrossVoid(6, drop).maxFallDamagePoints(0));
        PathResult blocked = strict.search(start, goal, NOT_CANCELLED);
        assertFalse(blocked.complete(), "with tolerance 0 there must be no path falling 8 blocks: " + movements(blocked));
        assertTrue(strict.fallDamageCapBlocked(), "unless it reports that loosening is worth it, the ladder won't run");

        // open the tolerance the same way relaxation when stuck passes it
        PathResult opened = new AStarPathfinder(terrain, SearchLimits.DEFAULT, null,
                new Tolerances(RunCaps.of(terrain), drop - ActionCosts.SAFE_FALL_BLOCKS, true,
                        terrain.placedBlockBudget(), false))
                .search(start, goal, NOT_CANCELLED);

        assertTrue(opened.complete(), "should be crossable once the tolerance is opened: " + movements(opened));
        assertTrue(opened.steps().stream().anyMatch(PathStep::bridging),
                "the void is crossed by bridging: " + movements(opened));
        assertEquals(drop, biggestDrop(start, opened),
                "it drops onto the lower island in one step (a damaging fall is on the path): " + movements(opened));
    }

    /**
     * An island at the same height needs no fall damage. <b>This is what layer 1 aims it at with a detour</b>:
     * "detour to an island at the same Y" works because the destination is crossable with the default settings.
     */
    @Test
    void reachesASameHeightIslandWithoutAnyFallDamage() {
        FakeCells cells = islandsAcrossVoid(6, 0).maxFallDamagePoints(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "an island at the same height is crossable with default settings: " + movements(result));
        assertTrue(biggestDrop(new BlockPos(0, 61, 0), result) <= ActionCosts.SAFE_FALL_BLOCKS,
                "a damaging fall is mixed in despite the same height: " + movements(result));
    }

    /**
     * <b>Pillars get the consecutive-length cap too.</b> {@code addPillar} incremented {@code bridgeRun} without
     * checking the cap, so towers could grow freely up to the ceiling of the search bounds.
     *
     * <p>This hit in-game (the_end, 2026-08-27): towers of about 150 levels from every standable cell on the island
     * became expansion targets, burning <b>510k cells</b> and ending at {@code NODE_BUDGET}.
     * <b>The real harm isn't the node count but that it prevents reaching {@code EXHAUSTED}</b>: the ladder that
     * loosens the bridge cap ({@code PathfindingExecutor}) only runs once it "proved there's no way within bounds",
     * so as long as it ends by running out of budget it <b>never fires</b>. That's why bridges in the End stayed stuck
     * at the cap of 30 and never made it across.
     */
    @Test
    void pillarsRespectTheRunCap() {
        // Mid-air with only a 1-block footing. Growing the tower is the only thing it can do, so the cap becomes the height
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 4, 200, 4))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(8)
                .set(0, 60, 0, FakeCells.BEDROCK);

        AStarPathfinder pathfinder = new AStarPathfinder(cells);
        PathResult result = pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(0, 190, 0), NOT_CANCELLED);

        assertFalse(result.complete(), "with a cap of 8, a 129-block tower must not be built");
        assertTrue(pathfinder.bridgeRunCapBlocked(), "unless it reports discarding by the cap, the relaxation ladder won't run");
        assertEquals(PathResult.Termination.EXHAUSTED, result.termination(),
                "if the cap works, the search is exhausted. Ending by running out of budget leaves stuck detection and relaxation idle: "
                        + result.expandedNodes() + " nodes");
    }

    /**
     * <b>A regression fixture that visualizes the known gap of not being able to sidestep a floating obstacle over the void.</b>
     * Bridging over the void is restricted to "only toward the destination"
     * (see the comment on the {@code voidBelow} check in this class). Bridges are only horizontal, and
     * this test's wall blocks up to the ceiling of the search bounds so it can't be pillared over either, so
     * the only detour (shift one step to the adjacent column, then come back) is uniformly rejected as
     * "a direction not approaching the destination", and the path is lost entirely.
     *
     * <p>As the code itself says, "known gap" and "loosen it when hit", this is <b>a limitation to fix</b>,
     * not the spec. This test <b>pins the current limitation as-is</b>. Once it's relaxed someday, rewrite
     * this assertion to the "reachable" side. Relaxing without rewriting it makes this test fail so it gets
     * noticed (that's why it isn't disabled).
     */
    @Test
    void bridgeCannotDetourSidewaysAroundAFloatingObstacleOverTheVoid() {
        // Departure island (x=-2..0) and arrival island (x=9..11), with void in all three columns z=-1..1 between (unset = air,
        // no floor). Only at x=5, put a wall blocking the z=0 column from the floor up to the ceiling of the search bounds.
        // z=±1 is open, so physically the terrain can be bypassed by shifting one step that way
        FakeCells cells = FakeCells.empty(new SearchBounds(-3, 20, -2, 12, 64, 2))
                .canPlaceBlocks(true);
        for (int x = -2; x <= 0; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        for (int x = 9; x <= 11; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        for (int y = 61; y <= 64; y++) {
            cells.set(5, y, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(10, 61, 0));

        assertFalse(result.complete(),
                "if the known gap has been closed (it can now detour), fix this test: " + movements(result));
    }

    /**
     * A control experiment putting the same wall and same detour width on the ground instead of the void. The detour
     * itself works normally. This shows the test above fails not because the terrain can't be bypassed, but
     * <b>because of the limitation</b> where {@code voidBelow} uniformly rejects "bridges not approaching the destination".
     */
    @Test
    void walksAroundTheSameObstacleWhenTheFloorIsSolidInsteadOfVoid() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-3, 20, -2, 12, 64, 2));
        for (int x = -2; x <= 11; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        for (int y = 61; y <= 64; y++) {
            cells.set(5, y, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(10, 61, 0));

        assertTrue(result.complete(), "on the ground it should be able to go around the wall: " + movements(result));
    }

    /**
     * <b>Over the void or lava, it doesn't bridge into places that can't be passed without digging.</b>
     *
     * <p>When "place a floor" and "dig a body cell" coexist in one step, the guidance <b>can't express the order</b>.
     * This is what the user hit in-game (the_end, 2026-08-27), and it showed up as two symptoms:
     * "told to place a block next to the block that should be dug" (the place box appears right below the dig box) and
     * "digging it right away dives into the void" (digging the visible dig box first leaves air over the void underfoot).
     *
     * <p>The correct order is "place the floor first → dig after", but since the dig box is visible it's natural to do that
     * first, and a mistake is fatal. You can't dig in mid-air, so the same discipline by which {@link #addJumpGap} and
     * diagonal moves require {@code clearWithoutDigging} is applied to bridges too.
     *
     * <p>Not applied in cavities with a bottom. Digging and falling just lands on the floor one block below, a completely different outcome.
     */
    @Test
    void doesNotBridgeIntoACellThatNeedsDiggingOverVoid() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 20, 0, 12, 90, 0))
                .canPlaceBlocks(true);
        cells.set(-1, 60, 0, FakeCells.BEDROCK).set(0, 60, 0, FakeCells.BEDROCK);
        for (int x = 4; x <= 8; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // Rock jutting out over the void. The route stepping over it is removed by the ceiling, so digging through is the only way
        cells.set(2, 61, 0, FakeCells.STONE).set(2, 62, 0, FakeCells.STONE);
        for (int x = -1; x <= 8; x++) {
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        for (PathStep step : result.steps()) {
            assertTrue(step.placedBlockPos() == null || step.digCells().isEmpty(),
                    "placing and digging coexist in one step (the order can't be expressed, so it falls into the void): "
                            + step.placedBlockPos() + " / " + step.digCells());
        }
    }

    /**
     * Bridges are built over the void too. Following only readable cells and never hitting bottom doesn't mean
     * "unknown"; it means it's known "there really is no bottom", and cost and caps decide whether to cross.
     */
    @Test
    void bridgesOverABottomlessGap() {
        CellSource cells = bottomlessGap(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(7, 61, 0));

        assertTrue(result.complete(), "bridges can be built over the void too: " + movements(result));
        assertEquals(6, result.steps().stream().filter(PathStep::bridging).count(),
                "crosses by placing as much footing as the gap is wide: " + movements(result));
    }

    /** A column where the scan stopped at an unloaded chunk is not "void". There might be water below, so don't place. */
    @Test
    void doesNotBridgeWhenTheColumnBelowIsUnreadable() {
        FakeCells cells = bottomlessGap(6);
        for (int y = 28; y <= 59; y++) {
            cells.set(1, y, 0, FakeCells.ABSENT);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(7, 61, 0));

        assertFalse(result.complete(), "can't place footing into a column whose bottom can't be read");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "mistook an unreadable column for void and bridged: " + movements(result));
    }

    /**
     * Doesn't start pillaring from on top of a sideways bridge. Jumping and placing underfoot on 1-wide footing
     * is almost certain to miss over the void.
     *
     * <p>The terrain is "footing with the headroom over the start blocked → void → destination 4 blocks higher".
     * A tower can only go up on the bridge, so blocking that leaves it out of reach. The loophole of pillaring
     * at the start first and crossing high is closed by the ceiling.
     */
    @Test
    void doesNotStartAPillarFromABridge() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 20, 93, 0))
                .canPlaceBlocks(true)
                .set(0, 60, 0, FakeCells.BEDROCK)
                .set(0, 63, 0, FakeCells.BEDROCK)
                .set(6, 64, 0, FakeCells.BEDROCK);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(6, 65, 0));

        assertFalse(result.complete(), "must not build a tower on the bridge to climb: " + movements(result));
    }

    /**
     * Over the void, bridges only extend toward the target. Without this, cap-length bridges in every direction
     * from every cell of the shore become expansion targets, and a wide gap can't be crossed on the default budget
     * (measured: on this terrain, 100k nodes burned until out of budget → reached in about 14k nodes).
     */
    @Test
    void crossesAWideVoidGapWithinTheDefaultBudget() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-40, 28, -40, 120, 93, 40))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(60).maxLavaBridgeRunBlocks(60).maxVoidBridgeRunBlocks(60);
        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 0; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
            for (int x = 61; x <= 66; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(63, 61, 0));

        assertTrue(result.complete(),
                "can't cross a 60-block void on the default budget (" + result.termination()
                        + ", expanded nodes " + result.expandedNodes() + ")");
    }

    /** Bridges over the void alone can be cut off with a separate cap. Same idea as the lava cap. */
    @Test
    void refusesToBridgeOverAVoidBeyondTheVoidRun() {
        CellSource cells = bottomlessGap(12).maxVoidBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "doesn't cross if the only bridges exceed the void cap");
    }

    /** The void cap applies only over the void. Gaps with a bottom are governed by maxBridgeRunBlocks as before. */
    @Test
    void theVoidRunCapLeavesBridgesOverFlooredGapsAlone() {
        CellSource cells = chasm(6).jumpGapEnabled(false).canPlaceBlocks(true)
                .maxBridgeRunBlocks(0).maxVoidBridgeRunBlocks(2);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "a gap with a bottom isn't bound by the void cap: " + movements(result));
    }

    /**
     * A 12-wide lava channel. Only the near part (x≦5) has a low overhanging ceiling, where pillars can't be built.
     *
     * <p>The ceiling is needed to <b>narrow down to one</b> path that sidesteps the cap with a pillar. With the sky fully open,
     * a separate equal-cost path "pillar on the first move, then cross on the high side" appears, and it arrives with the run
     * length still accumulated. {@code bridgeRun} isn't part of node identity, so at equal cost which run length
     * survives depends on expansion order, and the test would no longer probe the cap loophole.
     */
    private static FakeCells lavaChannelWithLowCeiling(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 40, -8, width + 12, 90, 8))
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
        for (int x = -1; x <= width + 1; x++) {
            cells.set(x, 60, 0, x >= 1 && x <= width ? FakeCells.LAVA : FakeCells.BEDROCK);
            // The ceiling is at y=63 for x≦5. The 2 blocks needed to cross (y=61,62) are free, but there's no room for a pillar
            int ceiling = x <= 5 ? 62 : 78;
            for (int y = 61; y <= ceiling; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        return cells;
    }

    /**
     * Pillaring doesn't reset the bridge run length. A pillar needs no footing (it stands on the block it just placed),
     * so back when this reset it to 0, "bridge up to the cap → pillar one block → bridge up to the cap again"
     * could break the cap.
     *
     * <p>The bigger real harm than the run length not resetting is whether {@code bridgeRunCapBlocked} gets set.
     * If a pillar can sidestep the cap, the dead end isn't reported as caused by the cap, and the staircase path is
     * finalized without the {@code PathfindingExecutor} cap relaxation (×2→×4→unlimited) ever running.
     */
    @Test
    void pillaringDoesNotResetTheBridgeRun() {
        FakeCells cells = lavaChannelWithLowCeiling(12).maxBridgeRunBlocks(6);
        AStarPathfinder pathfinder = new AStarPathfinder(cells);

        PathResult result = pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(13, 61, 0), NOT_CANCELLED);

        assertFalse(result.complete(), "must not cross beyond the cap even with a pillar in between: " + movements(result));
        assertTrue(result.steps().stream().filter(PathStep::bridging).count() <= 6,
                "total footing placed exceeds the cap = the run was reset by a pillar: " + movements(result));
        assertTrue(pathfinder.bridgeRunCapBlocked(),
                "unless the dead end is reported as caused by the cap, the re-search with a loosened cap won't run");
    }

    /**
     * Terrain where, past a small gap with a bottom, it dead-ends at an uncrossable void.
     * Distinguishes a finished bridge from an unfinishable one within a single path.
     */
    private static FakeCells crossingThenDeadEnd() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 40, 93, 0))
                .canPlaceBlocks(true)
                .jumpGapEnabled(false)
                .maxVoidBridgeRunBlocks(1);
        for (int x = 0; x <= 1; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // x=2..3 is a gap with a bottom. Too deep to fall in and climb back out, so bridging is the only way across
        for (int x = 2; x <= 3; x++) {
            cells.set(x, 50, 0, FakeCells.BEDROCK);
        }
        for (int x = 4; x <= 6; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // x>=7 is a bottomless void. A cap of 1 can't get across it
        return cells;
    }

    /**
     * From a cut-off path, only the <b>trailing</b> placement stretch is dropped. A bridge already crossed, standing
     * on the far bank, is correct guidance even if the path breaks off beyond it. Cutting too much would stop the
     * guidance before every crossable gap.
     */
    @Test
    void keepsBridgesThatWereAlreadyCrossed() {
        CellSource cells = crossingThenDeadEnd();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(20, 61, 0));

        assertFalse(result.complete(), "can't reach past the void");
        List<PathStep> steps = result.steps();
        assertTrue(steps.stream().anyMatch(PathStep::bridging),
                "must not erase even the finished bridge: " + movements(result));
        assertFalse(steps.get(steps.size() - 1).bridging(),
                "must not end the path in the middle of an unfinishable bridge: " + movements(result));
    }

    /**
     * A cut-off path doesn't end on footing it places itself. A path that didn't reach the goal only means
     * "you can get this far", but ending in the middle of a bridge would
     * <b>leave the player at a dead end, spending blocks without knowing whether it can be crossed</b>.
     * Where bridging is the only way across, having no guidable path at all is correct.
     */
    @Test
    void doesNotEndAPartialPathOnBlocksThePlayerHasToPlace() {
        CellSource cells = bottomlessGap(12).maxVoidBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete());
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "does not guide a bridge not proven to be crossable: " + movements(result));
    }

    /**
     * A shaft facing a bedrock cliff higher than the water surface. From the surface (y=63) it can't climb to the edge (y=67),
     * so the only way up is "pillar up from in the water". The key is that the wall is higher than the surface;
     * at the same height it could swim up and {@code Ascend} onto the edge, and whether it pillars couldn't be tested.
     */
    private static FakeCells floodedShaft() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                .BBB..
                .BBB..
                .BBB..
                ~BBB..
                ~BBB..
                ~BBB..
                BBBBBB""")
                .fillWith(FakeCells.BEDROCK);
    }

    @Test
    void doesNotPillarWhileFloatingInWater() {
        CellSource cells = floodedShaft().canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 67, 0));

        assertFalse(result.complete(), "since it can't pillar from in the water, it can't get on top of the cliff");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "does not guide pillaring up from in the water: " + result.steps());
    }

    /**
     * Confirms {@link #doesNotPillarWhileFloatingInWater} isn't passing vacuously. Draining the water from the same
     * shaft lets it pillar up = water is confirmed as the reason it can't climb.
     */
    @Test
    void pillarsUpTheSameShaftWhenItIsNotFlooded() {
        CellSource cells = floodedShaft()
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR)
                .set(0, 63, 0, FakeCells.AIR)
                .canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 67, 0));

        assertTrue(result.complete(), "without water it can pillar up");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "a pillaring stretch appears: " + movements(result));
    }

    /**
     * A monolithic cliff {@code drop} blocks high. Falling is the only way down: it's bedrock so it can't dig down,
     * and outside the cross-section is filled with bedrock so it can't go around. Start on top (x=0), end at the bottom (x=1).
     */
    private static FakeCells sheerDrop(int drop) {
        StringBuilder diagram = new StringBuilder("......\n......\n");
        diagram.append("B.....\n".repeat(drop));
        diagram.append("BBBBBB");
        return FakeCells.of(0, 60, 0, diagram.toString()).fillWith(FakeCells.BEDROCK);
    }

    private static BlockPos dropTop(int drop) {
        return new BlockPos(0, 61 + drop, 0);
    }

    private static final BlockPos DROP_BOTTOM = new BlockPos(1, 61, 0);

    @Test
    void fallsFreelyUpToTheSafeHeight() {
        CellSource cells = sheerDrop(3);

        PathResult result = search(cells, dropTop(3), DROP_BOTTOM);

        assertTrue(result.complete(), "a fall from a safe height is allowed regardless of settings");
        assertEquals(List.of(MovementType.DESCEND), movements(result));
    }

    @Test
    void doesNotFallBeyondTheSafeHeightByDefault() {
        CellSource cells = sheerDrop(5);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertFalse(result.complete(), "by default it doesn't offer a damaging fall");
    }

    @Test
    void fallsWithDamageWhenTolerated() {
        // A 5-block fall does 2 damage. Bring this within tolerance
        CellSource cells = sheerDrop(5).maxFallDamagePoints(2);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertTrue(result.complete(), "with damage within tolerance it can jump down");
        assertEquals(List.of(MovementType.FALL_DAMAGE), movements(result));
    }

    @Test
    void doesNotFallWhenTheDamageExceedsTheTolerance() {
        CellSource cells = sheerDrop(5).maxFallDamagePoints(1);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertFalse(result.complete(), "doesn't offer a fall whose damage exceeds the tolerance");
    }

    @Test
    void usesTheWaterBucketForDropsBeyondTheDamageTolerance() {
        // A 12-block fall does 9 damage. Unsurvivable even at full health (tolerance 6), but an MLG lands unharmed
        CellSource cells = sheerDrop(12).maxFallDamagePoints(6).canMlgWaterBucket(true);

        PathResult result = search(cells, dropTop(12), DROP_BOTTOM);

        assertTrue(result.complete(), "with a water bucket it can descend regardless of height");
        assertEquals(List.of(MovementType.FALL_MLG), movements(result));
    }

    @Test
    void takesTheCheapDamageRatherThanTheWaterBucket() {
        CellSource cells = sheerDrop(5).maxFallDamagePoints(6).canMlgWaterBucket(true);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertTrue(result.complete());
        assertEquals(List.of(MovementType.FALL_DAMAGE), movements(result),
                "doesn't bother with a water bucket for a fall that only does light damage");
    }

    /**
     * {@link PathResult#termination()} must correctly distinguish the reason for stopping. Cutting off at the expansion
     * limit versus searching until open runs out and finding no way within bounds call for completely different retry
     * decisions by the caller. Back when both were treated as just "unreached", there was a bug that kept firing
     * pointless retries at a dead end forever.
     */
    @Test
    void distinguishesNodeBudgetFromAnExhaustedSearchSpace() {
        // A 1-block space trapped in a bedrock box. All sides, ceiling and floor are undiggable bedrock, so
        // expanding the start generates no successors at all, and open runs out after a single expansion
        SearchBounds sealedBounds = new SearchBounds(-8, 55, -8, 8, 70, 8);
        CellSource sealed = FakeCells.empty(sealedBounds).fillWith(FakeCells.BEDROCK)
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR);
        PathResult exhausted = new AStarPathfinder(sealed)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertFalse(exhausted.complete());
        assertEquals(PathResult.Termination.EXHAUSTED, exhausted.termination());
        assertFalse(exhausted.budgetExhausted(), "open running out is a dead end, not running out of budget");

        // On the same terrain, narrowing the expansion limit to 1 hits the limit before expanding the start
        CellSource sameTerrain = FakeCells.empty(sealedBounds).fillWith(FakeCells.BEDROCK)
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR);
        SearchLimits tinyBudget = new SearchLimits(0, 2000, 1.5);
        PathResult budgetHit = new AStarPathfinder(sameTerrain, tinyBudget)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertFalse(budgetHit.complete());
        assertEquals(PathResult.Termination.NODE_BUDGET, budgetHit.termination());
        assertTrue(budgetHit.budgetExhausted(), "hitting the node limit is treated as running out of budget");
    }

    /**
     * Wiring check of the 3-arg constructor that injects {@link CostToGo} (stage 4). Passing {@code null}
     * gives exactly the same result as the 2-arg constructor (geometric {@link Heuristic} only).
     */
    @Test
    void nullCostToGoBehavesExactlyLikeTheTwoArgumentConstructor() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");

        PathResult withoutCostToGo = new AStarPathfinder(cells).search(new BlockPos(0, 61, 0),
                new BlockPos(5, 61, 0), NOT_CANCELLED);
        PathResult withNullCostToGo = new AStarPathfinder(cells, SearchLimits.DEFAULT, null)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertEquals(withoutCostToGo.expandedNodes(), withNullCostToGo.expandedNodes());
        assertEquals(movements(withoutCostToGo), movements(withNullCostToGo));
    }

    /**
     * The injected {@link CostToGo} is actually called from {@code node()} (a live wiring check).
     * It only looks at the call count, so it doesn't depend on the values being sensible. What this confirms is
     * "whether the injected instance is on the search's path"; how good it is as a heuristic is checked separately
     * by {@link net.prason.xaeronav.pathfinding.coarse.CoarseRouterTest} and
     * {@code PathfindingExecutorCoarseGuidedTest}.
     */
    @Test
    void injectedCostToGoIsActuallyConsultedDuringSearch() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");
        int[] callCount = {0};
        CostToGo counting = (x, y, z) -> {
            callCount[0]++;
            return 0.0;
        };

        PathResult result = new AStarPathfinder(cells, SearchLimits.DEFAULT, counting)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertTrue(result.complete());
        assertTrue(callCount[0] > 0, "the injected CostToGo was never called = not wired up");
    }

    private static PathStep last(PathResult result) {
        return result.steps().get(result.steps().size() - 1);
    }

    /** A {@code BooleanSupplier} that never cancels. Reads more clearly than a lambda. */
    private static final class BooleanSupplierNever implements java.util.function.BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    }
}
