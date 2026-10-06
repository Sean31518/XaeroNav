package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * The dotted line of a long-distance route must not get detached from the current position on the map.
 *
 * <p>The coarse route is not re-planned unless the destination changes, so connecting only the intermediate targets
 * in order leaves the start of the dotted line stranded at "where you were when the route was computed" as you
 * advance. On the map it just looks like a stale route left behind, and it repeatedly led to misdiagnosis, so the
 * continuity of the start is pinned down here.
 */
class MapPathOverlayTest {

    private static final int Y = 64;

    /** Marker size is set in on-screen pixels. Tests use a scale of 1 block = 1 pixel. */
    private static final double PIXELS_PER_BLOCK = 1.0;

    /** Picks up only the coarse route's dotted line ({@link PathColors#COARSE_ROUTE}). */
    private static List<BlockPos> coarseDots(MapPathOverlay.Snapshot snapshot) {
        List<BlockPos> dots = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x1, z1, x2, z2, red, green, blue) -> {
            if (red == PathColors.COARSE_ROUTE[0] && green == PathColors.COARSE_ROUTE[1]
                    && blue == PathColors.COARSE_ROUTE[2]) {
                dots.add(new BlockPos(x1, Y, z1));
            }
        }, PIXELS_PER_BLOCK);
        return dots;
    }

    /** Picks up only the rectangles that make up the destination marker. */
    private static List<int[]> markerRects(MapPathOverlay.Snapshot snapshot) {
        return markerRects(snapshot, PIXELS_PER_BLOCK);
    }

    private static List<int[]> markerRects(MapPathOverlay.Snapshot snapshot, double pixelsPerBlock) {
        List<int[]> rects = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x1, z1, x2, z2, red, green, blue) -> {
            if (isMarkerColor(red, green, blue)) {
                rects.add(new int[] {x1, z1, x2, z2});
            }
        }, pixelsPerBlock);
        return rects;
    }

    private static boolean isMarkerColor(float red, float green, float blue) {
        for (float[] color : List.of(PathColors.GOAL_MARKER, PathColors.GOAL_MARKER_HOLE,
                PathColors.GOAL_MARKER_OUTLINE)) {
            if (red == color[0] && green == color[1] && blue == color[2]) {
                return true;
            }
        }
        return false;
    }

    /** The set of blocks covered by the rectangles that make up the marker. */
    private static Set<BlockPos> markerBlocks(MapPathOverlay.Snapshot snapshot) {
        Set<BlockPos> blocks = new HashSet<>();
        for (int[] rect : markerRects(snapshot)) {
            for (int x = rect[0]; x < rect[2]; x++) {
                for (int z = rect[1]; z < rect[3]; z++) {
                    blocks.add(new BlockPos(x, Y, z));
                }
            }
        }
        return blocks;
    }

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    private static boolean hasDotBetween(List<BlockPos> dots, int fromX, int toX) {
        return dots.stream().anyMatch(dot -> dot.getX() > fromX && dot.getX() < toX);
    }

    @Test
    void coarseRouteReachesBackToThePlayerWhenNoDetailPathExists() {
        BlockPos player = new BlockPos(0, Y, 0);
        List<BlockPos> waypoints = List.of(new BlockPos(100, Y, 0), new BlockPos(200, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(null, new BlockPos(200, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertTrue(hasDotBetween(coarseDots(snapshot), 0, 100),
                "the segment to the first intermediate target is not drawn and the dotted line floats away from the player");
    }

    @Test
    void coarseRouteContinuesFromTheEndOfTheDetailPath() {
        BlockPos player = new BlockPos(0, Y, 0);
        PathResult detail = path(List.of(new BlockPos(0, Y, 0), new BlockPos(25, Y, 0), new BlockPos(50, Y, 0)));
        List<BlockPos> waypoints = List.of(new BlockPos(150, Y, 0), new BlockPos(250, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(250, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertTrue(hasDotBetween(coarseDots(snapshot), 50, 150),
                "the end of the detailed route is not connected to the first intermediate target");
    }

    /**
     * While the detailed route does not follow the intermediate targets (during layer-2 refinement it heads straight
     * for the real destination), the passed-target marker does not advance, so the first unpassed target stays far
     * behind the current position. Connecting them in order makes a line running backward alongside the route's own
     * line, so it just looks like two yellow dotted lines.
     */
    @Test
    void coarseRouteDoesNotRunBackToWaypointsAlreadyBehind() {
        BlockPos player = new BlockPos(300, Y, 0);
        PathResult detail = path(List.of(new BlockPos(300, Y, 0), new BlockPos(325, Y, 0), new BlockPos(350, Y, 0)));
        List<BlockPos> waypoints = List.of(
                new BlockPos(0, Y, 0), new BlockPos(100, Y, 0), new BlockPos(200, Y, 0),
                new BlockPos(300, Y, 0), new BlockPos(400, Y, 0), new BlockPos(500, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(500, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertFalse(hasDotBetween(coarseDots(snapshot), 0, 340),
                "the dotted line doubles back to an intermediate target behind the route's end, showing two yellow dotted lines");
    }

    /**
     * When the end of the route has passed an intermediate target and the next intermediate target is far away.
     *
     * <p>With a "advance only while the next point is close" approach, the next one is far, so it stops at the passed
     * point and draws one <b>backward line</b> from there to the end. The intermediate target list swaps between layer
     * 1's raw list and layer 2's refined version, so it cannot be cut by index (it is not re-laid when
     * {@code PathfindingState#pathWorthKeeping} keeps the route), and the only way to cut it is by projecting onto the
     * polyline. User report: "the yellow dotted line does not go away when it updates".
     */
    @Test
    void coarseRouteDoesNotDrawBackToAWaypointTheDetailPathOvershot() {
        BlockPos player = new BlockPos(120, Y, 0);
        PathResult detail = path(List.of(new BlockPos(120, Y, 0), new BlockPos(150, Y, 0)));
        List<BlockPos> waypoints = List.of(
                new BlockPos(0, Y, 0), new BlockPos(100, Y, 0), new BlockPos(400, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(400, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertFalse(hasDotBetween(coarseDots(snapshot), 99, 150),
                "the dotted line doubles back to a passed intermediate target");
    }

    /** Skipping double-backs must not trim a route that really does go backward (its start has overshot). */
    @Test
    void coarseRouteKeepsAGenuineBacktrack() {
        BlockPos player = new BlockPos(120, Y, 0);
        List<BlockPos> waypoints = List.of(new BlockPos(100, Y, 0), new BlockPos(100, Y, 200));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(null, new BlockPos(100, Y, 200), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertTrue(coarseDots(snapshot).stream().anyMatch(dot -> dot.getZ() > 20 && dot.getZ() < 180),
                "the head of a route containing a backtrack was skipped and the dotted line strays from the route");
    }

    /**
     * The destination marker must show even when the route and dotted line are turned off. What you cannot tell when
     * opening the map is "where am I heading", which is separate from the dotted line setting.
     */
    @Test
    void goalPinStandsOnTheDestinationWithoutTheDottedLine() {
        BlockPos goal = new BlockPos(400, Y, -200);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, goal, false, true,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        Set<BlockPos> blocks = markerBlocks(snapshot);
        assertTrue(blocks.contains(goal), "the pin's tip does not point at the destination block");
        assertTrue(blocks.stream().allMatch(block -> block.getZ() <= goal.getZ()),
                "the pin sticks out south of the destination (straddling it instead of pointing with its tip)");
        assertTrue(blocks.stream().anyMatch(block -> block.getZ() < goal.getZ() - 5),
                "the pin has no height; only its tip is drawn");
    }

    @Test
    void goalMarkerIsAbsentWhenTurnedOff() {
        BlockPos goal = new BlockPos(400, Y, -200);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, goal, true, false,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        assertTrue(markerRects(snapshot).isEmpty(), "the destination marker is drawn even though it is turned off in the settings");
    }

    /**
     * The marker keeps its on-screen size when the map is zoomed out: at half scale, it covers twice as many blocks.
     * If this breaks, the marker shrinks and disappears exactly when you zoom out to see the whole picture, which is
     * precisely when you lose track of the destination, so this property is the marker's whole reason to exist.
     */
    @Test
    void goalMarkerKeepsItsSizeOnScreenAsTheMapZoomsOut() {
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, new BlockPos(0, Y, 0), false, true,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        int atOnePixelPerBlock = markerSpanBlocks(snapshot, 1.0);
        int zoomedOut = markerSpanBlocks(snapshot, 0.5);

        assertTrue(zoomedOut >= atOnePixelPerBlock * 2 - 2,
                "zoomed out but the marker did not grow in blocks, so it shrinks on screen ("
                        + atOnePixelPerBlock + " → " + zoomedOut + ")");
    }

    /** One side of the area the marker covers (blocks). */
    private static int markerSpanBlocks(MapPathOverlay.Snapshot snapshot, double pixelsPerBlock) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] rect : markerRects(snapshot, pixelsPerBlock)) {
            min = Math.min(min, rect[0]);
            max = Math.max(max, rect[2]);
        }
        return max - min;
    }

    private static List<BlockPos> dotsOfColor(MapPathOverlay.Snapshot snapshot, float[] color) {
        List<BlockPos> dots = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x1, z1, x2, z2, red, green, blue) -> {
            if (red == color[0] && green == color[1] && blue == color[2]) {
                dots.add(new BlockPos(x1, Y, z1));
            }
        }, PIXELS_PER_BLOCK);
        return dots;
    }

    private static RoutePreview fullRoute(int toX, boolean complete) {
        List<BlockPos> points = new ArrayList<>();
        double[] ticks = new double[toX + 1];
        for (int x = 0; x <= toX; x++) {
            points.add(new BlockPos(x, Y, 0));
            ticks[x] = x * 3.5;
        }
        return new RoutePreview(points, ticks, complete);
    }

    @Test
    void aCompleteWholeRouteReplacesTheDottedLines() {
        PathResult detail = path(List.of(new BlockPos(0, Y, 0), new BlockPos(25, Y, 0), new BlockPos(50, Y, 0)));
        BlockPos goal = new BlockPos(300, Y, 0);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(detail, goal, true, true,
                new BlockPos(0, Y, 0), List.of(new BlockPos(150, Y, 40), goal), List.of(), 0, List.of(),
                fullRoute(300, true));

        assertTrue(hasDotBetween(dotsOfColor(snapshot, PathColors.FULL_ROUTE), 50, 300), "the whole route is drawn");
        assertFalse(hasDotBetween(dotsOfColor(snapshot, PathColors.FULL_ROUTE), -1, 40),
                "not over the part the detailed route already shows");
        assertTrue(coarseDots(snapshot).isEmpty(), "no long-distance guess where the real route is known");
        assertTrue(dotsOfColor(snapshot, PathColors.STRAIGHT).isEmpty());
        assertFalse(markerRects(snapshot).isEmpty(), "the destination pin stays");
    }

    @Test
    void aPartialWholeRouteHandsOverToTheDottedLine() {
        PathResult detail = path(List.of(new BlockPos(0, Y, 0), new BlockPos(25, Y, 0), new BlockPos(50, Y, 0)));
        BlockPos goal = new BlockPos(300, Y, 0);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(detail, goal, true, false,
                new BlockPos(0, Y, 0), List.of(new BlockPos(250, Y, 0), goal), List.of(), 0, List.of(),
                fullRoute(150, false));

        List<BlockPos> coarse = coarseDots(snapshot);
        assertTrue(hasDotBetween(coarse, 150, 250), "the dotted line carries on from where the whole route stops");
        assertFalse(hasDotBetween(coarse, 50, 140), "and not alongside it");
    }
}
