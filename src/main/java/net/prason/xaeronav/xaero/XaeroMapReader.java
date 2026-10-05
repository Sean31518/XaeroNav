package net.prason.xaeronav.xaero;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGridBuilder;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.region.Overlay;

/**
 * Builds a {@link CoarseMap} from the terrain saved by Xaero's world map.
 *
 * <p>What this reads is "the area that has ever been drawn on Xaero's map", not the currently loaded chunks.
 * So it knows the terrain far outside the loaded chunks, such as seas and mountains thousands of blocks away. Conversely,
 * there's no data for places never visited.
 *
 * <p><b>Thread contract:</b> call from the main thread. {@code getLeafMapRegion} checks for itself that it's on the main
 * thread when creation is involved, and throws {@link IllegalAccessError} on violation. Creation isn't requested here
 * ({@code create=false}), but since it touches the same structures as Xaero's writer thread,
 * the calls themselves are kept on the main thread.
 *
 * <p>Check {@link XaeroPresence#mapPresent()} before calling. Without Xaero installed,
 * loading this class itself fails.
 */
public final class XaeroMapReader {

    /**
     * The surface layer number. Cave layers are numbered by "the Y of the overhead ceiling" divided by 16,
     * while only the surface is represented by this sentinel (Xaero skips {@code caves/} only for this value).
     */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    /** 1 region = 32x32 chunks (512 blocks square). */
    private static final int CHUNKS_PER_REGION_SHIFT = 5;

    /** 1 tile chunk = 4x4 tiles. A region holds 8x8 tile chunks. */
    private static final int TILE_CHUNKS_PER_REGION = 8;
    private static final int TILES_PER_TILE_CHUNK = 4;

    /**
     * How many points to sample per chunk (= 1 tile, 16x16 blocks). Looking at all 256 points stalls the main thread for
     * hundreds of milliseconds just to gather a few hundred chunks square. Deciding sea vs. land only needs
     * "the rough character of that chunk", so thinning on a grid is enough.
     */
    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLES_PER_TILE = (16 / SAMPLE_STEP) * (16 / SAMPLE_STEP);

    /**
     * Sample ratio at which this cell is considered {@link CoarseMap#LAVA_MIXED} (passable but expensive).
     * Lava below this amount is ignored, on the premise that layers 2 and 3 can avoid it block by block.
     */
    private static final int LAVA_MIXED_NUMERATOR = 4;

    /**
     * Cap on load requests issued at once. Requests pile into a single queue on Xaero's loader thread, so
     * asking for a wide area at once makes the map display itself wait. Filling in from the near side is enough
     * to draw a long-distance route, so if it's not enough, the rest is requested on the next call.
     */
    private static final int MAX_LOAD_REQUESTS = 64;

    /**
     * Cap on the number of cave layers looked at in one read. Narrowed in order of closeness to the reference Y.
     *
     * <p><b>Slicing the Nether's height ({@code 0..127}) into {@link #CAVE_MODE_DEPTH} slices gives 8.
     * Having this at 4 dropped the layers of walkable levels entirely.</b> Real game (2026-09-07):
     * start y33 and destination y64 gave a reference Y of 48, and the chosen layers were 4, 3, 5, 2; layer 6, which holds
     * the walkable crimson forest (y65-97), fell outside the cap. Dropped layers also aren't covered by {@link #surveyRegions}
     * or {@link #requestLoad}, so <b>not even load requests are issued</b> (even when unloaded regions reach 0, it doesn't
     * mean "everything is visible"). As a result, layer 1 "sees" only a lava floor in that cell, and no lava-avoiding
     * path exists on the map
     * ({@code NetherCaveLayerSlabReproTest}).
     *
     * <p>The number of layers read directly affects main-thread map reading, but only <b>tiles in memory</b> are read,
     * and layers with no data finish at almost zero cost.
     *
     * <p><b>Must not exceed {@link CoarseMap#MAX_FLOORS}</b> ({@link #layersFor}'s "keep the total within
     * MAX_FLOORS"). In dimensions without a ceiling the surface layer takes one slot, so
     * this is set to exactly {@code MAX_FLOORS} as the cave layer cap; on the side that adds the surface,
     * {@link #layersFor} frees one slot. 2 of the 8 slices of the Nether's height are dropped, but
     * the dropped ones are the 2 farthest from the reference Y, and the real-game case (layer 6 dropped at reference Y=48) is included.
     */
    private static final int MAX_CAVE_LAYERS = CoarseMap.MAX_FLOORS;

