package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

/**
 * Cumulative counters carried across segment boundaries. Each segment of a path is solved by a separate
 * search, so without carrying them over <b>the caps reset once per segment</b>.
 *
 * <p>Pairs with the same-named fields of {@link PathNode} and goes straight into the search's start node.
 *
 * @param bridgeRun   Run length so far when the start is already partway along a bridge. Splitting a lava sea into 4 segments
 *                    would let a 120-block bridge through even with a cap of 30
 * @param placedBlocks Number of footholds <b>already committed to be used</b> further along this path. The inventory budget
 *                    ({@link Tolerances#placedBlockBudget()}) is recomputed from the blocks on hand on every search,
 *                    so without carrying this over each segment gets the full budget; long routes submit a search per
 *                    segment, producing <b>paths that place many times the blocks on hand in total</b>
 */
public record Carryover(int bridgeRun, int placedBlocks) {

    public static final Carryover NONE = new Carryover(0, 0);

    /** Carryover passed to a search solving the continuation of an already-fixed step sequence. */
    public static Carryover after(List<PathStep> steps) {
        return new Carryover(trailingBridgeRun(steps), placements(steps, 0));
    }

    /** Number of consecutive bridge blocks at the end of the step sequence. */
    public static int trailingBridgeRun(List<PathStep> steps) {
        int run = 0;
        for (int i = steps.size() - 1; i >= 0 && steps.get(i).bridging(); i--) {
            run++;
        }
        return run;
    }

    /**
     * Number of footholds to be placed from step {@code from} onward.
     *
     * <p><b>Counts "how many from here on", not "how many over the whole path".</b> Those behind have already
     * been placed and taken out of the inventory, so matching against the freshly recounted blocks on hand
     * requires looking only at what lies ahead; adding them would keep saying "not enough" forever.
     * That's why the search budget (this record) and the HUD's shortage warning share the same counting.
     */
    public static int placements(List<PathStep> steps, int from) {
        int placed = 0;
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            if (steps.get(i).bridging()) {
                placed++;
            }
        }
        return placed;
    }
}
