package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.VoxelRay;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.util.MathSupport;

/**
 * The route baked for in-world rendering. Rebuilt only when the route changes.
 *
 * <p>Runs of segments that share a color and continue in a straight line are merged into one. On flat
 * terrain, tens to hundreds of segments become one, cutting the vertex count by orders of magnitude. Both
 * ends stay as they were, so it looks the same.
 *
 * <p>Underwater and boat segments, and segments that only walk across level ground at one height, are merged
 * <b>as far as they can pass</b> even when not straight ({@link #fluidShortcut}, {@link #landShortcut}). The
 * position of each move means nothing there, and the grid-aligned staircase would otherwise show as a zigzag.
 */
final class PathGeometry {

    /** Raises water-surface segment lines slightly above the surface to avoid Z-fighting with the water texture. */
    private static final double WATER_SURFACE_OFFSET = 0.05;

    /**
     * How far below the water surface to draw lines for segments crossed by swimming (blocks).
     *
     * <p><b>When swimming prone, eye level is the water surface itself</b> ({@code Pose.SWIMMING} has
     * eyeHeight 0.4, and the body floats with the eyes at the surface). A line on the surface would sit
     * on the screen center, i.e. the horizon, until you finish crossing. It's moved down because moving it
     * up causes the same problem when looking up, and from underwater looking up it blends into the
     * rendered water surface.
     *
     * <p>The value is set by "out of view up close, still readable far away". At 1.25, it's 32 degrees below
     * the line of sight 2 blocks ahead (just outside the vertical field of view of ±35 degrees at the
     * default FOV 70) and 7 degrees below at 10 blocks ahead.
     *
     * <p><b>Boats aren't sunk.</b> There the eye level is more than a block above the surface, so the
     * surface line never blocks the view to begin with.
     */
    private static final double SWIM_LINE_DEPTH = 1.25;

    /** Upper bound of the cross product for treating two segments as collinear. Segments are about one block long, so this is effectively an exact match. */
    private static final double COLLINEAR_EPSILON = 1.0e-6;

    /**
     * Number of steps over which the end of a route that didn't reach the goal is drawn fading out. Only
     * here are segments kept separate even when straight (to lower the opacity step by step).
     */
    private static final int FADE_TAIL_STEPS = 8;

    /**
     * Maximum length (blocks) of an underwater or boat run that may be folded into one straight line.
     *
     * <p>The cap is needed because the check "rescans the chord from the run's start to the candidate each
     * time"; since each extension looks at the whole length, it's O(length^2). A long straight line just
     * splits into a few, which barely changes the look (same idea as {@code FlightSmoother#LOOKAHEAD_POINTS}).
     */
    private static final int MAX_FLUID_SHORTCUT_BLOCKS = 32;

    /** Maximum length (blocks) of a level-ground run that may be folded into one straight line. Same reason as {@link #MAX_FLUID_SHORTCUT_BLOCKS}. */
    private static final int MAX_LAND_SHORTCUT_BLOCKS = 32;

    /**
     * Step size (blocks) for checking the body's path on level-ground shortcuts. Finer than the body width
     * of 0.6, so no column the chord crosses is missed (a column only grazed at a corner shallower than the
     * step can be missed, but at most the body brushes the corner).
     */
    private static final double LAND_SHORTCUT_SAMPLE_BLOCKS = 0.25;

    /** Half-width of the player's hitbox (vanilla 0.6). */
    private static final double PLAYER_HALF_WIDTH = 0.3;

