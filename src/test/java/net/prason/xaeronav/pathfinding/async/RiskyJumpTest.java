package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * Avoid "jumps you can't recover from if you miss", and jump only when there's no other way.
 *
 * <p>The user's own argument turned into terrain: <b>the two ends of a C-shaped island are close by jumping but safe by walking around</b>,
 * so walk around. <b>Between two islands the only way is to jump</b>, so jump. The distinction is "whether there's another way",
 * which is exactly the trigger condition of the dead-end relaxation ladder ({@link PathfindingExecutor}), so it rides on that.
 */
class RiskyJumpTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final SearchLimits LIMITS =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final int FLOOR_Y = 60;
    private static final int FEET_Y = FLOOR_Y + 1;

    /** A width reachable by jumping, or spanned by a 3-block bridge. */
    private static final int GAP = 3;

    /** Length of the C's arms. {@link #GAP} blocks by jumping, about 80 by walking around, so the shortcut's temptation wins by an order of magnitude. */
    private static final int ARM_LENGTH = 40;

    /** Tip of the upper arm. */
    private static final BlockPos UPPER_TIP = new BlockPos(ARM_LENGTH, FEET_Y, 2);
    /** Tip of the lower arm. {@link #GAP} blocks from {@link #UPPER_TIP} across the void. */
    private static final BlockPos LOWER_TIP = new BlockPos(ARM_LENGTH, FEET_Y, 6);

    /**
     * A C-shaped island. Jumping the open mouth (right end) is a {@link #GAP}-block shortcut, but walking around the back (left end) arrives safely.
     *
     * <pre>
     *   z=0..2   #########################   ← upper arm
     *   z=3..5   ###......................   ← mouth (void). Only the left 3 blocks connect as the back
     *   z=6..8   #########################   ← lower arm
     * </pre>
     */
    private static FakeCells cShapedIsland() {
        SearchBounds bounds = new SearchBounds(-8, 20, -8, ARM_LENGTH + 8, FLOOR_Y + 32, 16);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int x = 0; x <= ARM_LENGTH; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean arm = z <= 2 || z >= 6;
                boolean spine = x <= 2;
                if (arm || spine) {
                    cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /**
     * From the upper arm's tip to the lower arm's tip. Jumping {@link #GAP} blocks over the void is shorter, but walking around the back arrives safely.
     * <b>Choose walking around</b>: a missed jump means the void, so the value of the shortcut doesn't balance it out.
     */
    @Test
    void walksAroundTheCShapeInsteadOfJumpingItsMouth() throws Exception {
        FakeCells cells = cShapedIsland();

        PathResult result = new PathfindingExecutor()
                .submit(cells, UPPER_TIP, LOWER_TIP, LIMITS, true, 0).get();

        assertTrue(result.complete(), "Walking around the back should arrive: " + result.termination());
        assertFalse(hasJump(result), "If there's a detour, don't jump over the void");
    }

    /**
     * On the same C, with the avoid setting turned off, it jumps: <b>the guarantee that the test above isn't passing vacuously</b>.
     * Without this, the test above would also pass on terrain where the mouth was simply too wide to jump
     * (in fact, the terrain first written had a 24-block mouth and never jumped even before the fix).
     */
    @Test
    void jumpsTheSameMouthWhenNotAvoidingRiskyJumps() throws Exception {
        FakeCells cells = cShapedIsland().avoidRiskyJumps(false);

        PathResult result = new PathfindingExecutor()
                .submit(cells, UPPER_TIP, LOWER_TIP, LIMITS, true, 0).get();

        assertTrue(result.complete(), "Jumping should arrive: " + result.termination());
        assertTrue(hasJump(result), "With the avoid setting off, it jumps the shortcut");
    }

    /**
     * <b>If bridging can get across, the relaxation ladder exhausts that option before opening up jumps.</b>
     *
     * <p>The terrain is "two platforms across the void", but this time with blocks in the inventory, and only the void bridge cap
     * falls short (a cap of 1 against the 3 blocks needed). The plain search fails, producing neither a jump nor a bridge, and
     * <b>both</b> {@code riskyJumpBlocked} and {@code bridgeRunCapBlocked} are set.
     *
     * <p>Previously, in this state the first rung of the ladder unconditionally opened jumps, so even where relaxing the cap one
     * rung would have allowed bridging, <b>it jumped because jumping was cheaper</b>. The real End is terrain where bridges are routine,
     * so jumps were unlocked on almost every route, and it even jumped cracks inside islands that could be walked around.
     *
     * <p>Now every rung that relaxes only the caps is tried before jumps are opened, so this crosses by bridge.
     */
    @Test
    void looseningTriesBridgingBeforeUnlockingRiskyJumps() throws Exception {
        FakeCells cells = twoPlatforms(GAP, 0)
                .canPlaceBlocks(true).maxBridgeRunBlocks(GAP).maxVoidBridgeRunBlocks(1);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "Relaxing the cap should allow bridging across: " + result.termination());
        assertFalse(hasJump(result), "If a bridge can get across, don't jump over the void");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging), "Must cross by bridge");
    }

    /**
     * Between two islands. There's no detour and no blocks to place, so jumping is the only option.
     * The relaxation ladder must open up and jump ("jump only when there's no other way", not "never jump").
     */
    @Test
    void jumpsBetweenIslandsWhenThereIsNoOtherWay() throws Exception {
        FakeCells cells = twoPlatforms(GAP, 0);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "With no other way, it should jump across: " + result.termination());
        assertTrue(hasJump(result), "Must cross by jumping");
    }

    /** A plain search without the ladder doesn't jump on the same terrain (i.e. the default is to avoid). */
    @Test
    void theBareSearchRefusesTheSameJump() {
        FakeCells cells = twoPlatforms(GAP, 0);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertFalse(result.complete(), "By default it doesn't jump over the void, so it can't reach: " + result.termination());
        assertFalse(hasJump(result), "The route must not contain a jump");
    }

    /**
     * A trench with a bottom, but deep enough that falling would kill you at current health. Avoided like the void:
     * {@code addFall} looks at heights for "intentionally dropping down", whereas this looks at "missing a jump".
     */
    @Test
    void refusesToJumpOverAPitDeepEnoughToKill() {
        int fatal = ActionCosts.SAFE_FALL_BLOCKS + 20;
        FakeCells cells = twoPlatforms(GAP, fatal + 2).fatalFallBlocks(fatal);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertFalse(hasJump(result), "Don't jump a trench deep enough to kill on a fall");
    }

    /** The same trench, if shallow enough to survive a fall, is jumped as before. */
    @Test
    void stillJumpsOverAPitShallowEnoughToSurvive() {
        int fatal = ActionCosts.SAFE_FALL_BLOCKS + 20;
        FakeCells cells = twoPlatforms(GAP, 6).fatalFallBlocks(fatal);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertTrue(result.complete(), "A shallow trench should be jumpable: " + result.termination());
        assertTrue(hasJump(result), "Must cross by jumping");
    }

    /**
     * Places two platforms {@code gap} blocks apart.
     *
     * @param pitDepth depth to the bottom of the gap. 0 means no bottom, i.e. the void
     */
    private static FakeCells twoPlatforms(int gap, int pitDepth) {
        int right = 8 + gap;
        SearchBounds bounds = new SearchBounds(-8, FLOOR_Y - pitDepth - 24, -8,
                right + 8, FLOOR_Y + 32, 16);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int z = 0; z <= 8; z++) {
            for (int x = 0; x <= 4; x++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
            }
            for (int x = 5 + gap; x <= right; x++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
            }
            if (pitDepth > 0) {
                for (int x = 5; x <= 4 + gap; x++) {
                    cells.set(x, FLOOR_Y - pitDepth, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    private static boolean hasJump(PathResult result) {
        for (PathStep step : result.steps()) {
            if (step.movement() == MovementType.JUMP) {
                return true;
            }
        }
        return false;
    }
}
