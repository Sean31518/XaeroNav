package net.prason.xaeronav.client;

import java.util.List;

//? if >=1.19.3 {
import org.joml.Matrix4f;
//?} else {
/*import com.mojang.math.Matrix4f;
import java.nio.FloatBuffer;
*///?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.util.MathSupport;
import net.prason.xaeronav.xaero.XaeroHookHealth;

/**
 * "What to place in which color" when drawing the route on Xaero's world map and minimap.
 *
 * <p>The two maps differ only in their render target ({@code VertexConsumer} vs. a fill helper) and the
 * conversion to map coordinates; how the route is read, the drawing order and the colors are exactly the
 * same. Without unifying it here, adding a new kind of route would update only one of them, leading to
 * "it shows on the world map but not on the minimap".
 *
 * <p>Clipping (discarding far-off parts that don't fit the minimap FBO) is the job of {@link QuadSink}.
 * What to clip depends on map coordinates, so it belongs with the caller that owns the conversion.
 */
public final class MapPathOverlay {

    /**
     * Sizes of each part of the destination marker (screen pixels). <b>The key is keeping these in pixels, not blocks</b>:
     * in blocks, the marker would shrink along with the map when zoomed out and disappear. Losing track of the destination
     * happens precisely when zoomed out to see the whole picture, so that would defeat the purpose.
     */
    private static final double PIN_WIDTH_PX = 11.0;
    private static final double PIN_HEIGHT_PX = 20.0;
    private static final double PIN_HOLE_RADIUS_PX = 2.6;
    /** Outline thickness. The whole pin is outlined in a dark color so it doesn't blend in, whether the terrain is bright or dark. */
    private static final double PIN_OUTLINE_PX = 1.0;
    /** Bands thinner than this aren't drawn. Drawing bands smaller than one block only blurs the outline without adding shape. */
    private static final double PIN_MIN_BAND_PX = 0.35;

    /**
     * The allowed range of screen pixels per block. {@link #pixelsPerBlock} derives the map scale from the matrix, but
     * because Xaero inserts an FBO midway the estimate can be off. Clamp it here at both ends so the marker size
     * doesn't swing without limit when it is.
     */
    private static final double MIN_PIXELS_PER_BLOCK = 0.05;
    private static final double MAX_PIXELS_PER_BLOCK = 16.0;

    /**
     * Places one rectangle on the map. Coordinates are block coordinates ({@code x2}/{@code z2} exclusive);
     * the implementation handles conversion to map coordinates and clipping.
     *
     * <p>The route is a chain of one-block dots, but the destination marker alone should have the same on-screen
     * size regardless of scale. Tiling it with dots would mean tens of thousands when zoomed out, so rectangles are the primitive.
     */
    @FunctionalInterface
    public interface QuadSink {
        void rect(int blockX1, int blockZ1, int blockX2, int blockZ2, float red, float green, float blue);

        /** A one-block dot. The route is drawn as a chain of these. */
        default void dot(int blockX, int blockZ, float red, float green, float blue) {
            rect(blockX, blockZ, blockX + 1, blockZ + 1, red, green, blue);
        }
    }

    /**
     * How many screen pixels one block becomes under this matrix. The length of the x-axis basis vector is the scale factor
     * (rotation doesn't change the length, so this works as-is for a rotating minimap).
     */
    public static double pixelsPerBlock(Matrix4f pose) {
        //? if >=1.19.3 {
        double x = pose.m00();
        double y = pose.m01();
        double z = pose.m02();
        //?} else {
        /*FloatBuffer values = FloatBuffer.allocate(16);
        pose.store(values);
        double x = values.get(0);
        double y = values.get(1);
        double z = values.get(2);
        *///?}
        double scale = Math.sqrt(x * x + y * y + z * z);
        return Double.isFinite(scale) ? MathSupport.clamp(scale, MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK) : 1.0;
    }

    private MapPathOverlay() {
    }

