package net.prason.xaeronav.pathfinding.flight;

import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * Bends the "dotted line to the destination" shown while gliding so it avoids mountains and hills in between.
 *
 * <p>Its purpose is completely different from the walking A*. This is <b>not a route to follow but a line showing
 * which way to point the nose</b>, so it needs neither optimality nor a reachability guarantee. All it asks is "looks
 * natural and doesn't pierce the terrain"; accordingly the search only tries a single bend point, and falls back to the plain straight line on failure.
 *
 * <p>The bend point is the midpoint of start and end, shifted perpendicular to the direction of travel. The shift
 * directions are 5: up, left/right, and diagonally up; radii are widened from small to large trying every direction,
 * and the first that no longer pierces the terrain is taken.
 * <b>The extra distance of the V is {@code 2*sqrt((L/2)^2 + r^2) - L}, determined by the radius r alone regardless of direction</b>.
 * So checking in order of increasing radius, the first one found is the cheapest; "sideways for a narrow ridge,
 * over the top for a wide mountain range" is decided by the terrain without hard-coding a priority.
 */
public final class FlightLineRouter {

    /**
     * Lower and upper bound of the bend-point search radius, and the per-step multiplier (blocks). Geometric rather
     * than arithmetic so the same number of steps reaches from hills of a few blocks to 100-block mountain ranges. With
     * a multiplier of 1.5, 8 to 128 blocks takes 8 steps, overshooting the minimum radius by at most 1.5x (a coarseness that doesn't affect the line's look).
     */
    private static final double BEND_MIN_RADIUS = 8.0;

    private static final double BEND_MAX_RADIUS = 128.0;

    private static final double BEND_RADIUS_GROWTH = 1.5;

    /**
     * Range the search can touch. The bend point can swing sideways by up to {@link #BEND_MAX_RADIUS}, so the
     * {@link SearchBounds} prepared by the caller needs this much horizontal margin (if narrow, the swung point falls
     * outside the range = no data, and a mountain that could have been avoided isn't).
     */
    public static final int HORIZONTAL_MARGIN_BLOCKS = (int) BEND_MAX_RADIUS + 16;

    /** Vertical margin. Flight altitude is determined by the height of mountains along the way, not the start/destination Y, so it's thicker than horizontal. */
    public static final int VERTICAL_MARGIN_BLOCKS = 192;

    private final CellSource view;

    public FlightLineRouter(CellSource view) {
        this.view = view;
    }

    /**
     * Builds the dotted line from start to end. 2 points if it doesn't pierce the terrain, 3 points with a bend point
     * if it needs to avoid it. If no bend avoids it, returns the 2 points as-is (even if it doesn't look ideal, it
     * still fulfills its real role of showing where to go; erasing the line here would be worse).
     */
    public List<Vec3> findGuideLine(Vec3 start, Vec3 goal) {
        if (!intersectsTerrain(start, goal)) {
            return List.of(start, goal);
        }

        double dx = goal.x - start.x;
        double dz = goal.z - start.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-4) {
            // Destination is directly above or below. There's no defined sideways direction, and bending would be pointless anyway
            return List.of(start, goal);
        }
        // Horizontal vector perpendicular to the direction of travel (unit length)
        double perpX = -dz / horizontal;
        double perpZ = dx / horizontal;
        Vec3 middle = start.add(goal).scale(0.5);

        for (double radius = BEND_MIN_RADIUS; radius <= BEND_MAX_RADIUS; radius *= BEND_RADIUS_GROWTH) {
            for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
                Vec3 bend = bendPoint(middle, perpX, perpZ, radius, direction);
                if (!intersectsTerrain(start, bend) && !intersectsTerrain(bend, goal)) {
                    return List.of(start, bend, goal);
                }
            }
        }
        return List.of(start, goal);
    }

    /**
     * Order: left, right, upper-left, upper-right, up. Bending straight down can't avoid terrain, so it's not included.
     *
     * <p>For the same radius every direction has the same extra distance (determined by radius alone), so at tight
     * distances where multiple directions avoid the terrain simultaneously, the order serves directly as the tiebreak.
     * Trying directions with larger horizontal components first prefers "veer sideways" over "go over the top" in close calls.
     */
    private static final int DIRECTION_COUNT = 5;

    private static final double DIAGONAL = Math.sqrt(0.5);

    /**
     * Bend point: the midpoint shifted by {@code radius} in the {@code direction}. Y is capped at the world's top
     * (returning a point above that would always be out of range = no data).
     */
    private Vec3 bendPoint(Vec3 middle, double perpX, double perpZ, double radius, int direction) {
        double lateral = switch (direction) {
            case 0 -> 1.0;
            case 1 -> -1.0;
            case 2 -> DIAGONAL;
            case 3 -> -DIAGONAL;
            default -> 0.0;
        };
        double vertical = switch (direction) {
            case 0, 1 -> 0.0;
            case 2, 3 -> DIAGONAL;
            default -> 1.0;
        };
        SearchBounds bounds = view.bounds();
        return new Vec3(
                middle.x + perpX * lateral * radius,
                Mth.clamp(middle.y + vertical * radius, bounds.minY(), bounds.maxY()),
                middle.z + perpZ * lateral * radius);
    }

    /**
     * Whether the segment pierces the terrain. The scan is done by {@link VoxelRay}.
     */
    private boolean intersectsTerrain(Vec3 a, Vec3 b) {
        return !VoxelRay.traverse(a, b, (x, y, z) -> !isSolid(x, y, z));
    }

    /**
     * Out-of-range and unloaded chunks ({@code ABSENT}) are not treated as obstacles. The destination is normally far
     * beyond render distance, and treating that as a wall would make the line always judged "piercing", just trying
     * every direction every time without ever fixing it. Avoiding only visible terrain and letting the unseen part
     * pass through fits this line's role.
     */
    private boolean isSolid(int x, int y, int z) {
        long cell = view.cell(x, y, z);
        return CellData.present(cell) && !CellData.passableEmpty(cell);
    }
}
