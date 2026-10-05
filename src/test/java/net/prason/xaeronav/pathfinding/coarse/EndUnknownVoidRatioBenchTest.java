package net.prason.xaeronav.pathfinding.coarse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>Measurement only, not a guard</b> (run explicitly with the `bench` task; same shape as {@link net.prason.xaeronav.pathfinding.astar.SearchProfileTest}).
 *
 * <p>{@link CoarseRouter} calibrates the multiplier for unexplored cells ({@code NO_DATA}) from the land:void ratio
 * of known cells (lower bound 1.6, close to land; upper bound ≈10, same as void). <b>This measures where that
 * calibration lands in practice.</b> It uses real End terrain dumps ({@code src/test/resources/end_*.txt.gz}, all
 * written out from real save data); in the End, "not on the map yet" is almost always void in reality, so it also
 * shows how optimistic the fixed 1.6 before calibration was.
 *
 * <p><b>How "land/void" is judged here.</b> The dumps contain only the real solid-block columns, so a chunk (16×16)
 * counts as land if it has even one {@link CellData#standable} cell, and as void if it has none; the same idea as
 * {@code XaeroMapReader#markVoidCells}'s definition (a column where no opaque block was seen = void). Water and lava
 * hardly appear in End terrain, so they aren't distinguished (this simplification holds precisely because the
 * target is narrowed to the End).
 */
@Tag("bench")
class EndUnknownVoidRatioBenchTest {

    private static final String[] RESOURCES = {
            "/end_terrain_columns.txt.gz",
            "/end_terrain_columns_2481.txt.gz",
            "/end_player_area.txt.gz",
    };

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, FakeCells::empty);
    }

    @Test
    void measuresHowOftenUnexploredEndChunksTurnOutToBeVoid() throws IOException {
        List<String> report = new ArrayList<>();
        int totalLand = 0;
        int totalVoid = 0;
        for (String resource : RESOURCES) {
            FakeCells cells = terrain(resource);
            SearchBounds bounds = cells.bounds();
            int[] counts = countLandAndVoidChunks(cells, bounds);
            totalLand += counts[0];
            totalVoid += counts[1];
            report.add(String.format(Locale.ROOT, "%s: land=%d void=%d voidRatio=%.3f",
                    resource, counts[0], counts[1], voidRatio(counts[0], counts[1])));
        }
        double overallVoidRatio = voidRatio(totalLand, totalVoid);
        double landRatio = 1.0 - overallVoidRatio;
        double calibrated = landRatio * 1.0 + overallVoidRatio * voidBridgeMultiplier();
        report.add("");
        report.add(String.format(Locale.ROOT, "overall: land=%d void=%d voidRatio=%.3f",
                totalLand, totalVoid, overallVoidRatio));
        report.add(String.format(Locale.ROOT,
                "calibration lower bound=1.600 / multiplier using the measured void ratio as-is=%.3f"
                        + " (CoarseRouter also mixes in a prior, so it comes out closer to the lower bound,"
                        + " VOID_BRIDGE_MULTIPLIER=%.3f)",
                calibrated, voidBridgeMultiplier()));
        String text = String.join("\n", report);
        System.out.println(text);
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("xaeronav.profileOut", System.getProperty("java.io.tmpdir")));
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve("end-unknown-void-ratio.txt"), text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same formula as {@code CoarseRouter#VOID_BRIDGE_MULTIPLIER} (that one is private, so it's recomputed here). */
    private static double voidBridgeMultiplier() {
        return (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                + ActionCosts.VOID_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;
    }

    private static double voidRatio(int land, int voidCount) {
        int total = land + voidCount;
        return total == 0 ? 0.0 : (double) voidCount / total;
    }

    /** @return {index 0: land chunk count, index 1: void chunk count} */
    private static int[] countLandAndVoidChunks(FakeCells cells, SearchBounds bounds) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int land = 0;
        int voidCount = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (chunkHasFloor(cells, bounds, chunkX, chunkZ)) {
                    land++;
                } else {
                    voidCount++;
                }
            }
        }
        return new int[] {land, voidCount};
    }

    private static boolean chunkHasFloor(FakeCells cells, SearchBounds bounds, int chunkX, int chunkZ) {
        int minX = Math.max(chunkX << 4, bounds.minX());
        int maxX = Math.min((chunkX << 4) + 15, bounds.maxX());
        int minZ = Math.max(chunkZ << 4, bounds.minZ());
        int maxZ = Math.min((chunkZ << 4) + 15, bounds.maxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (CellData.standable(cells.cell(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
