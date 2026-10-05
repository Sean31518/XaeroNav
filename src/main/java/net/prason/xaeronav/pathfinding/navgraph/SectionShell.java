package net.prason.xaeronav.pathfinding.navgraph;

import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * The cells of one section that go into the graph: only the volume within {@link #HORIZONTAL} horizontally and
 * {@link #VERTICAL} vertically of a naturally standable point ({@link NaturalColumns}).
 *
 * <p>99% of the closure is dig-through and mid-air volume; keeping all of it puts tens of millions of edges in the window. This width costs no quality
 * (measured: shelling the inside of a 160 window gives Nether 1.021 and mountains 1.000, same as no shell; applying it everywhere gives Nether 1.012, End 1.010).
 * <b>Narrower than 8 horizontally breaks End bridges</b> (1.108 at 2 horizontally).
 *
 * <p>Wider voids and lava seas only add the heights of columns a bridge could pass through ({@link NaturalColumns#bridgeCorridor}).
 */
final class SectionShell implements SectionMoves.Mask {

    static final int HORIZONTAL = 8;
    static final int VERTICAL = 2;

    private final int minX;
    private final int minZ;
    private final int minY;
    /** Per column of the section (16x16), the bits of the heights that may be kept. */
    private final long[][] allowed;

    private SectionShell(int minX, int minZ, int minY, long[][] allowed) {
        this.minX = minX;
        this.minZ = minZ;
        this.minY = minY;
        this.allowed = allowed;
    }

    /**
     * Upper limit (blocks) on searching for the middle of a bridge across a void. Used when the configured bridge length is unlimited (0). A bridge longer than the window diameter does not fit inside the window.
     */
    private static final int UNLIMITED_BRIDGE_REACH = 320;

    static SectionShell of(NaturalColumns naturals, CellSource cells, int sectionX, int sectionZ, int goalX,
                           int goalZ) {
        int words = naturals.words();
        int span = SectionMoves.SIZE + 2 * HORIZONTAL;
        int originX = sectionX * SectionMoves.SIZE - HORIZONTAL;
        int originZ = sectionZ * SectionMoves.SIZE - HORIZONTAL;
        // Dilate vertically, then horizontally in X then Z (separable, so two passes suffice)
        long[][] grown = new long[span * span][];
        for (int ax = 0; ax < span; ax++) {
            for (int az = 0; az < span; az++) {
                long[] column = new long[words];
                for (int w = 0; w < words; w++) {
                    column[w] |= naturals.word(cells, originX + ax, originZ + az, w);
                }
                long[] vertical = column.clone();
                for (int k = 1; k <= VERTICAL; k++) {
                    for (int w = 0; w < words; w++) {
                        vertical[w] |= column[w] << k;
                        vertical[w] |= column[w] >>> k;
                        // The part that dilates across word boundaries
                        if (w > 0) {
                            vertical[w] |= column[w - 1] >>> (64 - k);
                        }
                        if (w + 1 < words) {
                            vertical[w] |= column[w + 1] << (64 - k);
                        }
                    }
                }
                grown[ax + az * span] = vertical;
            }
        }
        long[][] alongX = new long[SectionMoves.SIZE * span][];
        for (int lx = 0; lx < SectionMoves.SIZE; lx++) {
            for (int az = 0; az < span; az++) {
                long[] merged = new long[words];
                for (int d = 0; d <= 2 * HORIZONTAL; d++) {
                    long[] bits = grown[lx + d + az * span];
                    for (int w = 0; w < words; w++) {
                        merged[w] |= bits[w];
                    }
                }
                alongX[lx + az * SectionMoves.SIZE] = merged;
            }
        }
        int reach = cells.maxVoidBridgeRunBlocks() > 0 ? cells.maxVoidBridgeRunBlocks() : UNLIMITED_BRIDGE_REACH;
        int lavaCap = RunCaps.stricter(cells.maxBridgeRunBlocks(), cells.maxLavaBridgeRunBlocks());
        int lavaReach = !cells.canPlaceBlocks() || !cells.lavaBridgingEnabled() ? 0
                : lavaCap > 0 ? lavaCap : UNLIMITED_BRIDGE_REACH;
        long[][] allowed = new long[SectionMoves.SIZE * SectionMoves.SIZE][];
        for (int lx = 0; lx < SectionMoves.SIZE; lx++) {
            for (int lz = 0; lz < SectionMoves.SIZE; lz++) {
                long[] merged = new long[words];
                for (int d = 0; d <= 2 * HORIZONTAL; d++) {
                    long[] bits = alongX[lx + (lz + d) * SectionMoves.SIZE];
                    for (int w = 0; w < words; w++) {
                        merged[w] |= bits[w];
                    }
                }
                long[] corridor = naturals.bridgeCorridor(cells, sectionX * SectionMoves.SIZE + lx,
                        sectionZ * SectionMoves.SIZE + lz, goalX, goalZ, reach, lavaReach);
                for (int w = 0; w < words; w++) {
                    merged[w] |= corridor[w];
                }
                allowed[lx + lz * SectionMoves.SIZE] = merged;
            }
        }
        return new SectionShell(sectionX * SectionMoves.SIZE, sectionZ * SectionMoves.SIZE, naturals.minY(),
                allowed);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        int lx = x - minX;
        int lz = z - minZ;
        int bit = y - minY;
        if (lx < 0 || lx >= SectionMoves.SIZE || lz < 0 || lz >= SectionMoves.SIZE || bit < 0) {
            return false;
        }
        long[] bits = allowed[lx + lz * SectionMoves.SIZE];
        return bit < bits.length * 64 && (bits[bit >> 6] >>> bit & 1L) != 0;
    }
}
