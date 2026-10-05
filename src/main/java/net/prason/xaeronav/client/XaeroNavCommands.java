package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.DiagnosticJobRunner;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightGuide;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.xaero.XaeroHooks;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.util.GameCompat;

/**
 * The {@code /xaeronav} client command.
 *
 * <p>Only three are used for guidance itself: {@code goto} / {@code clear} / {@code version}. The rest are measurement tools
 * that print numbers to chat without drawing a path, so they live under {@code debug}: putting them at the same level would
 * fill tab completion with measurement names for people who just want to set a goal.
 */
public final class XaeroNavCommands {

    /** Default check range (chunks). Comfortably wider than the default render distance, and small enough that reading finishes instantly. */
    private static final int DEFAULT_MAPDATA_RADIUS_CHUNKS = 64;

    /**
     * Upper bound for {@code mapdata}'s radius argument. {@link XaeroMapReader} reads are main-thread only
     * (see the class Javadoc), so they can't be offloaded to a worker, and a square of {@code radiusChunks*2+1} chunks per side
     * is read synchronously from Xaero's map in one go. Given that the default of 64 (129 per side, about 16,641 cells) is known
     * to finish "instantly", twice that is the safe upper bound. The old bound of 512 (1025 per side, about 1,050,625 cells)
     * was 16x that scale, and requesting it could stall the client for a long time.
     */
    private static final int MAPDATA_MAX_RADIUS_CHUNKS = 128;

    /**
     * Expanded-node count used for {@code probe}'s uncapped measurement. Made large enough to be unreachable so the time limit
     * (same as live navigation) takes effect first. The effective cutoff is time, so this measurement
     * finds out "how many nodes can be expanded, and whether it reaches, within the same time budget as live navigation".
     */
    private static final int PROBE_UNBOUNDED_MAX_EXPANDED_NODES = 100_000_000;

    /**
     * Dedicated async execution infrastructure used by {@code corridor}/{@code probe}/{@code flight}. A separate instance and thread from
     * live navigation's {@code PathfindingExecutor}: sharing it would cancel the in-progress real search just by
     * running a diagnostic command. The layer 1 parts that read Xaero's map
     * ({@link CoarseRouter}, {@link XaeroMapReader}) are main-thread only and excluded
     * (see the class Javadoc of {@link CorridorLegSolver} and {@code FlightNavState}); only the actually heavy
     * A* searches and aerial path computations are handed to this infrastructure.
     */
    private static final DiagnosticJobRunner DIAGNOSTIC =
            new DiagnosticJobRunner(runnable -> Minecraft.getInstance().execute(runnable));

