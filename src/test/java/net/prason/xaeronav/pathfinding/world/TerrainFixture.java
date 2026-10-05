package net.prason.xaeronav.pathfinding.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;

/**
 * Loads terrain exported from real world save data into {@link FakeCells}.
 * Exported by {@code tools/dump_terrain_columns.py}.
 *
 * <p>The format is the search bounds on line 1 ({@code minX minY minZ maxX maxY maxZ}), followed by
 * {@code x z <kind>fromY,toY <kind>fromY,toY ...}. The kind is a single {@link FakeCells} symbol character, and
 * <b>a run starting with a digit (or {@code -} for negative Y) has no kind, i.e. {@link FakeCells#STONE}</b>,
 * so fixtures exported before kinds existed still load as-is.
 * On the surface and in the Nether, water and lava determine the path itself, so those fixtures need kinds.
 *
 * <p>Settings (whether placing is allowed, bridge limit, fall tolerance, etc.) differ per real-game condition being reproduced,
 * so only building the empty {@link FakeCells} is handed to the caller.
 */
public final class TerrainFixture {

    /** Builds an empty {@link FakeCells} with the settings applied from the search bounds read. */
    @FunctionalInterface
    public interface Configure {
        FakeCells apply(SearchBounds bounds);
    }

    private TerrainFixture() {
    }

    public static FakeCells load(String resource, Configure configure) throws IOException {
        try (InputStream in = TerrainFixture.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Terrain data not found: " + resource);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] header = reader.readLine().trim().split(" ");
            FakeCells cells = configure.apply(new SearchBounds(
                    Integer.parseInt(header[0]), Integer.parseInt(header[1]), Integer.parseInt(header[2]),
                    Integer.parseInt(header[3]), Integer.parseInt(header[4]), Integer.parseInt(header[5])));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ");
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                for (int i = 2; i < parts.length; i++) {
                    String run = parts[i];
                    char kind = FakeCells.STONE;
                    if (!Character.isDigit(run.charAt(0)) && run.charAt(0) != '-') {
                        kind = run.charAt(0);
                        run = run.substring(1);
                    }
                    int comma = run.indexOf(',');
                    int from = Integer.parseInt(run.substring(0, comma));
                    int to = Integer.parseInt(run.substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, kind);
                    }
                }
            }
            if (resource.startsWith("/nether_")) {
                fillBelowLowestBlock(cells);
            }
            return cells;
        }
    }

    /** The highest standable Y in column {@code x,z}. {@link Integer#MIN_VALUE} if there is nowhere to stand. */
    public static int standableY(CellSource cells, SearchBounds bounds, int x, int z) {
        for (int y = bounds.maxY() - 1; y > bounds.minY(); y--) {
            if (CellData.standable(cells.cell(x, y - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Lowers {@code p} to the standable height at its X/Z.
     *
     * @throws IllegalStateException when there is nowhere to stand; the terrain data and coordinates are out of sync
     */
    public static BlockPos onGround(CellSource cells, SearchBounds bounds, BlockPos p) {
        int y = standableY(cells, bounds, p.getX(), p.getZ());
        if (y == Integer.MIN_VALUE) {
            throw new IllegalStateException(p.toShortString() + " is not standable, so the terrain data is out of sync");
        }
        return new BlockPos(p.getX(), y, p.getZ());
    }

    /**
     * Picks standable points inside the box with a fixed-seed RNG and builds pairs {@code minBlocks}-{@code maxBlocks} apart.
     *
     * <p><b>Scattered rather than radiating from a center</b> because looking only around one center measures just that
     * spot's terrain quirks. Both start and end are kept 16 blocks inside the box edges.
     *
     * <p><b>Fixing the seed is the point</b>: measuring different paths each run means a failure can't be reproduced, and
     * there's no telling whether a hard pair was drawn by chance or things really got worse.
     */
    public static List<BlockPos[]> randomRoutes(CellSource cells, SearchBounds bounds, long seed,
                                                int count, int minBlocks, int maxBlocks) {
        Random random = new Random(seed);
        List<BlockPos[]> routes = new ArrayList<>();
        int attempts = 0;
        while (routes.size() < count && attempts++ < 4000) {
            BlockPos start = randomStandable(cells, bounds, random);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = minBlocks + random.nextInt(maxBlocks - minBlocks + 1);
            int x = start.getX() + (int) Math.round(distance * Math.cos(angle));
            int z = start.getZ() + (int) Math.round(distance * Math.sin(angle));
            int y = standableY(cells, bounds, x, z);
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            routes.add(new BlockPos[] {start, new BlockPos(x, y, z)});
        }
        return routes;
    }

    private static BlockPos randomStandable(CellSource cells, SearchBounds bounds, Random random) {
        int x = bounds.minX() + 16 + random.nextInt(Math.max(1, bounds.maxX() - bounds.minX() - 32));
        int z = bounds.minZ() + 16 + random.nextInt(Math.max(1, bounds.maxZ() - bounds.minZ() - 32));
        int y = standableY(cells, bounds, x, z);
        return y == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }

    /**
     * Fills below the lowest block of each column with stone. The exporter ({@code tools/dump_terrain_columns.py}) puts the box floor
     * 8 below the lowest block and exports the Nether in bands, so as-is every column has an open bottom. The nav graph treats open-bottom
     * columns as void and places no nodes over lava seas (the real Nether has a bedrock floor). The End's void is real, so it is not filled.
     */
    private static void fillBelowLowestBlock(FakeCells cells) {
        SearchBounds b = cells.bounds();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                int lowest = b.minY();
                while (lowest <= b.maxY() && CellData.passableEmpty(cells.cell(x, lowest, z))) {
                    lowest++;
                }
                if (lowest > b.maxY()) {
                    continue;
                }
                for (int y = b.minY(); y < lowest; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
    }
}
