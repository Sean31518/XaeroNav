package net.prason.xaeronav.pathfinding.cost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dig cost calculation. Handles only the plain break cost of a single cell.
 * Falling-block chains span multiple cells, so they are scanned once on the
 * {@code AStarPathfinder} side, which knows the required cells (adding them per cell here would double-count).
 *
 * <p>Hardness and tool speed come not from our own table but straight from what Minecraft itself uses:
 * {@link BlockState#getDestroySpeed} / {@link ItemStack#getDestroySpeed}.
 * This avoids reimplementing a hardness chart by hand and always matches vanilla behavior.
 * The exception is Efficiency, which is a player attribute and cannot be read from the {@link ItemStack},
 * so the same formula as {@code Player#getDigSpeed} is reproduced here.
 *
 * <p>{@code BlockState#getDestroySpeed} just returns a precomputed field and does not touch the level, so
 * it can be called from a worker thread by passing {@link EmptyBlockGetter}. The hotbar is received as
 * a copy {@code ChunkView} made on the main thread (touching the live {@code Inventory} from a worker
 * thread would race).
 */
public final class DigCost {

    private DigCost() {
    }

    public static double compute(ItemStack[] hotbar, int[] hotbarEfficiency, BlockState state) {
        if (!DiggableBlocks.isDiggable(state)) {
            return ActionCosts.INFEASIBLE;
        }

        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0f) {
            return ActionCosts.INFEASIBLE;
        }

        double bestEffort = bestToolEffort(hotbar, hotbarEfficiency, state);
        if (Double.isInfinite(bestEffort)) {
            return ActionCosts.INFEASIBLE;
        }

        return hardness * bestEffort + ActionCosts.DIG_OVERHEAD_TICKS;
    }

    /**
     * Computes "divisor / speed" for each item in the hotbar (+ bare hand) and returns the minimum.
     * hardness is the same for all candidates, so it does not need to be part of the comparison.
     */
    private static double bestToolEffort(ItemStack[] hotbar, int[] hotbarEfficiency, BlockState state) {
        double best = effort(ItemStack.EMPTY, 0, state);
        for (int slot = 0; slot < hotbar.length; slot++) {
            double e = effort(hotbar[slot], hotbarEfficiency[slot], state);
            if (e < best) {
                best = e;
            }
        }
        return best;
    }

    private static double effort(ItemStack stack, int efficiencyLevel, BlockState state) {
        double speed = stack.getDestroySpeed(state);
        if (speed <= 0.0) {
            return ActionCosts.INFEASIBLE;
        }
        // Efficiency is added as the player's MINING_EFFICIENCY attribute, not as tool speed, so it is
        // not included in ItemStack#getDestroySpeed. The condition for adding it (when the base speed exceeds 1,
        // i.e. the tool is right for the target) also follows Player#getDigSpeed
        if (speed > 1.0 && efficiencyLevel > 0) {
            speed += (double) efficiencyLevel * efficiencyLevel + 1.0;
        }
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        double divisor = correctTool ? 30.0 : 100.0;
        return divisor / speed;
    }
}
