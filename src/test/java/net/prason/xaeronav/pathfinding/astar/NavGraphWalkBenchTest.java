package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * Builds the production {@link NavGraph} into a full walk and measures whether it achieves the same quality as
 * the measured "exact window + estimate outside", and how many ms building and guiding take. A measurement without assertions.
 *
 * <p>Sections at the window's edge are provisionally built with their surroundings missing, and rebuilt once the window moves and the surroundings become readable ({@link NavGraph}).
 */
@Tag("bench")
class NavGraphWalkBenchTest {

    /** The in-game default ({@code NavGraphGuide.WINDOW_BLOCKS}). Can be varied with {@code -Pxaeronav.window=128}. */
    private static final int WINDOW = Integer.getInteger("xaeronav.window", 224);

    private static final long SEED = 20260917L;

    /**
     * Optima for routes that don't return within the baseline ({@link ProgressiveWalk#fullVisibilityBest}'s 3M nodes). Values measured by
     * a full-visibility search with 30M nodes and weight 1.0; re-measure if the terrain or settings ({@link #netherTerrain}, {@code NetherTrapBenchTest#cells}) change.
     */
    private static final java.util.Map<String, Double> KNOWN_BEST = java.util.Map.of(
            "-271, 64, 395→-333, 59, 694", 3931.0,
            "-261, 66, 448→-333, 59, 694", 3685.0,
            "-212, 49, 553→-333, 59, 694", 4399.0,
            "-12, 64, 349→-53, 68, 716", 3944.0,
            "72, 69, 439→-53, 68, 716", 3008.0);

    /** As in production ({@code NavGraphGuide}), surface-type dimensions cut the bottom of the nav graph with {@link NavGraph#floorBelow}. */
    private static boolean OVERWORLD_FLOOR;

    private record Stats(long[] buildMillis, long[] fieldMillis, int[] maxEdges, long[] maxBytes) {
    }

    /**
     * Rebuilds the 3D coarse layer at the player's position, as in production ({@code NetherVoxelGuide}). With {@code -Pxaeronav.voxelFollow=false}
     * it is built only once at the start (so the effect of the box widening and cell edges coarsening with distance doesn't show in the model).
     */
    private static final boolean VOXEL_FOLLOW =
            Boolean.parseBoolean(System.getProperty("xaeronav.voxelFollow", "true"));

    /** Production's {@code NetherVoxelGuide.REBUILD_MOVE_BLOCKS} and {@code REBUILD_INSET_BLOCKS}. */
    private static final double VOXEL_REBUILD_MOVE_BLOCKS = 128.0;
    private static final int VOXEL_REBUILD_INSET_BLOCKS = 32;

