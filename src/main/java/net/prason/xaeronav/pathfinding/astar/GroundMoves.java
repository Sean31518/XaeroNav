package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * Ground move candidate generation (walk, diagonal, ascend/descend, jump, ladder, fall). Part of the
 * split of {@link AStarPathfinder}: the search loop, open set and node table stay in
 * {@link AStarPathfinder}, and only candidate generation was extracted here.
 *
 * <p>Exactly one is created per search (see the {@link AStarPathfinder} constructor). It is not
 * recreated on every expansion, so no allocations are added to the hot path. The fields and helpers
 * of {@link AStarPathfinder} accessed via {@code owner} mean the same as in the original class: the
 * logic was only moved, with no behavior change.
 */
final class GroundMoves {

    /**
     * Maximum width of a gap that can be jumped across (the landing spot is one block past the gap). A
     * sprint jump covers just under 4 blocks horizontally during its ~12.5 ticks of airtime, so a 3-block gap (landing 4 blocks ahead) is the vanilla reach limit.
     */
    private static final int MAX_JUMP_GAP_BLOCKS = 3;

    private final AStarPathfinder owner;

    GroundMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    void addTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(owner.view.cell(x, y, z));
        owner.relax(from, x, y, z, owner.stepCost(x, y, z) + owner.submerged(from, bodyCost),
                inWater ? MoveKind.SWIM : MoveKind.TRAVERSE);
    }

    /**
     * Diagonal move at the same height. With only the 4 cardinal directions, terrain that runs diagonally
     * would force a 2-move zigzag over a stretch that should take 1 move, an unnecessary detour.
     * Allowed only when both corner cells ({@link AStarPathfinder#clearWithoutDigging}) are passable without digging,
     * so no path is generated where the body slips through the corner of a wall.
     */
    void addDiagonalTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(owner.view.cell(x, y, z));
        owner.relax(from, x, y, z,
                owner.stepCost(x, y, z) * ActionCosts.DIAGONAL_DISTANCE + owner.submerged(from, bodyCost),
                inWater ? MoveKind.SWIM : MoveKind.DIAGONAL);
    }

    void addAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, from.y, z))) {
            return;
        }
        // You can't jump while holding onto a ladder/vine (same reason as addJumpGap: onGround() is false so
        // jumpFromGround() is never called)
        if (CellData.climbable(owner.view.cell(from.x, from.y, from.z))) {
            return;
        }
        // Headroom above the takeoff spot. If blocked, you can't jump as-is, but in caves breaking one block
        // of ceiling to climb up is the normal approach, so keep it as an option when it can be dug
        double clearanceCost = owner.columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        owner.relax(from, x, y, z,
                ActionCosts.ascendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z))
                        + owner.submerged(from, clearanceCost + bodyCost),
                MoveKind.ASCEND);
    }

    void addDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        // Stepping into water needs no floor. Shores are usually one block above the water surface, so
        // without this there would be no way at all to enter the sea from the shore
        boolean intoWater = CellData.water(owner.view.cell(x, y, z));
        if (!intoWater && !CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = owner.descendingBodyCost(x, from.y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        double baseCost = intoWater ? ActionCosts.SWIM_ONE_BLOCK
                : ActionCosts.descendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z));
        owner.relax(from, x, y, z, baseCost + owner.submerged(from, bodyCost),
                intoWater ? MoveKind.SWIM_DESCEND : MoveKind.DESCEND);
    }

    /**
     * Advance diagonally by one block while climbing one step (broadens the short-range repertoire). With
     * {@link #addAscend} limited to the 4 cardinal directions, stair-like terrain running diagonally splits
     * a 1-move stretch into 2 moves ("climb, then sideways"). Like {@link #addDiagonalTraverse}, both corner
     * cells must be passable without digging so the body does not slip through a wall corner.
     *
     * <p>Digging is not allowed. Rather than digging during a corner-cutting move, digging straight along
     * a cardinal is safer and gets the cost right. Like {@link #addAscend}, the model is dominated by jump
     * time, so terrain speed factors (ice, soul sand, etc.) are ignored.
     */
    void addDiagonalAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, from.y, z))) {
            return;
        }
        // You can't jump while holding onto a ladder/vine (same reason as addJumpGap)
        if (CellData.climbable(owner.view.cell(from.x, from.y, from.z))) {
            return;
        }
        // Check the 2 corner columns at the arrival height. The corners at the departure height are the step itself, so they may be blocked
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        // Headroom above the takeoff spot. If blocked, you can't jump
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        if (!owner.clearWithoutDigging(x, y, z)) {
            return;
        }
        owner.relax(from, x, y, z,
                ActionCosts.diagonalAscendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z)),
                MoveKind.DIAGONAL_ASCEND);
    }

    /**
     * Advance diagonally by one block while descending one step. Same goal as {@link #addDiagonalAscend}. Digging is not allowed.
     *
     * <p>Unlike {@link #addDescend}, stepping into water is not handled (the floor must be {@code standable}).
     * The cardinal side already handles the shoreline, and adding diagonals makes the path jitter at the water's edge.
     */
    void addDiagonalDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        // Check the 2 corner columns at the departure height
        if (!owner.clearWithoutDigging(from.x + dx, from.y, from.z)
                || !owner.clearWithoutDigging(from.x, from.y, from.z + dz)) {
            return;
        }
        // The 3 body cells at the arrival spot (the same vertical column as Descend). Calling it twice
        // checks y-1 to y+1 (the landing feet/head, and the same height as the departure feet) together
        if (!owner.clearWithoutDigging(x, y, z) || !owner.clearWithoutDigging(x, y + 1, z)) {
            return;
        }
        // A diagonal descent with the body underwater is swum, so a sprint-based price is too cheap:
        // it would be cheaper than a diagonal swim (7.857) and produce paths that zigzag up and down underwater.
        // Stepping into water from above is already excluded by the standable requirement above, so this
        // only covers the "already in water" case
        boolean swimming = CellData.water(owner.view.cell(from.x, from.y, from.z))
                || CellData.water(owner.view.cell(x, y, z));
        double cost = swimming
                ? ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE
                : ActionCosts.diagonalDescendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z));
        owner.relax(from, x, y, z, cost, swimming ? MoveKind.SWIM_DESCEND : MoveKind.DIAGONAL_DESCEND);
    }

    /**
     * Jump across a gap (same height, cardinal directions only).
     *
     * <p>Without this, a 1-block crack anyone would step over without thinking (a creek, cave fissure, ravine branch)
     * would mean placing a block to cross or taking a big detour.
     *
     * <p>Up to {@link #MAX_JUMP_GAP_BLOCKS} blocks. That is exactly the sprint jump's reach limit;
     * beyond it, no run-up is long enough. A missed jump means a fall, so whether to offer jumps at all
     * can be switched off with {@code CellSource#jumpGapEnabled()}.
     *
     * <p>Gaps are tried nearest first, settling on the first distance with a valid landing. With several landing spots
     * in one direction, there is no reason to jump far if you can land closer ({@link ActionCosts#jumpAcrossGap} also costs more with distance).
     *
     * <p>You can't dig mid-air, so the space passed through must be clear without digging. Headroom is checked too:
     * a jump rises 1.25 blocks, so a ceiling makes the jump fail and you drop into the gap.
     */
    void addJumpGap(PathNode from, int dx, int dz) {
        if (!owner.view.jumpGapEnabled()) {
            return;
        }
        int y = from.y;
        if (CellData.standable(owner.view.cell(from.x + dx, y - 1, from.z + dz))) {
            // There is floor, not a gap. If it can be walked, Traverse is cheaper
            return;
        }
        // Headroom above the takeoff spot. If blocked, the jump itself is impossible
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        // Soul sand and honey prevent reaching top sprint speed. Reach is determined by horizontal speed at
        // takeoff (airtime is constant regardless of distance), so jumping while slowed always drops into the gap.
        // The speed factor is looked up like the walking cost ({@code AStarPathfinder#stepCost}), following vanilla's
        // {@code getBlockSpeedFactor}
        if (slowedTakeoff(from.x, y, from.z)) {
            return;
        }
        // You can't jump from cobwebs. WebBlock#entityInside keeps multiplying movement by 0.25, so
        // both the speed built up by the sprint run-up and the jump's initial velocity are cut sharply at takeoff.
        // In theory it may sometimes reach across, but the chance of missing and falling is too high to be worth suggesting
        if (CellData.cobweb(owner.view.cell(from.x, y, from.z))) {
            return;
        }
        // You can't jump while holding onto a ladder/vine. onGround() is false so jumpFromGround() itself is
        // never called (LivingEntity#aiStep), and even if grounded while holding on, handleOnClimbable clamps
        // horizontal speed to ±0.15, so neither the sprint's 0.286 nor the takeoff boost of 0.2 survives
        if (CellData.climbable(owner.view.cell(from.x, y, from.z))
                || !CellData.standable(owner.view.cell(from.x, y - 1, from.z))) {
            return;
        }
        // A run-up is needed. Top sprint speed takes about 5 ticks (~1 block) to reach from standstill, and
        // mid-air acceleration is only 0.02/tick (LivingEntity#getFlyingSpeed), so reach is determined directly
        // by takeoff speed. From a 1-block-wide platform the run-up is limited to within your own block (~0.5 blocks),
        // and a 3-block gap, even if theoretically reachable, has zero margin. We only tell the player to jump;
        // it's the human who misses and falls (same policy as JUMP_REACH_PENALTY)
        if (!hasRunUp(from, y, dx, dz)) {
            return;
        }

        // How deep below the gap being jumped. Add a hazard fee using the same price table as addBridge
        double dropRisk = 0.0;
        for (int gap = 1; gap <= MAX_JUMP_GAP_BLOCKS; gap++) {
            int gapX = from.x + gap * dx;
            int gapZ = from.z + gap * dz;
            // If the space being jumped through is blocked, no run-up will reach beyond it
            if (!owner.clearWithoutDigging(gapX, y, gapZ)
                    || !CellData.occupiableWithoutDigging(owner.view.cell(gapX, y + 2, gapZ))) {
                return;
            }
            // Cobwebs have no collision box, so clearWithoutDigging passes through them, but if the body brushes one
            // mid-air, speed is cut by 0.25 again. Even checking only the takeoff, a web along the path makes you
            // drop into the gap for the same reason
            if (CellData.cobweb(owner.view.cell(gapX, y, gapZ))
                    || CellData.cobweb(owner.view.cell(gapX, y + 1, gapZ))) {
                return;
            }
            // Don't jump gaps over lava. Jump costs assume a miss means a fall, but over lava that
            // "miss" is death, so it is no longer something cost can balance.
            // Treat gaps whose bottom can't be read (unloaded) the same: we can't be sure it isn't lava
            if (owner.scans.lavaOrUnknownBelow(gapX, y, gapZ)) {
                return;
            }
            int gapDrop = missDrop(gapX, y, gapZ);
            if (owner.avoidRiskyJumps && gapDrop >= owner.view.fatalFallBlocks()) {
                // A gap where missing is fatal. Unlike lava, this does not "permanently remove the move of jumping over this gap";
                // the relaxation ladder opens only when it turns out there is no way around at all (riskyJumpBlocked)
                owner.markRiskyJumpBlocked();
                return;
            }
            dropRisk += ActionCosts.dropRiskPenalty(gapDrop, owner.view.fatalFallBlocks());
            int x = from.x + (gap + 1) * dx;
            int z = from.z + (gap + 1) * dz;
            if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
                // Can't land yet. The gap continues one more block
                continue;
            }
            if (!owner.clearWithoutDigging(x, y, z)) {
                return;
            }
            owner.relax(from, x, y, z, ActionCosts.jumpAcrossGap(gap) + dropRisk, MoveKind.JUMP);
            return;
        }
    }

    /**
     * How many blocks you fall if you miss this gap. Returns {@code CellSource#fatalFallBlocks()} if there is no bottom (void).
     *
     * <p>The drop is measured the same way as {@link #addFall} and {@code BuildMoves#addBridge}: those look at
     * the height of an "intentional descent", while this looks at the same drop as "a missed jump".
     * Lava and unloaded cells are already rejected by the caller ({@code lavaOrUnknownBelow}).
     */
    private int missDrop(int x, int y, int z) {
        int obstacleY = owner.scans.firstNonAirBelow(x, y - 1, z);
        if (obstacleY == ColumnScans.NOTHING_BELOW || obstacleY == ColumnScans.UNREADABLE_BELOW) {
            // The caller already rejects unloaded cells. It shouldn't get here, but make it explicit
            // so "unreadable" is never treated as "not dangerous"
            return owner.view.fatalFallBlocks();
        }
        long obstacle = owner.view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            // Vanilla resets fall distance on landing in water, so no fall height is fatal
            return 0;
        }
        return y - obstacleY - 1;
    }

    /** Whether the takeoff spot is on a slowing block (looked up the same way as vanilla's {@code Entity#getBlockSpeedFactor}). */
    private boolean slowedTakeoff(int x, int y, int z) {
        return owner.takeoffSpeedFactor(x, y, z) < 1.0;
    }

    /** Whether there is one block of footing behind the takeoff spot (opposite the jump direction) to run up from. */
    private boolean hasRunUp(PathNode from, int y, int dx, int dz) {
        int x = from.x - dx;
        int z = from.z - dz;
        return CellData.standable(owner.view.cell(x, y - 1, z)) && owner.clearWithoutDigging(x, y, z);
    }

    /**
     * Grab onto a ladder/vine from the side. Unlike {@link #addTraverse}, no footing is required;
     * this is needed to move onto a ladder hung partway up a vertical shaft.
     */
    void addClimb(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // If there is footing, Traverse produces the same move
            return;
        }
        if (!CellData.climbable(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.WALK_ONE_BLOCK, MoveKind.CLIMB);
    }

    /** Climb a ladder/vine. Past the top, you leave via a horizontal move from there (you can't go above the top). */
    void addClimbUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.climbable(owner.view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(from.x, y + 1, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.LADDER_UP_ONE_BLOCK, MoveKind.CLIMB_UP);
    }

    void addClimbDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.climbable(owner.view.cell(from.x, y, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.LADDER_DOWN_ONE_BLOCK, MoveKind.CLIMB_DOWN);
    }

    /**
     * Step off an edge and fall. A 1-block drop is handled by {@link #addDescend}, so this covers only falls of 2+ blocks.
     *
     * <p>By default, heights that cause fall damage are not offered (up to {@code ActionCosts#SAFE_FALL_BLOCKS}). Digging
     * down (Descend + digging) is also a way down, so a stair-like descent is better than suggesting a painful shortcut.
     * However, vanilla resets fall distance on landing in water, so any height is a safe descent there.
     *
     * <p>Only when allowed by config, damaging falls up to a health-derived limit and damage-free
     * water bucket MLG falls are added as candidates ({@code CellSource#maxFallDamagePoints} / {@code CellSource#canMlgWaterBucket}).
     */
    void addFall(PathNode from, int dx, int dz, int obstacleY) {
        if (obstacleY == ColumnScans.NOTHING_BELOW || obstacleY == ColumnScans.UNREADABLE_BELOW) {
            // No bottom (void), or what lies below can't be read. Either way, a landing spot can't be promised
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        // The 2 blocks stepped into must be open to leave the edge. You can't dig while falling, so they must be air
        if (!CellData.passableEmpty(owner.view.cell(x, from.y, z))
                || !CellData.passableEmpty(owner.view.cell(x, from.y + 1, z))) {
            return;
        }

        // Stepping off the edge is also slowed by the block underfoot (falling and after landing are unaffected)
        double takeoff = owner.takeoffSpeedFactor(from.x, from.y, from.z);
        long obstacle = owner.view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            owner.relax(from, x, obstacleY, z, ActionCosts.fallCost(from.y - obstacleY, takeoff),
                    MoveKind.FALL_TO_WATER);
            return;
        }
        if (!CellData.standable(obstacle)) {
            // Fences, ladders, etc.: things that don't serve as footing even if you fall onto them
            return;
        }
        int drop = from.y - obstacleY - 1;
        if (drop < 2) {
            return;
        }
        if (drop <= ActionCosts.SAFE_FALL_BLOCKS) {
            owner.relax(from, x, obstacleY + 1, z, ActionCosts.fallCost(drop, takeoff), MoveKind.FALL);
            return;
        }

        // Vanilla damage is ceil(fall distance - SAFE_FALL_DISTANCE). Fall distance is a whole number of blocks, so it's a plain subtraction
        int damage = drop - ActionCosts.SAFE_FALL_BLOCKS;
        boolean mlg = owner.view.canMlgWaterBucket();
        if (mlg) {
            owner.relax(from, x, obstacleY + 1, z,
                    ActionCosts.fallCost(drop, takeoff) + ActionCosts.MLG_WATER_OVERHEAD_TICKS,
                    MoveKind.FALL_MLG);
        }
        if (damage > owner.maxFallDamagePoints) {
            // A standable floor is there and reachable. Only the allowance is short: relaxing it might open a route.
            // Having got this far, it's neither void nor unloaded, so the flag correctly means "relaxing would help".
            //
            // Don't set it if a water bucket MLG already produces the same landing. That spot is passable regardless of the allowance,
            // so relaxing adds no moves; setting it would just make the relaxation ladder repeat the search without changing anything
            owner.markFallDamageCapBlocked(!mlg);
            return;
        }
        owner.relax(from, x, obstacleY + 1, z,
                ActionCosts.fallCost(drop, takeoff) + damage * ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT,
                MoveKind.FALL_DAMAGE);
    }
}
