package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.util.MathSupport;
import net.prason.xaeronav.util.GameCompat;

/**
 * Xaero-independent in-world rendering.
 * Structured so that this part survives even if the Xaero integration (world map and minimap adapters) breaks.
 *
 * <p>The path is drawn not as a flat 1px line but as a "tube" extruded from a square cross-section
 * perpendicular to the direction of travel (so it reads as a solid with thickness from any angle).
 *
 * <p>Draw positions, colors, and highlight targets are computed once per path by {@link PathGeometry}. The
 * per-frame work here is limited to dropping segments far from the camera and emitting vertices. Vertex
 * math is also done with scalars rather than {@link Vec3} (creating a dozen or so vectors per segment
 * would mean tens of thousands of objects per frame on long paths).
 */
public final class PathRenderer {

    /** Half-width of the tube cross-section (blocks). Kept only slightly thicker than the old 1px line. */
    private static final double TUBE_RADIUS = 0.03;

    /**
     * Radius around the camera within which swim segments are not drawn (blocks).
     *
     * <p>While swimming on the surface, the line sits {@code PathGeometry#SWIM_LINE_DEPTH} below and
     * doesn't block the view, but <b>dive to that depth and the line is at eye level again</b>. Swimming
     * bobs up and down with every breath, so this is the norm, not the exception. The nearest 1-2 blocks
     * carry no guidance content (an open-ocean line is just a straight line), so only the area around you
     * is cut out.
     *
     * <p>Only segments drawn sunk are cut. For land lines, sitting at your feet is the information itself;
     * cutting them would lose "which blocks you walk on".
     */
    private static final double SWIM_NEAR_CLIP_BLOCKS = 2.5;

    /**
     * Tube thickness for the flight route. The working distance differs from the walking path by two orders
     * of magnitude: the line at your feet is a few blocks ahead, but the flight route extends 50-200 blocks,
     * so at {@link #TUBE_RADIUS} it becomes sub-pixel on screen and disappears.
     *
     * <p>So it is <b>made proportional to the distance from the camera</b>, keeping the on-screen thickness
     * roughly constant regardless of distance. Up close it is floored at {@link #FLIGHT_TUBE_MIN_RADIUS} so
     * it doesn't get oddly thin right in front of you (this is where the user request "clearly thicker than
     * the walking line" is met).
     */
    private static final double FLIGHT_TUBE_RADIUS_PER_BLOCK = 0.0075;
    private static final double FLIGHT_TUBE_MIN_RADIUS = 0.12;
    private static final double FLIGHT_TUBE_MAX_RADIUS = 0.8;
    private static final float TUBE_ALPHA = 0.9f;

    /**
     * Pillar thickness (per block of distance from the camera). Thicker than the flight route tube so it can
     * be told apart by shape no matter how many waypoint dots overlap.
     */
    private static final double PILLAR_RADIUS_PER_BLOCK = 0.006;
    private static final double PILLAR_MIN_RADIUS = 0.35;
    /** Pillar height (blocks). Long enough that the upper part is visible in the sky even if the base is below the horizon. */
    private static final double PILLAR_HEIGHT_BLOCKS = 320.0;
    private static final float PILLAR_ALPHA = 0.55f;
    private static final float PILLAR_OCCLUDED_ALPHA = 0.3f;

    private static final float HIGHLIGHT_FILL_ALPHA = 0.35f;
    /** Push the highlight box slightly outside the block surface so it isn't hidden by Z-fighting with the terrain itself. */
    private static final double HIGHLIGHT_EXPAND = 0.006;

    /** Opacity of the flight route where hidden by terrain. Drawing it as strong as in front would ignore walls, so it is overlaid faintly. */
    private static final float OCCLUDED_TUBE_ALPHA = 0.3f;

    /**
     * Opacity of underwater segments seen from outside the water. Water writes depth, so in normal rendering
     * an underwater line is hidden by the water surface and only the occluded pass remains. But that occluder
     * is translucent water, not a wall, and the line is where it should actually be visible. At the same
     * faintness as through walls ({@link #OCCLUDED_TUBE_ALPHA}), the dark blue of swimming drowns in the
     * water's blue, and a line going into a water column and climbing up can't be read.
     */
    private static final float THROUGH_WATER_ALPHA = 0.85f;
    /**
     * How much to shift the through-water line toward white. Kept dark blue, it can't be told from the water's
     * blue no matter how opaque. The directly visible side (when underwater) keeps its original color, so the
     * legend colors don't change.
     */
    private static final float THROUGH_WATER_WHITEN = 0.5f;
    /**
     * Range (squared blocks) within which underwater segments of the ground path are drawn through water. Without a limit, a long path shows through the terrain in full and fills the view.
     *
     * <p>Dig/place boxes are not drawn through terrain. Where dig/place blocks line up, the see-through boxes overlap and block the view.
     */
    private static final double OCCLUDED_NEAR_RADIUS_SQ = 12.0 * 12.0;
    /** Only the next dig segment is drawn more opaque than other boxes so it can be told apart. */
    private static final float NEXT_DIG_FILL_ALPHA = 0.5f;