    /** Segment endpoints. Length is "segment count + 1". */
    final double[] pointX;
    final double[] pointY;
    final double[] pointZ;
    /** RGB per segment (segment count × 3). */
    final float[] segmentColor;
    /**
     * The step index at the end of each segment. Used to skip drawing segments already passed (segments
     * are merged per straight run, so this mapping is needed to look up a segment from a step index).
     */
    final int[] segmentEndStep;
    /**
     * Whether the segment was drawn with both ends sunk by {@link #SWIM_LINE_DEPTH}. Used by the renderer to
     * cut out the area around the player ({@code PathRenderer#SWIM_NEAR_CLIP_BLOCKS}).
     */
    final boolean[] segmentSunk;
    /**
     * Whether both end cells of the segment are water. Seen from outside the water, this segment is hidden
     * by depth behind the rendered water ({@code PathRenderer#THROUGH_WATER_ALPHA}).
     */
    final boolean[] segmentInWater;
    /**
     * Whether the segment is dangerous ({@link PathColors.Kind#DANGER}). So as not to rely on color alone,
     * the renderer emphasizes it with a dashed line (A11Y-01). Steps within a segment are grouped by the
     * same color, i.e. the same classification, so one flag per segment is enough.
     */
    final boolean[] segmentDashed;

    final int[] highlightX;
    final int[] highlightY;
    final int[] highlightZ;
    /** RGB per highlight (highlight count × 3). */
    final float[] highlightColor;
    /**
     * Whether the highlight is "a place to put a block". To remove the outline the moment the block is placed,
     * this marks only the cells whose current state the renderer must check every frame (dig spots are the
     * opposite: shown until broken).
     */
    final boolean[] highlightPlacement;
    /**
     * Index of the originating {@link PathStep} for each highlight (ascending). Needed to skip drawing highlights already passed.
     *
     * <p>Lines were already trimmed with {@link #segmentEndStep}, but highlights had no matching information,
     * so <b>only the outlines lingered until the route was recomputed</b>. A placement outline only disappears
     * once the block is "actually placed" ({@code PathRenderer#placementPending}), so a placement spot walked
     * past without placing stays {@code replaceable}, leaving a blue outline behind you.
     */
    final int[] highlightStep;
    /** Segments from here on are the truncated tail, fading out progressively. Equals the segment count for routes that reached the goal. */
    final int fadeFromSegment;

    private PathGeometry(double[] pointX, double[] pointY, double[] pointZ, float[] segmentColor,
                         int[] segmentEndStep, boolean[] segmentSunk, boolean[] segmentInWater,
                         boolean[] segmentDashed,
                         int[] highlightX, int[] highlightY, int[] highlightZ, float[] highlightColor,
                         boolean[] highlightPlacement, int[] highlightStep, int fadeFromSegment) {
        this.pointX = pointX;
        this.pointY = pointY;
        this.pointZ = pointZ;
        this.segmentColor = segmentColor;
        this.segmentEndStep = segmentEndStep;
        this.segmentSunk = segmentSunk;
        this.segmentInWater = segmentInWater;
        this.segmentDashed = segmentDashed;
        this.highlightX = highlightX;
        this.highlightY = highlightY;
        this.highlightZ = highlightZ;
        this.highlightColor = highlightColor;
        this.highlightPlacement = highlightPlacement;
        this.highlightStep = highlightStep;
        this.fadeFromSegment = fadeFromSegment;
    }

    /** A range of highlight indices {@code [from, to)}. Empty if {@link #from} equals {@link #to}. */
    record Range(int from, int to) {
        boolean contains(int index) {
            return index >= from && index < to;
        }

        boolean isEmpty() {
            return from >= to;
        }
    }

    /**
     * The highlight range of the dig cells for the first digging step at or after {@code fromStep}. One move may dig several cells, so a range is returned.
     *
     * <p>"The next place to dig" is searched for <b>from the current step onward</b>. Fixing it from the start of
     * the route would keep painting a dig spot bold even after passing it.
     */
    Range nextDig(int fromStep) {
        for (int i = 0; i < highlightStep.length; i++) {
            if (highlightStep[i] < fromStep || highlightPlacement[i]) {
                continue;
            }
            int to = i + 1;
            while (to < highlightStep.length && highlightStep[to] == highlightStep[i] && !highlightPlacement[to]) {
                to++;
            }
            return new Range(i, to);
        }
        return new Range(0, 0);
    }

    int segmentCount() {
        return segmentColor.length / 3;
    }

    int highlightCount() {
        return highlightColor.length / 3;
    }

