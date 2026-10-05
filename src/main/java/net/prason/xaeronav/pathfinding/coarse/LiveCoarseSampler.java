package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Builds a {@link CoarseMap} from the real data of loaded chunks. Used by the layer 3 retry when the budget runs out
 * (the coarse waypoint chain). It doesn't depend on Xaero's map, so it works without Xaero installed.
 */
public final class LiveCoarseSampler {

    /** How many blocks apart to sample within a chunk. Kept in line with {@code XaeroMapReader}. */
    private static final int SAMPLE_STEP = 4;
    /** Uses the same three-level lava classification as {@code XaeroMapReader} (so the same terrain doesn't look different in layers 1 and 3). */
    private static final int LAVA_MIXED_NUMERATOR = 4;

    /**
     * Maximum downward scan depth when finding the surface of column (x,z). {@link CellSource#openSkyY} comes from the heightmap
     * and is only a hint (it can be off depending on water surfaces), so it is verified by walking real cells down from there
     * (same idea as {@code AStarPathfinder#firstNonAirBelow}).
     *
     * <p>The actual scan stops at the bottom of the search bounds, so this is insurance against pathologically tall bounds.
     * It used to be fixed at 32, but that didn't reach lava seas below the search bounds (in the Nether, bounds top 74, lava surface 31),
     * so the sea itself never made it onto the map.
     */
    private static final int MAX_SCAN_DEPTH = 128;

    /**
     * Height tolerance (blocks) for treating floors found in different columns of one chunk as "the same floor".
     * Xaero's cave layers are split in units of {@code CAVE_MODE_DEPTH} (30) blocks, so genuinely separate layers are
     * usually at least 30 blocks apart. Meanwhile, even the same floor can shift by a few blocks with the terrain relief within a chunk.
     * A value in between, aiming to merge gentle slopes into one floor while keeping separate layers apart.
     */
    private static final int FLOOR_CLUSTER_THRESHOLD_BLOCKS = 12;

    private LiveCoarseSampler() {
    }

    /** Variant without a reference Y. Uses the middle of the search bounds and ignores cancellation (for tests and diagnostics). */
    public static CoarseMap sample(CellSource view, SearchBounds bounds) {
        return sample(view, bounds, (bounds.minY() + bounds.maxY()) / 2, () -> false);
    }

