package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * The full set of "risk allowances" loosened step by step when stuck. Bundles {@link RunCaps} (how many blocks/ticks in a row are allowed)
 * with the allowed fall-damage points.
 *
 * <p>They share one holder because the loosening side ({@code PathfindingExecutor}) holds the steps as a single
 * ladder. Loosening only one does nothing but pay for the same search again if the other one is still stuck.
 *
 * <p><b>Do not add {@code maxFallDamagePoints} directly to {@link RunCaps}.</b> There,
 * <b>0 means unlimited</b>, whereas for fall damage 0 means "none allowed", the exact opposite
 * ({@link RunCaps#NONE} would tip fall damage alone to the strictest side).
 *
 * @param maxFallDamagePoints how many points (in half hearts) of fall damage are acceptable. At 0, no fall beyond the safe
 *                            height is ever suggested. <b>Unlimited is not representable</b>: removing the cap would put
 *                            lethal falls into the guidance, so the loosening side derives the cap from health
 * @param allowRiskyJumps whether to allow jumps over bottomless void or over drops that kill on a miss. Avoided by default;
 *                        the loosening side opens it <b>only when not a single path could be drawn</b>. The user's intent is
 *                        "go around if you can", not "never jump"
 *                        (walking the rim of a C-shaped island beats jumping across its ends; between islands, jumping is the only way).
 *                        <b>Deliberately different</b> from {@code fallDamageToleranceEnabled}, which stays closed even to get unstuck:
 *                        that one is a preference for "I don't want to get hurt", and once declined no alternative is needed.
 *                        Here the only alternative is "no path at all", and jump legs always get
 *                        a warning color via {@code PathRisk.VOID_BELOW}
 * @param placedBlockBudget total number of footing blocks that may be placed along the whole path. 0 means unlimited. <b>It lives
 *                        here rather than in {@link RunCaps} because that one holds run lengths, i.e. "how many blocks in a row"</b>;
 *                        scaling a cumulative budget by the {@code RUN_CAP_LOOSEN_MULTIPLIERS} factors is meaningless
 *                        (the item count in the inventory doesn't grow with the terrain). The only way to loosen it is to remove it,
 *                        so it becomes 0 only on the last rung of the ladder
 * @param placeWithoutBlocks whether placement moves may be generated even when holding no blocks usable as footing.
 *                        <b>The last resort for getting unstuck</b>: in terrain where bridging is the only way, like island-hopping in
 *                        the End, not having blocks alone makes a path <b>fundamentally</b> impossible. With nothing in the
 *                        guidance, it just looks like "only island-hopping fails". Showing it makes clear "a bridge is needed here",
 *                        so the player can decide to mine for blocks or turn back. Treated the same as dropping {@code maxSubmergedTicks}
 *                        to show a dive longer than the player's breath, with the HUD telling how many blocks are needed
 */
public record Tolerances(RunCaps caps, int maxFallDamagePoints, boolean allowRiskyJumps,
                          int placedBlockBudget, boolean placeWithoutBlocks) {

    public static Tolerances of(CellSource view) {
        return new Tolerances(RunCaps.of(view), view.maxFallDamagePoints(), !view.avoidRiskyJumps(),
                view.placedBlockBudget(), false);
    }
}