    /**
     * The first segment that hasn't yet passed {@code step}. Returns the segment count if all have been passed.
     *
     * <p>The boundary is {@code >=} (greater or equal). {@code step} is "the step closest to the player", not
     * "a step already reached": right after a route is computed, your own feet are closest to the first step,
     * so {@code step} is 0 even without moving. Using {@code >} (strictly greater) would treat the whole
     * segment ending at that first step as "passed", and the line would no longer start from directly below you.
     */
    int firstSegmentFrom(int step) {
        for (int i = 0; i < segmentEndStep.length; i++) {
            if (segmentEndStep[i] >= step) {
                return i;
            }
        }
        return segmentEndStep.length;
    }

    /**
     * Writes to {@code out} the starting point for drawing segment {@code segment} that corresponds to the player's current position.
     *
     * <p>Projects <b>the player's continuous position itself</b> onto the segment's chord. Previously "the position
     * of the nearest step" was projected instead, but that nearest-step search doesn't include the route's start
     * (where the player is still standing) as a candidate, so right after the route was computed, even without
     * moving, the first step was always the nearest and the line started one step ahead rather than from directly below.
     */
    void cutPoint(int segment, double playerX, double playerY, double playerZ, double[] out) {
        projectOntoSegment(playerX, playerY, playerZ,
                pointX[segment], pointY[segment], pointZ[segment],
                pointX[segment + 1], pointY[segment + 1], pointZ[segment + 1], out);
    }

