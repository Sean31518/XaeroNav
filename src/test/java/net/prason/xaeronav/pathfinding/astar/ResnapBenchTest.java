package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** Measures, on the real-game saved terrain, the breakdown of time until a route appears right after resnapping the destination (-53,49,716 -> -53,68,716). */
@Tag("bench")
class ResnapBenchTest {

    private static final int WINDOW = 224;
    private static final BlockPos IN_ROCK = new BlockPos(-53, 49, 716);
    private static final BlockPos GOAL = new BlockPos(-53, 68, 716);
    private static final BlockPos[] PLAYERS = {new BlockPos(-235, 88, 505), new BlockPos(-193, 50, 559),
            new BlockPos(-152, 65, 521)};

    @Test
    void resnap() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        int workers = Runtime.getRuntime().availableProcessors() - 1;
        for (BlockPos raw : PLAYERS) {
            BlockPos player = StanceFinder.resolveStart(cells, raw);
            WindowedCells window = new WindowedCells(cells, player, WINDOW);
            CostToGo voxel = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
            NavGraph graph = new NavGraph(IN_ROCK, cells.bounds().minY(), cells.bounds().maxY());
            long began = System.currentTimeMillis();
            NavGraph.Refreshed cold = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(), workers,
                    () -> false);
            long coldMillis = System.currentTimeMillis() - began;
            graph.retarget(goal);
            began = System.currentTimeMillis();
            WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(), workers,
                    () -> false).field();
            long warmMillis = System.currentTimeMillis() - began;

            SearchBounds box = ProgressiveWalk.searchBox(cells, player, goal, WINDOW);
            WindowedCells view = new WindowedCells(cells, player, WINDOW, box);
            PathfindingExecutor executor = new PathfindingExecutor();
            SearchLimits limits = new SearchLimits(AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, 1.0);
            began = System.currentTimeMillis();
            PathResult guided = executor.submit(view, player, goal, limits, true, 0, Carryover.NONE, field).join();
            long guidedMillis = System.currentTimeMillis() - began;
            SearchLimits voxelLimits = new SearchLimits(AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
            began = System.currentTimeMillis();
            PathResult fallback = executor.submit(view, player, goal, voxelLimits, true, 0, Carryover.NONE, voxel).join();
            long fallbackMillis = System.currentTimeMillis() - began;
            System.out.printf(Locale.ROOT,
                    "player %s build with destination inside rock %dms (construction %dms guide %dms sections %d) guide after resnap %dms search with nav graph %dms(%d moves) search with 3D coarse layer %dms(%d moves)%n",
                    player.toShortString(), coldMillis, cold.buildMillis(), cold.field().buildMillis(), cold.sectionsBuilt(),
                    warmMillis, guidedMillis, guided.steps().size(), fallbackMillis, fallback.steps().size());
        }
    }

    /** From the spot where, in the real game (2026-09-23 20:50), extending drifted away to the northeast, trace where the guide's values come from. */
    @Test
    void whyNorthEast() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        BlockPos[][] cases = {
                {new BlockPos(-163, 73, 438), new BlockPos(-129, 71, 445), new BlockPos(-106, 60, 410)},
                {new BlockPos(-129, 71, 445), new BlockPos(-129, 71, 445), new BlockPos(-106, 60, 410)}};
        for (BlockPos[] c : cases) {
            BlockPos player = StanceFinder.resolveStart(cells, c[0]);
            WindowedCells window = new WindowedCells(cells, player, WINDOW);
            CostToGo voxel = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
            NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
            WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                    Runtime.getRuntime().availableProcessors(), () -> false).field();
            for (int i = 0; i < 3; i++) {
                BlockPos p = i == 0 ? player : StanceFinder.resolveStart(cells, c[i]);
                WindowField.Descent d = field.descend(p.getX(), p.getY(), p.getZ());
                System.out.printf(Locale.ROOT, "window center %s point %s value=%.0f source=%s%n", player.toShortString(),
                        p.toShortString(), field.estimate(p.getX(), p.getY(), p.getZ()),
                        d == null ? "-" : "%s inside window %.0f+outside %.0f raw 3D coarse layer value %.0f straight line from edge to destination %.0f".formatted(
                                d.exit().toShortString(), d.inside(), d.outside(),
                                voxel.estimate(d.exit().getX(), d.exit().getY(), d.exit().getZ()),
                                Math.hypot(d.exit().getX() - goal.getX(), d.exit().getZ() - goal.getZ())));
            }
        }
        // For comparison: the true shortest distance to the destination (no window)
        BlockPos from = StanceFinder.resolveStart(cells, new BlockPos(-163, 73, 438));
        System.out.printf(Locale.ROOT, "true shortest (no window) %s->destination = %.0f%n", from.toShortString(),
                ProgressiveWalk.fullVisibilityBest(cells, from, goal));
    }
}
