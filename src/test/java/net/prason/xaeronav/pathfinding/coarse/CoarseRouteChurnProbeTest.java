package net.prason.xaeronav.pathfinding.coarse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * Measurement probe (no asserts). Prints <b>how many times the long-distance route (layer 1) is redrawn right after goto,
 * while Xaero's map streams in, and how far the line swings each time</b>.
 *
 * <p>Backs up the user report "route selection is unstable at first". The mechanism is
 * "path flicker right after goto" in [[xaeronav-status]]: unloaded cells are nearly the cheapest in
 * {@link CoarseRouter}, so a big-picture route cutting straight through a not-yet-visible lava sea gets drawn,
 * then snaps to the avoiding side as regions fill in. {@code PathfindingState}'s
 * {@code redrawCoarseRouteForLoadedMap} redraws it every 3 seconds, up to 10 times.
 *
 * <p><b>How it's reproduced:</b> the saved terrain hides the chunks that "haven't arrived yet", starting from the one nearest the start,
 * with {@link CellData#ABSENT}, and opens them up step by step. At each step it runs the same
 * AVOID->ALLOW->BRIDGE ladder as {@code computeCoarseRoute} to get the intermediate-goal sequence. In the real game Xaero delivers
 * data in 512-block regions, but here the frontier's growth is modeled at chunk resolution
 * (the arrival order, i.e. by distance from the start, is the same as in the real game).
 *
 * <p><b>Values to read:</b>
 * <ul>
 *   <li>Each step's intermediate-goal sequence and its "swing" from the previous step (the max over the new polyline's points of the shortest distance to the old polyline)</li>
 *   <li>How many "lava chunks that will end up being avoided" the current big-picture route passes through
 *       (= the size of the "wrong route" the user sees)</li>
 *   <li>If publishing were changed to "wait until the known-cell ratio reaches T", how much the redraw count and lava-route steps
 *       would drop (= whether that's worth the cost of waiting)</li>
 * </ul>
 */
@Tag("slow")
class CoarseRouteChurnProbeTest {

    /** {@code CoarseMapWindow#PADDING_CHUNKS}. */
    private static final int PADDING_CHUNKS = 32;

    /** How many steps to grow the frontier in. Finer than the real game's 3-second redraws, to see the curve. */
    private static final int STAGES = 20;

    private record Scenario(String name, String resource, BlockPos start, BlockPos goal) {
    }

    @Test
    void printsHowMuchTheCoarseRouteChurnsWhileTheMapStreamsIn() throws IOException {
        List<Scenario> scenarios = List.of(
                // Same long-distance Nether route as the stall investigation of real-game run#2 (2026-09-09)
                new Scenario("run#2 long distance", "/nether_wide.txt.gz",
                        new BlockPos(-328, 64, 696), new BlockPos(-259, 64, 379)),
                // Crosses a lava ocean. If unknown cells = cheapest takes effect, a straight-through should show up here
                new Scenario("lava ocean crossing", "/nether_lava_sea.txt.gz",
                        new BlockPos(-405, 34, 440), new BlockPos(-200, 34, 640)));
        for (Scenario s : scenarios) {
            System.out.println("=== " + s.name() + " (" + s.resource() + ") ===");
            run(s);
            System.out.println();
        }
    }

    private void run(Scenario s) throws IOException {
        FakeCells all = terrain(s.resource());
        SearchBounds win = window(all.bounds(), s.start(), s.goal());
        int minChunkX = win.minX() >> 4;
        int minChunkZ = win.minZ() >> 4;
        int chunksX = (win.maxX() >> 4) - minChunkX + 1;
        int chunksZ = (win.maxZ() >> 4) - minChunkZ + 1;
        int referenceY = (s.start().getY() + s.goal().getY()) / 2;

        List<long[]> order = chunksNearestFirst(win, s.start());
        int dataChunks = 0;
        for (long[] c : order) {
            if (hasData(all, (int) c[1], (int) c[2])) {
                dataChunks++;
            }
        }

        boolean[] revealed = new boolean[chunksX * chunksZ];
        Arrays.fill(revealed, true);
        CoarseMap fullMap = LiveCoarseSampler.sample(
                new RevealedCells(all, revealed, minChunkX, minChunkZ, chunksX, chunksZ),
                win, referenceY, () -> false);
        List<BlockPos> fullRoute = route(fullMap, s.start(), s.goal()).waypoints();

        System.out.printf(Locale.ROOT, "window=%dx%d chunks withData=%d refY=%d finalIntermediateGoals=%d%n",
                chunksX, chunksZ, dataChunks, referenceY, fullRoute.size());
        System.out.println("step known% goals reached changed@ swing(blk) lavaCh");

        Arrays.fill(revealed, false);
        List<List<BlockPos>> perStage = new ArrayList<>();
        List<Double> known = new ArrayList<>();
        int cursor = 0;
        for (int stage = 0; stage < STAGES; stage++) {
            int target = (int) Math.round((stage + 1.0) / STAGES * order.size());
            while (cursor < target && cursor < order.size()) {
                long[] c = order.get(cursor++);
                revealed[((int) c[2] - minChunkZ) * chunksX + ((int) c[1] - minChunkX)] = true;
            }
            int revealedData = 0;
            for (long[] c : order.subList(0, cursor)) {
                if (hasData(all, (int) c[1], (int) c[2])) {
                    revealedData++;
                }
            }
            double knownPct = 100.0 * revealedData / dataChunks;
            CoarseMap map = LiveCoarseSampler.sample(
                    new RevealedCells(all, revealed, minChunkX, minChunkZ, chunksX, chunksZ),
                    win, referenceY, () -> false);
            CoarseRouter.Route r = route(map, s.start(), s.goal());
            List<BlockPos> wp = r.waypoints();
            List<BlockPos> before = perStage.isEmpty() ? List.of() : perStage.get(perStage.size() - 1);
            int changedAt = firstDifference(before, wp);
            double swung = before.isEmpty() ? 0 : swing(before, wp);
            System.out.printf(Locale.ROOT, "%4d %5.0f %8d %5s %5s %8.0f %6d%n",
                    stage, knownPct, wp.size(), r.reachedGoal() ? "○" : "×",
                    changedAt < 0 ? "-" : String.valueOf(changedAt), swung,
                    lavaChunksOnRoute(wp, fullMap, s.start()));
            perStage.add(wp);
            known.add(knownPct);
        }
        report(perStage, known, fullRoute, fullMap, s.start());
    }

    private static void report(List<List<BlockPos>> perStage, List<Double> known,
                               List<BlockPos> fullRoute, CoarseMap fullMap, BlockPos start) {
        int redraws = 0;
        int lastChange = -1;
        for (int i = 1; i < perStage.size(); i++) {
            if (firstDifference(perStage.get(i - 1), perStage.get(i)) >= 0) {
                redraws++;
                lastChange = i;
            }
        }
        int finalLava = lavaChunksOnRoute(perStage.get(perStage.size() - 1), fullMap, start);
        int lavaStages = 0;
        for (List<BlockPos> wp : perStage) {
            if (lavaChunksOnRoute(wp, fullMap, start) > finalLava) {
                lavaStages++;
            }
        }
        System.out.printf(Locale.ROOT,
                "redraws=%d stableAtKnown=%.0f%% (step %d) matchesFinal=%s%n",
                redraws, lastChange < 0 ? 0 : known.get(lastChange), lastChange,
                perStage.get(perStage.size() - 1).equals(fullRoute));
        System.out.printf(Locale.ROOT,
                "steps whose big-picture route passes extra \"lava to be avoided\"=%d/%d%n", lavaStages, perStage.size());
        System.out.println("If publishing waited until \"known% >= T\": remaining redraws / remaining lava steps / steps waited:");
        for (double threshold : new double[] {0, 25, 50, 75, 90}) {
            int gate = 0;
            while (gate < known.size() && known.get(gate) < threshold) {
                gate++;
            }
            int remRedraws = 0;
            int remLava = 0;
            for (int i = gate; i < perStage.size(); i++) {
                if (i > gate && firstDifference(perStage.get(i - 1), perStage.get(i)) >= 0) {
                    remRedraws++;
                }
                if (lavaChunksOnRoute(perStage.get(i), fullMap, start) > finalLava) {
                    remLava++;
                }
            }
            System.out.printf(Locale.ROOT, "  known%3.0f%%: %d / %d / %d%n",
                    threshold, remRedraws, remLava, gate);
        }
    }

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    /** Same window as {@code CoarseMapWindow#read} (bounding box of start and goal + padding). */
    private static SearchBounds window(SearchBounds world, BlockPos start, BlockPos goal) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + PADDING_CHUNKS;
        return new SearchBounds((minChunkX << 4), world.minY(), (minChunkZ << 4),
                (maxChunkX << 4) + 15, world.maxY(), (maxChunkZ << 4) + 15);
    }

    private static List<long[]> chunksNearestFirst(SearchBounds win, BlockPos start) {
        List<long[]> chunks = new ArrayList<>();
        int startChunkX = start.getX() >> 4;
        int startChunkZ = start.getZ() >> 4;
        for (int cx = win.minX() >> 4; cx <= win.maxX() >> 4; cx++) {
            for (int cz = win.minZ() >> 4; cz <= win.maxZ() >> 4; cz++) {
                long dx = cx - startChunkX;
                long dz = cz - startChunkZ;
                chunks.add(new long[] {dx * dx + dz * dz, cx, cz});
            }
        }
        chunks.sort((a, b) -> Long.compare(a[0], b[0]));
        return chunks;
    }

    /** The ladder from {@code PathfindingState#computeCoarseRoute}. Takes the result of the first policy that reaches the goal. */
    private static CoarseRouter.Route route(CoarseMap map, BlockPos start, BlockPos goal) {
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route r = CoarseRouter.findRoute(map, start, goal, true, policy);
            if (r.reachedGoal()) {
                return r;
            }
        }
        return CoarseRouter.findRoute(map, start, goal, true, CoarseRouter.BridgePolicy.BRIDGE);
    }

    /** Max over the new polyline's points of the shortest distance to the old polyline (one-sided Hausdorff, in blocks). */
    private static double swing(List<BlockPos> before, List<BlockPos> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return Double.NaN;
        }
        double worst = 0;
        for (BlockPos p : after) {
            double best = Double.MAX_VALUE;
            for (int i = 1; i < before.size(); i++) {
                best = Math.min(best, pointToSegment(p, before.get(i - 1), before.get(i)));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    private static double pointToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double abx = b.getX() - a.getX();
        double abz = b.getZ() - a.getZ();
        double apx = p.getX() - a.getX();
        double apz = p.getZ() - a.getZ();
        double len2 = abx * abx + abz * abz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (apx * abx + apz * abz) / len2));
        double dx = apx - t * abx;
        double dz = apz - t * abz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** How many chunks this big-picture route passes through that are lava on the fully visible map. */
    private static int lavaChunksOnRoute(List<BlockPos> waypoints, CoarseMap full, BlockPos start) {
        if (waypoints.isEmpty()) {
            return 0;
        }
        int count = 0;
        List<long[]> seen = new ArrayList<>();
        List<BlockPos> line = new ArrayList<>();
        line.add(start);
        line.addAll(waypoints);
        BlockPos prev = line.get(0);
        for (BlockPos wp : line) {
            int steps = Math.max(1,
                    (int) (Math.hypot(wp.getX() - prev.getX(), wp.getZ() - prev.getZ()) / 8));
            for (int step = 0; step <= steps; step++) {
                int x = prev.getX() + (wp.getX() - prev.getX()) * step / steps;
                int z = prev.getZ() + (wp.getZ() - prev.getZ()) * step / steps;
                int cx = x >> 4;
                int cz = z >> 4;
                boolean already = false;
                for (long[] c : seen) {
                    if (c[0] == cx && c[1] == cz) {
                        already = true;
                        break;
                    }
                }
                if (!already) {
                    seen.add(new long[] {cx, cz});
                    if (isLavaChunk(full, cx, cz)) {
                        count++;
                    }
                }
            }
            prev = wp;
        }
        return count;
    }

    private static boolean isLavaChunk(CoarseMap map, int cx, int cz) {
        int floors = map.floorCount(cx, cz);
        boolean lava = false;
        for (int f = 0; f < floors; f++) {
            byte kind = map.kindAtFloor(cx, cz, f);
            if (kind == CoarseMap.LAND || kind == CoarseMap.WATER) {
                return false;
            }
            if (kind == CoarseMap.LAVA || kind == CoarseMap.LAVA_MIXED) {
                lava = true;
            }
        }
        return lava;
    }

    private static int firstDifference(List<BlockPos> before, List<BlockPos> after) {
        if (before.isEmpty()) {
            return after.isEmpty() ? -1 : 0;
        }
        int shared = Math.min(before.size(), after.size());
        for (int i = 0; i < shared; i++) {
            if (!before.get(i).equals(after.get(i))) {
                return i;
            }
        }
        return before.size() != after.size() ? shared : -1;
    }

    private static boolean hasData(FakeCells all, int chunkX, int chunkZ) {
        SearchBounds b = all.bounds();
        int x = chunkX << 4;
        int z = chunkZ << 4;
        if (x + 15 < b.minX() || x > b.maxX() || z + 15 < b.minZ() || z > b.maxZ()) {
            return false;
        }
        for (int dx = 0; dx < 16; dx += 4) {
            for (int dz = 0; dz < 16; dz += 4) {
                for (int y = b.minY(); y <= b.maxY(); y += 8) {
                    if (all.cell(x + dx, y, z + dz) != CellData.ABSENT) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Hides every chunk in the window's bounding box except the opened ones with {@link CellData#ABSENT}. */
    private record RevealedCells(CellSource all, boolean[] revealed, int minChunkX, int minChunkZ,
                                 int chunksX, int chunksZ) implements CellSource {

        private boolean visible(int x, int z) {
            int lx = (x >> 4) - minChunkX;
            int lz = (z >> 4) - minChunkZ;
            return lx >= 0 && lx < chunksX && lz >= 0 && lz < chunksZ && revealed[lz * chunksX + lx];
        }

        @Override
        public long cell(int x, int y, int z) {
            return visible(x, z) ? all.cell(x, y, z) : CellData.ABSENT;
        }

        @Override
        public int openSkyY(int x, int z) {
            return visible(x, z) ? all.openSkyY(x, z) : Integer.MAX_VALUE;
        }

        @Override
        public boolean isInBounds(int x, int y, int z) {
            return all.isInBounds(x, y, z);
        }

        @Override
        public SearchBounds bounds() {
            return all.bounds();
        }

        @Override
        public boolean canPlaceBlocks() {
            return all.canPlaceBlocks();
        }

        @Override
        public boolean bridgingAllowedBySettings() {
            return all.bridgingAllowedBySettings();
        }

        @Override
        public int placedBlockBudget() {
            return all.placedBlockBudget();
        }

        @Override
        public boolean jumpGapEnabled() {
            return all.jumpGapEnabled();
        }

        @Override
        public boolean lavaBridgingEnabled() {
            return all.lavaBridgingEnabled();
        }

        @Override
        public int maxBridgeRunBlocks() {
            return all.maxBridgeRunBlocks();
        }

        @Override
        public int maxLavaBridgeRunBlocks() {
            return all.maxLavaBridgeRunBlocks();
        }

        @Override
        public int maxVoidBridgeRunBlocks() {
            return all.maxVoidBridgeRunBlocks();
        }

        @Override
        public int maxSubmergedTicks() {
            return all.maxSubmergedTicks();
        }

        @Override
        public int maxFallDamagePoints() {
            return all.maxFallDamagePoints();
        }

        @Override
        public int fatalFallBlocks() {
            return all.fatalFallBlocks();
        }

        @Override
        public boolean avoidRiskyJumps() {
            return all.avoidRiskyJumps();
        }

        @Override
        public boolean strictLimits() {
            return all.strictLimits();
        }

        @Override
        public double minDescentTicksPerBlock() {
            return all.minDescentTicksPerBlock();
        }

        @Override
        public double minDescentTicksPerBlock(int maxFallDamagePoints) {
            return all.minDescentTicksPerBlock(maxFallDamagePoints);
        }

        @Override
        public boolean canMlgWaterBucket() {
            return all.canMlgWaterBucket();
        }

        @Override
        public boolean boatAvailable() {
            return all.boatAvailable();
        }

        @Override
        public boolean ridingBoat() {
            return all.ridingBoat();
        }
    }
}
