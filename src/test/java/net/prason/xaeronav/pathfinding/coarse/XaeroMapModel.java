package net.prason.xaeronav.pathfinding.coarse;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * Feeds <b>the floors Xaero would presumably have saved</b> from a fixture's terrain into a {@link VoxelTerrain}.
 *
 * <p>Calls {@link VoxelTerrain#markFloor} in the same shape as production's {@code XaeroMapReader#forEachCaveFloor}
 * (for each column, one "Y of the topmost solid block" per cave layer). Real data
 * holds <b>only the floor's Y</b>; whether the blocks above and below are rock or air is unknown. This is a tool
 * for keeping the promise that an ideal guide built from the full 3D terrain is never treated as production's guarantee.
 *
 * <p>{@link #fill}'s {@code keepFraction} drops <b>unvisited chunks</b>. Xaero's map only fills in
 * where you have walked, so whether the guide succeeds depends on "does it hold up even when the area around the destination is missing".
 */
public final class XaeroMapModel {

    /**
     * Maximum number of floors picked from one column. Xaero's cave layers are split into slices
     * {@code CAVE_MODE_DEPTH} (30) blocks thick, and the layer number is {@code caveStart >> 4}, so at Nether
     * height (0..127) there are at most 8.
     *
     * <p><b>All 8 are present only when "that column was walked at every height band"</b>; real saves are much thinner.
     * The per-layer breakdown in a real-game log (2026-09-09) was {@code L4=3408} with all others 0: <b>just one layer</b>.
     * To measure at that thinness, use {@link #fill(VoxelTerrain, CellSource, int[], double, long)}.
     */
    private static final int MAX_LAYERS = 8;

    /** Xaero's default {@code CAVE_MODE_DEPTH}. One layer only holds a slice this thick. */
    private static final int CAVE_MODE_DEPTH = 30;

    /** Same subsampling as production's {@code XaeroMapReader#SAMPLE_STEP}. */
    private static final int SAMPLE_STEP = 2;

    private XaeroMapModel() {
    }

    /**
     * A box whose Y is <b>exactly the given range</b>. As long as the actual Nether height (0..127) is passed,
     * it has the same shape as production (production uses the floor range plus margin, excluding ceiling-less air).
     *
     * <p>To measure the same shape as production including environments whose dimension is taller than the Nether,
     * use {@link #grid(CellSource, BlockPos, BlockPos, LevelHeightAccessor, int[], double, long)}.
     */
    public static SearchBounds guideBox(BlockPos start, BlockPos goal, int minY, int maxY) {
        return new SearchBounds(
                Math.min(start.getX(), goal.getX()) - VoxelTerrain.MARGIN_BLOCKS, minY,
                Math.min(start.getZ(), goal.getZ()) - VoxelTerrain.MARGIN_BLOCKS,
                Math.max(start.getX(), goal.getX()) + VoxelTerrain.MARGIN_BLOCKS, maxY,
                Math.max(start.getZ(), goal.getZ()) + VoxelTerrain.MARGIN_BLOCKS);
    }

    /** A {@link LevelHeightAccessor} holding only the dimension's height, so production's {@code boxFor} can be used as-is. */
    public static LevelHeightAccessor height(int minY, int maxY) {
        return LevelHeightAccessor.create(minY, maxY - minY + 1);
    }

    /**
     * Builds the grid in <b>the same two passes as production ({@code NetherVoxelGuide#start})</b>. The first pass
     * measures the Ys that have floors, lets {@link VoxelTerrain#boxFor} choose the box, then feeds in the floors.
     *
     * <p>This is the only place that can measure environments where the dimension is taller than the walkable height (= the shape seen in the real game).
     *
     * @param caveLayers cave layer numbers. {@code null} gives the dense model that picks up to 8 from the full height
     */
    public static VoxelTerrain grid(CellSource all, BlockPos start, BlockPos goal,
                                     LevelHeightAccessor level, int[] caveLayers,
                                     double keepFraction, long seed) {
        SearchBounds full = guideBox(start, goal, level.getMinBuildHeight(),
                level.getMaxBuildHeight() - 1);
        int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        scan(all, full, caveLayers, keepFraction, seed, (x, z, floorTopY, lava) -> {
            range[0] = Math.min(range[0], floorTopY);
            range[1] = Math.max(range[1], floorTopY);
        });
        if (range[0] > range[1]) {
            return null;
        }
        VoxelTerrain terrain = VoxelTerrain.of(
                VoxelTerrain.boxFor(level, start, goal, range[0], range[1]), true);
        if (terrain != null) {
            scan(all, full, caveLayers, keepFraction, seed, terrain::markFloor);
        }
        return terrain;
    }

    /**
     * Feeds {@link #fill} into the {@link #guideBox} terrain and builds the same {@link VoxelCostToGo} as production.
     *
     * <p>The destination is the <b>raw, un-resnapped coordinate</b>. Production ({@code NetherVoxelGuide}) does the same:
     * when the guide is built the destination chunk isn't loaded yet, so it can't go through {@code StanceFinder}.
     * An offset of a few blocks is absorbed by {@code VoxelCostToGo}'s origin search.
     */
    public static VoxelCostToGo guide(CellSource all, BlockPos start, BlockPos goal, int minY, int maxY,
                                       double keepFraction, long seed) {
        VoxelTerrain terrain = VoxelTerrain.of(guideBox(start, goal, minY, maxY), true);
        fill(terrain, all, keepFraction, seed);
        return VoxelCostToGo.build(terrain, goal, () -> false);
    }

    /** Floors when every chunk counts as visited. */
    public static void fill(VoxelTerrain terrain, CellSource all) {
        fill(terrain, all, 1.0, 0L);
    }

    /**
     * Floors when only {@code keepFraction} of chunks were visited. Dropped chunks report
     * no floors at all (= Xaero doesn't have that region yet).
     */
    public static void fill(VoxelTerrain terrain, CellSource all, double keepFraction, long seed) {
        scan(all, terrain.box(), null, keepFraction, seed, terrain::markFloor);
    }

    /**
     * <b>Floors when the player has walked only certain height bands.</b> Cave layer {@code L} holds
     * <b>only the single topmost floor</b> in the slice {@code [L*16 - CAVE_MODE_DEPTH, L*16]}, and
     * layers for unwalked height bands don't exist at all (no tile).
     *
     * <p>Whereas {@link #fill(VoxelTerrain, CellSource, double, long)} picks up to 8 from the full height,
     * this matches the thinness of real saves. <b>Without measuring here, real-game breakdowns aren't caught as regressions</b>:
     * with the 8-floor model the guide inflates only 2.57x versus 5.84x in the real game,
     * so the very condition that clogs the search isn't reproduced.
     */
    public static void fill(VoxelTerrain terrain, CellSource all, int[] caveLayers,
                             double keepFraction, long seed) {
        scan(all, terrain.box(), caveLayers, keepFraction, seed, terrain::markFloor);
    }

    /**
     * Scans the XZ range of {@code box} column by column and reports floors. If {@code caveLayers} is {@code null},
     * up to {@link #MAX_LAYERS} from the full height; otherwise one slice per layer.
     */
    private static void scan(CellSource all, SearchBounds box, int[] caveLayers,
                              double keepFraction, long seed, XaeroMapReader.FloorVisitor visitor) {
        SearchBounds world = all.bounds();
        Random random = new Random(seed);
        Map<Long, Boolean> visited = new HashMap<>();
        for (int x = Math.max(box.minX(), world.minX()); x <= Math.min(box.maxX(), world.maxX());
                x += SAMPLE_STEP) {
            for (int z = Math.max(box.minZ(), world.minZ()); z <= Math.min(box.maxZ(), world.maxZ());
                    z += SAMPLE_STEP) {
                long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                if (!visited.computeIfAbsent(chunk, key -> random.nextDouble() < keepFraction)) {
                    continue;
                }
                if (caveLayers == null) {
                    scanColumn(visitor, all, box, world, x, z);
                    continue;
                }
                for (int layer : caveLayers) {
                    scanSlice(visitor, all, box, world, x, z, layer);
                }
            }
        }
    }

    /** Walks one layer's slice from the top and reports only the first floor found. */
    private static void scanSlice(XaeroMapReader.FloorVisitor visitor, CellSource all,
                                   SearchBounds box, SearchBounds world, int x, int z, int caveLayer) {
        boolean airSeen = false;
        int top = Math.min(caveLayer * 16, Math.min(world.maxY(), box.maxY()));
        int bottom = Math.max(caveLayer * 16 - CAVE_MODE_DEPTH, Math.max(world.minY(), box.minY()));
        for (int y = top; y >= bottom; y--) {
            long cell = all.cell(x, y, z);
            if (!CellData.present(cell)) {
                return;
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                continue;
            }
            if (airSeen) {
                visitor.floor(x, z, y, CellData.lava(cell));
                return;
            }
        }
    }

    /**
     * Walks one column from the top and reports solid surfaces below air as floors.
     * Same rule as {@code LiveCoarseSampler#sampleColumnFloors}.
     */
    private static void scanColumn(XaeroMapReader.FloorVisitor visitor, CellSource all,
                                    SearchBounds box, SearchBounds world, int x, int z) {
        boolean airSeen = false;
        int found = 0;
        int top = Math.min(world.maxY(), box.maxY());
        int bottom = Math.max(world.minY(), box.minY());
        for (int y = top; y >= bottom && found < MAX_LAYERS; y--) {
            long cell = all.cell(x, y, z);
            if (!CellData.present(cell)) {
                return;
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                continue;
            }
            if (airSeen) {
                visitor.floor(x, z, y, CellData.lava(cell));
                found++;
                airSeen = false;
            }
        }
    }
}
