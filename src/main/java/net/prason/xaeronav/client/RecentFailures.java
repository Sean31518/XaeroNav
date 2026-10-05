package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Briefly remembers the cells {@link PathValidator} just judged as not holding.
 * The next search avoids them via {@code AvoidedCellSource}.
 *
 * <p><b>Without this the loop never closes.</b> If the search's cell check and the recheck disagree at the same coordinate,
 * the replanned path goes through the same cell again and is immediately judged invalid again. Neither {@code Splice}'s retry gate
 * nor {@code SeamRepair}'s once-per-seam limit can stop <b>the search itself from picking that cell again</b>;
 * in an in-game report (#47) that loop kept spinning at the same coordinate for 14 seconds.
 *
 * <p>Remembering only temporarily is enough. If the disagreement is caused by a real terrain change, the next time it's looked at
 * the search side reaches the same verdict, so there's no longer any need to avoid it. {@link #TTL_NANOS} is long enough to reliably span
 * the loop period seen in in-game logs (2-5 seconds).
 *
 * <p><b>Main thread only.</b> Recording happens in the path recheck (tick), and reading when building the {@code ChunkView}
 * right before dispatching a search; both are confined to the main thread ({@code ChunkView#capture} is
 * main-thread only, so the reading side can't leave it in principle).
 */
final class RecentFailures {

    /** How long to remember. */
    private static final long TTL_NANOS = 15_000_000_000L;

    /**
     * Number of cells remembered at once. When it overflows, the oldest are dropped (same idea as {@code SeamRepair#QUEUE_LIMIT}).
     *
     * <p>It's not allowed to grow without limit because the more cells are avoided, <b>the more options are taken away from the search</b>.
     * What we want to stop is a loop sucked into the same single point, not sealing off terrain broadly.
     */
    private static final int LIMIT = 8;

    /** Cell that didn't hold → time recorded. Kept in insertion order; on overflow, dropped from the front. */
    private final Map<BlockPos, Long> failures = new LinkedHashMap<>();

    /** Remembers this cell as not holding. If already remembered, updates the time. */
    void note(BlockPos cell) {
        failures.remove(cell);
        failures.put(cell, System.nanoTime());
        Iterator<BlockPos> oldest = failures.keySet().iterator();
        while (failures.size() > LIMIT && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Cells to avoid right now. Expired ones are dropped here. */
    List<BlockPos> avoided() {
        long now = System.nanoTime();
        failures.values().removeIf(at -> now - at > TTL_NANOS);
        return new ArrayList<>(failures.keySet());
    }

    /** On a destination change, drops the remembered cells. */
    void clear() {
        failures.clear();
    }
}
