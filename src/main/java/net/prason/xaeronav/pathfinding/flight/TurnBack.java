package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.world.phys.Vec3;

/**
 * Trimming for when an extension <b>comes back to the earlier route</b> (same as trimming V-shapes when walking).
 *
 * <p>If beyond the end is a dead end, the extension goes back the way it came from the end and heads in another direction.
 * Joining that as-is makes the player go to the end and then turn back (a line that "goes east, then comes back to where
 * you are"). Rejoining where it comes back removes the whole out-and-back stretch. There's no re-search, so unlike a
 * recomputation it doesn't bounce back and forth between dead ends either.
 */
public final class TurnBack {

    /** An extension point is considered to have come back once it gets this close to the route ahead of the player (blocks). */
    private static final double RETURN_RADIUS_BLOCKS = 24.0;

    /** This length before the end is excluded from the "came back" check. The start of the extension is naturally close to it (blocks). */
    private static final double TAIL_GRACE_BLOCKS = 48.0;

    private TurnBack() {
    }

    /** Result of {@link #cut}. {@code aheadKept} runs from the player to the point where it came back, {@code rest} is everything beyond. */
    public record Cut(List<Vec3> aheadKept, List<Vec3> rest) {
    }

    /**
     * If the extension has come back to the earlier route, rejoins where it came back and <b>removes the out-and-back stretch</b>.
     *
     * <p>The join is the pair of the earliest possible point on the route ahead of the player and the latest possible point on
     * the extension, with a flyable line between them ({@code clearLine}). {@code null} if there is none.
     */
    public static Cut cut(List<Vec3> ahead, List<Vec3> extension, BiPredicate<Vec3, Vec3> clearLine) {
        if (ahead.size() < 2 || extension.size() < 2) {
            return null;
        }
        double total = 0.0;
        for (int i = 1; i < ahead.size(); i++) {
            total += ahead.get(i - 1).distanceTo(ahead.get(i));
        }
        double guarded = total - TAIL_GRACE_BLOCKS;
        if (guarded <= 0.0) {
            return null;
        }
        List<Vec3> aheadSamples = new ArrayList<>();
        List<Integer> aheadSegment = new ArrayList<>();
        aheadSamples.add(ahead.get(0));
        aheadSegment.add(0);
        double walked = 0.0;
        for (int i = 1; i < ahead.size() && walked < guarded; i++) {
            Vec3 a = ahead.get(i - 1);
            Vec3 b = ahead.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 8.0));
            for (int k = 1; k <= steps && walked + a.distanceTo(b) * k / steps <= guarded; k++) {
                aheadSamples.add(a.add(b.subtract(a).scale(k / (double) steps)));
                aheadSegment.add(i - 1);
            }
            walked += a.distanceTo(b);
        }
        List<Vec3> extensionSamples = new ArrayList<>();
        List<Integer> extensionSegment = new ArrayList<>();
        for (int i = 1; i < extension.size(); i++) {
            Vec3 a = extension.get(i - 1);
            Vec3 b = extension.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 8.0));
            for (int k = 1; k <= steps; k++) {
                extensionSamples.add(a.add(b.subtract(a).scale(k / (double) steps)));
                extensionSegment.add(i - 1);
            }
        }
        for (int ai = 0; ai < aheadSamples.size(); ai++) {
            Vec3 a = aheadSamples.get(ai);
            for (int ei = extensionSamples.size() - 1; ei >= 0; ei--) {
                Vec3 e = extensionSamples.get(ei);
                if (a.distanceTo(e) > RETURN_RADIUS_BLOCKS || !clearLine.test(a, e)) {
                    continue;
                }
                List<Vec3> kept = new ArrayList<>(ahead.subList(0, aheadSegment.get(ai) + 1));
                if (!kept.get(kept.size() - 1).equals(a)) {
                    kept.add(a);
                }
                List<Vec3> rest = new ArrayList<>();
                rest.add(e);
                rest.addAll(extension.subList(extensionSegment.get(ei) + 1, extension.size()));
                return new Cut(kept, rest);
            }
        }
        return null;
    }
}