    /**
     * Thickness (blocks) of the slice one cave layer holds. Xaero's default {@code CAVE_MODE_DEPTH};
     * only this far below {@code caveStart} is recorded. Reference Y differences smaller than this
     * see almost the same terrain, so they're meaningless as retry candidates.
     */
    private static final int CAVE_MODE_DEPTH = 30;

    /**
     * Range (distance from the reference Y, in blocks) of cave layers read in dimensions without a ceiling.
     *
     * <p>A layer only holds the slice from {@code caveStart} down to {@link #CAVE_MODE_DEPTH} below,
     * so a layer whose center is a whole slice away describes "terrain other than the current height band".
     * Reading deep cave layers while moving on the surface only increases main-thread map reading
     * (the heaviest part of layer 1) without changing the guidance.
     *
     * <p>Not applied in dimensions with a ceiling: the Nether only has data in cave layers, and dropping layers far from
     * the reference Y can leave nothing to read.
     */
    private static final int CAVE_LAYER_RELEVANCE_BLOCKS = CAVE_MODE_DEPTH;

    private XaeroMapReader() {
    }

    /**
     * Layers to look at when reading this range. Up to {@value #MAX_CAVE_LAYERS}, in order of closeness to the reference Y.
     *
     * <p>Xaero's {@code CaveStartCalculator} decides whether to write to cave layers by "whether every column of the 3x3
     * overhead has an opaque block". <b>This is a property of the local terrain, not of the dimension</b>, so
     * it writes to cave layers even while in Overworld caves; it isn't Nether-only.
     *
     * <p>In the Nether the overhead is always blocked by the bedrock ceiling, so it always falls to the cave side and nothing
     * goes into the surface layer. The Overworld writes to <b>both</b> (the surface layer when walking on the surface, cave layers
     * when going into caves), so cave layers are added while always including the surface layer.
     *
     * <p>The End is open overhead, so {@code CaveStartCalculator} returns the surface side and nothing is written to cave
     * layers; {@code caveLayers} becomes empty and it naturally falls back to the surface only.
     * (Splitting by {@code hasSkyLight()} would wrongly put the End on the cave side, but we don't split by dimension at all)
     *
     * <p><b>Keep the total within {@link CoarseMap#MAX_FLOORS}.</b> {@code CoarseMapBuilder.putFloor}
     * discards <b>the highest floor</b> when the cap is exceeded (implemented on the premise that the caller narrows it). In the Overworld the surface floor is
     * exactly the highest, so if surface + cave layers exceed the cap the surface is discarded and surface navigation breaks.
     * {@link #MAX_CAVE_LAYERS} equals that cap, so the side that adds the surface reduces the cave slots by one.
     *
     * <p>Layer numbers aren't predicted from the {@code caveStart} formula, because {@code caveStart} is
     * determined by the terrain above the player. Only what's actually in memory is looked at.
     */
    private static int[] layersFor(MapProcessor processor, int referenceY) {
        Level level = Minecraft.getInstance().level;
        boolean hasCeiling = level != null && level.dimensionType().hasCeiling();
        List<Integer> caveLayers = new ArrayList<>(loadedLayers(processor));
        caveLayers.removeIf(layer -> layer == SURFACE_LAYER);
        if (caveLayers.isEmpty()) {
            return new int[] {SURFACE_LAYER};
        }
        if (!hasCeiling) {
            caveLayers.removeIf(
                    layer -> Math.abs(layerCenterY(layer) - referenceY) > CAVE_LAYER_RELEVANCE_BLOCKS);
            if (caveLayers.isEmpty()) {
                return new int[] {SURFACE_LAYER};
            }
        }
        caveLayers.sort(Comparator.comparingInt(layer -> Math.abs(layerCenterY(layer) - referenceY)));
        // Dimensions with a ceiling put nothing in the surface layer, so no slot is freed. Otherwise the surface is always kept
        int caveSlots = hasCeiling ? MAX_CAVE_LAYERS : MAX_CAVE_LAYERS - 1;
        int count = Math.min(caveLayers.size(), caveSlots);
        int[] layers = new int[hasCeiling ? count : count + 1];
        int at = 0;
        if (!hasCeiling) {
            layers[at++] = SURFACE_LAYER;
        }
        for (int i = 0; i < count; i++) {
            layers[at++] = caveLayers.get(i);
        }
        return layers;
    }

