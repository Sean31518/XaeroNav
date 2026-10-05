package net.prason.xaeronav.pathfinding.world;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;

/**
 * Adapter that passes a {@link SurfaceGrid} (corridor-only, block-resolution Xaero surface data) as a {@link CellSource}
 * to {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder} (long-range route layer 2).
 *
 * <p>The real 3D block structure (caves, overhangs, buildings) is invisible, so "standable / passable / water / lava"
 * is synthesized from just one surface height per column (x,z). Below the surface is treated as unknown matter that
 * is standable but not diggable, and digging, which the detailed search (layer 3) assumes, isn't brought into layer 2.
 * Since only the {@code CellData} bits are shared, the existing {@code AStarPathfinder} move generation (Traverse/Ascend/Descend/Fall/Swim/JumpGap) works unmodified.
 */
public final class SurfaceCellSource implements CellSource {

    private final SurfaceGrid grid;
    private final SearchBounds bounds;
    private final boolean jumpGapEnabled;
    private final int maxSubmergedTicks;

    public SurfaceCellSource(SurfaceGrid grid, SearchBounds bounds, boolean jumpGapEnabled,
                             int maxSubmergedTicks) {
        this.grid = grid;
        this.bounds = bounds;
        this.jumpGapEnabled = jumpGapEnabled;
        this.maxSubmergedTicks = maxSubmergedTicks;
    }

    @Override
    public long cell(int x, int y, int z) {
        byte kind = grid.kindAt(x, z);
        short ground = grid.groundHeightAt(x, z);
        if (kind == CoarseMap.NO_DATA || ground == SurfaceGrid.UNKNOWN_HEIGHT) {
            return CellData.ABSENT;
        }
        short ceiling = kind == CoarseMap.WATER ? grid.surfaceHeightAt(x, z) : ground;
        if (y > ceiling) {
            return air();
        }
        if (kind == CoarseMap.WATER && y > ground) {
            return CellData.withDigTicks(CellData.PRESENT | CellData.WATER, 0.0);
        }
        if (y == ground && kind == CoarseMap.LAVA) {
            return CellData.withDigTicks(CellData.PRESENT | CellData.LAVA, Double.POSITIVE_INFINITY);
        }
        // The surface itself or below it; both are treated the same, as "solid ground of unknown matter"
        return solidGround();
    }

    private static long air() {
        return CellData.withDigTicks(CellData.PRESENT | CellData.PASSABLE_EMPTY, 0.0);
    }

    /** Ground of unknown matter. Standable but not diggable; layer 2 doesn't handle digging. */
    private static long solidGround() {
        return CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, Double.POSITIVE_INFINITY);
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    /** Layer 2 doesn't propose bridging by placing blocks; it can't know what can be placed on terrain of unknown matter. */
    @Override
    public boolean canPlaceBlocks() {
        return false;
    }

    @Override
    public boolean jumpGapEnabled() {
        return jumpGapEnabled;
    }

    /** {@link #canPlaceBlocks()} is false, so bridges themselves aren't offered. */
    @Override
    public boolean lavaBridgingEnabled() {
        return false;
    }

    /** Bridges aren't offered, so the limit is meaningless. */
    @Override
    public int maxBridgeRunBlocks() {
        return 0;
    }

    /**
     * Layer 2 also has a diving limit. Air supply is a fixed vanilla value, not player state, so it doesn't depend on
     * information layer 2 doesn't know (health, inventory); unlike {@link #maxFallDamagePoints}, there's no reason to
     * disable it with 0. Layer 2 holds water columns as {@code (bottom, surface]}, so without cutting here the corridor
     * solution would return routes diving along the bottom, disagreeing with layer 3.
     */
    @Override
    public int maxSubmergedTicks() {
        return maxSubmergedTicks;
    }

    /** Layer 2 doesn't know the player's state (health, inventory), so it proposes neither damaging drops nor water-bucket MLGs. */
    @Override
    public int maxFallDamagePoints() {
        return 0;
    }

    /**
     * Layer 2's result is only <b>coordinates of intermediate targets, not a route actually walked</b> ({@code CorridorLegSolver}).
     * Judging jump risk is layer 3's job, so here jumps are allowed as before and only the route's shape is taken.
     * Layer 2's 2.5D grid only has the surface and can't answer "what's below", so avoiding them here would erase
     * jumps wholesale without any basis for the judgment, and corridor refinement would fail widely.
     */
    @Override
    public boolean avoidRiskyJumps() {
        return false;
    }

    /** Layer 2's result is only intermediate targets, so whether to hold the limits is decided by layer 3's search. */
    @Override
    public boolean strictLimits() {
        return false;
    }

    /** {@link #avoidRiskyJumps()} is false, so this isn't referenced. */
    @Override
    public int fatalFallBlocks() {
        return Integer.MAX_VALUE;
    }

    /** Layer 2 knows neither the dimension nor the presence of water, so it stays at the terminal-velocity lower bound, safe anywhere. */
    @Override
    public double minDescentTicksPerBlock() {
        return ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
    }

    @Override
    public boolean canMlgWaterBucket() {
        return false;
    }

    /** Layer 2 doesn't know the inventory, so it doesn't offer boats either. */
    @Override
    public boolean boatAvailable() {
        return false;
    }

    @Override
    public int openSkyY(int x, int z) {
        byte kind = grid.kindAt(x, z);
        short height = kind == CoarseMap.WATER ? grid.surfaceHeightAt(x, z) : grid.groundHeightAt(x, z);
        return height == SurfaceGrid.UNKNOWN_HEIGHT ? Integer.MAX_VALUE : height + 1;
    }
}