    /**
     * The command tree registered on the loader's dispatcher.
     *
     * <p>The tree's contents don't depend on the loader, but brigadier's source type does
     * ({@code CommandSourceStack} on NeoForge, {@code FabricClientCommandSource} on Fabric).
     * The source type is a type parameter, and only the two operations that actually touch the source (the reply target and
     * resolving coordinate arguments) are taken from the caller.
     */
    public static <S> LiteralArgumentBuilder<S> tree(Function<CommandContext<S>, NavCommandSink> sink,
            BlockPosReader<S> blockPos) {
        return XaeroNavCommands.<S>literal("xaeronav")
                .then(XaeroNavCommands.<S>literal("goto")
                        .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos resolved = PathfindingState.INSTANCE.setGoal(blockPos.read(ctx, "pos"));
                                    // Show the resolved goal, not the given coordinates. Y is snapped to the height actually standable
                                    // in that column, so showing it as given would look inconsistent with where guidance leads
                                    if (resolved != null) {
                                        sink.apply(ctx).success(TextCompat.translatable("commands.xaeronav.goal_walk",
                                                resolved.toShortString()));
                                    }
                                    return 1;
                                })))
                .then(XaeroNavCommands.<S>literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            sink.apply(ctx).success(TextCompat.translatable("commands.xaeronav.cleared"));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("version")
                        .executes(ctx -> {
                            sink.apply(ctx).success(
                                    TextCompat.translatable("commands.xaeronav.version", modVersion()));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("debug")
                        .then(XaeroNavCommands.<S>literal("mapdata")
                                .executes(ctx -> reportMapData(sink.apply(ctx), DEFAULT_MAPDATA_RADIUS_CHUNKS))
                                .then(XaeroNavCommands.<S, Integer>argument("radiusChunks",
                                        IntegerArgumentType.integer(1, MAPDATA_MAX_RADIUS_CHUNKS))
                                        .executes(ctx -> reportMapData(sink.apply(ctx),
                                                IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                        .then(XaeroNavCommands.<S>literal("route")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportRoute(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("corridor")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportCorridor(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("probe")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportProbe(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("hooks")
                                .executes(ctx -> reportHooks(sink.apply(ctx))))
                        .then(XaeroNavCommands.<S>literal("summary")
                                .executes(ctx -> reportSummary(sink.apply(ctx))))
                        .then(XaeroNavCommands.<S>literal("flight")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportFlight(sink.apply(ctx),
                                                blockPos.read(ctx, "pos"))))));
    }

    /**
     * Prints the state of the Xaero integration line by line. Triaging "no line on the map" can't proceed
     * without knowing whether the integrated mod isn't installed / the mixins didn't apply / they applied but nothing
     * is drawn.
     */
    private static int reportHooks(NavCommandSink out) {
        for (XaeroHooks.Hook hook : XaeroHooks.Hook.values()) {
            Component name = TextCompat.translatable(hook.nameKey());
            if (!ModPresence.isLoaded(hook.modId())) {
                out.success(TextCompat.translatable("commands.xaeronav.hooks_mod_missing", name, hook.modId()));
            } else if (!XaeroHooks.applied(hook)) {
                out.success(TextCompat.translatable("commands.xaeronav.hooks_not_applied", name));
            } else {
                out.success(TextCompat.translatable("commands.xaeronav.hooks_ok", name));
            }
        }
        if (XaeroHookHealth.worldMapRenderBroken()) {
            out.failure(TextCompat.translatable("commands.xaeronav.hooks_render_broken"));
        }
        return 1;
    }

    /**
     * Prints a summary of the latest recalculation decisions. Merge rejections, skipped seam re-solves and unstandable targets
     * are all logs that "stay silent while the same reason continues", so the only way to know the current state in-game
     * was to dig back through the log. This puts it all in one command.
     */
    private static int reportSummary(NavCommandSink out) {
        PathfindingState.DiagnosticSummary summary = PathfindingState.INSTANCE.diagnosticSummary();
        out.success(TextCompat.translatable("commands.xaeronav.summary_splice_refusal",
                summary.spliceRefusal() != null
                        ? summary.spliceRefusal() : TextCompat.translatable("commands.xaeronav.summary_none")));
        out.success(TextCompat.translatable("commands.xaeronav.summary_seam_repair_refusal",
                summary.seamRepairRefusal() != null
                        ? summary.seamRepairRefusal() : TextCompat.translatable("commands.xaeronav.summary_none")));
        out.success(TextCompat.translatable("commands.xaeronav.summary_unstandable_target",
                summary.unstandableTarget() != null
                        ? summary.unstandableTarget().toShortString()
                        : TextCompat.translatable("commands.xaeronav.summary_none")));
        return 1;
    }

    /**
     * Extracts block coordinates from the {@code pos} argument.
     *
     * <p>The argument type ({@link BlockPosArgument}) itself doesn't care about the source type, but resolving `~` relative coordinates
     * needs a {@code CommandSourceStack}, so only that part is left to the loader side.
     */
    @FunctionalInterface
    public interface BlockPosReader<S> {
        BlockPos read(CommandContext<S> ctx, String name);
    }

    private static <S> LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S, T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    /** For in-game debugging: checks which git commit the loaded build is (embedded into mod_version at build time). */
    private static String modVersion() {
        return ModPresence.version(XaeroNav.MOD_ID);
    }

    /** How far (chunks) to widen the range {@link #reportRoute} reads around the start and end. */
    private static final int ROUTE_PADDING_CHUNKS = 32;

    /** Ranges wider than this per side aren't read. Even for a coarse map, unbounded would freeze on array allocation alone. */
    private static final int ROUTE_MAX_SPAN_CHUNKS = 1024;

    /**
     * For visual checks of stage A. Doesn't actually start guidance; just lists in chat the intermediate targets
     * {@link CoarseRouter} drew. This is the only way to see whether it bends as intended around real seas and mountains.
     */
    private static int reportRoute(NavCommandSink out, BlockPos goal) {
        return withCoarseRoute(out, goal, (start, waypoints) -> {
            for (int i = 0; i < waypoints.size(); i++) {
                int number = i + 1;
                BlockPos waypoint = waypoints.get(i);
                out.success(TextCompat.translatable("commands.xaeronav.route_waypoint",
                        number, waypoints.size(), waypoint.toShortString()));
            }
        });
    }

    /** Per-command continuation called after {@link #withCoarseRoute} prints the layer 1 summary. */
    @FunctionalInterface
    private interface RouteDetail {
        void report(BlockPos start, List<BlockPos> waypoints);
    }

    /**
     * The first half shared by two diagnostic commands: checking the player and map data, running layer 1, reporting when no path
     * could be drawn, and summarizing the waypoint count and elapsed time. {@code detail} is called only when it got as far as
     * the summary.
     *
     * <p>Reading the map ({@link #readCoarseMapOrFail}) runs synchronously on the main thread per the Xaero API contract,
     * but the following {@link CoarseRouter#findRoute} is pure computation that reads no Minecraft/Xaero state,
     * so it's offloaded to a {@link #DIAGNOSTIC} worker. {@code detail} is called
     * from the main-thread callback after the worker completes.
     */
    private static int withCoarseRoute(NavCommandSink out, BlockPos goal, RouteDetail detail) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            out.failure(TextCompat.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        BlockPos start = player.blockPosition();
        boolean boatAvailable = ChunkView.boatAvailable(player);
        CoarseMap map = readCoarseMapOrFail(out, start, goal);
        if (map == null) {
            return 0;
        }

        out.success(TextCompat.translatable("commands.xaeronav.debug_running"));
        long generation = DIAGNOSTIC.begin();
        long startNanos = System.nanoTime();
        DIAGNOSTIC.submit(generation,
                // Diagnostic commands show the default weighting as-is (the lava ladder is a PathfindingState concern)
                cancelled -> CoarseRouter.findRoute(map, start, goal, boatAvailable, CoarseRouter.BridgePolicy.ALLOW),
                (route, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: Layer 1 search for diagnostic command failed", error);
                        return;
                    }
                    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
                    if (route.isEmpty()) {
                        if (route.reachedGoal()) {
                            out.success(TextCompat.translatable("commands.xaeronav.route_same_chunk"));
                            return;
                        }
                        out.failure(TextCompat.translatable("commands.xaeronav.route_none", elapsedMillis));
                        return;
                    }

                    List<BlockPos> waypoints = route.waypoints();
                    out.success(TextCompat.translatable(
                            route.reachedGoal() ? "commands.xaeronav.route_summary_reached"
                                    : "commands.xaeronav.route_summary_partial",
                            waypoints.size(), elapsedMillis));
                    detail.report(start, waypoints);
                });
        // The layer 1 search was delegated to a worker, so what can be returned here is "whether the job was submitted",
        // not the search result itself. The result reaches the player asynchronously via out.success/out.failure
        return 1;
    }

    /**
     * The layer 1 map read shared by {@link #reportRoute} and {@link #reportCorridor}. If the range
     * exceeds {@link #ROUTE_MAX_SPAN_CHUNKS}, sends a failure and returns {@code null}.
     */
    private static CoarseMap readCoarseMapOrFail(NavCommandSink out, BlockPos start, BlockPos goal) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > ROUTE_MAX_SPAN_CHUNKS || chunksZ > ROUTE_MAX_SPAN_CHUNKS) {
            out.failure(TextCompat.translatable("commands.xaeronav.route_too_far"));
            return null;
        }
        return XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ, (start.getY() + goal.getY()) / 2);
    }

    /**
     * For visual checks of long-range route layer 2 (the block-resolution surface graph). Links layer 1's waypoint list in adjacent pairs,
     * cuts out a corridor per segment with {@link CorridorLegSolver}, and runs the existing {@link AStarPathfinder}.
     * {@code goto} (live navigation) also refines waypoints with the same {@link CorridorLegSolver}, but
     * this one is kept separately as a visual-check command that prints each leg's result to chat on the spot.
     */
    private static int reportCorridor(NavCommandSink out, BlockPos goal) {
        return withCoarseRoute(out, goal, (start, waypoints) -> {
            List<BlockPos> legs = new ArrayList<>();
            legs.add(start);
            legs.addAll(waypoints);
            int legCount = legs.size() - 1;
            // prepare reads Xaero's map data, so it's main-thread only (see CorridorLegSolver's class
            // Javadoc). Do it for all legs up front and pass only immutable results to the search chain after that,
            // the same order as PathfindingState#refineRouteAsync (the separation live navigation established first)
            List<TimedLeg> prepared = new ArrayList<>(legCount);
            for (int i = 0; i < legCount; i++) {
                long prepareStartNanos = System.nanoTime();
                CorridorLegSolver.PreparedLeg leg = CorridorLegSolver.prepare(legs.get(i), legs.get(i + 1));
                prepared.add(new TimedLeg(leg, (System.nanoTime() - prepareStartNanos) / 1_000_000));
            }
            out.success(TextCompat.translatable("commands.xaeronav.debug_running"));
            reportCorridorLeg(out, DIAGNOSTIC.begin(), prepared, 0, legCount);
        });
    }

    /** One {@link CorridorLegSolver#prepare} result and how long it took (used for reporting when there's no map data). */
    private record TimedLeg(CorridorLegSolver.PreparedLeg leg, long prepareElapsedMillis) {
    }

    /**
     * A recursive chain that searches the legs one at a time in order. Waits for the previous leg to complete before submitting the next,
     * the same idea as {@code PathfindingState#refineRouteAsync}'s leg-by-leg chain.
     */
    private static void reportCorridorLeg(NavCommandSink out, long generation, List<TimedLeg> prepared,
                                           int index, int total) {
        if (index >= total) {
            return;
        }
        TimedLeg timed = prepared.get(index);
        if (timed.leg().view() == null) {
            out.failure(TextCompat.translatable("commands.xaeronav.corridor_no_data",
                    index + 1, total, timed.prepareElapsedMillis(), timed.leg().pendingRegions()));
            reportCorridorLeg(out, generation, prepared, index + 1, total);
            return;
        }
        long startNanos = System.nanoTime();
        DIAGNOSTIC.submit(generation,
                cancelled -> new AStarPathfinder(timed.leg().view(), CorridorLegSolver.SEARCH_LIMITS)
                        .search(timed.leg().from(), timed.leg().to(), cancelled),
                (result, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: Leg search for corridor diagnostic failed", error);
                        return;
                    }
                    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
                    out.success(TextCompat.translatable(
                            result.complete() ? "commands.xaeronav.corridor_leg_reached"
                                    : "commands.xaeronav.corridor_leg_partial",
                            index + 1, total, result.steps().size(), elapsedMillis, timed.leg().pendingRegions()));
                    reportCorridorLeg(out, generation, prepared, index + 1, total);
                });
    }

    /**
     * For checking on the spot how much terrain can be read from Xaero's map. Long-range routes are
     * built on top of this data, so nothing can be judged without first seeing "how far it can be read".
     */
    private static int reportMapData(NavCommandSink out, int radiusChunks) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            out.failure(TextCompat.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        int centerChunkX = player.blockPosition().getX() >> 4;
        int centerChunkZ = player.blockPosition().getZ() >> 4;
        int referenceY = player.blockPosition().getY();
        int side = radiusChunks * 2 + 1;
        long startNanos = System.nanoTime();
        CoarseMap map = XaeroMapReader.readSurface(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        int known = map.knownCells();
        int total = map.totalCells();
        int percent = total == 0 ? 0 : known * 100 / total;
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_summary",
                side * 16, known, total, percent, elapsedMillis));

        XaeroMapReader.RegionStats regions = XaeroMapReader.surveyRegions(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_regions",
                regions.loaded(), regions.pendingLoad(), regions.inRange()));

        if (regions.pendingLoad() > 0) {
            int requested = XaeroMapReader.requestLoad(
                    centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_requested",
                    requested));
        }

        reportKindHistogram(out, map, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);
        reportMapLayers(out, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);

        // Report the floor nearest the Y actually stood on. Coarse map heights come from scanning downward from the cave layer's caveStart,
        // so whether they disagree with what's underfoot can only be told by comparing the two.
        // Also note when this cell has multiple floors (i.e. independent passages stacked vertically)
        int hereFloorCount = map.floorCount(centerChunkX, centerChunkZ);
        int hereFloor = map.nearestFloor(centerChunkX, centerChunkZ, referenceY);
        byte hereKind = hereFloor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(centerChunkX, centerChunkZ, hereFloor);
        int hereHeight = hereFloor < 0 ? 0 : map.heightAtFloor(centerChunkX, centerChunkZ, hereFloor);
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_here",
                describeKind(hereKind), hereHeight, referenceY, hereFloorCount));
        return 1;
    }

    /**
     * Breakdown of the coarse map's terrain kinds. In {@link CoarseRouter} only lava is impassable (everything else, even unknown, is passable),
     * so when a long-range route is cut short, this shows how much lava is eating into the passable area.
     *
     * <p>Counted per <b>floor</b>, not per cell: one cell can have multiple floors (in dimensions with a ceiling,
     * independent passages stacked vertically), so per-cell counts would understate the data actually read.
     */
    private static void reportKindHistogram(NavCommandSink out, CoarseMap map,
                                             int minChunkX, int minChunkZ, int side) {
        int land = 0;
        int water = 0;
        int lava = 0;
        int lavaMixed = 0;
        int voidCells = 0;
        int noData = 0;
        for (int chunkX = minChunkX; chunkX < minChunkX + side; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ < minChunkZ + side; chunkZ++) {
                int floorCount = map.floorCount(chunkX, chunkZ);
                if (floorCount == 0) {
                    noData++;
                    continue;
                }
                for (int floor = 0; floor < floorCount; floor++) {
                    switch (map.kindAtFloor(chunkX, chunkZ, floor)) {
                        case CoarseMap.LAND -> land++;
                        case CoarseMap.WATER -> water++;
                        case CoarseMap.LAVA -> lava++;
                        case CoarseMap.LAVA_MIXED -> lavaMixed++;
                        case CoarseMap.VOID -> voidCells++;
                        default -> noData++;
                    }
                }
            }
        }
        // Ratios are relative to known cells. Relative to the whole, unexplored area dilutes them and
        // hides how much of the passable area is being eaten away
        int known = land + water + lava + lavaMixed + voidCells;
        int lavaPercent = known == 0 ? 0 : lava * 100 / known;
        final int landCount = land;
        final int waterCount = water;
        final int lavaCount = lava;
        final int lavaMixedCount = lavaMixed;
        final int voidCount = voidCells;
        final int noDataCount = noData;
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_kinds",
                landCount, waterCount, lavaCount, lavaMixedCount, voidCount, noDataCount, lavaPercent));
    }

    /**
     * Lists which layers Xaero holds data in for this range. In skyless dimensions like the Nether
     * the surface layer is empty, and data is split into Y bands of {@code caveStart >> 4}. When long-range routing
     * doesn't work, this tells apart whether the terrain can't be read or the wrong place is being read.
     */
    private static void reportMapLayers(NavCommandSink out, int minChunkX, int minChunkZ, int side) {
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_cave_mode",
                XaeroMapReader.caveModeType()));

        List<XaeroMapReader.LayerProbe> probes = XaeroMapReader.probeLayers(minChunkX, minChunkZ, side, side);
        if (probes.isEmpty()) {
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_layers_none"));
            return;
        }
        for (XaeroMapReader.LayerProbe probe : probes) {
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_layer",
                    probe.isSurface()
                            ? TextCompat.translatable("commands.xaeronav.mapdata_layer_surface")
                            : TextCompat.literal(String.valueOf(probe.caveLayer())),
                    probe.knownCells(), probe.minHeight(), probe.maxHeight()));
        }
    }

    private static Component describeKind(byte kind) {
        return TextCompat.translatable(switch (kind) {
            case CoarseMap.LAND -> "commands.xaeronav.mapdata_land";
            case CoarseMap.WATER -> "commands.xaeronav.mapdata_water";
            case CoarseMap.LAVA -> "commands.xaeronav.mapdata_lava";
            case CoarseMap.LAVA_MIXED -> "commands.xaeronav.mapdata_lava_mixed";
            case CoarseMap.VOID -> "commands.xaeronav.mapdata_void";
            default -> "commands.xaeronav.mapdata_none";
        });
    }

    /**
     * A diagnostic command that runs the walking detailed A* synchronously with the same settings and range as {@code goto}, and shows on the spot
     * whether it reaches, the expanded-node count and the breakdown of move types (whether diagonal ascents/descents are actually chosen).
     * For backing things up with numbers instead of stopping at "probably works".
     *
     * <p>The first run searches with the regular margin. Then it searches the same box with only digging turned off and reports the
     * expanded-node counts side by side (to measure how much digging affects the branching factor). If it didn't reach because it hit the
     * expanded-node cap, it also measures with the cap removed and only time as the cutoff (to learn the required node count itself).
     * If it didn't reach even though the goal is in range, it searches once more under the same conditions and size as {@link PathfindingState}'s
     * "retry widening the search range to the full loaded chunks", and reports that result too.
     */
    /**
     * Solves the aerial path once and prints its contents. No need to be flying: firing it from the ground to check grid granularity and
     * expansion counts is far easier to measure than reading the screen while flying.
     */
    private static int reportFlight(NavCommandSink out, BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return 0;
        }

        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        BlockPos playerPos = player.blockPosition();
        SearchBounds bounds = SearchBounds.around(level, playerPos, goal,
                renderRadius, FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        boolean rockets = ChunkView.hasItem(GameCompat.inventory(player), stack -> stack.getItem() instanceof FireworkRocketItem);
        Vec3 start = player.position();
        Vec3 target = Vec3.atCenterOf(goal);

        out.success(TextCompat.translatable("commands.xaeronav.debug_running"));
        long generation = DIAGNOSTIC.begin();
        long startedAt = System.nanoTime();
        DIAGNOSTIC.submit(generation,
                cancelled -> FlightRouter.route(view, start, target, rockets, FlightNavState.tuning(),
                        FlightNavState.loadedHorizon(start, renderRadius), FlightGuide.NONE, cancelled),
                (route, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: Path computation for flight diagnostic failed", error);
                        return;
                    }
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    Vec3 tail = route.tail();
                    out.success(TextCompat.translatable("commands.xaeronav.flight_result",
                            route.points().size(), route.termination().name(), route.expandedNodes(), elapsedMillis,
                            route.cellBlocks(), rockets ? 1 : 0));
                    if (tail != null) {
                        out.success(TextCompat.translatable("commands.xaeronav.flight_tail",
                                Mth.floor(tail.x), Mth.floor(tail.y), Mth.floor(tail.z),
                                Mth.floor(Math.sqrt(tail.distanceToSqr(target)))));
                    }
                    if (level.dimensionType().hasCeiling()) {
                        // Beyond render distance the coarse layer (from Xaero's map) takes over. Zero intermediate targets means
                        // the map has no data in that direction, i.e. unvisited. It reads Xaero, so it's main-thread
                        // only (see FlightNavState's class Javadoc); we're already back on the main thread here
                        CoarseRouter.Route coarse = FlightNavState.solveCoarseRoute(level, playerPos, goal, rockets);
                        out.success(TextCompat.translatable("commands.xaeronav.flight_coarse",
                                coarse.waypoints().size(), coarse.reachedGoal() ? 1 : 0));
                    }
                    // This command only measures; it doesn't set a goal. Showing a line needs goto
                    out.success(TextCompat.translatable("commands.xaeronav.flight_diagnostic_only"));
                });
        return 1;
    }

    private static int reportProbe(NavCommandSink out, BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return 0;
        }

        BlockPos start = player.blockPosition();
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        int verticalMargin = PathfindingState.verticalSearchMargin(level, false);
        int normalMargin = XaeroNavConfig.INSTANCE.searchHorizontalMargin();

        CapturedView normal = captureProbeView(level, player, start, goal, normalMargin, verticalMargin,
                renderRadius);
        reportPlacementAvailability(out, normal.view());
        reportGoalCell(out, level, normal.view(), normal.bounds(), start, goal, renderRadius);
        out.success(TextCompat.translatable("commands.xaeronav.debug_running"));

        long generation = DIAGNOSTIC.begin();
        DIAGNOSTIC.submit(generation,
                cancelled -> runProbe(normal.view(), normal.bounds(), start, goal, cancelled),
                (normalRun, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: Regular-budget run for probe diagnostic failed", error);
                        return;
                    }
                    reportProbeRun(out, "commands.xaeronav.probe_normal", normalRun);
                    continueProbeAfterNormal(out, generation, level, player, start, goal, renderRadius,
                            normalMargin, normal, normalRun);
                });
        return 1;
    }

    /**
     * With digging enabled, every solid cell becomes "enterable at finite cost" (ChunkView#computeState).
     * The search space changes from the surface (a plane) to the mountain (a volume), so the difference from the expanded-node count
     * of running the same box and cap with only digging off is exactly how much digging affects the branching factor.
     * To compare without changing the box size, it uses a derived view sharing the chunk references
     * ({@link #DIAGNOSTIC} is single-threaded, so this runs sequentially after the regular-budget run completes).
     */
    private static void continueProbeAfterNormal(NavCommandSink out, long generation, Level level, Player player,
                                                  BlockPos start, BlockPos goal, int renderRadius, int normalMargin,
                                                  CapturedView normal, ProbeRun normalRun) {
        if (!XaeroNavConfig.INSTANCE.diggingEnabled()) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_no_digging_skipped"));
            continueProbeAfterDigging(out, generation, level, player, start, goal, renderRadius, normalMargin,
                    normal, normalRun);
            return;
        }
        DIAGNOSTIC.submit(generation,
                cancelled -> runProbe(normal.view().withoutDigging(), normal.bounds(), start, goal, cancelled),
                (noDiggingRun, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: Digging-off run for probe diagnostic failed", error);
                        return;
                    }
                    reportProbeRun(out, "commands.xaeronav.probe_no_digging", noDiggingRun);
                    continueProbeAfterDigging(out, generation, level, player, start, goal, renderRadius,
                            normalMargin, normal, normalRun);
                });
    }

    /**
     * Not reaching because the budget ran out (node cap, time limit) just hits the same cap the same way even with a wider box,
     * and the result doesn't change (confirmed in-game: the expanded-node counts matched exactly between the regular margin and widened box).
     * Without rejecting it here, it would submit another pointless A* and produce output that misleads into "the box is the cause".
     * Runs cut off by the time limit are included too: looking only at the expansion count, they'd be misread as "range too narrow"
     * and tip into widenTriggered.
     */
    private static void continueProbeAfterDigging(NavCommandSink out, long generation, Level level, Player player,
                                                   BlockPos start, BlockPos goal, int renderRadius, int normalMargin,
                                                   CapturedView normal, ProbeRun normalRun) {
        int maxExpandedNodes = XaeroNavConfig.INSTANCE.maxExpandedNodes();
        boolean budgetExhausted = normalRun.result().budgetExhausted();
        boolean widenTriggered = !normalRun.result().complete() && !budgetExhausted
                && horizontalDistance(start, goal) <= renderRadius && normalMargin < renderRadius;
        if (!normalRun.result().complete() && budgetExhausted) {
            out.success(TextCompat.translatable(
                    "commands.xaeronav.probe_widen_skipped_budget", maxExpandedNodes));
            // Comparing runs pinned at the cap always gives the same expanded-node count, so nothing can be learned from it.
            // Leave the cutoff to time alone to measure "how many nodes this terrain actually needs to reach the goal",
            // and tell apart whether the setting is just too low or it can't reach even within the time budget, i.e. a search-side problem
            SearchLimits unboundedLimits = new SearchLimits(PROBE_UNBOUNDED_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, XaeroNavConfig.INSTANCE.heuristicWeight());
            DIAGNOSTIC.submit(generation,
                    cancelled -> runProbe(normal.view(), normal.bounds(), start, goal, unboundedLimits, cancelled),
                    (unboundedRun, error) -> {
                        if (error != null) {
                            XaeroNav.LOGGER.error("XaeroNav: Uncapped run for probe diagnostic failed", error);
                            return;
                        }
                        reportProbeRun(out, "commands.xaeronav.probe_unbounded", unboundedRun);
                    });
        } else {
            out.success(TextCompat.translatable(widenTriggered
                    ? "commands.xaeronav.probe_widen_triggered" : "commands.xaeronav.probe_widen_skipped"));
        }
        if (widenTriggered) {
            CapturedView widened = captureProbeView(level, player, start, goal, renderRadius,
                    PathfindingState.verticalSearchMargin(level, true), renderRadius);
            DIAGNOSTIC.submit(generation,
                    cancelled -> runProbe(widened.view(), widened.bounds(), start, goal, cancelled),
                    (widenedRun, error) -> {
                        if (error != null) {
                            XaeroNav.LOGGER.error("XaeroNav: Widened retry for probe diagnostic failed", error);
                            return;
                        }
                        reportProbeRun(out, "commands.xaeronav.probe_widened", widenedRun);
                    });
        }
    }

    /**
     * Reports whether the goal cell itself can satisfy the search's termination condition. The arrival check is an exact coordinate match
     * ({@code AStarPathfinder#reachedGoal}), so if the goal is outside the box, has no standable ground underfoot, or the body's two cells
     * can't be entered, it never arrives no matter how much budget is added. Looking only at the expanded-node count,
     * this "search that can't finish at all" gets misread as a lack of budget.
     *
     * <p>The body's two cells are passable if they can be dug into, so only undiggable cells (lava, hazard cells, dig-forbidden config) are
     * treated as unreachable. Judging by plain emptiness would report goals as unreachable even when digging reaches them normally.
     */
    private static void reportGoalCell(NavCommandSink out, Level level, ChunkView view, SearchBounds bounds,
                                        BlockPos start, BlockPos goal, int renderRadius) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (!bounds.contains(x, y, z)) {
            // The box is clipped by renderRadius toward the goal. Passing a long-range navigation goal as-is always lands
            // here, so unless it also shows how far can be measured, the same request gets repeated
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_outside_bounds",
                    Math.round(horizontalDistance(start, goal)), renderRadius));
            return;
        }
        BlockPos feetPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        long feetCell = view.cell(x, y, z);
        long headCell = view.cell(x, y + 1, z);
        long belowCell = view.cell(x, y - 1, z);
        // Even without footing, it can be reached if a block can be placed there to stand on (addBridge makes a floor and lands).
        // Declaring it "fundamentally unreachable" without checking whether placing is possible would misread goals reachable by bridging
        // as a search-side problem
        boolean floorReachable = CellData.standable(belowCell)
                || view.canPlaceBlocks()
                && (CellData.lava(belowCell) || CellData.replaceable(belowCell));
        Component feet = describeGoalCell(level, feetPos, feetCell);
        Component head = describeGoalCell(level, headPos, headCell);
        if (floorReachable && enterable(feetCell) && enterable(headCell)) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_ok", feet, head));
        } else {
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_blocked",
                    TextCompat.translatable(floorReachable ? "commands.xaeronav.probe_goal_cell_ok"
                            : "commands.xaeronav.probe_goal_cell_blocked"), feet, head));
        }
    }

    /** Cells that can be dug into are also passable. Only undiggable cells (lava, hazard cells, dig-forbidden config) can't be entered. */
    private static boolean enterable(long cell) {
        return CellData.occupiableWithoutDigging(cell) || !Double.isInfinite(CellData.digTicks(cell));
    }

    private static ResourceLocation blockId(Block block) {
        return BlockRegistryCompat.keyOf(block);
    }

    /**
     * {@code UNRESOLVED_SHAPE} (blocks with {@code hasDynamicShape()}, see CellData) is often a mod block,
     * and unless the block is named, "why only this spot is impassable" can't be told from the terrain.
     */
    private static Component describeGoalCell(Level level, BlockPos pos, long cell) {
        if (CellData.unresolvedShape(cell)) {
            ResourceLocation id = blockId(level.getBlockState(pos).getBlock());
            return TextCompat.translatable("commands.xaeronav.probe_goal_cell_unresolved_shape",
                    id == null ? "?" : id.toString());
        }
        if (CellData.occupiableWithoutDigging(cell)) {
            return TextCompat.translatable("commands.xaeronav.probe_goal_cell_ok");
        }
        return TextCompat.translatable(Double.isInfinite(CellData.digTicks(cell))
                ? "commands.xaeronav.probe_goal_cell_blocked" : "commands.xaeronav.probe_goal_cell_dig");
    }

    /**
     * {@link ChunkView#capture} is main-thread only. The {@link CapturedView} made here is passed to the background
     * {@link #DIAGNOSTIC}, and the actual A* search runs on the worker thread.
     */
    private static CapturedView captureProbeView(Level level, Player player, BlockPos start, BlockPos goal,
                                                  int horizontalMargin, int verticalMargin, int renderRadius) {
        SearchBounds bounds = SearchBounds.around(level, start, goal, horizontalMargin, verticalMargin, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());
        return new CapturedView(view, bounds);
    }

    private record CapturedView(ChunkView view, SearchBounds bounds) {
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                      BooleanSupplier cancelled) {
        return runProbe(view, bounds, start, goal, XaeroNavConfig.INSTANCE.searchLimits(), cancelled);
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                      SearchLimits limits, BooleanSupplier cancelled) {
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits);
        long startNanos = System.nanoTime();
        PathResult result = pathfinder.search(start, goal, cancelled);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProbeRun(start, result, bounds, elapsedMillis, view.loadedChunksInBounds(),
                view.totalChunksInBounds(), pathfinder.trimmedPlacements(), pathfinder.bridgeRunCapBlocked());
    }

    private static void reportProbeRun(NavCommandSink out, String labelKey, ProbeRun run) {
        PathResult result = run.result();
        SearchBounds bounds = run.bounds();
        int spanX = bounds.maxX() - bounds.minX() + 1;
        int spanZ = bounds.maxZ() - bounds.minZ() + 1;
        Component label = TextCompat.translatable(labelKey);
        out.success(TextCompat.translatable(
                result.complete() ? "commands.xaeronav.probe_summary_reached"
                        : "commands.xaeronav.probe_summary_partial",
                label, result.steps().size(), result.expandedNodes(), run.elapsedMillis(), spanX, spanZ,
                result.distinctNodes()));
        if (run.loadedChunks() < run.totalChunks()) {
            // Unloaded chunks are treated as impassable cells (ChunkView#capture).
            // The edge of the search range just hasn't loaded yet, so after a short wait the result may change even for the same coordinates
            out.success(TextCompat.translatable("commands.xaeronav.probe_chunks_missing",
                    run.loadedChunks(), run.totalChunks()));
        }
        if (!result.steps().isEmpty()) {
            String breakdown = describeMovements(result.steps(), run.start());
            out.success(TextCompat.translatable("commands.xaeronav.probe_movements", breakdown));
            reportWorkload(out, result.steps(), run.start());
        }
        if (run.trimmedPlacements() > 0) {
            // Looking only at the path after trimming, "didn't build a bridge" and "built one but couldn't cross" both
            // look like 0 placements. The causes are opposite, so show the fact that it was trimmed
            out.success(TextCompat.translatable("commands.xaeronav.probe_trimmed",
                    run.trimmedPlacements()));
        }
        if (run.bridgeRunCapBlocked()) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_bridge_cap_blocked",
                    XaeroNavConfig.INSTANCE.maxBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxLavaBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxVoidBridgeRunBlocks()));
        }
    }

    /**
     * Whether moves that place footing can be suggested. Both config and inventory are needed ({@code ChunkView#capture}).
     *
     * <p>Without this, a run where the hotbar simply has no blocks and a run where the terrain prevents bridging
     * both look like "0 placements". It's the first premise to rule out when investigating bridge behavior, so it's shown before the search.
     */
    private static void reportPlacementAvailability(NavCommandSink out, ChunkView view) {
        if (view.canPlaceBlocks()) {
            // Also show the budget (total placeable along the whole path). The three caps only say "how many blocks in a row
            // one bridge may run", so without this, a run where bridges were cut short because of the inventory count
            // would be mistaken for a terrain issue
            out.success(TextCompat.translatable("commands.xaeronav.probe_placing_on",
                    XaeroNavConfig.INSTANCE.maxBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxLavaBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxVoidBridgeRunBlocks(),
                    view.placedBlockBudget()));
        } else {
            out.success(TextCompat.translatable("commands.xaeronav.probe_placing_off",
                    TextCompat.translatable(XaeroNavConfig.INSTANCE.bridgingEnabled()
                            ? "commands.xaeronav.probe_placing_no_blocks"
                            : "commands.xaeronav.probe_placing_disabled")));
        }
    }

    /**
     * The amount of work the path requires. Shows what the {@code MovementType} breakdown alone doesn't.
     *
     * <p>Bridges and pillars are distinguished by {@code MoveKind} and don't appear in the public {@link MovementType} API
     * (both are counted as TRAVERSE/ASCEND), so they're recounted from whether there's a placement target.
     *
     * <p>Cumulative ascent/descent is listed to judge, by comparing with straight-line distance, whether vertical movement is
     * "unavoidable given the terrain" or "created by how the path was chosen". Without numbers, how much up-and-down there is
     * can only be described by impression.
     */
    private static void reportWorkload(NavCommandSink out, List<PathStep> steps, BlockPos start) {
        int placements = 0;
        int digCells = 0;
        int climbed = 0;
        int descended = 0;
        BlockPos previous = start;
        for (PathStep step : steps) {
            if (step.bridging()) {
                placements++;
            }
            digCells += step.digCells().size();
            int dy = step.pos().getY() - previous.getY();
            if (dy > 0) {
                climbed += dy;
            } else {
                descended -= dy;
            }
            previous = step.pos();
        }
        Component line = TextCompat.translatable("commands.xaeronav.probe_workload",
                placements, digCells, climbed, descended,
                steps.get(steps.size() - 1).pos().getY() - start.getY());
        out.success(line);
    }

    /**
     * Tallies steps per {@link MovementType}. ASCEND/DESCEND steps offset in both X and Z from the previous point
     * are tallied separately as "diagonal" ({@code MoveKind.DIAGONAL_ASCEND/DESCEND} are internal types of the
     * astar package and don't appear in the public API, but cardinal Ascend/Descend by definition move along
     * only one axis, so movement on both axes means diagonal).
     */
    private static String describeMovements(List<PathStep> steps, BlockPos start) {
        Map<MovementType, Integer> counts = new EnumMap<>(MovementType.class);
        Map<MovementType, Integer> diagonalCounts = new EnumMap<>(MovementType.class);
        BlockPos previous = start;
        for (PathStep step : steps) {
            MovementType type = step.movement();
            counts.merge(type, 1, Integer::sum);
            if ((type == MovementType.ASCEND || type == MovementType.DESCEND)
                    && step.pos().getX() != previous.getX() && step.pos().getZ() != previous.getZ()) {
                diagonalCounts.merge(type, 1, Integer::sum);
            }
            previous = step.pos();
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<MovementType, Integer> entry : counts.entrySet()) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(entry.getKey()).append(' ').append(entry.getValue());
            Integer diagonal = diagonalCounts.get(entry.getKey());
            if (diagonal != null) {
                text.append(" diag=").append(diagonal);
            }
        }
        return text.toString();
    }

    /**
     * The result of one {@link #runProbe}. {@link #reportProbeRun} also needs the start to compute the search range size.
     *
     * @param trimmedPlacements number of placement steps dropped from the end as not suggestible. Non-zero means a bridge was built but
     *                          couldn't be crossed, the exact opposite conclusion from "0 placements"
     */
    private record ProbeRun(BlockPos start, PathResult result, SearchBounds bounds, long elapsedMillis,
                             int loadedChunks, int totalChunks, int trimmedPlacements,
                             boolean bridgeRunCapBlocked) {
    }

    /**
     * Measures horizontal distance the same way as the condition under which {@link PathfindingState} triggers the widened retry ({@code y} is ignored).
     * The same check has to be reproduced here, so it keeps its own copy of the same formula.
     */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
