package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * Move generation that places blocks to stand on (bridges, pillars). Part of the split of
 * {@link AStarPathfinder}; see the class Javadoc of {@link GroundMoves}.
 */
final class BuildMoves {

    private final AStarPathfinder owner;

    BuildMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    /**
     * A move that crosses a gap with no floor (e.g. between End islands) by placing a block. The
     * horizontal counterpart of Pillar. The opposite of digging: allowed only when the floor cell is
     * completely empty ({@code passableEmpty}); never placed over water (separately from the
     * after-the-fact check in {@code PathSafetyChecker}, it isn't treated as a placement target at all).
     *
     * <p>The cell just above a water surface is also air, so the floor cell alone can't tell it apart
     * from empty space. Laying blocks across the sea means building a walkway where you could simply
     * swim, so this move isn't created when water is visible below.
     *
     * <p>Lava is allowed only when {@code CellSource#lavaBridgingEnabled()}, with
     * {@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS} added on top. The floor cell may itself be lava:
     * this is vanilla bridging where the placed block replaces the lava, so the body never enters it
     * (routes that do enter it are already rejected as INFEASIBLE by {@code AStarPathfinder#standingBodyCost}).
     */
    void addBridge(PathNode from, int dx, int dz, int obstacleY) {
        if (!canPlace()) {
            return;
        }
        // While holding on to vines or a ladder, you can't take off, for the same reason as addPillar
        // (onGround() is false and handleOnClimbable pins the velocity). A bridge is a run-up jump to the
        // neighbor, which doesn't work while hanging on; you have to finish climbing before stepping out
        if (CellData.climbable(owner.view.cell(from.x, from.y, from.z))) {
            return;
        }
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        long floorCell = owner.view.cell(x, y - 1, z);
        boolean overLava = CellData.lava(floorCell);
        // Having no collision is not the same as being placeable there. Weeping vines, twisting vines,
        // torches and rails can be walked through but aren't replaceable, so aiming at them places the
        // block in the neighboring cell (BlockPlaceContext#getClickedPos); it never lands where we guided
        if (!overLava && (CellData.standable(floorCell) || !CellData.replaceable(floorCell))) {
            return;
        }
        // Don't place inside or next to vines or ladders. Climbables have thin collision but still block
        // the line of sight, so aiming at the target hits them instead. Ordinary vines are replaceable, so
        // the block goes into the vine's cell; ladders and weeping vines aren't, so it goes into the cell
        // next to them. Either way it isn't placed where we guided (the getClickedPos note above, one block over)
        if (climbableNear(x, y - 1, z)) {
            return;
        }
        // If the floor is lava, the placed block replaces that lava. What supports it doesn't matter
        boolean lavaFarBelow = false;
        boolean voidBelow = false;
        // There is a floor, but the drop to it is fatal. Like the void, a bridge where "a miss means death"
        boolean fatalDropBelow = false;
        // How far you fall if you miss the placement. Priced continuously by {@link ActionCosts#dropRiskPenalty}
        int dropBelow = 0;
        if (!overLava) {
            if (obstacleY == ColumnScans.UNREADABLE_BELOW) {
                // The scan stopped at an unloaded chunk. We truly don't know what's below, so don't place;
                // this mix-up is exactly what leads to suggesting a walkway over a water surface
                return;
            }
            if (obstacleY == ColumnScans.NOTHING_BELOW) {
                // Following only readable cells hit nothing, i.e. there is no bottom. A miss is not
                // survivable, so treat it the same as lava
                voidBelow = true;
                dropBelow = owner.view.fatalFallBlocks();
            } else {
                long obstacle = owner.view.cell(x, obstacleY, z);
                if (CellData.water(obstacle)) {
                    return;
                }
                // Even with no lava at the feet or adjacent, if far below (e.g. the floor of an open Nether
                // cavern) is lava, the outcome of a missed placement is the same. hasAdjacentLava only looks
                // one block below the feet, so without this check "a long bridge in mid-air over lava" would be treated like a harmless bridge
                lavaFarBelow = CellData.lava(obstacle);
                // There is a floor. But without looking at <b>how many blocks down</b> it is, the outcome of a miss
                // is unknown. If the drop is fatal the outcome is the same as the void (death), so align the price
                // and the rules with that. This is the user report "it seems to think there's a block below so it's fine".
                // The drop is measured the same way as GroundMoves#missDrop (water was rejected above)
                dropBelow = y - obstacleY - 1;
                fatalDropBelow = !lavaFarBelow && dropBelow >= owner.view.fatalFallBlocks();
            }
        }
        // Don't place where it touches water. It flows in and washes away the footing
        if (owner.hasAdjacentWater(x, y - 1, z)) {
            return;
        }
        // Over a bottomless void, only extend bridges in directions that approach the goal.
        //
        // A bridge is a structure the player builds, not terrain, and over the void there is no terrain to
        // route around in the first place. If floating islands or pillars are in the way, they're avoided by
        // "which shore to leave from", and that choice happens on real ground so it isn't subject to this
        // restriction. Allowing it instead would make every shore cell expand bridges in every direction up
        // to the cap, inflating the search space from lines into areas.
        //
        // Measured on a synthetic archipelago (4 islands, void between them) over one island's segment:
        // burned 100k nodes and ran out of budget → reached in 64978 nodes. Measure a single segment; over
        // the full route the budget falls short anyway and every variant fails, so you can't see whether it helps.
        // Cardinal moves always change the L1 distance by ±1, so only the moves heading away are dropped here.
        //
        // Known gap: terrain where an obstacle floating over the void forces a sideways detour loses the route.
        // If this is hit, relax it, e.g. allow all directions only while the run is still short
        if (voidBelow && Math.abs(x - owner.goalX) + Math.abs(z - owner.goalZ)
                >= Math.abs(from.x - owner.goalX) + Math.abs(from.z - owner.goalZ)) {
            return;
        }
        boolean lavaNearby = overLava || lavaFarBelow || hasAdjacentLava(x, y - 1, z);
        if (lavaNearby && !owner.view.lavaBridgingEnabled()) {
            return;
        }
        // Over the void or lava, don't bridge to places that can't be passed without digging.
        //
        // When "place the floor" and "dig the body cells" share one move, the guidance <b>can't express the order</b>.
        // The correct order is "place the floor first, then dig", but with the dig outline visible it's natural
        // to do that first, and then the footing ahead <b>has no floor yet</b>; over the void you fall and die.
        // Users in-game saw this as two symptoms, "it tells me to place a block next to the block I'm supposed
        // to dig" and "digging it as shown makes me dive", but there was only this one cause.
        //
        // You can't dig in mid-air, so this is the same rule as {@link GroundMoves#addJumpGap} and diagonal moves
        // requiring {@code clearWithoutDigging}. It doesn't apply over gaps with a bottom: digging and falling just
        // lands you on the floor one block below, a completely different outcome
        // Fatal drops are included here too. The reasoning above, "it doesn't apply over gaps with a bottom: digging
        // and falling just lands you on the floor one block below", <b>only holds for shallow bottoms</b>. A floor
        // 43 blocks down doesn't count as a bottom. To avoid adding dead ends, though, this rides the same
        // relaxation ladder as jumps ({@code avoidRiskyJumps}); the void and lava stay unconditional as before
        if ((voidBelow || lavaNearby || (fatalDropBelow && owner.avoidRiskyJumps))
                && !owner.clearWithoutDigging(x, y, z)) {
            return;
        }
        // Cut off by the length of consecutive bridging. The key is choosing "don't create the move" rather
        // than "make it expensive": with weights, A* expands cheap edges first, so it exhausts the surroundings
        // before reaching for the bridge, burns through the expansion budget, and still can't get past
        // (exactly the measurement recorded in ActionCosts#LAVA_BRIDGE_PENALTY_TICKS). Without the edge, the
        // search only looks at detours from the start.
        //
        // Only over lava and the void is a separate (shorter) cap applied. Over a gap with a bottom a missed
        // block just means a fall, but over these two it means death, so the acceptable length differs even for
        // the same bridge. A bridge that hits both is cut by the stricter one
        int cap = owner.maxBridgeRun;
        if (lavaNearby) {
            cap = RunCaps.stricter(cap, owner.maxLavaBridgeRun);
        }
        if (voidBelow || fatalDropBelow) {
            cap = RunCaps.stricter(cap, owner.maxVoidBridgeRun);
        }
        int bridgeRun = from.bridgeRun + 1;
        if (cap > 0 && bridgeRun > cap) {
            owner.markBridgeRunCapBlocked();
            return;
        }
        // Cap the total placements on the route by the number of blocks carried. The run length (cap above)
        // is "how many blocks one bridge may continue", so routes that build many short bridges slip past it;
        // if the blocks run out midway, the guidance from there on can't be carried out
        if (placedBudgetExceeded(from)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        // Only the one block of travel is divided by the takeoff point's speed factor. Placed blocks are
        // normal speed, so the slow part is only stepping off soul sand and the like. The placement effort
        // (placementCostTicks) has nothing to do with the block you're standing on, so it isn't divided.
        //
        // The surcharge for interrupting a sprint isn't added over the void or lava. There, the upper bound
        // on the price isn't set by human preference but by "whether the search can reach the bridge"; going
        // outside the measured window (ActionCosts#LAVA_BRIDGE_PENALTY_TICKS) stops routes from coming out at all.
        // RealEndTerrainTest confirmed that End island hopping becomes unsolvable even with 600k nodes.
        // The intent of encouraging a detour buys nothing there either, since no detour exists inside the search box
        double interruption = voidBelow || lavaNearby
                ? 0.0
                : ActionCosts.TERRAIN_EDIT_INTERRUPTION_TICKS;
        double cost = ActionCosts.SPRINT_ONE_BLOCK / owner.takeoffSpeedFactor(from.x, from.y, from.z)
                + owner.placementCostTicks + interruption
                + (lavaNearby ? owner.profile.lavaBridgePenaltyTicks() : 0.0)
                // If far below is lava, don't measure the drop. The lava surcharge already represents the outcome of
                // a miss, so charging again by depth would also shift the price of unmeasured Nether bridges
                + (lavaFarBelow ? 0.0 : owner.profile.dropRiskPenalty(dropBelow, owner.view.fatalFallBlocks()))
                + owner.submerged(from, bodyCost);
        owner.relax(from, x, y, z, cost, MoveKind.BRIDGE, bridgeRun);
    }

    /**
     * Climbs one block straight up by placing a block underfoot while jumping (Pillar). The vertical
     * counterpart of {@link #addBridge}; without it, a cliff can only be routed around, however low.
     *
     * <p>The placement target is the very cell you're standing in, so it must be genuine air. You can't
     * take off while floating in water, and you can't place where you're holding on to a ladder.
     *
     * <p>No footing is required. From the second consecutive step on, you're standing on the block you
     * just placed; that cell doesn't exist in the terrain data yet, so requiring footing would limit the
     * climb to one block.
     *
     * <p>Only the cell that becomes the new head (two above) is unverified. The new feet are the old head,
     * whose passability is already confirmed by standing there. As with {@link GroundMoves#addAscend}, a
     * blocked ceiling is dug through if it can be dug.
     */
    void addPillar(PathNode from) {
        if (!canPlace()) {
            return;
        }
        // Don't start stacking from on top of a sideways bridge. Jumping and placing underfoot on a
        // one-block-wide walkway is something you'll likely miss over the void or lava; it's not a move to offer as guidance.
        //
        // The key is the "previous move was also a pillar" condition. Pillars also extend the run length,
        // so cutting on {@code bridgeRun > 0} alone would stop a tower climbing a cliff after one block.
        //
        // The reason is purely safety; it doesn't affect search efficiency (measured with and without on the
        // synthetic archipelago: zero difference). What keeps expansion over the void in check is the direction filter in {@link #addBridge}
        if (from.bridgeRun > 0 && from.kind != MoveKind.PILLAR) {
            return;
        }
        // Pillars also get a run-length cap. {@code bridgeRun} was only incremented and never checked,
        // so towers could grow freely up to the ceiling of the search range. In-game (the_end), about 150
        // steps from every standable island cell became expansion targets, burning 510k cells and running out of budget.
        //
        // The real harm isn't the node count but that it prevents reaching {@code EXHAUSTED}.
        // {@code PathfindingExecutor} only relaxes caps once it has "proven there is no path within range", so
        // it never triggers as long as the search ends by running out of budget; bridges stay stuck at the cap and never get across.
        //
        // The lava and void caps aren't applied. Pillars start from a real floor (the branch above guarantees
        // this), so the premise "it spans a place where a miss is fatal" doesn't hold
        int pillarRun = from.bridgeRun + 1;
        if (owner.maxBridgeRun > 0 && pillarRun > owner.maxBridgeRun) {
            owner.markBridgeRunCapBlocked();
            return;
        }
        if (placedBudgetExceeded(from)) {
            return;
        }
        long standing = owner.view.cell(from.x, from.y, from.z);
        // The target is the very cell you're in. While holding on to a ladder or vines, onGround() is false so
        // jumpFromGround() isn't called (LivingEntity#aiStep), and even when grounded while holding on,
        // handleOnClimbable pins horizontal and downward velocity to ±0.15, so jumping and stacking doesn't work.
        // Water is replaceable, so underwater used to pass as a "placeable spot" too. In practice you float and
        // can't take off, so you can't stack up as guided
        if (!CellData.replaceable(standing) || CellData.climbable(standing)
                || CellData.water(standing)) {
            return;
        }
        double clearanceCost = owner.columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        // A pillar always starts from real footing (the replaceable check above), so the surcharge for
        // interrupting a sprint always applies. The exemption on the bridge side is based on "there is no
        // detour over the void or lava", which doesn't apply to climbing one block from the ground
        double cost = ActionCosts.ascendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z))
                + owner.placementCostTicks + ActionCosts.TERRAIN_EDIT_INTERRUPTION_TICKS
                + owner.submerged(from, clearanceCost);
        // The block on top of the stack is footing you placed, not terrain, so it doesn't break the bridge run.
        // Back when this reset to 0, "bridge up to the cap → stack one block → bridge up to the cap again" was legal.
        // Whether it actually triggers depends on expansion order (bridgeRun isn't part of node identity, so if
        // another path reaches the node on the pillar at equal cost, that path's run length is kept), which made it
        // worse: on the same terrain the cap sometimes applied and sometimes didn't, and on runs where it didn't,
        // bridgeRunCapBlocked never got set, so PathfindingExecutor's cap relaxation never ran and a staircase route was finalized
        owner.relax(from, from.x, from.y + 1, from.z, cost, MoveKind.PILLAR, pillarRun);
    }

    /**
     * Whether moves that place footing may be created. Even with no blocks in the inventory, they are created
     * if the dead-end fallback has opened this up ({@code Tolerances#placeWithoutBlocks()}). Otherwise, terrain
     * where bridging is the only way, such as End island hopping, could never produce a route, and the guidance would show nothing at all.
     */
    private boolean canPlace() {
        if (owner.view.canPlaceBlocks()) {
            return true;
        }
        // Don't open it up when settings forbid it. Only "simply not carrying any" may be opened up
        if (!owner.view.bridgingAllowedBySettings()) {
            return false;
        }
        if (!owner.placeWithoutBlocks) {
            owner.markPlacementBlockedByEmptyInventory();
            return false;
        }
        return true;
    }

    /**
     * Whether placing one more block from {@code from} exceeds the inventory budget. If so, sets
     * {@code placedBudgetBlocked} to tell the caller that a retry without the budget is needed.
     */
    private boolean placedBudgetExceeded(PathNode from) {
        if (owner.placedBudget <= 0 || from.placedTotal + 1 <= owner.placedBudget) {
            return false;
        }
        owner.markPlacedBudgetBlocked();
        return true;
    }

    /**
     * Whether there is something climbable (vines, weeping vines, ladders) in or around the cell where the block is placed.
     *
     * <p>Unlike the water and lava adjacency checks, this <b>also looks straight up</b>. Straight up is the cell you'll
     * stand in after placing the footing, and if vines hang there, the line of sight aimed at the target hits them first.
     */
    private boolean climbableNear(int x, int y, int z) {
        return CellData.climbable(owner.view.cell(x, y, z))
                || CellData.climbable(owner.view.cell(x, y + 1, z))
                || owner.hasAdjacentCell(x, y, z, CellData::climbable);
    }

    /** Whether there is lava around the cell where the block is placed (the 5 faces excluding straight up). */
    private boolean hasAdjacentLava(int x, int y, int z) {
        return owner.hasAdjacentCell(x, y, z, CellData::lava);
    }
}
