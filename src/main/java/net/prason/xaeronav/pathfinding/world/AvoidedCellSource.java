package net.prason.xaeronav.pathfinding.world;

import org.jspecify.annotations.Nullable;
import java.util.Collection;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;

/**
 * Terrain where only the given cells are treated as "absent". <b>Keeps the next search from picking again the cells
 * that the previous route re-check judged as failing.</b>
 *
 * <p>When the search's cell checks and {@code PathValidator}'s checks disagree at the same coordinate, the route the
 * search let through is immediately judged invalid, and the re-planned route passes through the same cell again. In a
 * real-world report (#47), "terrain changed → detour → failed to rejoin → re-plan" repeated for 14 seconds at the same
 * coordinate (the footing at {@code 2051,63,1283}). The proper fix is to remove the disagreement itself, but this
 * is what <b>stops the loop even if a disagreement remains</b>.
 *
 * <p>It returns {@link CellData#ABSENT} because in this codebase that value already means "cannot touch, cannot
 * stand, cannot dig" (treated the same as an unloaded chunk). It cannot be dug open either, so the search always
 * draws a line that avoids the cell.
 *
 * <p>The world is not modified. The delegate is not read at construction time either (only the difference is held),
 * the same structure as {@link PlannedCellSource}.
 */
public final class AvoidedCellSource implements CellSource {

    private final CellSource source;
    private final LongSet avoided;

    private AvoidedCellSource(CellSource source, LongSet avoided) {
        this.source = source;
        this.avoided = avoided;
    }

    /**
     * Returns the delegate as-is if there are no cells to avoid. {@link #cell} is called millions of times per search,
     * so the usual case with nothing to avoid should not pay for an extra level of indirection.
     */
    public static CellSource wrap(CellSource source, Collection<BlockPos> avoided) {
        if (avoided.isEmpty()) {
            return source;
        }
        LongSet keys = new LongOpenHashSet(avoided.size());
        for (BlockPos pos : avoided) {
            keys.add(pos.asLong());
        }
        return new AvoidedCellSource(source, keys);
    }

    @Override
    public long cell(int x, int y, int z) {
        if (avoided.contains(BlockPos.asLong(x, y, z))) {
            return CellData.ABSENT;
        }
        return source.cell(x, y, z);
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return source.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return source.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return source.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return source.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return source.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return source.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return source.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return source.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return source.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return source.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return source.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return source.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return source.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return source.avoidRiskyJumps();
    }

    @Override
    public boolean strictLimits() {
        return source.strictLimits();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return source.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return source.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return source.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return source.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return source.ridingBoat();
    }

    @Override
    public @Nullable Mount mount() {
        return source.mount();
    }

    @Override
    public RouteProfile routeProfile() {
        return source.routeProfile();
    }

    @Override
    public boolean swimmingEnabled() {
        return source.swimmingEnabled();
    }

    @Override
    public int openSkyY(int x, int z) {
        return source.openSkyY(x, z);
    }

    @Override
    public int surfacedY(int x, int z) {
        return source.surfacedY(x, z);
    }
}
