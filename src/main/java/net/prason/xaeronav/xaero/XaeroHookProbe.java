package net.prason.xaeronav.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * In runtime CI, counts that the Xaero mixin injection points were not just "attached" but actually called.
 *
 * <p>In normal launches the system property is absent, so each injection point only does a single boolean check. There's no
 * API for the CI driver to increment the count; it's recorded only when the transformed Xaero target method is actually run.
 */
public final class XaeroHookProbe {

    public static final String PROPERTY = "xaeronav-ci.runtimeHookProbe";

    public enum Point {
        WORLD_MAP_RENDER,
        MINIMAP_RENDER,
        WORLD_MAP_KEY,
        WORLD_MAP_MENU,
        WAYPOINT_MENU
    }

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(Point.values().length);

    private XaeroHookProbe() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(Point point) {
        if (ENABLED) {
            COUNTS.incrementAndGet(point.ordinal());
        }
    }

    public static boolean ran(Point point) {
        return COUNTS.get(point.ordinal()) > 0;
    }

    public static List<Point> missing() {
        List<Point> missing = new ArrayList<>();
        for (Point point : Point.values()) {
            if (!ran(point)) {
                missing.add(point);
            }
        }
        return List.copyOf(missing);
    }
}