    /**
     * The Y a layer number represents. The inverse of {@code caveLayer == caveStart >> 4}, but {@code caveStart} is
     * the <b>top</b> of the slice, not its center (what's actually recorded is
     * {@code [caveStart - CAVE_MODE_DEPTH, caveStart]}). Using {@code caveLayer << 4} as-is
     * would estimate CAVE_MODE_DEPTH/2 higher than the actual center, systematically biasing the choice of layers
     * near the reference Y upward.
     */
    private static int layerCenterY(int caveLayer) {
        return caveLayer == SURFACE_LAYER || caveLayer == Integer.MIN_VALUE
                ? 0 : (caveLayer << 4) - CAVE_MODE_DEPTH / 2;
    }

    private static List<Integer> loadedLayers(MapProcessor processor) {
        List<Integer> layers = new ArrayList<>();
        processor.getMapWorld().getCurrentDimension().getLayeredMapRegions()
                .applyToEachLoadedLayer((layer, regions) -> layers.add(layer));
        return layers;
    }

    /**
     * When reading multiple layers stacked, decides per cell which layer's value to adopt.
     * The height closest to the reference Y wins, since in the Nether the same (x,z) can be recorded in multiple Y bands.
     *
     * <p>Where the source differs between adjacent cells, a step appears there. The coarse map already treats cliffs
     * as relief so nothing breaks, but vertical movement across layers can't be represented in layers 1/2.
     */
    private static final class LayerMerge {

        private final int minX;
        private final int minZ;
        private final int sizeX;
        private final int sizeZ;
        private final int referenceY;
        private final int[] bestDistance;

        LayerMerge(int minX, int minZ, int sizeX, int sizeZ, int referenceY) {
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.referenceY = referenceY;
            this.bestDistance = new int[sizeX * sizeZ];
            Arrays.fill(this.bestDistance, Integer.MAX_VALUE);
        }

        /** Returns {@code true} if {@code height} should be written to this cell, and records the winning distance. */
        boolean accept(int x, int z, int height) {
            int localX = x - minX;
            int localZ = z - minZ;
            if (localX < 0 || localX >= sizeX || localZ < 0 || localZ >= sizeZ) {
                return false;
            }
            int index = localZ * sizeX + localX;
            int distance = Math.abs(height - referenceY);
            if (distance >= bestDistance[index]) {
                return false;
            }
            bestDistance[index] = distance;
            return true;
        }
    }

    /**
     * Reads the surface of the given range. If no cell could be read, {@link CoarseMap#knownCells()} is 0
     * (the range is unvisited, or Xaero hasn't loaded the region yet).
     *
     * <p>Each layer chosen by {@link #layersFor} is stacked into the {@link CoarseMap} as an independent floor
     * (not squashed into one). In dimensions with a ceiling, this is what first lets layer 1 see multiple
     * vertically stacked passages at once; when they were squashed, a retry re-reading with a different reference Y
     * (the ladder) was needed, but now one read gathers floors from all layers, so it's unnecessary.
     */
    public static CoarseMap readSurface(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int referenceY) {
        return readSurfaceReporting(minChunkX, minChunkZ, chunksX, chunksZ, referenceY).map();
    }

