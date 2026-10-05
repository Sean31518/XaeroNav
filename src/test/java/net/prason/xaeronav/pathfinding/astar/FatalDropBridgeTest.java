package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>"Makes me cross for no reason": bridging a valley where a miss is fatal, even though it can be detoured.</b>
 *
 * <p>User report (2026-08-29, in-game screenshot): "it seems to think there's a block below so it's fine".
 * That was exactly it: as long as {@code obstacleY} pointed at a real floor, {@code addBridge} treated it as an
 * ordinary bridge without ever looking at <b>how many blocks down</b> that floor was:
 *
 * <table>
 *   <tr><th>Below the bridge</th><th>No digging</th><th>Run-length cap</th><th>Extra cost</th></tr>
 *   <tr><td>Void</td><td>Required</td><td>{@code maxVoidBridgeRun}</td><td>{@code VOID_BRIDGE_PENALTY}</td></tr>
 *   <tr><td>Lava</td><td>Required</td><td>{@code maxLavaBridgeRun}</td><td>{@code LAVA_BRIDGE_PENALTY}</td></tr>
 *   <tr><td><b>Floor 43 blocks down (instant death)</b></td><td><b>None</b></td><td>Normal only</td><td><b>0</b></td></tr>
 * </table>
 *
 * <p>The outcome of falling, death, is the same as the void, yet the price was the same as "a one-block dip with a bottom".
 * #23 added the fatal drop to jumps ({@code addJumpGap}), but it was forgotten for bridges.
 */
class FatalDropBridgeTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 63;
    private static final int STAND_Y = 64;
    /** Bottom of the valley. 43 blocks below {@code STAND_Y}, far beyond the default fatal drop (23). */
    private static final int CHASM_FLOOR_Y = 20;

    private static final int CHASM_MIN_X = 30;
    private static final int CHASM_MAX_X = 36;
    /** The valley only extends this far. Going around to the south of it (larger Z) lets you walk across. */
    private static final int CHASM_MAX_Z = 25;

    /**
     * A valley 7 blocks wide and 43 blocks deep splits a flat plateau partway.
     * Going around the valley's south end ({@code CHASM_MAX_Z}) reaches the other side without a single bridge.
     */
    private static FakeCells terrain() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X && z <= CHASM_MAX_Z;
                cells.set(x, inChasm ? CHASM_FLOOR_Y : GROUND_Y, z, FakeCells.STONE);
            }
        }
        return cells;
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

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal) {
        SearchLimits limits = new SearchLimits(500_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        return new AStarPathfinder(cells, limits).search(start, goal, NEVER);
    }

    /**
     * <b>The main case.</b> You can walk around the valley's south end, so don't bridge a valley where a miss is fatal.
     */
    @Test
    void walksAroundAChasmDeepEnoughToKillInsteadOfBridgingIt() {
        FakeCells cells = terrain();
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        int maxZ = result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);

        System.out.printf("Fatal-drop valley: complete=%s steps=%d bridges=%d maxZ=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result), maxZ);

        assertTrue(result.complete(), "Walking around to the south always gets there: " + result.termination());
        assertEquals(0, bridgeSteps(result),
                "Bridged a valley where a miss is fatal (even though it could detour). bridges=" + bridgeSteps(result));
        assertTrue(maxZ > CHASM_MAX_Z, "Didn't go around the valley's south end: maxZ=" + maxZ);
    }

    /**
     * <b>Control.</b> On the same terrain with a shallow valley (not deep enough to kill), the reason to detour
     * disappears and it crosses by bridge. Without this, it can't be told apart from a test that "just always detours".
     */
    @Test
    void stillBridgesAShallowChasmWhereFallingIsSurvivable() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        // The bottom is only 2 blocks down. Falling won't kill you, so bridging is cheaper than detouring
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X && z <= CHASM_MAX_Z;
                cells.set(x, inChasm ? GROUND_Y - 2 : GROUND_Y, z, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        int maxZ = result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);
        System.out.printf("Shallow dip:       complete=%s steps=%d bridges=%d maxZ=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result), maxZ);

        assertTrue(result.complete());
        assertTrue(maxZ <= CHASM_MAX_Z,
                "No reason to detour around a shallow dip (if this control breaks, the main test is passing vacuously): maxZ=" + maxZ);
    }

    /**
     * With no detour, even a fatal-drop valley gets bridged. Pins down that <b>it was made expensive, not forbidden</b>,
     * so the fix doesn't add dead ends.
     */
    @Test
    void stillBridgesAFatalChasmWhenThereIsNoWayAround() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        // A valley split from end to end. There's no way around
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X;
                cells.set(x, inChasm ? CHASM_FLOOR_Y : GROUND_Y, z, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        System.out.printf("No detour:         complete=%s steps=%d bridges=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result));

        assertTrue(result.complete(), "If it can't detour, bridging across is the only option: " + result.termination());
        assertTrue(bridgeSteps(result) > 0, "How did it cross without bridging?");

        // Even when bridging is the only option, the color conveys that a miss is fatal (pins down that PathSafetyChecker
        // is the "counterpart" using the same check as addBridge)
        PathResult annotated = PathSafetyChecker.annotate(cells, result);
        boolean warned = annotated.steps().stream()
                .anyMatch(step -> step.bridging() && step.risk() == PathRisk.VOID_BELOW);
        assertTrue(warned, "The bridge over a fatal drop has no warning (it would be drawn in the same color as a safe bridge)");
    }
}