    /**
     * Everything to draw at that moment, frozen into one. The worker thread can swap the route at any time, so if the
     * "is there anything" check and the actual drawing read separately, a route judged present may be gone by the time it's drawn.
     */
    public record Snapshot(PathResult ground, BlockPos goal, boolean straightLine, boolean goalMarker,
                            BlockPos playerPos, List<BlockPos> coarseWaypoints, List<Vec3> flightRoute,
                            int flightRouteFrom, List<Vec3> flightDash, RoutePreview fullRoute) {

        public Snapshot {
            coarseWaypoints = List.copyOf(coarseWaypoints);
            flightRoute = List.copyOf(flightRoute);
            flightDash = List.copyOf(flightDash);
        }

        public Snapshot(PathResult ground, BlockPos goal, boolean straightLine, boolean goalMarker,
                        BlockPos playerPos, List<BlockPos> coarseWaypoints, List<Vec3> flightRoute,
                        int flightRouteFrom, List<Vec3> flightDash) {
            this(ground, goal, straightLine, goalMarker, playerPos, coarseWaypoints, flightRoute, flightRouteFrom,
                    flightDash, RoutePreview.NONE);
        }

        public boolean isEmpty() {
            return ground == null && goal == null && coarseWaypoints.isEmpty() && flightRoute.isEmpty();
        }
    }