    /**
     * @param forwardOnly like production's {@code NavGraphGuide.Far#forwardOnly} (End only), on each rebuild drop from the seeds
     *                    the edges that are farther by estimate than the window's center
     */
    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos goal, Function<BlockPos, FarField> farAt,
                                                      boolean forwardOnly, Stats stats) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        // In-game, searches use the old guide while the guide is being rebuilt. Don't rebuild until this far from the previous center
        int lag = Integer.getInteger("xaeronav.navGraphLag", 0);
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] == null || (!player.equals(last[0]) && Math.max(Math.abs(player.getX() - last[0].getX()),
                    Math.abs(player.getZ() - last[0].getZ())) >= lag)) {
                CellSource window = new WindowedCells(cells, player, WINDOW);
                if (OVERWORLD_FLOOR) {
                    graph.floorBelow(player.getY());
                }
                FarField far = farAt.apply(player);
                if (forwardOnly) {
                    far = FarField.forwardOf(far, player.getX(), player.getY(), player.getZ());
                }
                // Build in parallel as in-game. FakeCells may be shared as long as it's read-only
                NavGraph.Refreshed refreshed = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                        Runtime.getRuntime().availableProcessors(), () -> false);
                WindowField field = refreshed.field();
                if (Boolean.getBoolean("xaeronav.navGraphVerbose")) {
                    System.out.printf(Locale.ROOT, "  rebuild %s sections%d build%dms guide%dms%n", player.toShortString(),
                            refreshed.sectionsBuilt(), refreshed.buildMillis(), field.buildMillis());
                }
                stats.buildMillis()[0] += refreshed.buildMillis();
                stats.fieldMillis()[0] += field.buildMillis();
                stats.fieldMillis()[1] = Math.max(stats.fieldMillis()[1], field.buildMillis());
                stats.maxEdges()[0] = Math.max(stats.maxEdges()[0], field.edges());
                stats.maxBytes()[0] = Math.max(stats.maxBytes()[0], graph.bytes());
                stats.maxBytes()[1] = Math.max(stats.maxBytes()[1], field.bytes());
                cached[0] = field;
                last[0] = player;
            }
            return cached[0];
        };
    }

    /** Whether the sky is visible overhead. In-game, guidance starting underground is solved by first getting to the surface (a segment without the guide). */
    private static boolean underSky(FakeCells cells, BlockPos pos) {
        for (int y = pos.getY() + 2; y <= cells.bounds().maxY(); y++) {
            long cell = cells.cell(pos.getX(), y, pos.getZ());
            if (!net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(cell)
                    && !net.prason.xaeronav.pathfinding.world.CellData.water(cell)) {
                return false;
            }
        }
        return true;
    }

    /** A cave floor 8+ blocks below the column's surface where you can stand without digging. {@code null} if none. */
    private static BlockPos caveBelow(FakeCells cells, BlockPos surface) {
        for (int y = surface.getY() - 8; y > cells.bounds().minY() + 1; y--) {
            long below = cells.cell(surface.getX(), y - 1, surface.getZ());
            long feet = cells.cell(surface.getX(), y, surface.getZ());
            long head = cells.cell(surface.getX(), y + 1, surface.getZ());
            if (net.prason.xaeronav.pathfinding.world.CellData.standable(below)
                    && net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(feet)
                    && net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(head)
                    && !underSky(cells, new BlockPos(surface.getX(), y, surface.getZ()))) {
                return new BlockPos(surface.getX(), y, surface.getZ());
            }
        }
        return null;
    }

    /** Routes whose start and end are both under the sky. */
    static List<BlockPos[]> surfaceRoutes(FakeCells cells, int count, int min, int max) {
        List<BlockPos[]> routes = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, count * 10, min, max)) {
            if (routes.size() < count && underSky(cells, route[0]) && underSky(cells, route[1])) {
                routes.add(route);
            }
        }
        return routes;
    }

    /** Walks with the window guide built from the closure. The closure is large, so it's separated so it can be released before measuring the nav graph. */
    private static ProgressiveWalk.Trace closureWalk(FakeCells cells, BlockPos start, BlockPos goal,
                                                     ProgressiveWalk.Mode mode, FarField far) {
        ClosureGraph closure = ClosureGraph.build(cells, start, goal,
                ClosureGraph.box(cells, start, goal, 224, cells.bounds().minY(), cells.bounds().maxY()));
        BlockPos[] lastPlayer = {null};
        CostToGo[] closureGuide = {null};
        return ProgressiveWalk.trace(cells, start, goal, WINDOW, mode, ProgressiveWalk.Aim.GOAL,
                (Function<BlockPos, CostToGo>) player -> {
                    if (!player.equals(lastPlayer[0])) {
                        closureGuide[0] = closure.windowGuide(goal, player, WINDOW, far::at);
                        lastPlayer[0] = player;
                    }
                    return closureGuide[0];
                }, 1.0);
    }

    private static void measure(String name, FakeCells cells, List<BlockPos[]> routes, ProgressiveWalk.Mode mode,
                                ProgressiveWalk.Aim currentAim, boolean forwardOnly, Function<BlockPos[], FarField> farFor) {
        measureFollowing(name, cells, routes, mode, currentAim, forwardOnly, route -> {
            FarField far = farFor.apply(route);
            return player -> far;
        });
    }

    /** Maximum, at each point of the walk, of how many blocks horizontally it has moved away from the destination relative to "the closest so far". */
    private static double worstRetreat(List<PathStep> steps, BlockPos goal) {
        double closest = Double.POSITIVE_INFINITY;
        double worst = 0;
        for (PathStep step : steps) {
            double left = Math.hypot(step.pos().getX() - goal.getX(), step.pos().getZ() - goal.getZ());
            closest = Math.min(closest, left);
            worst = Math.max(worst, left - closest);
        }
        return worst;
    }

    /**
     * Estimate outside the window from the 3D coarse layer × {@code scale}. With {@link #VOXEL_FOLLOW}, rebuilt from the player's position on the same
     * triggers as production (walked 128 from where it was built, or within 32 of the box edge).
     */
    private static Function<BlockPos, FarField> voxelFar(FakeCells cells, BlockPos start, BlockPos goal, double scale) {
        CostToGo[] voxel = {XaeroMapModel.guide(cells, start, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L)};
        if (!VOXEL_FOLLOW) {
            FarField far = FarField.of((x, y, z) -> scale * voxel[0].estimate(x, y, z));
            return player -> far;
        }
        BlockPos[] builtAt = {start};
        return player -> {
            SearchBounds box = XaeroMapModel.guideBox(builtAt[0], goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y);
            boolean inside = player.getX() >= box.minX() + VOXEL_REBUILD_INSET_BLOCKS
                    && player.getX() <= box.maxX() - VOXEL_REBUILD_INSET_BLOCKS
                    && player.getZ() >= box.minZ() + VOXEL_REBUILD_INSET_BLOCKS
                    && player.getZ() <= box.maxZ() - VOXEL_REBUILD_INSET_BLOCKS;
            if (!inside || Math.hypot(player.getX() - builtAt[0].getX(), player.getZ() - builtAt[0].getZ())
                    >= VOXEL_REBUILD_MOVE_BLOCKS) {
                builtAt[0] = player;
                voxel[0] = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                        NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
                if (Boolean.getBoolean("xaeronav.navGraphVerbose")) {
                    System.out.printf(Locale.ROOT, "  rebuilt 3D coarse layer %s edge%d%n", player.toShortString(),
                            VoxelTerrain.cellBlocksFor(XaeroMapModel.guideBox(player, goal,
                                    NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y)));
                }
            }
            CostToGo current = voxel[0];
            return FarField.of((x, y, z) -> scale * current.estimate(x, y, z));
        };
    }

    private static void measureFollowing(String name, FakeCells cells, List<BlockPos[]> routes, ProgressiveWalk.Mode mode,
                                         ProgressiveWalk.Aim currentAim, boolean forwardOnly,
                                         Function<BlockPos[], Function<BlockPos, FarField>> farFor) {
        List<List<Double>> ratios = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<Double> retreats = new ArrayList<>();
        for (BlockPos[] route : routes.subList(Math.min(routes.size(), Integer.getInteger("xaeronav.routeSkip", 0)),
                Math.min(routes.size(), Integer.getInteger("xaeronav.routeLimit", 99)))) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            if (!Double.isFinite(best)) {
                best = KNOWN_BEST.getOrDefault(start.toShortString() + "→" + goal.toShortString(), best);
            }
            Function<BlockPos, FarField> farAt = farFor.apply(new BlockPos[] {start, goal});
            FarField far = farAt.apply(start);

            // When re-measuring only the nav graph implementation, skip the heavy two (current, closure window)
            boolean graphOnly = Boolean.getBoolean("xaeronav.navGraphOnly");
            ProgressiveWalk.Trace current = graphOnly ? null
                    : ProgressiveWalk.trace(cells, start, goal, WINDOW, mode, currentAim, null);
            ProgressiveWalk.Trace closureWalk = graphOnly || Boolean.getBoolean("xaeronav.skipClosure") ? null
                    : closureWalk(cells, start, goal, mode, far);

            Stats stats = new Stats(new long[1], new long[2], new int[1], new long[2]);
            ProgressiveWalk.UNGUIDED_LEGS.set(0);
            ProgressiveWalk.REVIEWS.set(0);
            ProgressiveWalk.Trace graphWalk = ProgressiveWalk.trace(cells, start, goal, WINDOW, mode,
                    ProgressiveWalk.Aim.GOAL, guide(cells, goal, farAt, forwardOnly, stats), 1.0);
            double retreat = worstRetreat(graphWalk.steps(), goal);
            retreats.add(retreat);
            double[] values = new double[3];
            ProgressiveWalk.Trace[] traces = {current, closureWalk, graphWalk};
            for (int i = 0; i < 3; i++) {
                values[i] = traces[i] == null || traces[i].steps().isEmpty() ? Double.POSITIVE_INFINITY
                        : ProgressiveWalk.cost(traces[i].steps()) / best;
                ratios.get(i).add(values[i]);
            }
            System.out.printf(Locale.ROOT,
                    "%s %s→%s baseline%.0f tick current%.3f closureWindow%.3f navGraph%.5f(%.0f tick) maxRetreat%.0f unguidedLegs%d reviews%d redraws%d(near%d) joints%d buildTotal%dms guideTotal%dms(max%dms) maxEdges%d graphMax%dMB guideMax%dMB %s%n",
                    name, start.toShortString(), goal.toShortString(), best, values[0], values[1], values[2],
                    graphWalk.steps().isEmpty() ? Double.POSITIVE_INFINITY : ProgressiveWalk.cost(graphWalk.steps()),
                    retreat,
                    ProgressiveWalk.UNGUIDED_LEGS.get(), ProgressiveWalk.REVIEWS.get(), graphWalk.redraws(),
                    graphWalk.nearRedraws(), graphWalk.joints().size(), stats.buildMillis()[0], stats.fieldMillis()[0], stats.fieldMillis()[1],
                    stats.maxEdges()[0], stats.maxBytes()[0] >> 20, stats.maxBytes()[1] >> 20, graphWalk.stopped());
        }
        String[] names = {"current", "closure window", "nav graph"};
        for (int i = 0; i < 3; i++) {
            List<Double> list = ratios.get(i);
            System.out.printf(Locale.ROOT, "== %s %s avg%.3f worst%.3f%n", name, names[i],
                    list.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).average().orElse(0),
                    list.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }
        System.out.printf(Locale.ROOT, "== %s nav graph max retreat %s (forwardOnly=%s voxelFollow=%s)%n", name,
                retreats.stream().map(r -> String.format(Locale.ROOT, "%.0f", r)).toList(), forwardOnly, VOXEL_FOLLOW);
    }

    /** {@link NetherLiveWalkTest#terrain} aligned to in-game defaults (30-block lava bridges, no fall damage allowed). */
    private static FakeCells netherTerrain() throws IOException {
        return NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(30).maxFallDamagePoints(0);
    }

    /** Settings are the in-game defaults (no fall damage allowed). */
    private static FakeCells overworld(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(0));
    }

    @Test
    void wideLong() throws IOException {
        OVERWORLD_FLOOR = true;
        FakeCells cells = overworld("/overworld_wide.txt.gz");
        measure("overworld/wide (long)", cells, surfaceRoutes(cells, 4, 200, 450), ProgressiveWalk.Mode.EXTEND,
                ProgressiveWalk.Aim.HORIZON, false, route -> layer1Far(CoarseRouter.farEstimate(LiveCoarseSampler.sample(
                        cells, cells.bounds(), route[0].getY(), () -> false), route[1], false,
                        CoarseRouter.BridgePolicy.BRIDGE)));
    }

    /**
     * Routes whose start isn't under the sky (caves, underwater, under rock). An underground start sometimes doesn't connect to the shell, in which case
     * it's solved by the conventional segment without the nav graph ({@link ProgressiveWalk#trace}'s {@code unguided}).
     */
    @Test
    void wideLongUnderground() throws IOException {
        OVERWORLD_FLOOR = true;
        FakeCells cells = overworld("/overworld_wide.txt.gz");
        List<BlockPos[]> routes = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, 400, 200, 450)) {
            BlockPos cave = caveBelow(cells, route[0]);
            if (routes.size() < 4 && cave != null) {
                routes.add(new BlockPos[] {cave, route[1]});
            }
        }
        measure("overworld/wide (long, underground start)", cells, routes, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON,
                false, route -> layer1Far(CoarseRouter.farEstimate(LiveCoarseSampler.sample(cells, cells.bounds(),
                        route[0].getY(), () -> false), route[1], false, CoarseRouter.BridgePolicy.BRIDGE)));
    }

    @Test
    void end() throws IOException {
        FakeCells cells = overworld("/end_terrain_columns.txt.gz");
        measure("End", cells, TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, 4, 120, 220),
                ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON, true,
                route -> "unknown".equals(System.getProperty("xaeronav.navGraphFar")) ? FarField.UNKNOWN
                        : endFar(cells, route));
    }

    /**
     * Production's ({@code PathfindingState#goalGuide}) outside-the-window for the End: the straight-line distance to the destination multiplied by
     * the factor the long-range route's map applies to unknown cells ({@link CoarseRouter#unknownMultiplier}). The map is taken from the whole terrain file.
     */
    private static FarField endFar(FakeCells cells, BlockPos[] route) {
        return FarField.straightLineTo(route[1], CoarseRouter.unknownMultiplier(
                LiveCoarseSampler.sample(cells, cells.bounds(), route[0].getY(), () -> false)));
    }

    /** Outer End islands from the real world (void over 16 blocks between islands). From where it got stuck in-game. */
    @Test
    void endOuterIslands() throws IOException {
        FakeCells cells = TerrainFixture.load("/end_outer_islands.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(0).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
        List<BlockPos[]> routes = List.of(
                new BlockPos[] {new BlockPos(2298, 57, 1149), new BlockPos(2440, 60, 1160)},
                new BlockPos[] {new BlockPos(2163, 54, 1047), new BlockPos(2298, 57, 1149)},
                new BlockPos[] {new BlockPos(2028, 57, 1098), new BlockPos(2440, 60, 1160)});
        measure("End outer islands", cells, routes, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON, true,
                route -> endFar(cells, route));
    }

    /** A stretch in the real Nether that went back and forth around a lava sea. If it can't cross the islets across lava, it goes out to the perimeter and turns back. */
    @Test
    void netherLavaSea() throws IOException {
        FakeCells cells = netherTerrain();
        double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));
        List<BlockPos[]> routes = List.of(
                new BlockPos[] {new BlockPos(-271, 64, 395), new BlockPos(-333, 59, 694)},
                new BlockPos[] {new BlockPos(-261, 66, 448), new BlockPos(-333, 59, 694)},
                new BlockPos[] {new BlockPos(-212, 48, 553), new BlockPos(-333, 59, 694)});
        measureFollowing("Nether lava sea", cells, routes, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, false,
                route -> voxelFar(cells, route[0], route[1], scale));
    }

    @Test
    void nether() throws IOException {
        FakeCells cells = netherTerrain();
        // Production's NavGraphGuide.VOXEL_FAR_SCALE
        double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));
        measureFollowing("Nether (outside=3D coarse layer x" + scale + ")", cells, NetherLiveWalkTest.routes(), ProgressiveWalk.Mode.REPAIR,
                ProgressiveWalk.Aim.GOAL, false, route -> voxelFar(cells, route[0], route[1], scale));
    }

    /** Lava-heavy terrain where it went into the trap (-65,47,521) and turned back in-game ({@link NetherTrapBenchTest}). Conditions match in-game defaults. */
    @Test
    void netherTrap() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));
        List<BlockPos[]> routes = NetherTrapBenchTest.STARTS.stream()
                .map(start -> new BlockPos[] {start, NetherTrapBenchTest.GOAL}).toList();
        measureFollowing("Nether trap", cells, routes, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, false,
                route -> voxelFar(cells, route[0], route[1], scale));
    }

    /** Real seaside terrain dumped down to the underground ({@code tools/dump_terrain_columns.py ... --depth 0}). Has many layers of caves. */
    @Test
    void overworldOceanFull() throws IOException {
        OVERWORLD_FLOOR = true;
        FakeCells cells = overworld("/overworld_ocean_full.txt.gz");
        List<BlockPos[]> routes = new ArrayList<>(surfaceRoutes(cells, 4, 150, 300));
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, 400, 150, 300)) {
            BlockPos cave = caveBelow(cells, route[0]);
            if (routes.size() < 7 && cave != null) {
                routes.add(new BlockPos[] {cave, route[1]});
            }
        }
        measure("overworld/seaside (incl. underground)", cells, routes, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON,
                false, route -> layer1Far(CoarseRouter.farEstimate(LiveCoarseSampler.sample(cells, cells.bounds(),
                        route[0].getY(), () -> false), route[1], false, CoarseRouter.BridgePolicy.BRIDGE)));
    }

    private static FarField layer1Far(CostToGo estimate) {
        return FarField.byGoal(FarField.of(estimate), FarField.UNKNOWN);
    }
}
