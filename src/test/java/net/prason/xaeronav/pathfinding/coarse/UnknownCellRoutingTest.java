package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * <b>"Cells missing from the map" are among the cheapest routes for layer 1.</b>
 *
 * <p>The price of {@code NO_DATA} in {@link CoarseRouter} is <b>not fixed</b>: it is calibrated on the spot
 * from the land:void ratio of known cells ({@code CoarseRouter#calibratedUnknownMultiplier}). But <b>the
 * floor is 1.6, with land = 1.0 and void = {@code VOID_BRIDGE_MULTIPLIER} (≒ 10)</b>, and if known cells are
 * few or mostly land, calibration stays near 1.6. It never goes below this floor: the original design
 * intent "not knowing is not a reason for something to be expensive" is preserved.
 *
 * <p>Keeping the unknown passable is itself a deliberate design (otherwise no route would ever head toward
 * unexplored directions), but <b>in The End, "not on the map yet" is in reality mostly void</b>: measured on
 * three real terrains ({@code EndUnknownVoidRatioBenchTest}), the void ratio of known cells is 35-53%. So
 * {@link #prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid} pins down that calibration rises above 1.6
 * when the known void ratio is high.
 *
 * <p><b>"Partial maps change the route" has been refuted with real-game data (2026-08-29).</b>
 * Even trimming the read radius on real End terrain down to 4 chunks, the route was identical to the
 * one with the full map: unknown is uniformly cheap (even after calibration), so even a partial map picks
 * the same shortest band. So there's no check on real terrain; only <b>the ordering of the multipliers itself</b> is pinned down.
 */
class UnknownCellRoutingTest {

    /**
     * Lays an unknown band next to a void band and checks the ordering of the multipliers by which one layer 1
     * takes. If this flips, paths avoiding the void come out even with a partial map.
     *
     * <p>In this terrain most of the known area (everything except the unknown band) is land, so the known
     * void ratio is low and calibration stays at the 1.6 floor: a test that assumes no effect from calibration.
     */
    @Test
    void unknownCellsAreFarCheaperThanKnownVoid() {
        int radius = 20;
        CoarseMapBuilder builder = new CoarseMapBuilder(-radius, -radius, radius * 2, radius * 2);
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                // Band x∈[4,8]: z<0 is void (known to have no floor), z>=0 is unknown (not written)
                if (x >= 4 && x <= 8) {
                    if (z < 0) {
                        builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                                CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
                    }
                    continue;
                }
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        CoarseMap map = builder.build();

        // Start and destination both at z=-8 (facing the void band). Whether it detours into the unknown band (z>=0)
        BlockPos start = new BlockPos(0 * 16 + 8, 64, -8 * 16 + 8);
        BlockPos goal = new BlockPos(12 * 16 + 8, 64, -8 * 16 + 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                CoarseRouter.BridgePolicy.ALLOW);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ >= 0,
                "didn't detour into the unknown band = the premise that unknown is cheaper than void is broken: " + route.waypoints());
    }

    /**
     * <b>If most of the known area is void, unknown cells are calibrated toward void too, and known land is chosen even if longer.</b>
     *
     * <p>Terrain: between start (0,0) and destination (10,0), x=1-9, z=0 is <b>left unknown</b> (going straight
     * crosses 9 unknown cells). Meanwhile, the "U-shaped" detour via z=6 (north along column x=0 → east along
     * z=6 → south along column x=10, 22 cells total) is all <b>known land</b>. Separately, lots of known void is
     * placed away from the route (the band x=-20 to -11) to create the sample "most known cells are void".
     *
     * <p>Without calibration (fixed 1.6x), straight (9×1.6+1 ≒ 15.4) is cheaper than the detour (22), so
     * straight wins; this is the control for {@link #takesTheDirectCrossingWhenTheKnownAreaIsMostlyLand}. Here,
     * with a high known void ratio, the calibrated multiplier (over 8x on this terrain) pushes straight above 22 and the detour wins instead.
     */
    @Test
    void prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid() {
        CoarseRouter.Route route = detourVersusFogRoute(true);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ >= 5 * 16,
                "went straight across the unknown though the known area is mostly void = calibration isn't working: " + route.waypoints());
    }

    /**
     * <b>Control.</b> Making the outside of the detour (away from the route) land keeps the known void ratio
     * low, so calibration stops at the 1.6 floor, and going straight (across the unknown) stays cheaper even
     * with the same detour as {@link #prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid}. Without this, it
     * couldn't be told apart from terrain where "it always detours whenever a detour exists".
     */
    @Test
    void takesTheDirectCrossingWhenTheKnownAreaIsMostlyLand() {
        CoarseRouter.Route route = detourVersusFogRoute(false);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ < 5 * 16,
                "avoided crossing the unknown though the known area is mostly land = calibration overshoots below the 1.6 floor: "
                        + route.waypoints());
    }

    /**
     * Between start (0,0) and destination (10,0), leaves x=1-9, z=0 unknown and prepares a "U-shaped" known-land
     * detour via z=6. If {@code voidBackground} is {@code true}, fills the band x=-20 to -11, away from the
     * route, with known void to raise the known cells' void ratio (fills it with land if {@code false}).
     */
    private static CoarseRouter.Route detourVersusFogRoute(boolean voidBackground) {
        int radius = 20;
        CoarseMapBuilder builder = new CoarseMapBuilder(-radius, -radius, radius * 2, radius * 2);

        // Don't touch the start, destination, or the unknown band between them (x=1-9, z=0)
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(10, 0, CoarseMap.LAND, 64);

        // Detour (U-shaped, all known land)
        for (int z = 0; z <= 6; z++) {
            builder.putFloor(0, z, CoarseMap.LAND, 64);
            builder.putFloor(10, z, CoarseMap.LAND, 64);
        }
        for (int x = 0; x <= 10; x++) {
            builder.putFloor(x, 6, CoarseMap.LAND, 64);
        }

        // Background for calibration (a band away from the route)
        for (int x = -radius; x <= -11; x++) {
            for (int z = -radius; z < radius; z++) {
                if (voidBackground) {
                    builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                            CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
                } else {
                    builder.putFloor(x, z, CoarseMap.LAND, 64);
                }
            }
        }

        CoarseMap map = builder.build();
        BlockPos start = new BlockPos(0 * 16 + 8, 64, 0 * 16 + 8);
        BlockPos goal = new BlockPos(10 * 16 + 8, 64, 0 * 16 + 8);
        return CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);
    }

    /**
     * The estimate outside the window has a value even where Xaero's map has nothing. Returning 0 drops window-edge cells as "unknown" from the seeds, and
     * where the map has nothing (right after a teleport, unexplored caves) the window edge has no seeds at all and the nav graph's guide is entirely empty.
     */
    @Test
    void farEstimateValuesCellsMissingFromTheMap() {
        CoarseMap map = new CoarseMapBuilder(-20, -20, 40, 40).build();
        BlockPos goal = new BlockPos(15 * 16, 63, 15 * 16);

        double value = CoarseRouter.farEstimate(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE)
                .estimate(-15 * 16, -30, -15 * 16);

        assertTrue(value > 0, "estimate for a cell missing from the map is empty: " + value);
    }
}