    /** The projection of point {@code p} onto segment {@code a}-{@code b} (clamped to the segment). */
    static void projectOntoSegment(double px, double py, double pz,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz, double[] out) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = lengthSq < 1.0e-12 ? 0.0
                : MathSupport.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSq, 0.0, 1.0);
        out[0] = ax + dx * t;
        out[1] = ay + dy * t;
        out[2] = az + dz * t;
    }

    static PathGeometry build(Level level, PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        int count = steps.size();

        double[] rawX = new double[count + 1];
        double[] rawY = new double[count + 1];
        double[] rawZ = new double[count + 1];
        float[][] rawColor = new float[count][];
        // Keep the original block coordinates separately from the draw positions. Water-surface segments
        // lift the line above the surface, so using draw positions for passability would look at the air cell above
        BlockPos[] rawBlock = new BlockPos[count + 1];

        // Points drawn sunk. Kept so the per-segment flag (segmentSunk) can be assembled later
        boolean[] rawSunk = new boolean[count + 1];
        boolean[] rawInWater = new boolean[count + 1];
        // Whether dangerous (kept so the per-segment flag segmentDashed can be assembled later, A11Y-01)
        boolean[] rawDangerous = new boolean[count];

        rawBlock[0] = start;
        // Treat the start the same as the move leaving it. Treating it differently makes just the first segment step up or down
        rawSunk[0] = center(level, start, steps.isEmpty() ? null : steps.get(0), rawX, rawY, rawZ, 0);
        rawInWater[0] = level.getFluidState(start).is(FluidTags.WATER);
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            rawBlock[i + 1] = step.pos();
            rawSunk[i + 1] = center(level, step.pos(), step, rawX, rawY, rawZ, i + 1);
            rawInWater[i + 1] = level.getFluidState(step.pos()).is(FluidTags.WATER);
            rawColor[i] = PathColors.forStep(step);
            rawDangerous[i] = PathColors.kindFor(step) == PathColors.Kind.DANGER;
        }

        double[] outX = new double[count + 1];
        double[] outY = new double[count + 1];
        double[] outZ = new double[count + 1];
        float[][] outColor = new float[count][];
        int[] outEndStep = new int[count];
        outX[0] = rawX[0];
        outY[0] = rawY[0];
        outZ[0] = rawZ[0];
        int points = 1;
        int segments = 0;
        int tailStartStep = result.complete() ? count : Math.max(0, count - FADE_TAIL_STEPS);
        int fadeFromSegment = Integer.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // The raw index of the start of the segment currently being extended. The underwater shortcut check looks at the chord from this point
        int segmentStart = 0;
        for (int i = 1; i <= count; i++) {
            float[] color = rawColor[i - 1];
            boolean inTail = i > tailStartStep;
            if (!inTail && segments > 0 && outColor[segments - 1] == color
                    && (continuesStraight(outX[points - 2], outY[points - 2], outZ[points - 2],
                    outX[points - 1], outY[points - 1], outZ[points - 1],
                    rawX[i], rawY[i], rawZ[i])
                    || fluidShortcut(level, cursor, color, rawBlock[segmentStart], rawBlock[i])
                    || landShortcut(level, cursor, color, rawBlock[segmentStart], rawBlock[i]))) {
                outX[points - 1] = rawX[i];
                outY[points - 1] = rawY[i];
                outZ[points - 1] = rawZ[i];
                // Point indices are one greater than step indices (the first point is the player's current position)
                outEndStep[segments - 1] = i - 1;
                continue;
            }
            outX[points] = rawX[i];
            outY[points] = rawY[i];
            outZ[points] = rawZ[i];
            points++;
            outColor[segments] = color;
            outEndStep[segments] = i - 1;
            segments++;
            segmentStart = i - 1;
            if (inTail && fadeFromSegment == Integer.MAX_VALUE) {
                fadeFromSegment = segments - 1;
            }
        }

        float[] flatSegmentColor = new float[segments * 3];
        boolean[] flatSegmentSunk = new boolean[segments];
        boolean[] flatSegmentInWater = new boolean[segments];
        boolean[] flatSegmentDashed = new boolean[segments];
        int startRaw = 0;
        for (int i = 0; i < segments; i++) {
            flatSegmentColor[i * 3] = outColor[i][0];
            flatSegmentColor[i * 3 + 1] = outColor[i][1];
            flatSegmentColor[i * 3 + 2] = outColor[i][2];
            int endRaw = outEndStep[i] + 1;
            flatSegmentSunk[i] = rawSunk[startRaw] && rawSunk[endRaw];
            flatSegmentInWater[i] = rawInWater[startRaw] && rawInWater[endRaw];
            // Steps within a segment are grouped by the same color, i.e. the same classification, so the last step represents the whole segment
            flatSegmentDashed[i] = rawDangerous[outEndStep[i]];
            startRaw = endRaw;
        }

        // The upper bound can be counted in advance (the number of digCells() per move + 1 for bridging), so write
        // directly into primitive arrays rather than boxing through ArrayList<Boolean>/<Integer>
        int highlightCapacity = 0;
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            highlightCapacity += step.digCells().size();
            if (step.bridging()) {
                highlightCapacity++;
            }
        }

        int[] hx = new int[highlightCapacity];
        int[] hy = new int[highlightCapacity];
        int[] hz = new int[highlightCapacity];
        float[] hColor = new float[highlightCapacity * 3];
        boolean[] hPlacement = new boolean[highlightCapacity];
        int[] hStep = new int[highlightCapacity];
        int highlights = 0;
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            for (BlockPos cell : step.digCells()) {
                hx[highlights] = cell.getX();
                hy[highlights] = cell.getY();
                hz[highlights] = cell.getZ();
                hColor[highlights * 3] = PathColors.DIGGING[0];
                hColor[highlights * 3 + 1] = PathColors.DIGGING[1];
                hColor[highlights * 3 + 2] = PathColors.DIGGING[2];
                hPlacement[highlights] = false;
                hStep[highlights] = i;
                highlights++;
            }
            if (step.bridging()) {
                BlockPos cell = step.placedBlockPos();
                hx[highlights] = cell.getX();
                hy[highlights] = cell.getY();
                hz[highlights] = cell.getZ();
                hColor[highlights * 3] = PathColors.BRIDGE[0];
                hColor[highlights * 3 + 1] = PathColors.BRIDGE[1];
                hColor[highlights * 3 + 2] = PathColors.BRIDGE[2];
                hPlacement[highlights] = true;
                hStep[highlights] = i;
                highlights++;
            }
        }

        return new PathGeometry(
                Arrays.copyOf(outX, points), Arrays.copyOf(outY, points), Arrays.copyOf(outZ, points),
                flatSegmentColor, Arrays.copyOf(outEndStep, segments), flatSegmentSunk, flatSegmentInWater,
                flatSegmentDashed,
                hx, hy, hz, hColor, hPlacement, hStep, Math.min(fadeFromSegment, segments));
    }

    /**
     * Whether an underwater or boat run may become <b>a straight line as far as it can pass</b> rather than a grid-aligned polyline.
     *
     * <p>On land routes, the position of moves with steps, digging or placement matters (it is itself the instruction to stand on this block).
     * In water and on a boat there is no footing, and the staircase that A* returns is <b>purely an artifact of the search grid</b>;
     * with no terrain to hide it, the staircase shows up directly in the line, looking like a meaningless zigzag in open sea.
     * Only the direction matters, the same property as lines while gliding, so it's handled the same way
     * (same idea as the string pull in {@code FlightSmoother}, and the check uses the same {@link VoxelRay}).
     *
     * <p>Requires every cell the shortcut passes through to be water, and the cell above each to be passable without digging. With only
     * the former the line would cut across headlands and shallows; without the latter it would get the body stuck in low-ceilinged channels.
     */
    private static boolean fluidShortcut(Level level, BlockPos.MutableBlockPos cursor, float[] color,
                                         BlockPos from, BlockPos to) {
        if (color != PathColors.SWIM && color != PathColors.BOAT && color != PathColors.DROWNING) {
            return false;
        }
        if (from.distSqr(to) > (double) MAX_FLUID_SHORTCUT_BLOCKS * MAX_FLUID_SHORTCUT_BLOCKS) {
            return false;
        }
        // The check uses cell centers of block coordinates. Draw positions are lifted only for water-surface segments,
        // so passing those would scan the air cell one above
        Vec3 a = new Vec3(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
        Vec3 b = new Vec3(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
        return VoxelRay.traverse(a, b, (x, y, z) -> {
            cursor.set(x, y, z);
            if (!CellData.water(CellData.flagsOf(level.getBlockState(cursor)))) {
                return false;
            }
            cursor.set(x, y + 1, z);
            return CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(cursor)));
        });
    }

    /**
     * Whether a run that only walks across level ground at one height may become <b>a straight line as far as it can pass</b> rather than a grid-aligned staircase.
     *
     * <p>The search solves on an 8-direction grid, so level-ground routes heading anywhere other than 45-degree diagonals become a mix of diagonal and straight steps.
     * Walking the staircase as-is is up to about 8% longer than a straight line (re-straightening optimal routes on real terrain averages about 3% in the Nether and End).
     * Walking on level ground involves no digging, placing or jumping, and the position of each move means nothing, so as underwater, only the direction is shown.
     *
     * <p>Requires, in every column the 0.6-wide body touches while moving along the chord, a standable floor underfoot and air for body and head passable without digging.
     * Looking only at the chord through column centers would produce lines that clip chipped floor corners or wall corners.
     */
    private static boolean landShortcut(Level level, BlockPos.MutableBlockPos cursor, float[] color,
                                        BlockPos from, BlockPos to) {
        if (color != PathColors.WALK || from.getY() != to.getY()
                || from.distSqr(to) > (double) MAX_LAND_SHORTCUT_BLOCKS * MAX_LAND_SHORTCUT_BLOCKS) {
            return false;
        }
        double ax = from.getX() + 0.5;
        double az = from.getZ() + 0.5;
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / LAND_SHORTCUT_SAMPLE_BLOCKS));
        int y = from.getY();
        for (int s = 0; s <= samples; s++) {
            double t = (double) s / samples;
            double px = ax + dx * t;
            double pz = az + dz * t;
            for (int x = (int) Math.floor(px - PLAYER_HALF_WIDTH); x <= (int) Math.floor(px + PLAYER_HALF_WIDTH); x++) {
                for (int z = (int) Math.floor(pz - PLAYER_HALF_WIDTH); z <= (int) Math.floor(pz + PLAYER_HALF_WIDTH); z++) {
                    if (!walkableColumn(level, cursor, x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean walkableColumn(Level level, BlockPos.MutableBlockPos cursor, int x, int y, int z) {
        long floor = CellData.flagsOf(level.getBlockState(cursor.set(x, y - 1, z)));
        if (!CellData.standable(floor) || CellData.hazard(floor)) {
            return false;
        }
        for (int dy = 0; dy <= 1; dy++) {
            long body = CellData.flagsOf(level.getBlockState(cursor.set(x, y + dy, z)));
            if (!CellData.passableEmpty(body) || CellData.water(body) || CellData.hazard(body)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code b} lies on the straight line from {@code a} to {@code c} without turning back. */
    private static boolean continuesStraight(double ax, double ay, double az,
                                             double bx, double by, double bz,
                                             double cx, double cy, double cz) {
        double ux = bx - ax;
        double uy = by - ay;
        double uz = bz - az;
        double vx = cx - bx;
        double vy = cy - by;
        double vz = cz - bz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        if (crossX * crossX + crossY * crossY + crossZ * crossZ > COLLINEAR_EPSILON) {
            return false;
        }
        return ux * vx + uy * vy + uz * vz > 0;
    }

    /**
     * Turns a route cell into a point on the line. Returns {@code true} if drawn sunk.
     *
     * <p><b>Only water-surface cells</b> move the line off the cell center. In terms of block height, a water-surface
     * cell is inside the water, so drawing at the cell center (+0.55) sinks it into the rendered water surface, and
     * when riding a boat it's directly under your own body and invisible. For segments that travel over water (boats,
     * swimming at the surface), this is the normal case.
     *
     * <p>The direction it moves depends on <b>whether there is footing</b>. With your feet down, eye level is above the
     * surface, so the line is lifted above the surface (boats, wading through shallows). With no footing, i.e. swimming,
     * eye level is the surface itself, so instead it's sunk by {@link #SWIM_LINE_DEPTH}. <b>Sinking also removes the step
     * between surface and underwater</b>: back when it was lifted, the line dropped 1.5 blocks when the route dipped just
     * one block (the surface cell at +1.05, the one below at +0.55).
     *
     * <p>Using the cell's Y as-is for cells other than the water surface is unchanged. Previously <b>underwater cells were
     * aligned to the surface column by column</b>, so {@code SwimUp}/{@code SwimDown}, which share XZ and differ only in Y,
     * collapsed into one point, and diving and surfacing became zero-length segments that vanished from rendering.
     */
    private static boolean center(Level level, BlockPos pos, PathStep step,
                                  double[] outX, double[] outY, double[] outZ, int index) {
        outX[index] = pos.getX() + 0.5;
        outZ[index] = pos.getZ() + 0.5;
        if (!isWaterSurface(level, pos)) {
            outY[index] = pos.getY() + 0.55;
            return false;
        }
        if (sinkable(level, pos, step)) {
            outY[index] = pos.getY() + 1.0 - SWIM_LINE_DEPTH;
            return true;
        }
        outY[index] = pos.getY() + 1.0 + WATER_SURFACE_OFFSET;
        return false;
    }

    /**
     * Whether this water-surface cell may be drawn sunk as part of a swimming segment.
     *
     * <p>Boats are excluded because their eye level differs ({@link #SWIM_LINE_DEPTH}). Footing is checked because
     * segments that <b>walk</b> through shallows also pass through water-surface cells; sinking there would put the
     * line below the ground you're walking on. {@code MoveKind.SWIM} is set even with feet down, so footing is checked rather than the kind.
     */
    private static boolean sinkable(Level level, BlockPos pos, PathStep step) {
        return step != null && !step.boating()
                && !CellData.standable(CellData.flagsOf(level.getBlockState(pos.below())));
    }

    /** A water cell whose cell above isn't water, i.e. the water surface. */
    private static boolean isWaterSurface(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER)
                && !level.getFluidState(pos.above()).is(FluidTags.WATER);
    }
}
