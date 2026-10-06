package net.prason.xaeronav.pathfinding.astar;

import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * Collects every move leaving one nav graph section (16³), <b>using exactly the same move generation as the search</b>.
 *
 * <p>The key is not re-deriving edges with a different cost model. Layers 1 and 2 did that and ended up with "a different
 * guess" rather than "a coarsened version of the true cost", which put a ceiling on guide quality.
 *
 * <p>Only cells in {@code mask} are expanded, and an edge's destination may lie outside the section.
 */
public final class SectionMoves {

    public static final int SIZE = 16;

    /** Cells that may be expanded (the shell). */
    @FunctionalInterface
    public interface Mask {
        boolean contains(int x, int y, int z);
    }

    /** Collected edges. Coordinates are {@link BlockPos#asLong}, costs are in ticks. */
    @FunctionalInterface
    public interface Sink {
        void edge(long from, long to, float cost);
    }

    private SectionMoves() {
    }

    /**
     * @param goalX the destination column. Bridges over the void are only built in the direction approaching the destination ({@link BuildMoves#addBridge}),
     *              so the edges collected here differ per destination
     * @return {@code false} if cut off
     */
    public static boolean build(CellSource cells, int sectionX, int sectionY, int sectionZ, Mask mask, int goalX,
                                int goalZ, Sink sink, BooleanSupplier cancelled) {
        LongArrayList seeds = new LongArrayList();
        int minX = sectionX * SIZE;
        int minY = sectionY * SIZE;
        int minZ = sectionZ * SIZE;
        for (int y = minY; y < minY + SIZE; y++) {
            for (int x = minX; x < minX + SIZE; x++) {
                for (int z = minZ; z < minZ + SIZE; z++) {
                    if (mask.contains(x, y, z)) {
                        seeds.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        if (seeds.isEmpty()) {
            return true;
        }
        // Run as a plain Dijkstra with weight 0. No cap: expansion is confined to the section's shell
        AStarPathfinder closure = new AStarPathfinder(cells, new SearchLimits(Integer.MAX_VALUE, Long.MAX_VALUE / 4, 0.0));
        closure.expandFilter((x, y, z) -> Math.floorDiv(x, SIZE) == sectionX && Math.floorDiv(y, SIZE) == sectionY
                && Math.floorDiv(z, SIZE) == sectionZ && mask.contains(x, y, z));
        closure.edgeSink((fx, fy, fz, fromBoating, tx, ty, tz, toBoating, edgeCost, kind) -> {
            // The search decides the underwater surcharge from the breath accounting along the path taken. As an edge cost, treat it as applying
            // when "the head is underwater at the destination and it isn't surfacing one block straight up" (same exemption as AStarPathfinder#relax)
            boolean surfacing = ty > fy && Math.abs(tx - fx) + Math.abs(tz - fz) <= 1;
            boolean submerged = !surfacing && CellData.water(cells.cell(tx, ty + 1, tz));
            sink.edge(BlockPos.asLong(fx, fy, fz), BlockPos.asLong(tx, ty, tz),
                    (float) ((submerged ? edgeCost * closure.profile.submergedTravelPenalty() : edgeCost)
                            + closure.edgeHazardPenalty(kind, tx, ty, tz)));
        });
        return closure.exhaust(seeds.elements(), seeds.size(), goalX, goalZ, cancelled) >= 0;
    }
}