    /**
     * Reads what to draw now, exactly once. If there is nothing to draw, {@link Snapshot#isEmpty()} is true and
     * the caller can skip obtaining the {@code VertexConsumer} entirely.
     */
    public static Snapshot snapshot() {
        XaeroHookHealth.hookRan();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Snapshot(null, null, false, false, null, List.of(), List.of(), 0, List.of());
        }
        return PathfindingState.INSTANCE.mapOverlaySnapshot(player.blockPosition());
    }

    public static void draw(Snapshot snapshot, QuadSink sink, double pixelsPerBlock) {
        MapDots dots = snapshot.ground() == null ? null : MapDots.forPath(snapshot.ground());
        if (dots != null) {
            // Don't draw segments already passed (same reason as PathRenderer.renderGroundPath). Routes extended
            // by appending aren't recomputed until deviation or arrival, so without this the trail you walked stays
            // on the map and the line appears to grow without limit
            int first = dots.firstDotFrom(PathProgress.INSTANCE.indexFor(snapshot.ground()));
            for (int i = first; i < dots.count; i++) {
                sink.dot(dots.x[i], dots.z[i],
                        dots.color[i * 3], dots.color[i * 3 + 1], dots.color[i * 3 + 2]);
            }
        }

        // The whole route planned ahead (singleplayer), from where the detailed route ends. It replaces the
        // long-distance dotted line up to where it reaches; past that the dotted lines carry on from its end
        RoutePreview full = snapshot.fullRoute();
        int tailX = dots != null && dots.count > 0 ? dots.x[dots.count - 1] : snapshot.playerPos().getX();
        int tailZ = dots != null && dots.count > 0 ? dots.z[dots.count - 1] : snapshot.playerPos().getZ();
        boolean fullRouteDrawn = false;
        if (!full.isEmpty() && snapshot.flightRoute().isEmpty()) {
            List<BlockPos> points = full.points();
            for (int i = full.nearestIndex(tailX, tailZ); i < points.size(); i++) {
                BlockPos point = points.get(i);
                sink.dot(point.getX(), point.getZ(), PathColors.FULL_ROUTE[0], PathColors.FULL_ROUTE[1],
                        PathColors.FULL_ROUTE[2]);
            }
            BlockPos end = points.get(points.size() - 1);
            tailX = end.getX();
            tailZ = end.getZ();
            fullRouteDrawn = true;
            if (full.complete()) {
                if (snapshot.goal() != null && snapshot.goalMarker()) {
                    drawGoalMarker(sink, snapshot.goal(), pixelsPerBlock);
                }
                return;
            }
        }

        // Connect the long-distance route's intermediate waypoints first. The dotted line to the destination (below)
        // continues from here, so "a dotted line along the coarse route" and "a straight line to the destination" don't
        // appear at the same time pointing in conflicting directions
        // (joining straight to the destination without waypoints would look like it cuts right through the mountains or sea the coarse route detours around)
        //
        // The start is the end of the detailed route (or the player if there is none), not the waypoint itself. Joining
        // only the waypoints would leave the dotted line floating detached from your position after leaving the route, looking like a stale route
        List<BlockPos> coarseWaypoints = snapshot.coarseWaypoints();
        BlockPos lastCoarseWaypoint = coarseWaypoints.isEmpty() ? null : coarseWaypoints.get(coarseWaypoints.size() - 1);
        if (!coarseWaypoints.isEmpty()) {
            int previousX;
            int previousZ;
            if (fullRouteDrawn || dots != null && dots.count > 0) {
                previousX = tailX;
                previousZ = tailZ;
            } else {
                previousX = snapshot.playerPos().getX();
                previousZ = snapshot.playerPos().getZ();
            }
            for (int i = firstAheadWaypoint(coarseWaypoints, previousX, previousZ); i < coarseWaypoints.size(); i++) {
                BlockPos next = coarseWaypoints.get(i);
                StraightDots.forEach(previousX, previousZ, next.getX(), next.getZ(),
                        (x, z) -> sink.dot(x, z,
                                PathColors.COARSE_ROUTE[0], PathColors.COARSE_ROUTE[1], PathColors.COARSE_ROUTE[2]));
                previousX = next.getX();
                previousZ = next.getZ();
            }
        }

        // The aerial route. The map is flat so altitude can't be shown, but "which way it goes around" can.
        // Use the same color as the thick in-world line, to distinguish it from the dotted line (direction only)
        List<Vec3> flightRoute = snapshot.flightRoute();
        Vec3 flightTail = flightRoute.isEmpty() ? null : flightRoute.get(flightRoute.size() - 1);
        if (!flightRoute.isEmpty()) {
            int previousX = snapshot.playerPos().getX();
            int previousZ = snapshot.playerPos().getZ();
            // Don't draw segments already passed (use the same index as renderFlightRoute in the world).
            // The first point is the player position at computation time, so drop it and draw from the current position
            for (int i = Math.min(snapshot.flightRouteFrom(), flightRoute.size() - 1);
                    i < flightRoute.size(); i++) {
                Vec3 next = flightRoute.get(i);
                int nextX = (int) Math.floor(next.x);
                int nextZ = (int) Math.floor(next.z);
                StraightDots.forEach(previousX, previousZ, nextX, nextZ,
                        (x, z) -> sink.dot(x, z,
                                PathColors.FLIGHT[0], PathColors.FLIGHT[1], PathColors.FLIGHT[2]));
                previousX = nextX;
                previousZ = nextZ;
            }
        }

        BlockPos goal = snapshot.goal();
        if (goal != null && snapshot.straightLine()) {
            // The dotted line starts at the end of the coarse route if there is one, otherwise at the end of the route.
            // With neither a route nor a coarse route yet, draw from the player. When the coarse route's end has been
            // replaced by the destination itself, from=to gives zero length, and StraightDots draws nothing so it naturally disappears
            int fromX;
            int fromZ;
            if (flightTail != null) {
                // Only connect beyond the stretch covered by the aerial route with a dotted line
                fromX = (int) Math.floor(flightTail.x);
                fromZ = (int) Math.floor(flightTail.z);
            } else if (lastCoarseWaypoint != null) {
                fromX = lastCoarseWaypoint.getX();
                fromZ = lastCoarseWaypoint.getZ();
            } else if (fullRouteDrawn) {
                fromX = tailX;
                fromZ = tailZ;
            } else if (dots != null && dots.count > 0) {
                fromX = dots.x[dots.count - 1];
                fromZ = dots.z[dots.count - 1];
            } else {
                fromX = snapshot.playerPos().getX();
                fromZ = snapshot.playerPos().getZ();
            }
            // While gliding, follow the long-distance route's waypoints (otherwise the curved dotted line, otherwise a straight line)
            for (Vec3 point : snapshot.flightDash()) {
                int nextX = (int) Math.floor(point.x);
                int nextZ = (int) Math.floor(point.z);
                straightDots(sink, fromX, fromZ, nextX, nextZ, PathColors.STRAIGHT);
                fromX = nextX;
                fromZ = nextZ;
            }
            straightDots(sink, fromX, fromZ, goal.getX(), goal.getZ(), PathColors.STRAIGHT);
        }

        // The marker goes last. It sits where the route and dotted line overlap, so place it afterwards on top
        if (goal != null && snapshot.goalMarker()) {
            drawGoalMarker(sink, goal, pixelsPerBlock);
        }
    }

    /**
     * The waypoint to start connecting from the start point. Skipped while the route keeps approaching the start point.
     *
     * <p>Whether a waypoint has been passed is judged by "which waypoint the detailed route is heading to", but while the
     * detailed route isn't following waypoints (during layer 2 refinement it heads straight for the real destination) that
     * index doesn't advance, and the first unpassed waypoint stays far behind your position. Connecting them in order then makes
     * the line going back from your position and the route's own line run side by side down the same corridor, looking like
     * nothing but <b>two yellow dotted lines</b>.
     *
     * <p>Conversely, if the route really does double back (the start point has overshot), the second point moves away from the
     * start, so it isn't skipped here.
     */
    /**
     * The waypoint to start the dotted line from. <b>Determined by projecting the end of the route onto the polyline</b>.
     *
     * <p>Don't cut by index. The waypoint list swaps between layer 1's raw list and layer 2's refined version, so the same
     * index ends up pointing somewhere else; the refined version is more closely spaced, and applying a raw-list index
     * <b>brings already-passed points into the drawn range</b>. The index is only reset on recomputation, and that recomputation
     * returns without updating anything when keeping a completed route ({@code PathfindingState#pathWorthKeeping}), so once
     * it drifts it never recovers, i.e. the stale dotted line never disappears.
     *
     * <p>"Advance only while the next point is close" isn't enough either. Where the polyline bends, it stops short, and from there
     * a single <b>backward-pointing line</b> is drawn to the end. Drawing from the end of the segment closest to the route's end
     * always drops passed points, even around bends.
     */
    static int firstAheadWaypoint(List<BlockPos> waypoints, int fromX, int fromZ) {
        int first = 0;
        double nearest = distanceSq(waypoints.get(0), fromX, fromZ);
        for (int i = 1; i < waypoints.size(); i++) {
            double distance = segmentDistanceSq(waypoints.get(i - 1), waypoints.get(i), fromX, fromZ);
            if (distance < nearest) {
                nearest = distance;
                first = i;
            }
        }
        return first;
    }

    /** Squared distance between segment {@code a}-{@code b} and point {@code (x, z)} (in the XZ plane). */
    private static double segmentDistanceSq(BlockPos a, BlockPos b, int x, int z) {
        double dx = b.getX() - (double) a.getX();
        double dz = b.getZ() - (double) a.getZ();
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0
                : MathSupport.clamp(((x - (double) a.getX()) * dx + (z - (double) a.getZ()) * dz) / lengthSq, 0.0, 1.0);
        double px = a.getX() + t * dx - x;
        double pz = a.getZ() + t * dz - z;
        return px * px + pz * pz;
    }

    private static long distanceSq(BlockPos pos, int x, int z) {
        long dx = pos.getX() - (long) x;
        long dz = pos.getZ() - (long) z;
        return dx * dx + dz * dz;
    }

    /**
     * A pin placed at the destination. Both the route and the dotted line only show "the way there", so when you open
     * the map you can't tell where the destination is without following the line to its end, especially when it's far
     * and the route is cut off partway.
     *
     * <p><b>Its size tracks the map scale so it always appears the same size on screen.</b> A pin is "a mark on the map",
     * not "an object on the ground", so like Xaero's own waypoints it's correct for it not to shrink when zoomed out.
     * Losing track of the destination happens precisely when zoomed out to see the whole picture, and a pin that shrank
     * and vanished there would defeat the purpose.
     *
     * <p>The shape is drawn as horizontal bands, one per row. The number of bands depends on the on-screen height, so
     * however far you zoom out it takes only a few dozen rectangles (slicing per block would mean tens of thousands when zoomed out).
     *
     * <p><b>The tip is the destination block itself</b>, and the pin stands from there toward the north (the top of the map).
     * The three layers (outline, body, hole) are each drawn as a whole because stacking them row by row would let the next
     * row's outline overwrite the previous row's body, leaving dark streaks inside the pin.
     */
    private static void drawGoalMarker(QuadSink sink, BlockPos goal, double pixelsPerBlock) {
        double radius = PIN_WIDTH_PX / 2.0;
        int top = -(int) Math.ceil(PIN_HEIGHT_PX + PIN_OUTLINE_PX) - 1;
        int bottom = (int) Math.ceil(PIN_OUTLINE_PX);

        for (int row = top; row <= bottom; row++) {
            double body = pinHalfWidth(row, radius, PIN_HEIGHT_PX);
            // The wider of the same shape grown by one size and "body + outline thickness". With only the former,
            // per-row rounding differences make the body poke out and break the outline
            double grown = pinHalfWidth(row - PIN_OUTLINE_PX, radius + PIN_OUTLINE_PX,
                    PIN_HEIGHT_PX + 2 * PIN_OUTLINE_PX);
            pinBand(sink, goal, row, body > 0 ? Math.max(grown, body + PIN_OUTLINE_PX) : grown,
                    pixelsPerBlock, PathColors.GOAL_MARKER_OUTLINE);
        }
        for (int row = top; row <= 0; row++) {
            pinBand(sink, goal, row, pinHalfWidth(row, radius, PIN_HEIGHT_PX),
                    pixelsPerBlock, PathColors.GOAL_MARKER);
        }
        double centre = -(PIN_HEIGHT_PX - radius);
        for (int row = top; row <= 0; row++) {
            // Measure at the band's center height. Measuring at the row's top edge shifts the hole up by half a row
            double fromCentre = row + 0.5 - centre;
            if (Math.abs(fromCentre) <= PIN_HOLE_RADIUS_PX) {
                pinBand(sink, goal, row,
                        Math.sqrt(PIN_HOLE_RADIUS_PX * PIN_HOLE_RADIUS_PX - fromCentre * fromCentre),
                        pixelsPerBlock, PathColors.GOAL_MARKER_HOLE);
            }
        }
    }

    /**
     * Half-width of the pin at {@code rowPx} above the tip (tip = 0, negative is up). Computed as the union of the head
     * circle and a triangle tapering from the circle's center to the tip.
     */
    private static double pinHalfWidth(double rowPx, double radius, double height) {
        double centre = -(height - radius);
        double half = 0.0;
        double fromCentre = rowPx - centre;
        if (Math.abs(fromCentre) <= radius) {
            half = Math.sqrt(radius * radius - fromCentre * fromCentre);
        }
        if (rowPx >= centre && rowPx <= 0.0) {
            half = Math.max(half, radius * (rowPx / centre));
        }
        return half;
    }

    /** One horizontal row band of the pin. Converts its on-screen position and width to blocks and places it. */
    private static void pinBand(QuadSink sink, BlockPos goal, double rowPx, double halfPx,
                                 double pixelsPerBlock, float[] color) {
        if (halfPx < PIN_MIN_BAND_PX) {
            return;
        }
        int z1 = goal.getZ() + scaled(rowPx, pixelsPerBlock);
        int z2 = Math.max(goal.getZ() + scaled(rowPx + 1, pixelsPerBlock), z1 + 1);
        int x1 = goal.getX() + scaled(-halfPx, pixelsPerBlock);
        int x2 = Math.max(goal.getX() + scaled(halfPx, pixelsPerBlock), x1 + 1);
        sink.rect(x1, z1, x2, z2, color[0], color[1], color[2]);
    }

    /** The number of blocks (signed) corresponding to {@code pixels} on screen. */
    private static int scaled(double pixels, double pixelsPerBlock) {
        return (int) Math.round(pixels / pixelsPerBlock);
    }

    private static void straightDots(QuadSink sink, int fromX, int fromZ, int toX, int toZ) {
        straightDots(sink, fromX, fromZ, toX, toZ, PathColors.STRAIGHT);
    }

    private static void straightDots(QuadSink sink, int fromX, int fromZ, int toX, int toZ, float[] color) {
        StraightDots.forEach(fromX, fromZ, toX, toZ,
                (x, z) -> sink.dot(x, z, color[0], color[1], color[2]));
    }
}
