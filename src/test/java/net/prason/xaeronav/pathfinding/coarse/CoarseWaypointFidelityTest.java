package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Test;

/**
 * Pins down with real End save data that <b>layer 2 always finds "a place to stand" for the intermediate
 * targets layer 1 produces</b>.
 *
 * <p>{@code CoarseRouter#toBlockPos} always returns <b>the chunk center</b> regardless of cell type.
 * In The End, cells with only a few percent of floor are priced the same as dense cells, so an
 * intermediate target's coordinates can be <b>directly above the void</b>. Layer 3 builds a bridge into the
 * void to reach that point; this genuinely exists as the mechanism behind "making you cross for no reason".
 *
 * <p><b>But with real data this mechanism doesn't fire.</b> Examining 107 routes and 190 intermediate
 * targets, layer 2's 8-block snap ({@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}) <b>rescued all
 * of them</b>. As long as layer 2 is usable, a raw chunk center never reaches layer 3.
 *
 * <p>The remaining suspect is "when layer 2 isn't usable": if Xaero's map data lacks that segment,
 * {@code CorridorLegSolver.prepare} returns {@code view=null} and {@code PathfindingState#solveLeg} falls
 * back to the raw chunk center. <b>That can't be measured offline</b> (it depends on Xaero's region
 * loading state).
 */
class CoarseWaypointFidelityTest {

    /**
     * Radius within which layer 2 snaps an intermediate target to a place to stand ({@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}).
     * If none is found here, {@code prepare} returns {@code view=null}.
     */
    private static final int LAYER2_SNAP_RADIUS = 8;

    private static FakeCells endTerrain() throws IOException {
        // Match the real game's defaults (maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96, fall tolerance 6)
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    /** Horizontal distance from {@code waypoint} to the nearest column you can actually stand in. {@link Double#NaN} if none. */
    private static double distanceToNearestStandable(CellSource cells, SearchBounds bounds,
                                                      BlockPos waypoint, int searchRadius) {
        double best = Double.NaN;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = waypoint.getX() + dx;
                int z = waypoint.getZ() + dz;
                if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
                    continue;
                }
                if (TerrainFixture.standableY(cells, bounds, x, z) == Integer.MIN_VALUE) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (Double.isNaN(best) || d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * Draws many routes from a grid of real start and destination points and checks that <b>not a single
     * intermediate target layer 2 can't rescue appears</b>. If this breaks after changing layer 1's cost, the
     * thinning interval or layer 2's snap radius, conditions have changed so that "unstandable intermediate targets" occur in real data too.
     */
    @Test
    void layer2AlwaysSnapsCoarseWaypointsOntoStandableGround() throws IOException {
        FakeCells terrain = endTerrain();
        SearchBounds b = terrain.bounds();
        CoarseMap map = LiveCoarseSampler.sample(terrain, b);

        // Lay a grid of start and destination points over the range with data (1130..1390 x 990..1250)
        List<BlockPos> anchors = new ArrayList<>();
        for (int x = 1150; x <= 1370; x += 55) {
            for (int z = 1010; z <= 1230; z += 55) {
                int y = TerrainFixture.standableY(terrain, b, x, z);
                if (y != Integer.MIN_VALUE) {
                    anchors.add(new BlockPos(x, y, z));
                }
            }
        }

        int intermediates = 0;
        int layer2WouldFail = 0;
        List<String> examples = new ArrayList<>();
        for (BlockPos from : anchors) {
            for (BlockPos to : anchors) {
                if (from.equals(to)) {
                    continue;
                }
                CoarseRouter.Route route = CoarseRouter.findRoute(map, from, to, false,
                        CoarseRouter.BridgePolicy.ALLOW);
                if (!route.reachedGoal() || route.waypoints().size() < 2) {
                    continue;
                }
                // Finally, replaceLast in PathfindingState#freshRoute overwrites it with the actual destination
                for (int i = 0; i < route.waypoints().size() - 1; i++) {
                    BlockPos w = route.waypoints().get(i);
                    intermediates++;
                    if (Double.isNaN(distanceToNearestStandable(terrain, b, w, LAYER2_SNAP_RADIUS))) {
                        layer2WouldFail++;
                        if (examples.size() < 6) {
                            examples.add(w.toShortString());
                        }
                    }
                }
            }
        }

        assertEquals(0, layer2WouldFail,
                "intermediate targets not rescued by layer 2's snap radius " + LAYER2_SNAP_RADIUS + ": " + layer2WouldFail
                        + "/" + intermediates + " (e.g. " + examples + ") = "
                        + "conditions changed so the \"unstandable intermediate target\" hypothesis holds in real data");
    }
}