    /**
     * {@link #readSurface} with <b>each layer's share</b> attached.
     *
     * <p>The breakdown of "the map isn't visible" can't be known without reporting which layers cells came from:
     * the remedy differs depending on whether the walkable level's layer is empty or was excluded from reading altogether
     * (see {@link #MAX_CAVE_LAYERS}).
     */
    public static SurfaceRead readSurfaceReporting(int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                                                    int referenceY) {
        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        MapProcessor processor = processor();
        if (processor == null) {
            return new SurfaceRead(builder.build(), "no layers");
        }
        LongSet voidCandidates = new LongOpenHashSet();
        StringBuilder perLayer = new StringBuilder();
        for (int caveLayer : layersFor(processor, referenceY)) {
            int before = builder.knownCells();
            readLayer(processor, caveLayer, minChunkX, minChunkZ, chunksX, chunksZ, builder, voidCandidates);
            if (!perLayer.isEmpty()) {
                perLayer.append(' ');
            }
            perLayer.append(caveLayer == SURFACE_LAYER ? "surface" : "L" + caveLayer)
                    .append('=').append(builder.knownCells() - before);
        }
        markVoidCells(builder, voidCandidates);
        return new SurfaceRead(builder.build(), perLayer.toString());
    }

    /**
     * The map that was read, and per layer the <b>number of cells that newly got a floor</b>. Cells that already have a floor
     * from another layer aren't counted, so the total matches {@link CoarseMap#knownCells()}.
     */
    public record SurfaceRead(CoarseMap map, String layerBreakdown) {
    }

    /**
     * Of the cells that got no floor from any layer, those whose air columns were actually read are made
     * {@link CoarseMap#VOID}.
     *
     * <p><b>The key is deferring this to the end rather than writing per layer.</b> In dimensions like the Nether where the same XZ
     * is recorded in multiple Y bands, a layer without a floor doesn't mean other layers lack one. Writing the void
     * per layer would stack an extra unreachable floor onto cells that have real floors.
     */
    private static void markVoidCells(CoarseMapBuilder builder, LongSet voidCandidates) {
        LongIterator iterator = voidCandidates.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (builder.floorCount(chunkX, chunkZ) > 0) {
                continue;
            }
            builder.putFloor(chunkX, chunkZ, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                    CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static void readLayer(MapProcessor processor, int caveLayer,
                                   int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                                   CoarseMapBuilder builder, LongSet voidCandidates) {
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;

        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                forEachLoadedTile(processor, caveLayer, regionX, regionZ,
                        tile -> readTile(tile, builder, voidCandidates));
            }
        }
    }