    /**
     * @param referenceY reference for which height band to keep when a cell's floors exceed {@link CoarseMap#MAX_FLOORS}.
     *                   Pass the search start's Y: dropping the start's floor makes that cell's {@code nearestFloor} return
     *                   another level, and the whole coarse map becomes unrelated to where the player actually stands
     * @param cancelled  whether it may stop partway. Once true, returns the map built so far
     *                   (the caller discards the result on the same signal, so a partial map is never used)
     */
    public static CoarseMap sample(CellSource view, SearchBounds bounds, int referenceY,
                                    BooleanSupplier cancelled) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;

        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            // The scan reaches every chunk in the search bounds x 16 columns x up to 128 cells. If a job evicted by a newer search
            // runs this to completion, it pays the same cell reads as the live job a second time over
            if (cancelled.getAsBoolean()) {
                return builder.build();
            }
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                sampleChunk(view, bounds, chunkX, chunkZ, referenceY, builder);
            }
        }
        return builder.build();
    }

    /**
     * Groups the floors found in each of the chunk's 16 columns by nearby height before passing them to
     * {@link CoarseMapBuilder}. A column can have several floors (in dimensions with a ceiling, when
     * independent passages stack vertically), so a simple one-value-per-column aggregate can't represent it.
     */
    private static void sampleChunk(CellSource view, SearchBounds bounds, int chunkX, int chunkZ,
                                     int referenceY, CoarseMapBuilder builder) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        List<FloorAccumulator> floors = new ArrayList<>(CoarseMap.MAX_FLOORS);
        // Number of columns confirmed to have no floor at all. Kept distinct from columns whose scan stopped at unloaded chunks
        int voidColumns = 0;

        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                ColumnScan scan = sampleColumnFloors(view, bounds, baseX + dx, baseZ + dz);
                if (scan.voidColumn()) {
                    voidColumns++;
                }
                for (ColumnSample sample : scan.floors()) {
                    accumulate(floors, sample);
                }
            }
        }
        if (floors.isEmpty()) {
            // No column with a floor. If air-only columns were actually read, this is "no floor" rather than
            // "not known yet"; the live reader keeps the same distinction as XaeroMapReader#markVoidCells
            if (voidColumns > 0) {
                builder.putFloor(chunkX, chunkZ, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                        CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
            }
            return;
        }
        // Each column is capped at MAX_FLOORS, but clusters across 16 columns easily exceed it
        // (routine in the Nether, where the search bounds span the full height). Don't leave picking the overflow to
        // CoarseMapBuilder: it always evicts the "highest floor", so the top corridor the player stands in
        // simply disappears. Keep them here in order of closeness to the reference Y
        if (floors.size() > CoarseMap.MAX_FLOORS) {
            floors.sort(Comparator.comparingInt(floor -> Math.abs(floor.approxHeight() - referenceY)));
            floors.subList(CoarseMap.MAX_FLOORS, floors.size()).clear();
        }
        for (FloorAccumulator floor : floors) {
            floor.emit(chunkX, chunkZ, builder);
        }
    }

    /** Adds to an existing aggregate of nearby height, or creates a new one (no cap on cluster count; {@link #sampleChunk} trims it). */
    private static void accumulate(List<FloorAccumulator> floors, ColumnSample sample) {
        FloorAccumulator closest = null;
        int closestDistance = FLOOR_CLUSTER_THRESHOLD_BLOCKS + 1;
        for (FloorAccumulator candidate : floors) {
            int distance = Math.abs(candidate.approxHeight() - sample.height);
            if (distance <= FLOOR_CLUSTER_THRESHOLD_BLOCKS && distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        if (closest == null) {
            closest = new FloorAccumulator();
            floors.add(closest);
        }
        closest.add(sample);
    }

    /**
     * Finds every standable floor in column (x,z). Even if the top is solid, it passes through without digging down to
     * the true ground (a floor counts only while {@link CellData#passableEmpty} continues; if the top of the search bounds is
     * inside rock, misreading that as ground makes the lava sea underfoot vanish from the map).
     *
     * <p>After recording a floor, air must be seen again before the next floor counts, so consecutive solid cells
     * within the same mass aren't double-counted as multiple floors.
     *
     * <p>When there is no floor, {@link ColumnScan#voidColumn()} tells why. <b>Zero floors has three
     * meanings</b>: only air (void), solid from top to bottom (inside rock), or the scan stopped at
     * unloaded chunks (unknown). Only void maps to {@link CoarseMap#VOID}, so it must be confirmed that
     * "air was actually seen". Treating rock-filled columns as void would make underground
     * cells targets for bridging across.
     */
    private static ColumnScan sampleColumnFloors(CellSource view, SearchBounds bounds, int x, int z) {
        int top = Math.min(view.openSkyY(x, z), bounds.maxY());
        int bottom = Math.max(bounds.minY(), top - MAX_SCAN_DEPTH);
        if (top < bottom) {
            // The top of the scan is below the bottom of the search band. In a column with no floor at all {@code MOTION_BLOCKING} is empty,
            // so {@code ChunkView#openSkyY} returns <b>the world's minimum Y + 1</b>. The End's
            // void is exactly this; entering the scan below as-is never runs the loop body,
            // and "no air seen" is treated as unknown (in-game logs showed only 51/154 cells known).
            //
            // If the chunk is readable, we can say for sure "this band has no floor". If not, it's unknown
            return new ColumnScan(List.of(), CellData.present(view.cell(x, bottom, z)));
        }
        List<ColumnSample> floors = new ArrayList<>(CoarseMap.MAX_FLOORS);
        boolean airSeen = false;
        boolean anyAir = false;
        boolean anySolid = false;
        for (int y = top; y >= bottom && floors.size() < CoarseMap.MAX_FLOORS; y--) {
            long cell = view.cell(x, y, z);
            if (!CellData.present(cell)) {
                // Unloaded chunk. What lies beyond is unknown, so stop (out of bounds stops at bottom)
                return new ColumnScan(floors, false);
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                anyAir = true;
                continue;
            }
            anySolid = true;
            if (airSeen) {
                floors.add(new ColumnSample(kindOf(cell), y));
                airSeen = false;
            }
        }
        return new ColumnScan(floors, anyAir && !anySolid);
    }

    /**
     * The scan result of one column. {@code voidColumn} is whether the column was confirmed to have no floor,
     * i.e. "read fully and found only air".
     */
    private record ColumnScan(List<ColumnSample> floors, boolean voidColumn) {
    }

    private static byte kindOf(long cell) {
        if (CellData.lava(cell)) {
            return CoarseMap.LAVA;
        }
        if (CellData.water(cell)) {
            return CoarseMap.WATER;
        }
        return CoarseMap.LAND;
    }

    private record ColumnSample(byte kind, int height) {
    }

    /** Aggregates samples from multiple columns treated as the same floor, using the same rules as {@code XaeroMapReader#readTile}. */
    private static final class FloorAccumulator {
        private int waterSamples;
        private int lavaSamples;
        private int heightSum;
        private int heightSamples;
        private int minHeight = Integer.MAX_VALUE;
        private int maxHeight = Integer.MIN_VALUE;
        // Average lava surface height. Used only when every sample was lava
        private int lavaHeightSum;
        private int samples;

        void add(ColumnSample sample) {
            samples++;
            if (sample.kind() == CoarseMap.LAVA) {
                lavaSamples++;
                lavaHeightSum += sample.height();
                // Lava surfaces are excluded from the representative height (same reason as XaeroMapReader#readTile:
                // lava isn't standable, so mixing it in drops waypoints onto the lava surface)
                return;
            }
            if (sample.kind() == CoarseMap.WATER) {
                waterSamples++;
            }
            heightSum += sample.height();
            heightSamples++;
            minHeight = Math.min(minHeight, sample.height());
            maxHeight = Math.max(maxHeight, sample.height());
        }

        /** Representative height used during clustering. The lava surface height if there is only lava so far. */
        int approxHeight() {
            return heightSamples > 0 ? heightSum / heightSamples : lavaHeightSum / lavaSamples;
        }

        void emit(int chunkX, int chunkZ, CoarseMapBuilder builder) {
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
            int averageHeight = approxHeight();
            int representativeMin = heightSamples > 0 ? minHeight : averageHeight;
            int representativeMax = heightSamples > 0 ? maxHeight : averageHeight;
            builder.putFloor(chunkX, chunkZ, kind, averageHeight, representativeMin, representativeMax);
        }
    }
}
