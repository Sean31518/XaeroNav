package net.prason.xaeronav.client;

import java.util.function.Function;

import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * Puts "rebuild only when the path changes" in one place.
 *
 * <p>The guidance display, map dots, in-world rendering, and HUD warnings all derive something from the path and
 * use it every frame. The derived result doesn't change as long as the path is the same, so rebuilding it every
 * frame is pointless. Four places each held this same decision in their own way, so every new derived value
 * meant writing "compare with the previous path" from scratch, and forgetting the comparison silently fell back
 * to recomputing every frame.
 *
 * <p>The comparison uses {@code ==}. A {@link PathResult} is created fresh for every search and is a separate
 * instance even when the contents are equal, so reference equality directly answers "is this the same path?".
 *
 * <p>Not thread-safe. Use it from only one of the render thread or the client thread.
 */
final class PathCache<T> {

    private PathResult source;
    private T value;

    T get(PathResult result, Function<PathResult, T> build) {
        if (source != result) {
            source = result;
            value = build.apply(result);
        }
        return value;
    }
}
