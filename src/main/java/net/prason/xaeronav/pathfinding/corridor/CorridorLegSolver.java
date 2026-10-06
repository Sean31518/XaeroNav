package net.prason.xaeronav.pathfinding.corridor;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.SurfaceCellSource;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * Prepares one waypoint segment of long-distance route layer 1 to be resolved with layer 2 (Xaero's block-resolution surface data).
 * Logic shared by the {@code /xaeronav debug corridor} diagnostic command and {@link net.prason.xaeronav.client.PathfindingState}'s
 * waypoint refinement.
 *
 * <p><b>Thread contract:</b> {@link #prepare} reads Xaero's map data via {@link XaeroMapReader}, so it's
 * main-thread only (see the {@link XaeroMapReader} class Javadoc). The resulting {@link PreparedLeg#view()} is
 * an immutable {@link SurfaceCellSource}, so the A* search using it may itself run on a worker thread
 * (running the search is left to the caller, since both the diagnostic command and live navigation want to run it asynchronously).
 */
public final class CorridorLegSolver {

    /** Horizontal margin (blocks) added to a leg's bounding box. A design value of long-distance route layer 2. */
    public static final int HORIZONTAL_MARGIN_BLOCKS = 48;

    /**
     * Vertical margin (blocks) added to a leg's bounding box. {@link SurfaceCellSource#cell}
     * doesn't actually look at the Y range (passability is decided only by each column's surface height), so this
     * means nothing beyond making {@code bounds()} well-formed.
     */
    public static final int VERTICAL_MARGIN_BLOCKS = 64;

    /**
     * Search time cap per leg (milliseconds). Layer 2 doesn't handle digging, doors or cobwebs, so nodes are cheap, and
     * most legs solve in plenty of time even with a trimmed cap
     * (prevents the total from ballooning to tens of seconds on long routes with many waypoints).
     */
    public static final long LEG_TIME_LIMIT_MILLIS = 300;

    /**
     * Maximum radius (blocks) to search for a standable column instead when an endpoint is a lava or unknown column.
     * At the edge of a Nether lava sea it's not unusual for an endpoint to land right in lava, and giving up layer 2's corridor
     * refinement entirely when there's land just a few blocks away would be a shame. Widening too much drifts toward places
     * unrelated to the corridor, so it's kept well below the waypoint spacing (24 blocks).
     */
    private static final int ENDPOINT_FALLBACK_RADIUS_BLOCKS = 8;

    public static final SearchLimits SEARCH_LIMITS = new SearchLimits(
            AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
            LEG_TIME_LIMIT_MILLIS,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private CorridorLegSolver() {
    }

    /**
     * Prepares the leg from {@code from} to {@code to} for searching with layer 2. If surface data is missing at either end,
     * {@link PreparedLeg#view()} is {@code null} (the caller either waits for loading or
     * falls back to the raw waypoints). {@link PreparedLeg#pendingRegions()} is always filled regardless of
     * whether data exists, so that failure reports can also say "how many more regions need reading for this to possibly resolve".
     *
     * <p>{@code readSurfaceDetailed} reads with create=false, so if this leg's regions aren't in Xaero's
     * memory yet they are silently treated as NO_DATA. Even visited regions are often just not in memory right now,
     * so if there are unloaded regions, loading is requested via {@link XaeroMapReader#requestLoad}
     * (leaving the possibility of resolving on the next call).
     */
    public static PreparedLeg prepare(BlockPos from, BlockPos to) {
        int minBlockX = Math.min(from.getX(), to.getX()) - HORIZONTAL_MARGIN_BLOCKS;
        int maxBlockX = Math.max(from.getX(), to.getX()) + HORIZONTAL_MARGIN_BLOCKS;
        int minBlockZ = Math.min(from.getZ(), to.getZ()) - HORIZONTAL_MARGIN_BLOCKS;
        int maxBlockZ = Math.max(from.getZ(), to.getZ()) + HORIZONTAL_MARGIN_BLOCKS;
        int sizeX = maxBlockX - minBlockX + 1;
        int sizeZ = maxBlockZ - minBlockZ + 1;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;
        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        // In dimensions with a ceiling, Xaero's map is split into layers per Y band. Without passing
        // which Y band this leg is about, the layer to read can't be chosen
        int referenceY = (from.getY() + to.getY()) / 2;
        XaeroMapReader.RegionStats regionStats =
                XaeroMapReader.surveyRegions(minChunkX, minChunkZ, chunksX, chunksZ, referenceY);
        int pendingRegions = regionStats.pendingLoad();
        if (pendingRegions > 0) {
            XaeroMapReader.requestLoad(minChunkX, minChunkZ, chunksX, chunksZ, referenceY);
        }

        SurfaceGrid grid = XaeroMapReader.readSurfaceDetailed(minBlockX, minBlockZ, sizeX, sizeZ, referenceY);
        BlockPos resolvedFrom =
                grid.resolveNearestStandable(from.getX(), from.getZ(), ENDPOINT_FALLBACK_RADIUS_BLOCKS);
        BlockPos resolvedTo = grid.resolveNearestStandable(to.getX(), to.getZ(), ENDPOINT_FALLBACK_RADIUS_BLOCKS);
        if (resolvedFrom == null || resolvedTo == null) {
            // The corridor can't be solved, but if one end was resolved, that answer isn't thrown away. When the caller
            // gives up on the leg it falls back to the raw waypoint (layer 1's chunk center), so if a snapped endpoint
            // can be passed, it is; even if the center is void or lava, that one is a coordinate that can actually be stood on
            return new PreparedLeg(null, resolvedFrom, resolvedTo, pendingRegions);
        }

        SearchBounds bounds = new SearchBounds(minBlockX, resolvedFrom.getY() - VERTICAL_MARGIN_BLOCKS, minBlockZ,
                maxBlockX, resolvedFrom.getY() + VERTICAL_MARGIN_BLOCKS, maxBlockZ);
        // Swimming here also stands in for boats (layer 2 has no boat moves), so water is closed only when both are off
        CellSource view = new SurfaceCellSource(grid, bounds, XaeroNavConfig.INSTANCE.jumpGapEnabled(),
                XaeroNavConfig.INSTANCE.maxSubmergedTicks(),
                XaeroNavConfig.INSTANCE.swimmingEnabled() || XaeroNavConfig.INSTANCE.boatsEnabled());
        return new PreparedLeg(view, resolvedFrom, resolvedTo, pendingRegions);
    }

    /**
     * The result of {@link #prepare}. {@code view} is immutable, so it may be searched from a worker thread.
     *
     * <p>If {@code view} is {@code null}, the leg couldn't be solved with layer 2. Even then,
     * {@code from}/{@code to} <b>are filled if just one end could be resolved</b>, so that when the caller gives up
     * on the leg and falls back to the raw waypoints, it can use the snapped coordinates if any.
     * If neither could be resolved, both are {@code null}.
     */
    public record PreparedLeg(CellSource view, BlockPos from, BlockPos to, int pendingRegions) {
    }
}
