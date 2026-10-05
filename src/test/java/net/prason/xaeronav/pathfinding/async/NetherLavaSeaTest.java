package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>A Nether lava sea can be crossed all the way by building bridges.</b> The terrain uses the exact coordinates from the real-game report
 * (south from the narrow footing at {@code -289,72,525}). User report: "can't cross areas of the Nether with lots of magma".
 *
 * <p>The terrain consists of <b>open sky with lava 40 blocks below</b>. The footing ends at {@code z=528}, and south of that
 * there's nothing at {@code y=35-100}. The only way across is a 50+ block bridge built in midair,
 * which exceeds {@code maxLavaBridgeRunBlocks}(30), so no path appears until the limit relaxation is reached.
 *
 * <p><b>What's checked is the single search with the deep budget</b> ({@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}).
 * In the real game this timed out, and the coarse waypoint chain that runs afterward is <b>heavier than the single search</b>
 * (1.04M vs 570k nodes), so it failed in a cascade. The place to fix is this, not the chain.
 *
 * <p><b>The time limit is looser than in the real game.</b> What this wants to measure is "is the node cap sufficient",
 * not execution speed. Using the real-game limit would make the result depend on CI's processing speed alone.
 * The seconds needed in the real game are documented in the {@code DEEP_SEARCH_MAX_MILLIS} javadoc.
 */
@Tag("slow")
class NetherLavaSeaTest {

    /** The real game's deep-budget node cap (normal budget 100k x {@code DEEP_SEARCH_BUDGET_FACTOR}). */
    private static final SearchLimits DEEP_LIMITS = new SearchLimits(800_000, 60_000, 1.5);

    /** The spot from the real-game report. Heading south from here, the footing runs out. */
    private static final BlockPos START = new BlockPos(-289, 72, 525);

    private static final BlockPos GOAL = new BlockPos(-296, 57, 584);

    /**
     * The path that reached the goal actually crosses lava by bridge. <b>This is what distinguishes it from "terrain you can walk
     * around"</b>: if you could walk around, no bridges would be needed, so a row of bridges itself proves the terrain.
     * Measured: 29.
     */
    private static final int MIN_BRIDGES = 15;

    /**
     * The Nether is a dimension with a ceiling, so the real game's search range covers <b>the full height of the dimension</b>
     * ({@code PathfindingState#verticalSearchMargin}). Putting the box's top above the bedrock ceiling lets
     * the path walk on top of the ceiling, making it different terrain.
     */
    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .bounds(new SearchBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), 128, bounds.maxZ()))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(6));
    }

    @Test
    void bridgesAcrossTheLavaSeaWithTheDeepBudget() throws Exception {
        FakeCells cells = terrain();
        long began = System.currentTimeMillis();
        PathResult result = new PathfindingExecutor()
                .submit(cells, START, GOAL, DEEP_LIMITS, true, 0).get();
        long bridges = result.steps().stream().filter(PathStep::bridging).count();
        System.out.println(String.format(Locale.ROOT, "%s->%s %s %d steps %d bridges %d nodes %.1fs",
                START.toShortString(), GOAL.toShortString(), result.termination(),
                result.steps().size(), bridges, result.expandedNodes(),
                (System.currentTimeMillis() - began) / 1000.0));
        assertTrue(result.complete(), "Didn't make it across the lava sea: " + result.termination()
                + " (" + result.steps().size() + " steps, " + result.expandedNodes() + " nodes)");
        assertTrue(bridges >= MIN_BRIDGES,
                "Only " + bridges + " bridges = this terrain isn't serving as a lava-sea control");
    }
}