    /** Opacity ratio at the very end of a truncated path's tail. At 0 the break would become invisible. */
    private static final float FADE_TAIL_MIN_RATIO = 0.15f;

    /** Length and gap of each dash of the approximate straight (dotted) line (blocks). */
    private static final double DASH_LENGTH = 1.0;
    private static final double DASH_GAP = 1.0;

    /**
     * Distance (blocks) at which the approximate straight line starts being drawn. A lower bound so that
     * when the end of the path reaches the destination, no dotted line to the same spot is drawn on top.
     */
    private static final double STRAIGHT_MIN_DISTANCE = 3.0;

    private static final float STRAIGHT_ALPHA = 0.8f;
    private static final float STRAIGHT_OCCLUDED_ALPHA = 0.3f;

    private final PathCache<PathGeometry> geometryCache = new PathCache<>();

    // The four vertices of the tube cross-section. Reused rather than rebuilt per segment (render thread only).
    private final double[] ringX = new double[4];
    private final double[] ringY = new double[4];
    private final double[] ringZ = new double[4];

    // Start point of a segment trimmed where it has been passed (render thread only). Avoids allocating an array per segment
    private final double[] segmentCut = new double[3];

    // Player's feet, where the dotted line starts when there is no path yet (render thread only).
    private double playerX;
    private double playerY;
    private double playerZ;
    // Player's feet Y without offset, used to trim ground path segments (render thread only).
    // playerY is lowered by 2 blocks for the dotted line's origin; using it as-is for trimming shifts the
    // lower side of segments.
    private double groundPlayerY;

    // Dotted line waypoints laid out as x,y,z triples. The occluded and normal passes trace the same list
    // twice, so it is reused rather than rebuilt every frame (render thread only).
    private double[] straightPoints = new double[12];

    /**
     * Called after translucent blocks are drawn. Receives only the camera and matrix from the per-loader event
     * (NeoForge: {@code RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS}, Fabric:
     * {@code WorldRenderEvents.AFTER_TRANSLUCENT}).
     */
    public void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Fetch once and read only this snapshot afterwards. Calling individual getters repeatedly while
        // drawing lets a worker callback interleave and draw, for one frame, "a combination that never existed
        // at any instant" (e.g. a new goal with the old currentResult)
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        PathResult groundResult = view.currentResult();
        FlightRoute flight = view.flightRoute();
        BlockPos goal = view.goal();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasFlight = !flight.isEmpty();
        boolean arrived = view.arrived();
        // Don't show the direction dotted line while the arrival display is up. The arrival radius (3) and
        // the distance at which the dotted line starts (3) are the same, so if the destination is below your
        // feet, a dotted line pointing straight down would remain from the moment you arrive
        BlockPos pillar = view.skyPillar();
        // No dotted line while the pillar is shown. The pillar marks where to land; the dotted line would just stop short of the horizon
        boolean hasStraight = pillar == null && goal != null && !arrived
                && XaeroNavConfig.INSTANCE.straightLineEnabled();
        if (!hasGround && !hasFlight && !hasStraight && pillar == null) {
            return;
        }

        Vec3 cameraPos = ClientCompat.cameraPosition(camera);
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack.Pose pose = poseStack.last();
        // Beyond render distance the terrain itself isn't drawn, so there's no point emitting the path out there
        double cullRadius = ClientCompat.renderDistance(mc.options) * 16.0;
        double cullRadiusSq = cullRadius * cullRadius;

        BlockPos playerPos = mc.player.blockPosition();
        boolean playerInWater = mc.level.getFluidState(playerPos).is(FluidTags.WATER);
        // If the eyes are underwater, no water surface sits in between, so the through-water treatment isn't needed
        boolean cameraInWater = mc.level.getFluidState(GameCompat.containing(cameraPos)).is(FluidTags.WATER);
        double playerFeetY = playerPos.getY() + 0.55;
        playerX = mc.player.getX();
        groundPlayerY = playerInWater ? playerFeetY : mc.player.getY() + 0.55;
        // Lower only the origin of the dotted line (straight line to the goal) by 2 blocks. Drawn from eye
        // height, it gets buried in your own body when looking down, e.g. while flying
        playerY = groundPlayerY - 2.0;
        playerZ = mc.player.getZ();

        PathGeometry current = null;
        if (hasGround) {
            current = geometryCache.get(groundResult, r -> PathGeometry.build(mc.level, r, playerPos));
            renderGroundPath(bufferSource, pose, current, groundResult, cameraPos, cullRadiusSq, cameraInWater);
        }
        if (hasFlight) {
            renderFlightRoute(bufferSource, pose, flight, cullRadius, cameraPos);
        }
        if (hasStraight) {
            renderStraightLine(bufferSource, pose, current, hasFlight ? flight.tail() : null, goal, cullRadius);
        }
        if (pillar != null) {
            renderSkyPillar(bufferSource, pose, pillar, cullRadius, cameraPos);
        }
        //? if >=26.2 {
        /*bufferSource.endFrame();
        *///?}

