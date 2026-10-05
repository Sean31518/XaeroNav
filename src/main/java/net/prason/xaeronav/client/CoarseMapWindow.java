package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * How the range of Xaero's map read for long-distance routes is decided. Walking ({@link net.prason.xaeronav.pathfinding.coarse.CoarseMap}) and
 * flight ({@link net.prason.xaeronav.pathfinding.flight.CoarseAirMap}) differ only in the number of states per cell.
 *
 * <p>Don't build them separately. The diagnostic command once read a range offset by one chunk from production, and reports disagreed
 * only in cases where the destination fell outside the map.
 */
final class CoarseMapWindow {

    /** Range (chunks) to expand around each of the start and end. */
    private static final int PADDING_CHUNKS = 32;

    /** Cap on the read range (chunks square). Unlimited, it would hang just allocating the arrays. */
    private static final int MAX_SPAN_CHUNKS = 1024;

    /**
     * Cap on the number of search states. {@link net.prason.xaeronav.pathfinding.coarse.CoarseRouter#findRoute}
     * allocates {@code cells * levels} {@code double[]}/{@code int[]}/{@code boolean[]} in one go,
     * and is called up to twice by the lava policy ladder; a cap on chunks square alone would double directly
     * as layer 1 went 3D with multiple levels per cell. Capping by state count keeps the usual reach for elongated ranges (destinations far
     * along only one axis), while bringing only the square worst case back to the same allocation as in the 2D era.
     *
     * <p>Raise this along with any raise of {@link CoarseMap#MAX_FLOORS}. Leaving it as is means <b>just increasing the number of floors
     * shrinks how far long-distance routes reach</b>: what's capped isn't the cell count but the state count, so
     * more floors per cell means fewer cells fit within the same cap.
     */
    private static final int MAX_STATES = 1536 * 1024;

    private CoarseMapWindow() {
    }

    /**
     * The map read, and the number of regions in this range that are <b>on disk but not yet loaded into memory</b>.
     *
     * @param map             {@code null} if the range was too wide to read
     * @param pendingRegions  if greater than 0, waiting a bit and rereading will add to the map
     * @param layerBreakdown  share per cave layer ({@link XaeroMapReader.SurfaceRead})
     * @param readMillis      time spent on {@link XaeroMapReader#readSurfaceReporting} alone.
     *                        0 on rounds where the range was too wide to read (for continuously checking whether in-game stutter
     *                        comes from map reading on the main thread)
     */
    record Window(CoarseMap map, int pendingRegions, String layerBreakdown, long readMillis) {
    }

    /**
     * Reads the map for the range containing the two points. If the allocation would exceed the cap, {@link Window#map()} is {@code null};
     * the caller should treat that as "no long-distance route".
     *
     * <p><b>It also requests loading.</b> {@link XaeroMapReader#readSurface} <b>only reads regions Xaero has already loaded
     * into memory</b>, so without requesting, distant terrain stays
     * {@link CoarseMap#NO_DATA} forever, and since unknown cells are nearly the cheapest in {@code CoarseRouter},
     * <b>a route heading straight through a lava sea not yet seen gets drawn</b> (hit in-game).
     * Layer 2 ({@code CorridorLegSolver#prepare}) already did the same from the start.
     *
     * <p><b>Main thread only</b> ({@link XaeroMapReader#readSurface} touches the same structures as Xaero's
     * writer thread).
     *
     * @param statesPerCell number of states allocated per cell (the maximum number of floors/altitude bands)
     */
    static Window read(BlockPos from, BlockPos to, int statesPerCell) {
        int minChunkX = (Math.min(from.getX(), to.getX()) >> 4) - PADDING_CHUNKS;
        int maxChunkX = (Math.max(from.getX(), to.getX()) >> 4) + PADDING_CHUNKS;
        int minChunkZ = (Math.min(from.getZ(), to.getZ()) >> 4) - PADDING_CHUNKS;
        int maxChunkZ = (Math.max(from.getZ(), to.getZ()) >> 4) + PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > MAX_SPAN_CHUNKS || chunksZ > MAX_SPAN_CHUNKS
                || (long) chunksX * chunksZ * statesPerCell > MAX_STATES) {
            return new Window(null, 0, "", 0L);
        }
        int referenceY = (from.getY() + to.getY()) / 2;
        int pending = XaeroMapReader
                .surveyRegions(minChunkX, minChunkZ, chunksX, chunksZ, referenceY).pendingLoad();
        if (pending > 0) {
            // The request is asynchronous, so it won't make it in time for this read. The caller
            // looks at pendingRegions and replans (PathfindingState#cachedOrFreshRoute)
            XaeroMapReader.requestLoad(minChunkX, minChunkZ, chunksX, chunksZ, referenceY);
        }
        long startMillis = MonotonicTime.millis();
        XaeroMapReader.SurfaceRead read =
                XaeroMapReader.readSurfaceReporting(minChunkX, minChunkZ, chunksX, chunksZ, referenceY);
        long readMillis = MonotonicTime.millis() - startMillis;
        return new Window(read.map(), pending, read.layerBreakdown(), readMillis);
    }
}