    /**
     * Reads a corridor (the segment between layer 1 waypoints ± a margin) at block resolution. Unlike {@link #readSurface},
     * it doesn't thin out (all 256 points/chunk), so it's only for narrow ranges (about 96x96 blocks per corridor).
     */
    public static SurfaceGrid readSurfaceDetailed(int minBlockX, int minBlockZ, int sizeX, int sizeZ,
                                                   int referenceY) {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(minBlockX, minBlockZ, sizeX, sizeZ);
        MapProcessor processor = processor();
        if (processor == null) {
            return builder.build();
        }

        int minChunkX = minBlockX >> 4;
        int maxChunkX = (minBlockX + sizeX - 1) >> 4;
        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = (minBlockZ + sizeZ - 1) >> 4;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = maxChunkX >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = maxChunkZ >> CHUNKS_PER_REGION_SHIFT;

        LayerMerge merge = new LayerMerge(minBlockX, minBlockZ, sizeX, sizeZ, referenceY);
        for (int caveLayer : layersFor(processor, referenceY)) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    forEachLoadedTile(processor, caveLayer, regionX, regionZ,
                            tile -> readTileDetailed(tile, builder, merge));
                }
            }
        }
        return builder.build();
    }

    /** A floor reported by {@link #forEachCaveFloor} for one column. */
    @FunctionalInterface
    public interface FloorVisitor {

        /**
         * @param floorTopY the Y of the topmost solid block found in this layer. One above it is where you stand
         * @param lava      whether that surface is lava
         */
        void floor(int x, int z, int floorTopY, boolean lava);
    }

    /**
     * Reports the floors held by cave layers one by one, <b>at block resolution, per layer</b>.
     * The only data source for the 3D coarse layer ({@code VoxelTerrain}).
     *
     * <p>Unlike {@link #readSurfaceDetailed}, it <b>doesn't squash layers into one</b>. Squashing would leave only one of the
     * vertically stacked passages in the Nether, defeating the point of going 3D. Unlike {@link #readSurface}, it
     * <b>doesn't average per chunk either</b>: measured, chunk-averaged floors made only 6% of the grid walkable,
     * and the guide started answering "bridging straight across unknown places is cheaper".
     *
     * <p>The {@link #MAX_CAVE_LAYERS} cap isn't applied either. That limit exists to fit within {@link CoarseMap#MAX_FLOORS},
     * and doesn't affect this grid (however many floors a column has, they go in separately if the cells differ).
     *
     * <p><b>Main thread only.</b> Touches Xaero's region structures.
     *
     * @param step how many blocks apart to sample. At half the grid cell's edge, several points fall into each cell
     * @return the number of floors reported. 0 means Xaero doesn't have the map for this range yet
     */
    public static int forEachCaveFloor(int minBlockX, int minBlockZ, int sizeX, int sizeZ,
                                        int referenceY, int step, FloorVisitor visitor) {
        MapProcessor processor = processor();
        if (processor == null) {
            return 0;
        }
        int minRegionX = (minBlockX >> 4) >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = ((minBlockX + sizeX - 1) >> 4) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = (minBlockZ >> 4) >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = ((minBlockZ + sizeZ - 1) >> 4) >> CHUNKS_PER_REGION_SHIFT;
        int[] reported = {0};
        for (int caveLayer : allLayers(processor, referenceY)) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    forEachLoadedTile(processor, caveLayer, regionX, regionZ, tile -> {
                        int blockX = tile.getChunkX() * 16;
                        int blockZ = tile.getChunkZ() * 16;
                        for (int x = 0; x < 16; x += step) {
                            for (int z = 0; z < 16; z += step) {
                                if (blockX + x < minBlockX || blockX + x >= minBlockX + sizeX
                                        || blockZ + z < minBlockZ || blockZ + z >= minBlockZ + sizeZ) {
                                    continue;
                                }
                                MapBlock block = tile.getBlock(x, z);
                                if (block == null || isEmpty(block)) {
                                    continue;
                                }
                                boolean lava = isLava(block);
                                // For water, the passable height is the surface. Passing the bottom's height would make unstandable places floors
                                int height = !lava && isWater(block) ? block.getTopHeight() : block.getHeight();
                                visitor.floor(blockX + x, blockZ + z, height, lava);
                                reported[0]++;
                            }
                        }
                    });
                }
            }
        }
        return reported[0];
    }

    /**
     * All layers in memory. Unlike {@link #layersFor}, the count isn't narrowed
     * (only for {@link #forEachCaveFloor}). They're ordered by closeness to the reference Y so that if cut off midway,
     * the nearby height bands remain.
     */
    private static int[] allLayers(MapProcessor processor, int referenceY) {
        List<Integer> layers = new ArrayList<>(loadedLayers(processor));
        layers.sort(Comparator.comparingInt(layer -> Math.abs(layerCenterY(layer) - referenceY)));
        return layers.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The state of regions in range. When few cells could be read, the cause splits in two:
     * visited but not in memory yet ({@code pendingLoad}), or never visited at all
     * (counted in neither {@code loaded} nor {@code pendingLoad}). The former is filled in by
     * {@link #requestLoad}, but nothing can be done about the latter.
     *
     * <p>Note that {@code pendingLoad} isn't "the number on disk". Once Xaero finishes loading a region,
     * it discards that detection info ({@code MapLayer.removeRegionDetection}).
     * So this is the number "on disk and not loaded yet", which decreases as loading progresses.
     */
    public record RegionStats(int inRange, int loaded, int pendingLoad) {
    }

    /** Counts only the region load status for the same range as {@link #readSurface}. */
    public static RegionStats surveyRegions(int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                                             int referenceY) {
        MapProcessor processor = processor();
        if (processor == null) {
            return new RegionStats(0, 0, 0);
        }

        int inRange = 0;
        int loaded = 0;
        int pendingLoad = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        for (int caveLayer : layersFor(processor, referenceY)) {
            MapLayer layer = processor.getMapWorld().getCurrentDimension()
                    .getLayeredMapRegions().getLayer(caveLayer);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    inRange++;
                    MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, false);
                    if (region != null && region.isLoaded()) {
                        loaded++;
                    }
                    if (layer != null && layer.getRegionDetection(regionX, regionZ) != null) {
                        pendingLoad++;
                    }
                }
            }
        }
        return new RegionStats(inRange, loaded, pendingLoad);
    }

    /**
     * Requests loading of regions in range that are "on disk but not loaded yet".
     * Actual loading is asynchronous, so the caller must wait until the return value reaches 0
     * (or give up). Returns the number of requests made.
     *
     * <p>Xaero only keeps in memory the range needed to display the map. Right after startup, or
     * without ever opening the world map, not a single region is loaded, even for visited land.
     */
    public static int requestLoad(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int referenceY) {
        MapProcessor processor = processor();
        if (processor == null) {
            return 0;
        }

        int requested = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        // The cap is one shared across layers. Using the full amount per layer overflows Xaero's single load queue,
        // making the map display itself wait
        for (int caveLayer : layersFor(processor, referenceY)) {
            if (requested >= MAX_LOAD_REQUESTS) {
                break;
            }
            MapLayer layer = processor.getMapWorld().getCurrentDimension()
                    .getLayeredMapRegions().getLayer(caveLayer);
            if (layer == null) {
                continue;
            }
            for (int regionX = minRegionX; regionX <= maxRegionX && requested < MAX_LOAD_REQUESTS; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ && requested < MAX_LOAD_REQUESTS; regionZ++) {
                    // No detection info = unvisited or already loaded. Neither is worth requesting
                    // (requesting would just create an empty region in memory)
                    if (layer.getRegionDetection(regionX, regionZ) == null) {
                        continue;
                    }
                    // create=true only here. Requesting a load needs the region container itself
                    MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, true);
                    if (region == null || region.isLoaded()) {
                        continue;
                    }
                    processor.getMapSaveLoad().requestLoad(region, "xaeronav");
                    requested++;
                }
            }
        }
        return requested;
    }

    /**
     * How much data one layer has for a given range. If {@code caveLayer} is
     * {@link Integer#MAX_VALUE}, it's the surface layer.
     */
    public record LayerProbe(int caveLayer, int knownCells, int minHeight, int maxHeight) {

        public boolean isSurface() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * For diagnostics: reads every layer in memory individually as-is, without selection or merging, and compares them.
     * Used to confirm with real data that in the Nether "the surface layer is empty and data is scattered across cave layers".
     */
    public static List<LayerProbe> probeLayers(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return List.of();
        }
        List<LayerProbe> probes = new ArrayList<>();
        for (int caveLayer : loadedLayers(processor)) {
            CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
            // Only one layer is read, so multiple floors never stack in one cell (looking at floor 0 alone is enough).
            // No void marks: this diagnostic answers "how many floors does this layer have", and mixing in
            // heightless VOID floors would change the meaning of knownCells and the height range
            readLayer(processor, caveLayer, minChunkX, minChunkZ, chunksX, chunksZ, builder,
                    new LongOpenHashSet());
            CoarseMap map = builder.build();

            int minHeight = Integer.MAX_VALUE;
            int maxHeight = Integer.MIN_VALUE;
            for (int chunkX = minChunkX; chunkX < minChunkX + chunksX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ < minChunkZ + chunksZ; chunkZ++) {
                    if (map.floorCount(chunkX, chunkZ) == 0) {
                        continue;
                    }
                    short height = map.heightAtFloor(chunkX, chunkZ, 0);
                    minHeight = Math.min(minHeight, height);
                    maxHeight = Math.max(maxHeight, height);
                }
            }
            probes.add(new LayerProbe(caveLayer, map.knownCells(),
                    minHeight == Integer.MAX_VALUE ? 0 : minHeight,
                    maxHeight == Integer.MIN_VALUE ? 0 : maxHeight));
        }
        probes.sort(Comparator.comparingInt(LayerProbe::caveLayer));
        return probes;
    }

    /** Xaero's cave mode setting. 0 = disabled (to the surface layer) / 1 = split by Y band / 2 = single layer. */
    public static int caveModeType() {
        MapProcessor processor = processor();
        return processor == null ? -1 : processor.getMapWorld().getCurrentDimension().getCaveModeType();
    }

    private static MapProcessor processor() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("XaeroMapReader must be called from the main thread");
        }
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return null;
        }
        MapProcessor processor = session.getMapProcessor();
        // Until the map's world is settled, the region coordinate system itself isn't determined
        return processor != null && processor.isMapWorldUsable() ? processor : null;
    }

    /**
     * Visits every loaded tile in one region. The triple nesting of region -> tile chunk -> tile and the
     * "is it readable" check are the same for both the coarse reading ({@link #readTile}) and
     * block resolution ({@link #readTileDetailed}), so they're folded in here.
     */
    private static void forEachLoadedTile(MapProcessor processor, int caveLayer, int regionX, int regionZ,
                                           Consumer<MapTile> visitor) {
        // create=false, so regions Xaero hasn't loaded yet come back as null.
        // Not making it read from disk here, because loading is asynchronous and completion can't be awaited
        MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, false);
        if (region == null || !region.isLoaded()) {
            return;
        }
        for (int tileChunkX = 0; tileChunkX < TILE_CHUNKS_PER_REGION; tileChunkX++) {
            for (int tileChunkZ = 0; tileChunkZ < TILE_CHUNKS_PER_REGION; tileChunkZ++) {
                MapTileChunk tileChunk = region.getChunk(tileChunkX, tileChunkZ);
                if (tileChunk == null) {
                    continue;
                }
                for (int tileX = 0; tileX < TILES_PER_TILE_CHUNK; tileX++) {
                    for (int tileZ = 0; tileZ < TILES_PER_TILE_CHUNK; tileZ++) {
                        MapTile tile = tileChunk.getTile(tileX, tileZ);
                        if (tile == null || !tile.isLoaded()) {
                            continue;
                        }
                        visitor.accept(tile);
                    }
                }
            }
        }
    }

    /**
     * 1 tile = 1 chunk. The tile itself holds its chunk coordinates, so there's no need to recover them from the outer indices.
     *
     * <p>When {@link #readSurface} reads multiple layers, each call here stacks one floor via
     * {@link CoarseMapBuilder#putFloor}. Layers aren't squashed into one height; squashing would make independent
     * vertically stacked passages in dimensions with a ceiling either survive only on one side or appear connected
     * by unjustified steps.
     */
    private static void readTile(MapTile tile, CoarseMapBuilder builder, LongSet voidCandidates) {
        int waterSamples = 0;
        int lavaSamples = 0;
        int heightSum = 0;
        int heightSamples = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        // Average lava surface height. Used only in the rare case where all samples were lava (below)
        int lavaHeightSum = 0;
        int samples = 0;
        // Number of columns "known to have no floor". Xaero writes air for columns with no opaque block at all,
        // so a tile that exists but is only air = void. Different from the tile itself not existing (unvisited)
        int voidSamples = 0;

        for (int x = 0; x < 16; x += SAMPLE_STEP) {
            for (int z = 0; z < 16; z += SAMPLE_STEP) {
                MapBlock block = tile.getBlock(x, z);
                if (block == null) {
                    continue;
                }
                if (isEmpty(block)) {
                    voidSamples++;
                    continue;
                }
                samples++;
                boolean water = isWater(block);
                boolean lava = !water && isLava(block);
        // The water surface height is used because what the coarse route looks at is "can it pass there".
        // Measuring steps by the bottom height would make deep seas appear as huge cliffs and distort the route
                int sampleHeight = water ? block.getTopHeight() : block.getHeight();
                if (water) {
                    waterSamples++;
                } else if (lava) {
                    lavaSamples++;
                    lavaHeightSum += sampleHeight;
                    // Lava surfaces are excluded from the representative height (= the waypoint's Y). Lava can't be stood on,
                    // so mixing in that height would drop the waypoint onto the surface of a lava sea, and layer 2's
                    // resolveStandable couldn't reach it
                    continue;
                }
                heightSum += sampleHeight;
                heightSamples++;
                minHeight = Math.min(minHeight, sampleHeight);
                maxHeight = Math.max(maxHeight, sampleHeight);
            }
        }

        if (samples == 0) {
            // Not a single column with a floor. If air columns were actually read, that's not "not known yet" but
            // the information "there's no floor", so it must not be treated like unvisited. However, other layers
            // might have a floor in this cell, so only remember it here and decide after reading all layers
            // ({@link #markVoidCells})
            if (voidSamples > 0) {
                voidCandidates.add(chunkKey(tile.getChunkX(), tile.getChunkZ()));
            }
            return;
        }
        // Like water, lava is impassable at a "majority", and below that the cell becomes passable but expensive.
        // Previously it became impassable at a quarter of this amount, but in the Nether, where lava is part of the terrain,
        // 58% of known cells became walls, and even the start became impassable
        byte kind;
        if (lavaSamples * 2 >= samples) {
            kind = CoarseMap.LAVA;
        } else if (lavaSamples * LAVA_MIXED_NUMERATOR >= samples) {
            kind = CoarseMap.LAVA_MIXED;
        } else if (waterSamples * 2 >= samples) {
            kind = CoarseMap.WATER;
        } else {
            kind = CoarseMap.LAND;
        }
        // heightSamples==0 only when every sample is lava (= kind is always LAVA). The lava surface height is
        // correct here: when crossing this cell with BridgePolicy.BRIDGE, that's exactly the height where footing is placed
        int averageHeight = heightSamples > 0 ? heightSum / heightSamples : lavaHeightSum / lavaSamples;
        int representativeMin = heightSamples > 0 ? minHeight : averageHeight;
        int representativeMax = heightSamples > 0 ? maxHeight : averageHeight;
        builder.putFloor(tile.getChunkX(), tile.getChunkZ(), kind, averageHeight, representativeMin,
                representativeMax);
    }

    /**
     * Reads one tile at block resolution (256 points). Unlike {@link #readTile} it doesn't thin out; it's only called
     * for narrow corridor-limited ranges, so there's no risk of sweeping hundreds of chunks here.
     */
    private static void readTileDetailed(MapTile tile, SurfaceGridBuilder builder, LayerMerge merge) {
        int blockX = tile.getChunkX() * 16;
        int blockZ = tile.getChunkZ() * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                MapBlock block = tile.getBlock(x, z);
                if (block == null || isEmpty(block)) {
                    continue;
                }
                boolean lava = isLava(block);
                boolean water = !lava && isWater(block);
                // Adoption is decided by the passable height: the water surface for water, otherwise the surface itself
                int mergeHeight = water ? block.getTopHeight() : block.getHeight();
                if (!merge.accept(blockX + x, blockZ + z, mergeHeight)) {
                    continue;
                }
                if (lava) {
                    builder.put(blockX + x, blockZ + z, CoarseMap.LAVA, block.getHeight());
                } else if (water) {
                    // Both the bottom and the surface of water can be read, so it can carry water depth, which layer 1 (surface only) lacks
                    builder.put(blockX + x, blockZ + z, CoarseMap.WATER, block.getHeight(), block.getTopHeight());
                } else {
                    builder.put(blockX + x, blockZ + z, CoarseMap.LAND, block.getHeight());
                }
            }
        }
    }

    /**
     * Water is recorded as an overlay rather than as a surface block. The seabed sand goes into {@code state}, with
     * the water overlay on top, so looking only at {@code state} makes the sea look like a beach.
     */
    private static boolean isWater(MapBlock block) {
        ArrayList<Overlay> overlays = block.getOverlays();
        if (overlays != null) {
            for (Overlay overlay : overlays) {
                if (overlay.isWater()) {
                    return true;
                }
            }
        }
        BlockState state = block.getState();
        return state != null && state.getFluidState().is(FluidTags.WATER);
    }

    /**
     * Whether this column has no data in this layer.
     *
     * <p>Xaero writes columns where it found no opaque block in the scan range as air at height
     * {@code worldBottomY} (0 in the Nether) ({@code MapWriter#loadPixel}). Cave layers only look from
     * {@code caveStart} down to {@code CAVE_MODE_DEPTH} blocks below, so this happens routinely.
     *
     * <p>Read as-is, there would be ground at the bottom of the void and waypoints would drop to {@code y=1}. The surface layer
     * always scans to the bottom and hits something, so air can be used as the mark for "no data".
     */
    @SuppressWarnings("deprecation")
    private static boolean isEmpty(MapBlock block) {
        BlockState state = block.getState();
        return state == null || state.isAir();
    }

    private static boolean isLava(MapBlock block) {
        BlockState state = block.getState();
        return state != null && state.getFluidState().is(FluidTags.LAVA);
    }
}
