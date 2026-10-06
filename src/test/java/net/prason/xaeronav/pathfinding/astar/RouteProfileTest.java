package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Route profiles ({@link RouteProfile}): BALANCED must be today's cost model to the last bit, and the other profiles
 * must tip the same terrain toward a different route.
 *
 * <p>Each terrain offers exactly two routes, a short one that uses the thing the profile reprices (a jump, a bridge,
 * a dig) and a walking detour, sized so that the detour sits clearly between the two profiles' prices of the short
 * route. The searches run with weight 1.0 so the cheapest route is returned, not merely a good one.
 */
class RouteProfileTest {

    private static final SearchLimits EXACT = new SearchLimits(200_000, 10_000, 1.0);

    private static final int FATAL_FALL_BLOCKS = ActionCosts.SAFE_FALL_BLOCKS + 20;

    private static PathResult search(FakeCells cells, RouteProfile profile, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells.routeProfile(profile), EXACT).search(start, goal, () -> false);
    }

    private static List<MovementType> movements(PathResult result) {
        return result.steps().stream().map(PathStep::movement).toList();
    }

    @Test
    void balancedPricesAreExactlyTheBaseConstants() {
        RouteProfile balanced = RouteProfile.BALANCED;
        for (int gap = 1; gap <= 3; gap++) {
            assertEquals(ActionCosts.jumpAcrossGap(gap), balanced.jumpAcrossGap(gap), 0.0);
        }
        for (int drop = 0; drop <= FATAL_FALL_BLOCKS + 5; drop++) {
            assertEquals(ActionCosts.dropRiskPenalty(drop, FATAL_FALL_BLOCKS),
                    balanced.dropRiskPenalty(drop, FATAL_FALL_BLOCKS), 0.0);
        }
        assertEquals(ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT, balanced.fallDamagePenaltyPerPoint(), 0.0);
        assertEquals(ActionCosts.EDGE_HAZARD_PENALTY_TICKS, balanced.edgeHazardPenaltyTicks(), 0.0);
        assertEquals(ActionCosts.LAVA_BRIDGE_PENALTY_TICKS, balanced.lavaBridgePenaltyTicks(), 0.0);
        assertEquals(ActionCosts.SUBMERGED_TRAVEL_PENALTY, balanced.submergedTravelPenalty(), 0.0);
        assertEquals(1.0, balanced.placementCostScale(), 0.0);
        assertEquals(0.0, balanced.digSurchargeTicks(), 0.0);
        assertFalse(balanced.forcesSafeLimits());
    }

    /** No profile may price a move below the time it takes, or make the submerged surcharge bob (see its Javadoc). */
    @Test
    void everyProfileOnlyAddsSurchargesOnTopOfTravelTime() {
        for (RouteProfile profile : RouteProfile.values()) {
            for (int gap = 1; gap <= 3; gap++) {
                assertTrue(profile.jumpAcrossGap(gap) >= ActionCosts.JUMP_ACROSS_GAP, profile + " jump");
            }
            for (int drop = 0; drop <= FATAL_FALL_BLOCKS; drop++) {
                assertTrue(profile.dropRiskPenalty(drop, FATAL_FALL_BLOCKS) >= 0.0, profile + " drop risk");
            }
            assertTrue(profile.fallDamagePenaltyPerPoint() >= 0.0, profile + " fall damage");
            assertTrue(profile.edgeHazardPenaltyTicks() >= 0.0, profile + " edge hazard");
            assertTrue(profile.lavaBridgePenaltyTicks() >= 0.0, profile + " lava bridge");
            assertTrue(profile.submergedTravelPenalty() >= 1.0
                    && profile.submergedTravelPenalty() < ActionCosts.DIAGONAL_DISTANCE, profile + " submerged");
            assertTrue(profile.placementCostScale() >= 1.0, profile + " placement must stay a markup");
            assertTrue(profile.digSurchargeTicks() >= 0.0, profile + " dig must stay a markup");
        }
    }

    @Test
    void safestForcesRiskyJumpAvoidanceAndNoFallDamage() {
        MovementOptions safest = options(RouteProfile.SAFEST);
        assertTrue(safest.avoidRiskyJumps());
        assertFalse(safest.fallDamageToleranceEnabled());
        assertTrue(safest.withoutDigging().avoidRiskyJumps(), "derived options keep the forced values");

        MovementOptions balanced = options(RouteProfile.BALANCED);
        assertFalse(balanced.avoidRiskyJumps(), "other profiles leave the user's choice alone");
        assertTrue(balanced.fallDamageToleranceEnabled());
    }

    /** Options with fall damage allowed and risky jumps not avoided, so forcing them is visible. */
    private static MovementOptions options(RouteProfile profile) {
        return new MovementOptions(true, true, true, true, 96, 30, 96, 250, true, false, true, 0, false,
                profile, true, true);
    }

    @Test
    void safestWalksAroundAJumpThatBalancedTakes() {
        // A 3-wide gap 8 blocks deep (a miss hurts but isn't fatal). Jumping it costs about 32 ticks at BALANCED
        // prices and about 68 at SAFEST prices; walking around via z=5 costs about 46
        FakeCells cells = gapWithDetour(3, 8, 5);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(4, 61, 0);

        PathResult balanced = search(cells, RouteProfile.BALANCED, start, goal);
        assertTrue(balanced.complete());
        assertTrue(movements(balanced).contains(MovementType.JUMP), "BALANCED jumps: " + movements(balanced));

        PathResult safest = search(cells, RouteProfile.SAFEST, start, goal);
        assertTrue(safest.complete());
        assertFalse(movements(safest).contains(MovementType.JUMP), "SAFEST walks around: " + movements(safest));
    }

    @Test
    void fastestJumpsWhereBalancedWalksAround() {
        // 13 blocks deep this time: about 44 ticks to jump at BALANCED prices, about 28 at FASTEST prices; the
        // detour via z=4 costs about 39
        FakeCells cells = gapWithDetour(3, 13, 4);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(4, 61, 0);

        PathResult balanced = search(cells, RouteProfile.BALANCED, start, goal);
        assertTrue(balanced.complete());
        assertFalse(movements(balanced).contains(MovementType.JUMP), "BALANCED walks around: " + movements(balanced));

        PathResult fastest = search(cells, RouteProfile.FASTEST, start, goal);
        assertTrue(fastest.complete());
        assertTrue(movements(fastest).contains(MovementType.JUMP), "FASTEST jumps: " + movements(fastest));
    }

    @Test
    void resourceSavingWalksAroundAGapThatBalancedBridges() {
        // Jumping off, so the 2-wide gap is either bridged (about 91 ticks at BALANCED prices, about 155 with the
        // placing action tripled) or walked around via z=16 (about 121)
        FakeCells cells = gapWithDetour(2, 8, 16).jumpGapEnabled(false).canPlaceBlocks(true);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(3, 61, 0);

        PathResult balanced = search(cells, RouteProfile.BALANCED, start, goal);
        assertTrue(balanced.complete());
        assertTrue(placements(balanced) > 0, "BALANCED bridges: " + movements(balanced));

        PathResult saving = search(cells, RouteProfile.RESOURCE_SAVING, start, goal);
        assertTrue(saving.complete());
        assertEquals(0, placements(saving), "RESOURCE_SAVING places nothing: " + movements(saving));
    }

    @Test
    void resourceSavingWalksAroundAWallThatBalancedDigsThrough() {
        // A 3-high dirt wall. Digging through costs about 57 ticks at BALANCED prices and about 145 with the dig
        // overhead tripled; walking around via z=11 costs about 81
        FakeCells cells = FakeCells.empty(new SearchBounds(-6, 40, -14, 8, 80, 16));
        int detourZ = 11;
        for (int x = -2; x <= 4; x++) {
            for (int z = -10; z <= detourZ + 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                if (x == 1 && z < detourZ) {
                    for (int y = 61; y <= 63; y++) {
                        cells.set(x, y, z, FakeCells.SOFT);
                    }
                }
            }
        }
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(2, 61, 0);

        PathResult balanced = search(cells, RouteProfile.BALANCED, start, goal);
        assertTrue(balanced.complete());
        assertTrue(balanced.steps().stream().anyMatch(PathStep::digging), "BALANCED digs: " + movements(balanced));

        PathResult saving = search(cells, RouteProfile.RESOURCE_SAVING, start, goal);
        assertTrue(saving.complete());
        assertTrue(saving.steps().stream().noneMatch(PathStep::digging),
                "RESOURCE_SAVING walks around: " + saving.steps().stream().map(PathStep::pos).toList());
    }

    private static long placements(PathResult result) {
        return result.steps().stream().filter(PathStep::bridging).count();
    }

    /**
     * A floor at y=60 (standing height 61) with a {@code width}-wide gap at x=1..width, {@code depth} blocks deep,
     * running from z=-10 to {@code detourZ}-1. At z={@code detourZ} the floor continues across, so the gap can also
     * be walked around. Past z=-10 there is nothing, so that is the only way around.
     */
    private static FakeCells gapWithDetour(int width, int depth, int detourZ) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-6, 30, -14, width + 6, 80, detourZ + 4));
        for (int x = -2; x <= width + 3; x++) {
            for (int z = -10; z <= detourZ + 1; z++) {
                boolean gap = x >= 1 && x <= width && z < detourZ;
                cells.set(x, gap ? 60 - depth : 60, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }
}