        poseStack.popPose();
    }

    /**
     * Shows the stretch where the path is unknown as a dotted straight line to the destination. Beyond
     * unloaded chunks, or when the destination's Y is not standable, the actually traversable path ends there.
     * Cutting the line there would also lose "which way to go", so the rest is joined with a straight line.
     *
     * <p>It starts at the end of the path (or at the player if there is none). It is also drawn faintly through
     * walls: this line shows direction and distance rather than following the terrain, so hiding it behind
     * occluders would defeat its purpose.
     */
    private void renderStraightLine(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                     PathGeometry geometry, Vec3 flightTail, BlockPos goal, double cullRadius) {
        double fromX = playerX;
        double fromY = playerY;
        double fromZ = playerZ;
        if (flightTail != null) {
            // Only connect the part beyond where the flight route reaches with a dotted line. If the end
            // reaches the destination, the length falls below the minimum and drawStraightDashes naturally draws nothing
            fromX = flightTail.x;
            fromY = flightTail.y;
            fromZ = flightTail.z;
        } else if (geometry != null) {
            int last = geometry.pointX.length - 1;
            fromX = geometry.pointX[last];
            fromY = geometry.pointY[last];
            fromZ = geometry.pointZ[last];
        }

        // While gliding, the dotted line follows the long-range route's intermediate targets (otherwise a curved dotted line)
        List<Vec3> dash = PathfindingState.INSTANCE.flightDashWaypoints();
        int points = 0;
        points = pushStraightPoint(points, fromX, fromY, fromZ);
        for (Vec3 point : dash) {
            points = pushStraightPoint(points, point.x, point.y, point.z);
        }
        points = pushStraightPoint(points, goal.getX() + 0.5, goal.getY() + 0.55, goal.getZ() + 0.5);

        // Emit the occluded pass completely and close the buffer before moving on to the normal pass.
        // BufferSource can only build one RenderType at a time, and the previous buffer is closed as soon as
        // the next getBuffer is called; holding two and writing alternately crashes on writes to the closed one
        VertexConsumer occludedQuads = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        drawStraightDashes(occludedQuads, pose, points, cullRadius, STRAIGHT_OCCLUDED_ALPHA);
        NavRenderTypes.endOccludedBatch(bufferSource, NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quadBuffer = bufferSource.getBuffer(NavRenderTypes.DEBUG_QUADS);
        drawStraightDashes(quadBuffer, pose, points, cullRadius, STRAIGHT_ALPHA);
        bufferSource.endBatch(NavRenderTypes.DEBUG_QUADS);
    }

    /**
     * Draws the flight route as a tube. Unlike the walking path there is no per-step coloring (danger, digging,
     * move type), only a polyline, so it doesn't go through {@link PathGeometry}.
     *
     * <p>The first point is the player position at computation time, up to one recompute interval old by the
     * time it arrives. Without redrawing from the current position, the line appears to sprout from slightly
     * behind you.
     */
    private void renderFlightRoute(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                    FlightRoute route, double cullRadius, Vec3 camera) {
        List<Vec3> points = route.points();
        // Don't draw segments already passed. The flight route advances tens of blocks between redraws, so
        // without this the line keeps extending behind you (same reason as the walking renderGroundPath)
        int first = PathfindingState.INSTANCE.flightRouteFrom();
        int count = 0;
        // Draw the line from the point on the route closest to you. Drawing from your own position makes the
        // near end stick to your body and move, so it looks redrawn even though the route wasn't recomputed
        Vec3 anchor = PathfindingState.INSTANCE.flightRouteAnchor(Minecraft.getInstance().player.position());
        if (anchor != null) {
            count = pushStraightPoint(count, anchor.x, anchor.y, anchor.z);
        } else {
            count = pushStraightPoint(count, playerX, playerY, playerZ);
        }
        for (int i = first; i < points.size(); i++) {
            Vec3 point = points.get(i);
            count = pushStraightPoint(count, point.x, point.y, point.z);
        }

        // Emit the occluded pass fully, close the buffer, then move to the normal pass (same reason as renderStraightLine)
        VertexConsumer occluded = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        drawTubeSegments(occluded, pose, count, cullRadius, OCCLUDED_TUBE_ALPHA, camera, PathColors.FLIGHT);
        NavRenderTypes.endOccludedBatch(bufferSource, NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quads = bufferSource.getBuffer(NavRenderTypes.DEBUG_QUADS);
        drawTubeSegments(quads, pose, count, cullRadius, TUBE_ALPHA, camera, PathColors.FLIGHT);
        bufferSource.endBatch(NavRenderTypes.DEBUG_QUADS);
    }

    /**
     * Light pillar placed at the landing spot. <b>It stands at the true position even beyond render
     * distance</b>: the projection's far depth limit is 4x render distance ({@code GameRenderer#getDepthFar} /
     * {@code Camera#depthFar} on 26.x), and the pillar can be drawn even without terrain. Pulling it closer
     * would make it slide over the ground as you fly and appear to point somewhere else.
     * Only if it exceeds even that is it pulled in to 3x the distance in the same direction.
     */
    private void renderSkyPillar(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose, BlockPos pillar,
                                  double cullRadius, Vec3 camera) {
        double x = pillar.getX() + 0.5;
        double z = pillar.getZ() + 0.5;
        double dx = x - camera.x;
        double dz = z - camera.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double reach = cullRadius * 3.0;
        if (horizontal > reach) {
            x = camera.x + dx / horizontal * reach;
            z = camera.z + dz / horizontal * reach;
        }
        double bottom = pillar.getY();
        double top = bottom + PILLAR_HEIGHT_BLOCKS;
        double radius = Math.max(PILLAR_MIN_RADIUS, Math.min(horizontal, reach) * PILLAR_RADIUS_PER_BLOCK);
        float[] color = PathColors.SKY_PILLAR;

        VertexConsumer occluded = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        drawTube(occluded, pose, radius, x, bottom, z, x, top, z, color[0], color[1], color[2], PILLAR_OCCLUDED_ALPHA);
        NavRenderTypes.endOccludedBatch(bufferSource, NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quads = bufferSource.getBuffer(NavRenderTypes.DEBUG_QUADS);
        drawTube(quads, pose, radius, x, bottom, z, x, top, z, color[0], color[1], color[2], PILLAR_ALPHA);
        bufferSource.endBatch(NavRenderTypes.DEBUG_QUADS);
    }

    /** Half-width of the tube on this segment, keeping on-screen thickness constant regardless of distance. */
    private static double flightTubeRadius(Vec3 camera, double fromX, double fromY, double fromZ,
                                            double toX, double toY, double toZ) {
        double distance = Math.sqrt(distanceSqToSegment(camera, fromX, fromY, fromZ, toX, toY, toZ));
        return MathSupport.clamp(distance * FLIGHT_TUBE_RADIUS_PER_BLOCK,
                FLIGHT_TUBE_MIN_RADIUS, FLIGHT_TUBE_MAX_RADIUS);
    }

    private void drawTubeSegments(VertexConsumer buffer, PoseStack.Pose pose, int points, double cullRadius,
                                   float alpha, Vec3 camera, float[] color) {
        for (int i = 0; i + 1 < points; i++) {
            double fromX = straightPoints[i * 3];
            double fromY = straightPoints[i * 3 + 1];
            double fromZ = straightPoints[i * 3 + 2];
            double toX = straightPoints[i * 3 + 3];
            double toY = straightPoints[i * 3 + 4];
            double toZ = straightPoints[i * 3 + 5];
            double dx = toX - fromX;
            double dy = toY - fromY;
            double dz = toZ - fromZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-4) {
                continue;
            }
            // Beyond render distance the terrain isn't drawn at all, so emitting out there wouldn't be visible
            double drawn = Math.min(length, cullRadius);
            drawTube(buffer, pose, flightTubeRadius(camera, fromX, fromY, fromZ, toX, toY, toZ),
                    fromX, fromY, fromZ,
                    fromX + dx / length * drawn, fromY + dy / length * drawn, fromZ + dz / length * drawn,
                    color[0], color[1], color[2], alpha);
        }
    }

    private int pushStraightPoint(int count, double x, double y, double z) {
        if ((count + 1) * 3 > straightPoints.length) {
            straightPoints = Arrays.copyOf(straightPoints, straightPoints.length * 2);
        }
        straightPoints[count * 3] = x;
        straightPoints[count * 3 + 1] = y;
        straightPoints[count * 3 + 2] = z;
        return count + 1;
    }

    private void drawStraightDashes(VertexConsumer buffer, PoseStack.Pose pose, int points, double cullRadius,
                                     float alpha) {
        for (int i = 0; i + 1 < points; i++) {
            double fromX = straightPoints[i * 3];
            double fromY = straightPoints[i * 3 + 1];
            double fromZ = straightPoints[i * 3 + 2];
            double dx = straightPoints[i * 3 + 3] - fromX;
            double dy = straightPoints[i * 3 + 4] - fromY;
            double dz = straightPoints[i * 3 + 5] - fromZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < STRAIGHT_MIN_DISTANCE) {
                continue;
            }
            dx /= length;
            dy /= length;
            dz /= length;
            // Beyond render distance the terrain isn't drawn at all, so emitting dots out there wouldn't be visible
            double drawn = Math.min(length, cullRadius);
            drawDashes(buffer, pose, fromX, fromY, fromZ, dx, dy, dz, drawn, alpha);
        }
    }

    private void drawDashes(VertexConsumer buffer, PoseStack.Pose pose,
                            double fromX, double fromY, double fromZ,
                            double dirX, double dirY, double dirZ, double length, float alpha) {
        for (double start = 0.0; start < length; start += DASH_LENGTH + DASH_GAP) {
            double end = Math.min(start + DASH_LENGTH, length);
            drawTube(buffer, pose, TUBE_RADIUS,
                    fromX + dirX * start, fromY + dirY * start, fromZ + dirZ * start,
                    fromX + dirX * end, fromY + dirY * end, fromZ + dirZ * end,
                    PathColors.STRAIGHT[0], PathColors.STRAIGHT[1], PathColors.STRAIGHT[2], alpha);
        }
    }

    /**
     * Draws the side hidden by terrain first, then draws on top with normal depth testing. The hidden side is
     * drawn only for nearby underwater segments ({@link #OCCLUDED_NEAR_RADIUS_SQ}).
     */
    private void renderGroundPath(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                   PathGeometry geometry, PathResult result, Vec3 camera, double cullRadiusSq,
                                   boolean cameraInWater) {
        int segments = geometry.segmentCount();
        int highlights = geometry.highlightCount();
        // Don't draw segments already passed. The path isn't recomputed as you walk, so without this the line
        // keeps extending endlessly behind you. The line stays on the path and is not pulled toward the player
        // (when you're off to the side, it would look like the line sprouts from you)
        int matched = PathProgress.INSTANCE.indexFor(result);
        int first = geometry.firstSegmentFrom(matched);
        // Trim the dig/place boxes by the same progress as the line. Using the same matched is the point;
        // computing them separately makes the line and boxes drift apart (e.g. only the dig boxes stay behind)
        PathGeometry.Range nextDig = geometry.nextDig(matched);

        VertexConsumer occludedQuads = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        if (!cameraInWater) {
            for (int i = first; i < segments; i++) {
                if (!geometry.segmentInWater[i] || !segmentVisible(geometry, i, camera, OCCLUDED_NEAR_RADIUS_SQ)) {
                    continue;
                }
                drawSegment(occludedQuads, pose, geometry, i, THROUGH_WATER_ALPHA, true, i == first, camera);
            }
        }
        NavRenderTypes.endOccludedBatch(bufferSource, NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quadBuffer = bufferSource.getBuffer(NavRenderTypes.DEBUG_QUADS);
        for (int i = first; i < segments; i++) {
            if (!segmentVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawSegment(quadBuffer, pose, geometry, i, TUBE_ALPHA, false, i == first, camera);
        }
        int visibleHighlights = 0;
        for (int i = 0; i < highlights; i++) {
            if (!highlightVisible(geometry, i, matched, camera, cullRadiusSq)) {
                continue;
            }
            visibleHighlights++;
            drawHighlightBox(quadBuffer, pose, geometry, i,
                    nextDig.contains(i) ? NEXT_DIG_FILL_ALPHA : HIGHLIGHT_FILL_ALPHA);
        }
        bufferSource.endBatch(NavRenderTypes.DEBUG_QUADS);

        if (visibleHighlights > 0) {
            VertexConsumer lineBuffer = bufferSource.getBuffer(NavRenderTypes.LINES);
            for (int i = 0; i < highlights; i++) {
                if (!highlightVisible(geometry, i, matched, camera, cullRadiusSq)) {
                    continue;
                }
                drawHighlightOutline(lineBuffer, pose, geometry, i);
            }
            bufferSource.endBatch(NavRenderTypes.LINES);
        }
    }

    /**
     * If {@code cutAtPlayer}, the segment is cut at the player's current position and only the part ahead is
     * drawn. Merged long straight segments only have points at their ends, so without this it's all or
     * nothing per segment, and the start of the line jumps tens of blocks ahead.
     */
    private void drawSegment(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index,
                             float alpha, boolean throughWater, boolean cutAtPlayer, Vec3 camera) {
        double fromX = geometry.pointX[index];
        double fromY = geometry.pointY[index];
        double fromZ = geometry.pointZ[index];
        if (cutAtPlayer) {
            geometry.cutPoint(index, playerX, groundPlayerY, playerZ, segmentCut);
            fromX = segmentCut[0];
            fromY = segmentCut[1];
            fromZ = segmentCut[2];
        }
        float red = geometry.segmentColor[index * 3];
        float green = geometry.segmentColor[index * 3 + 1];
        float blue = geometry.segmentColor[index * 3 + 2];
        if (throughWater) {
            red += (1.0f - red) * THROUGH_WATER_WHITEN;
            green += (1.0f - green) * THROUGH_WATER_WHITEN;
            blue += (1.0f - blue) * THROUGH_WATER_WHITEN;
        }
        float segmentAlpha = alpha * fadeRatio(geometry, index);
        double toX = geometry.pointX[index + 1];
        double toY = geometry.pointY[index + 1];
        double toZ = geometry.pointZ[index + 1];
        // Dangerous segments are dashed so they're identifiable without relying on color alone (A11Y-01).
        // Visibility takes priority over the sunk handling that avoids the camera; danger should stand out
        if (geometry.segmentDashed[index] && XaeroNavConfig.INSTANCE.dangerDashedEnabled()) {
            drawDashedTube(buffer, pose, fromX, fromY, fromZ, toX, toY, toZ, red, green, blue, segmentAlpha);
            return;
        }
        if (!geometry.segmentSunk[index]) {
            drawTube(buffer, pose, TUBE_RADIUS, fromX, fromY, fromZ, toX, toY, toZ,
                    red, green, blue, segmentAlpha);
            return;
        }
        drawTubeOutsideCamera(buffer, pose, fromX, fromY, fromZ, toX, toY, toZ, camera,
                red, green, blue, segmentAlpha);
    }

    /**
     * Draws the segment as a dashed line of {@link #DASH_LENGTH}/{@link #DASH_GAP}. Short segments (about one
     * move long) are covered entirely by the first dash, so they still look solid; only long segments
     * clearly appear dashed.
     */
    private void drawDashedTube(VertexConsumer buffer, PoseStack.Pose pose,
                                double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ,
                                float red, float green, float blue, float alpha) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6) {
            return;
        }
        double dirX = dx / length;
        double dirY = dy / length;
        double dirZ = dz / length;
        for (double start = 0.0; start < length; start += DASH_LENGTH + DASH_GAP) {
            double end = Math.min(start + DASH_LENGTH, length);
            drawTube(buffer, pose, TUBE_RADIUS,
                    fromX + dirX * start, fromY + dirY * start, fromZ + dirZ * start,
                    fromX + dirX * end, fromY + dirY * end, fromZ + dirZ * end,
                    red, green, blue, alpha);
        }
    }

    /**
     * Draws the tube avoiding a sphere of {@link #SWIM_NEAR_CLIP_BLOCKS} around the camera. A segment entering
     * the sphere is split into near and far pieces (while swimming along the middle of the line this is the
     * norm, and cutting only the near side would leave the part running right beside you).
     */
    private void drawTubeOutsideCamera(VertexConsumer buffer, PoseStack.Pose pose,
                                       double fromX, double fromY, double fromZ,
                                       double toX, double toY, double toZ, Vec3 camera,
                                       float red, float green, float blue, float alpha) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double ox = fromX - camera.x;
        double oy = fromY - camera.y;
        double oz = fromZ - camera.z;
        double half = dx * ox + dy * oy + dz * oz;
        double offset = ox * ox + oy * oy + oz * oz
                - SWIM_NEAR_CLIP_BLOCKS * SWIM_NEAR_CLIP_BLOCKS;
        double discriminant = half * half - lengthSq * offset;
        if (discriminant <= 0.0) {
            // Doesn't touch the sphere. This is the vast majority (all distant segments end up here)
            drawTube(buffer, pose, TUBE_RADIUS, fromX, fromY, fromZ, toX, toY, toZ, red, green, blue, alpha);
            return;
        }
        double root = Math.sqrt(discriminant);
        double enter = (-half - root) / lengthSq;
        double exit = (-half + root) / lengthSq;
        if (enter > 0.0) {
            drawTube(buffer, pose, TUBE_RADIUS, fromX, fromY, fromZ,
                    fromX + dx * Math.min(enter, 1.0), fromY + dy * Math.min(enter, 1.0),
                    fromZ + dz * Math.min(enter, 1.0), red, green, blue, alpha);
        }
        if (exit < 1.0) {
            double start = Math.max(exit, 0.0);
            drawTube(buffer, pose, TUBE_RADIUS, fromX + dx * start, fromY + dy * start, fromZ + dz * start,
                    toX, toY, toZ, red, green, blue, alpha);
        }
    }

    /**
     * Ratio by which a truncated path's tail fades out toward its end. If the line ends abruptly, you can't
     * tell visually whether it's a dead end or the search just didn't reach further.
     */
    private static float fadeRatio(PathGeometry geometry, int index) {
        int segments = geometry.segmentCount();
        if (index < geometry.fadeFromSegment) {
            return 1.0f;
        }
        float progress = (float) (index - geometry.fadeFromSegment + 1) / (segments - geometry.fadeFromSegment);
        return 1.0f - progress * (1.0f - FADE_TAIL_MIN_RATIO);
    }

    private void drawHighlightBox(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index,
                                  float alpha) {
        drawBox(buffer, pose, geometry.highlightX[index], geometry.highlightY[index], geometry.highlightZ[index],
                geometry.highlightColor[index * 3], geometry.highlightColor[index * 3 + 1],
                geometry.highlightColor[index * 3 + 2], alpha);
    }

    private void drawHighlightOutline(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index) {
        drawBoxOutline(buffer, pose, geometry.highlightX[index], geometry.highlightY[index], geometry.highlightZ[index],
                geometry.highlightColor[index * 3], geometry.highlightColor[index * 3 + 1],
                geometry.highlightColor[index * 3 + 2]);
    }

    private boolean segmentVisible(PathGeometry geometry, int index, Vec3 camera, double cullRadiusSq) {
        return distanceSqToSegment(camera,
                geometry.pointX[index], geometry.pointY[index], geometry.pointZ[index],
                geometry.pointX[index + 1], geometry.pointY[index + 1], geometry.pointZ[index + 1]) <= cullRadiusSq;
    }

    /**
     * @param matched the step you're currently on. Highlights before it are not drawn, the same trimming as
     *                the line; <b>without this, passed boxes remain until the path is recomputed</b>. Boxes
     *                for planned work only disappear when {@link #placementPending}/{@link #digPending} see
     *                them as "already done", so walking past without touching them leaves the cells unchanged
     *                and the boxes stay (the cause of the user report "blue boxes remain after passing by")
     */
    private boolean highlightVisible(PathGeometry geometry, int index, int matched, Vec3 camera,
                                     double cullRadiusSq) {
        if (geometry.highlightStep[index] < matched) {
            return false;
        }
        double dx = geometry.highlightX[index] + 0.5 - camera.x;
        double dy = geometry.highlightY[index] + 0.5 - camera.y;
        double dz = geometry.highlightZ[index] + 0.5 - camera.z;
        if (dx * dx + dy * dy + dz * dz > cullRadiusSq) {
            return false;
        }
        return geometry.highlightPlacement[index]
                ? placementPending(geometry, index)
                : digPending(geometry, index);
    }

    /**
     * Whether the planned placement spot can still be placed in. This is for removing the box the moment
     * something is placed, without waiting for the path to be recomputed (the path is only rebuilt every few
     * tens of ticks, so the box would appear to linger on the placed footing).
     *
     * <p>The check is {@link CellData#replaceable}, the same rule the search side
     * ({@code AStarPathfinder#addBridge}) uses to decide where placement is possible. Back when it checked for
     * air, <b>boxes for footing placed into lava, grass, or snow layers were never drawn</b> (none are air,
     * but all can be placed into). A bridge over lava is exactly this case, so there was guidance but no way
     * to tell where to place.
     */
    private boolean placementPending(PathGeometry geometry, int index) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return true;
        }
        BlockPos pos = new BlockPos(geometry.highlightX[index], geometry.highlightY[index],
                geometry.highlightZ[index]);
        return CellData.replaceable(CellData.flagsOf(level.getBlockState(pos)));
    }

    /**
     * Whether a cell planned for digging is still blocked. The dig version of {@link #placementPending},
     * with the same aim: <b>it removes the box the moment the block breaks, without waiting for the path to
     * be recomputed</b>. Recomputes happen only every few tens of ticks, and breaking itself triggers a
     * recompute, so waiting would leave an orange box visible for a few seconds where you finished digging.
     *
     * <p>The check is {@link CellData#occupiableWithoutDigging}, the same rule the search side
     * ({@code AStarPathfinder#occupyCost}) uses to decide "can't pass without digging".
     * Checking for air would leave boxes on <b>cells the body can occupy without digging</b>, like water or vines.
     *
     * <p>When digging a column of sand or gravel, breaking one makes the one above fall, and the same box
     * appears to move down a level. This is correct, since the number of dig moves really did increase.
     */
    private boolean digPending(PathGeometry geometry, int index) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return true;
        }
        BlockPos pos = new BlockPos(geometry.highlightX[index], geometry.highlightY[index],
                geometry.highlightZ[index]);
        return !CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(pos)));
    }

    /**
     * Squared shortest distance between the camera and the segment. Straight runs are merged and can get long,
     * so judging by endpoints alone would drop a long segment that passes right beside the camera.
     */
    private static double distanceSqToSegment(Vec3 camera, double ax, double ay, double az,
                                               double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double apx = camera.x - ax;
        double apy = camera.y - ay;
        double apz = camera.z - az;
        double lengthSq = abx * abx + aby * aby + abz * abz;
        double t = lengthSq > 0.0 ? (apx * abx + apy * aby + apz * abz) / lengthSq : 0.0;
        t = MathSupport.clamp(t, 0.0, 1.0);
        double dx = apx - abx * t;
        double dy = apy - aby * t;
        double dz = apz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Draws the segment as a "tube". A flat line is hard to see edge-on, so a square cross-section
     * perpendicular to the direction of travel is extruded into a 3D shape.
     */
    private void drawTube(VertexConsumer buffer, PoseStack.Pose pose, double radius,
                          double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                          float red, float green, float blue, float alpha) {
        double dirX = toX - fromX;
        double dirY = toY - fromY;
        double dirZ = toZ - fromZ;
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0e-4) {
            return;
        }
        dirX /= length;
        dirY /= length;
        dirZ /= length;

        // Pick a reference vector that isn't parallel to the direction of travel (to avoid a degenerate cross product).
        // Its Z component is always 0, so that term is folded out of the cross products below.
        boolean steep = Math.abs(dirY) > 0.99;
        double refX = steep ? 1.0 : 0.0;
        double refY = steep ? 0.0 : 1.0;

        double rightX = -dirZ * refY;
        double rightY = dirZ * refX;
        double rightZ = dirX * refY - dirY * refX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        rightX = rightX / rightLength * radius;
        rightY = rightY / rightLength * radius;
        rightZ = rightZ / rightLength * radius;

        double upX = rightY * dirZ - rightZ * dirY;
        double upY = rightZ * dirX - rightX * dirZ;
        double upZ = rightX * dirY - rightY * dirX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        upX = upX / upLength * radius;
        upY = upY / upLength * radius;
        upZ = upZ / upLength * radius;

        ringX[0] = upX + rightX;
        ringY[0] = upY + rightY;
        ringZ[0] = upZ + rightZ;
        ringX[1] = rightX - upX;
        ringY[1] = rightY - upY;
        ringZ[1] = rightZ - upZ;
        ringX[2] = -ringX[0];
        ringY[2] = -ringY[0];
        ringZ[2] = -ringZ[0];
        ringX[3] = upX - rightX;
        ringY[3] = upY - rightY;
        ringZ[3] = upZ - rightZ;

        for (int i = 0; i < 4; i++) {
            int next = (i + 1) & 3;
            vertex(buffer, pose, fromX + ringX[i], fromY + ringY[i], fromZ + ringZ[i], red, green, blue, alpha);
            vertex(buffer, pose, fromX + ringX[next], fromY + ringY[next], fromZ + ringZ[next], red, green, blue, alpha);
            vertex(buffer, pose, toX + ringX[next], toY + ringY[next], toZ + ringZ[next], red, green, blue, alpha);
            vertex(buffer, pose, toX + ringX[i], toY + ringY[i], toZ + ringZ[i], red, green, blue, alpha);
        }
    }

    private void drawBox(VertexConsumer buffer, PoseStack.Pose pose, int cellX, int cellY, int cellZ,
                         float red, float green, float blue, float alpha) {
        double minX = cellX - HIGHLIGHT_EXPAND;
        double minY = cellY - HIGHLIGHT_EXPAND;
        double minZ = cellZ - HIGHLIGHT_EXPAND;
        double maxX = cellX + 1 + HIGHLIGHT_EXPAND;
        double maxY = cellY + 1 + HIGHLIGHT_EXPAND;
        double maxZ = cellZ + 1 + HIGHLIGHT_EXPAND;

        quad(buffer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
        quad(buffer, pose, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        quad(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, red, green, blue, alpha);
    }

    private void drawBoxOutline(VertexConsumer buffer, PoseStack.Pose pose, int cellX, int cellY, int cellZ,
                                 float red, float green, float blue) {
        float minX = (float) (cellX - HIGHLIGHT_EXPAND);
        float minY = (float) (cellY - HIGHLIGHT_EXPAND);
        float minZ = (float) (cellZ - HIGHLIGHT_EXPAND);
        float maxX = (float) (cellX + 1 + HIGHLIGHT_EXPAND);
        float maxY = (float) (cellY + 1 + HIGHLIGHT_EXPAND);
        float maxZ = (float) (cellZ + 1 + HIGHLIGHT_EXPAND);

        line(buffer, pose, minX, minY, minZ, maxX, minY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue);
        line(buffer, pose, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue);
        line(buffer, pose, minX, minY, maxZ, minX, minY, minZ, red, green, blue);
        line(buffer, pose, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue);
        line(buffer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue);
        line(buffer, pose, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue);
        line(buffer, pose, minX, minY, minZ, minX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue);
        line(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue);
    }

    private void quad(VertexConsumer buffer, PoseStack.Pose pose,
                      double x0, double y0, double z0, double x1, double y1, double z1,
                      double x2, double y2, double z2, double x3, double y3, double z3,
                      float red, float green, float blue, float alpha) {
        vertex(buffer, pose, x0, y0, z0, red, green, blue, alpha);
        vertex(buffer, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(buffer, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(buffer, pose, x3, y3, z3, red, green, blue, alpha);
    }

    // On 1.20.1, VertexConsumer is the older-generation API (chain color/normal starting from
    // vertex(double,double,double) and finalize with endVertex()), a different shape from 1.21.1's addVertex
    // family (takes the Pose argument directly, no endVertex). The coordinate, color, and normal values are
    // the same, so gating just the two places vertex/line is enough
    private void vertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double y, double z,
                        float red, float green, float blue, float alpha) {
        //? if >=1.21 {
        buffer.addVertex(pose, (float) x, (float) y, (float) z).setColor(red, green, blue, alpha);
        //?} else {
        /*buffer.vertex(pose.pose(), (float) x, (float) y, (float) z)
                .color(red, green, blue, alpha)
                .endVertex();
        *///?}
    }

    private void line(VertexConsumer buffer, PoseStack.Pose pose,
                      float x0, float y0, float z0, float x1, float y1, float z1,
                      float red, float green, float blue) {
        //? if >=1.21.11 {
        /*// Line width is per vertex. Match the width of vanilla's block outline
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
        buffer.addVertex(pose, x0, y0, z0).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f).setLineWidth(width);
        buffer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f).setLineWidth(width);
        *///?} else if >=1.21 {
        buffer.addVertex(pose, x0, y0, z0).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f);
        buffer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f);
        //?} else if >=1.20.5 {
        /*// 1.20.5-1.20.6 still use the older-generation chain, but normal takes a Pose instead of a Matrix3f
        buffer.vertex(pose.pose(), x0, y0, z0)
                .color(red, green, blue, 1.0f)
                .normal(pose, 0f, 1f, 0f)
                .endVertex();
        buffer.vertex(pose.pose(), x1, y1, z1)
                .color(red, green, blue, 1.0f)
                .normal(pose, 0f, 1f, 0f)
                .endVertex();
        *///?} else {
        /*buffer.vertex(pose.pose(), x0, y0, z0)
                .color(red, green, blue, 1.0f)
                .normal(pose.normal(), 0f, 1f, 0f)
                .endVertex();
        buffer.vertex(pose.pose(), x1, y1, z1)
                .color(red, green, blue, 1.0f)
                .normal(pose.normal(), 0f, 1f, 0f)
                .endVertex();
        *///?}
    }
}
