package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * A whole route from the player to the destination, planned at once ({@link FullRoutePlanner}), and the planning
 * itself: the same detailed search as the live route, run leg after leg along a corridor until the destination.
 *
 * <p>Legs follow the long-distance route's waypoints (or a straight line without one), split every {@link #LEG_BLOCKS}.
 * Each leg starts where the previous one ended and carries over the blocks already placed, exactly like the live route
 * extends itself while walking. What differs is only where the blocks come from: the singleplayer save lets the
 * terrain past render distance be read too.
 *
 * <p>Doesn't touch {@code Minecraft}, so it can be tested on terrain made up in the test.
 *
 * @param points       block positions of the route, from the start
 * @param ticks        estimated ticks from the start to each point
 * @param complete     whether it reaches the destination; otherwise it stops where the readable terrain or the
 *                     search ran out
 * @param steps        the moves themselves, risk-annotated like the live route's, one per point after the start; this is
 *                     what the live route takes over ({@link PathfindingState#adoptFullRoute})
 */
record RoutePreview(List<BlockPos> points, double[] ticks, boolean complete, List<PathStep> steps) {

    static final RoutePreview NONE = new RoutePreview(List.of(), new double[0], false);

    /** Distance between leg targets along the corridor (blocks). The live route aims about this far per search too. */
    static final int LEG_BLOCKS = 80;

    /** Horizontal radius within which an intermediate leg target counts as reached. */
    static final int LEG_TARGET_RADIUS = 8;

    /** Radius at the destination itself: its height may only be the map's estimate. */
    static final int GOAL_RADIUS = 2;

    /** A leg that gets no further than this (blocks) ends the plan: the way on isn't readable or doesn't exist. */
    static final double MIN_LEG_PROGRESS = 6.0;

    /** Attempts at one target before moving on to the next one. */
    private static final int TRIES_PER_TARGET = 3;

    /** Per-leg budget. Smaller than the live search: many legs run back to back on a background thread. */
    static final SearchLimits LEG_LIMITS = new SearchLimits(150_000, 3_000, 1.5);

    RoutePreview {
        points = List.copyOf(points);
        steps = List.copyOf(steps);
    }

    /** Points only, without the moves (enough for drawing). */
    RoutePreview(List<BlockPos> points, double[] ticks, boolean complete) {
        this(points, ticks, complete, List.of());
    }

    boolean isEmpty() {
        return points.isEmpty();
    }

    /** Estimated ticks from the point nearest to {@code (x, z)} to the end. */
    double ticksFrom(int x, int z) {
        if (points.isEmpty()) {
            return 0.0;
        }
        int nearest = nearestIndex(x, z);
        return ticks[ticks.length - 1] - ticks[nearest];
    }

    /** Index of the point horizontally nearest to {@code (x, z)}. */
    int nearestIndex(int x, int z) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            long dx = points.get(i).getX() - x;
            long dz = points.get(i).getZ() - z;
            long distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * Leg targets along the polyline: a point every {@code spacing} blocks of horizontal distance, and the last point
     * itself. The Y of each target is taken from the terrain where it's known ({@code openSkyY}); otherwise from the
     * polyline.
     */
    static List<BlockPos> legTargets(CellSource view, List<BlockPos> polyline, int spacing) {
        List<BlockPos> targets = new ArrayList<>();
        if (polyline.size() < 2) {
            targets.addAll(polyline.subList(Math.min(1, polyline.size()), polyline.size()));
            return targets;
        }
        double carried = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            BlockPos from = polyline.get(i - 1);
            BlockPos to = polyline.get(i);
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            double at = spacing - carried;
            while (at < length) {
                int x = (int) Math.round(from.getX() + dx * at / length);
                int z = (int) Math.round(from.getZ() + dz * at / length);
                int y = from.getY() + (int) Math.round((to.getY() - from.getY()) * at / length);
                targets.add(onSurface(view, x, y, z));
                at += spacing;
            }
            carried = length - (at - spacing);
        }
        targets.add(polyline.get(polyline.size() - 1));
        return targets;
    }

    private static BlockPos onSurface(CellSource view, int x, int fallbackY, int z) {
        int sky = view.openSkyY(x, z);
        return new BlockPos(x, sky == Integer.MAX_VALUE ? fallbackY : sky, z);
    }

    /**
     * Plans leg after leg from {@code start} through {@code targets}. Reports every finished leg to {@code progress}
     * (so the map fills in as it goes) and returns the final result.
     *
     * @param legViews a fresh view per leg. A view remembers every cell it was asked for; over a few dozen legs one
     *                 shared view would grow to millions of entries
     */
    static RoutePreview plan(Supplier<? extends CellSource> legViews, BlockPos start, List<BlockPos> targets,
                             BooleanSupplier cancelled, Consumer<RoutePreview> progress) {
        List<BlockPos> points = new ArrayList<>();
        List<Double> ticks = new ArrayList<>();
        List<PathStep> moves = new ArrayList<>();
        points.add(start);
        ticks.add(0.0);
        BlockPos from = start;
        int bridgeRun = 0;
        int placed = 0;
        double total = 0.0;
        for (int t = 0; t < targets.size(); t++) {
            boolean last = t == targets.size() - 1;
            BlockPos target = targets.get(t);
            int radius = last ? GOAL_RADIUS : LEG_TARGET_RADIUS;
            boolean reached = false;
            for (int attempt = 0; attempt < TRIES_PER_TARGET && !reached; attempt++) {
                if (cancelled.getAsBoolean()) {
                    return snapshot(points, ticks, false, moves);
                }
                CellSource legView = legViews.get();
                PathResult leg = new AStarPathfinder(legView, LEG_LIMITS)
                        .search(from, target, cancelled, new Carryover(bridgeRun, placed), radius);
                // The same risk marks as the live route, so auto-walk and the HUD warn about this one too
                leg = PathSafetyChecker.annotate(legView, leg);
                List<PathStep> steps = leg.steps();
                if (steps.isEmpty() || horizontal(from, steps.get(steps.size() - 1).pos()) < MIN_LEG_PROGRESS
                        && !leg.complete()) {
                    return snapshot(points, ticks, false, moves);
                }
                for (PathStep step : steps) {
                    total += step.cost();
                    moves.add(step);
                    points.add(step.pos());
                    ticks.add(total);
                    bridgeRun = step.bridging() ? bridgeRun + 1 : 0;
                    placed += step.bridging() ? 1 : 0;
                }
                from = steps.get(steps.size() - 1).pos();
                reached = leg.complete();
                progress.accept(snapshot(points, ticks, false, moves));
            }
            if (!reached && last) {
                return snapshot(points, ticks, false, moves);
            }
        }
        RoutePreview done = snapshot(points, ticks, true, moves);
        progress.accept(done);
        return done;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static RoutePreview snapshot(List<BlockPos> points, List<Double> ticks, boolean complete,
                                         List<PathStep> moves) {
        double[] array = new double[ticks.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = ticks.get(i);
        }
        return new RoutePreview(points, array, complete, moves);
    }
}
