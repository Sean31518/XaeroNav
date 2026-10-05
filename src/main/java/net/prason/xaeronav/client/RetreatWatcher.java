package net.prason.xaeronav.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * Detects "moved away again after getting closest to the destination". Exists so that the
 * <b>roughly 300-block round trip</b> seen in-game in the Nether can be traced from the log next time it happens.
 *
 * <p>It can't be judged from the route's shape. Each segment always gets a correct route; what's wrong is the
 * <b>movement across plans</b>, "enter the eastern corridor, hit a dead end, turn back west"
 * (in-game 2026-09-18: the distance to the destination grew 189→229→242 blocks).
 *
 * <p>Routes that make large detours around lava seas or the void are progressing correctly while moving away from the destination.
 * So up to {@link #RETREAT_BLOCKS} it stays quiet, treating it as an ordinary detour.
 *
 * <p>This only holds the judgment; {@code PathfindingState} writes the log (the same split as {@link StuckTracker}).
 */
final class RetreatWatcher {

    /**
     * Record once this far from the closest approach (blocks).
     *
     * <p>The in-game round trip was about 300 blocks, while correct detours (going around the edge of a lava sea) measured
     * at most in the 60s (worst retreats of 63/61/24 on three model Nethers). This sits between the two.
     */
    static final double RETREAT_BLOCKS = 80.0;

    /** Interval (blocks) so a single retreat isn't written many times. */
    private static final double REPORT_STEP_BLOCKS = 32.0;

    private double closest = Double.MAX_VALUE;
    private @Nullable BlockPos closestAt;
    /** The distance at the last record. 0 if nothing has been recorded yet. */
    private double reportedDistance;

    /**
     * A retreat worth recording. {@code closest} is the horizontal distance at the closest approach, {@code distance} the current horizontal distance.
     */
    record Retreat(BlockPos at, double distance, BlockPos closestAt, double closest) {

        double retreated() {
            return distance - closest;
        }
    }

    void reset() {
        closest = Double.MAX_VALUE;
        closestAt = null;
        reportedDistance = 0;
    }

    /**
     * Reports the current position. Returns a retreat worth recording if one has occurred.
     *
     * <p>On getting closer, updates the closest approach and resets the recording interval; anything after that counts as a separate retreat.
     */
    @Nullable Retreat observe(BlockPos at, BlockPos goal) {
        double distance = horizontal(at, goal);
        if (distance < closest) {
            closest = distance;
            closestAt = at;
            reportedDistance = 0;
            return null;
        }
        if (closestAt == null || distance - closest < RETREAT_BLOCKS) {
            return null;
        }
        if (reportedDistance > 0 && distance - reportedDistance < REPORT_STEP_BLOCKS) {
            return null;
        }
        reportedDistance = distance;
        return new Retreat(at, distance, closestAt, closest);
    }

    /**
     * Whether this guidance <b>takes you even farther than the closest point you've reached</b>.
     *
     * <p>Where {@link #observe} "records what happened", this is used to "keep it from happening".
     * The boundary is the same {@link #RETREAT_BLOCKS}: a retreat worth recording must not be adopted as guidance either.
     *
     * <p><b>Don't look only at the end.</b> When the player was dragged back 80 blocks in-game (2026-09-19 01:10), the end of
     * the route was 55 blocks from the closest approach (inside the band). What moved away were <b>the positions stepped on along the way</b>,
     * and the player is made to walk those. The model's "correct detours are at worst 67 blocks" was also measured as the maximum
     * over all points on the route, so this uses the same measure.
     *
     * <p>If nothing has been observed yet there's no reference, so {@code false}; the first route must not be rejected.
     */
    boolean leadsAway(Iterable<BlockPos> positions, BlockPos goal) {
        if (closestAt == null) {
            return false;
        }
        double limit = closest + RETREAT_BLOCKS;
        for (BlockPos position : positions) {
            if (horizontal(position, goal) >= limit) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal distance at the closest approach. {@link Double#MAX_VALUE} if nothing has been observed yet. */
    double closest() {
        return closest;
    }

    /** The position of the closest approach. {@code null} if nothing has been observed yet. */
    @Nullable BlockPos closestAt() {
        return closestAt;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
