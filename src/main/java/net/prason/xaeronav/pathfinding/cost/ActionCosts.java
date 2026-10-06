package net.prason.xaeronav.pathfinding.cost;

/**
 * Baseline movement costs (unit: ticks).
 * The walk, sprint, jump and fall values are identical to the measured values used in Baritone
 * (ActionCosts.java, LGPL), but only the ideas and numbers are borrowed; the code is an independent implementation.
 * Climbing and underwater mining are derived from the vanilla Minecraft implementation; the placement and
 * door-opening overheads are this mod's own estimates.
 */
public final class ActionCosts {

    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;

    /**
     * Swim one block forward underwater. The water branch of {@code LivingEntity#travel} adds
     * {@code f5} to the velocity via {@code moveRelative(f5)} and then multiplies by {@code f4}, so
     * the recurrence is v_(n+1) = (v_n + f5)·f4, converging to v* = f4·f5/(1−f4).
     *
     * <p>{@code f4} is 0.9 if {@code isSprinting()}, otherwise {@code getWaterSlowDown()}=0.8.
     * {@code f5} is 0.02. <b>A player crossing the ocean always swims prone</b> (the condition for
     * staying in {@code Entity#updateSwimming} is "sprinting and body in water"), so we take 0.9:
     * v* = 0.9·0.02/0.1 = 0.18 blocks/tick = 3.6 blocks/s.
     *
     * <p><b>Same price even with feet on the bottom.</b> The water branch of {@code travel} is entered
     * on {@code isInWater()} alone, and {@code onGround()} only feeds the equipment (Depth Strider)
     * factor; standing or swimming, v* depends only on {@code isSprinting()}. There used to be a separate
     * constant (2.2 blocks/s) for "walking on the bottom", but this formula yields that value for neither
     * posture, and it overestimated shallows by 1.64x. As a result, routes dove into deeper water to avoid
     * shallows or took wide detours around swamps.
     */
    public static final double SWIM_ONE_BLOCK = 20.0 / 3.6;

    /**
     * Rise one block underwater (holding jump). {@code LivingEntity#jumpInLiquid} adds +0.04 to the
     * y velocity every tick, and {@code travel} multiplies that by 0.8 before subtracting gravity
     * (1/16 of {@code Attributes.GRAVITY}=0.08 = 0.005).
     * v* = 0.8·(v* + 0.04) − 0.005 → v* = 0.135 blocks/tick = 2.7 blocks/s.
     */
    public static final double SWIM_UP_ONE_BLOCK = 20.0 / 2.7;

    /**
     * Sink one block underwater (holding sneak). Same recurrence as rising, with the addend being −0.04.
     * v* = 0.8·(v* − 0.04) − 0.005 → v* = −0.185 blocks/tick = 3.7 blocks/s.
     *
     * <p><b>Sinking is faster than rising</b> because gravity decelerates the rise but accelerates the descent.
     * Using the same value for both makes A* underestimate routes that "dive, then resurface".
     */
    public static final double SWIM_DOWN_ONE_BLOCK = 20.0 / 3.7;


    /**
     * Ticks until air runs out ({@code Entity#getMaxAirSupply}). While the eyes are underwater,
     * {@code decreaseAirSupply} reduces it by 1 every tick, and drowning damage starts once it reaches −20.
     * At the surface, {@code increaseAirSupply} restores 4 per tick.
     */
    public static final int AIR_SUPPLY_TICKS = 300;

    /**
     * Steady-state speed of a boat going straight (ticks per block).
     * From {@code Boat#floatBoat()} (invFriction=0.9F on water) and {@code Boat#controlBoat()}
     * (with forward held, adds f=0.04F after friction), the velocity recurrence
     * v_(n+1) = 0.9 * v_n + 0.04 converges to v* = 0.04/(1-0.9) = 0.4 blocks/tick = 8.0 blocks/s.
     * The time constant is 1/(1-0.9)=10 ticks (0.5 s), so on still water it converges almost instantly.
     */
    public static final double PADDLE_ONE_BLOCK = 20.0 / 8.0;

    /**
     * Move one block through cobweb. {@code WebBlock#entityInside} multiplies the movement itself by 0.25
     * ({@code Entity#move}), so even sprinting only covers 1/4 of the normal distance.
     */
    public static final double SPRINT_ONE_IN_COBWEB = SPRINT_ONE_BLOCK / 0.25;

    private static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    private static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;

    /**
     * Ticks needed to climb one block by jumping. By the symmetry of the parabola, derived as the
     * difference between "time to fall 1.25 blocks" and "time to fall the last 0.25 blocks"
     * (using the fact that the rise from takeoff to the apex at 1.25 blocks equals the symmetric descent).
     */
    public static final double JUMP_ONE_BLOCK = FallPhysics.ticksToFall(1.25) - FallPhysics.ticksToFall(0.25);

    /**
     * Overhead (ticks) added each time the path goes up or down one block. Applied to both ascents and descents.
     *
     * <p><b>The value is set by balancing "one block up/down" against "how many blocks of sideways detour".</b>
     * Measured purely in time, going down one block and back up costs only {@code 1.069} ticks more than
     * two flat blocks, which is <b>cheaper</b> than a detour of one block sideways and back
     * ({@link #SIDESTEP_ONE_BLOCK} = 2.952). So the model says "stepping through a one-block dip is better",
     * and such routes really do come out (user report: "on flat ground the route often goes down one block
     * and back up one block").
     *
     * <p>At 3.0, a one-block dip costs {@code 1.069 + 2×3.0 = 7.07} = <b>a two-block sideways detour</b>,
     * and a two-block dip balances against four blocks sideways. That is the width at which a human
     * feels "I'd rather walk around".
     *
     * <p><b>The key is applying it to both up and down.</b> A meaningful height change (the destination
     * is higher) only pays it one way, whereas a wasteful round-trip up and down pays twice; only the
     * latter is what we want to avoid, so this asymmetry works directly.
     *
     * <p>Measurements (two real overworld routes of ~200 blocks; "wasted up/down" blocks after subtracting
     * the net height change):
     *
     * <pre>
     * 0.0 → 60 / 82 (214 steps / 214 steps)
     * 1.5 → 44 / 72 (218 steps / 215 steps)
     * 3.0 → 40 / 62 (222 steps / 216 steps)   ← here
     * 5.0 → 34 / 42 (237 steps / 270 steps)
     * </pre>
     *
     * <p>Raising it to 5.0 reduces wasted up/down further, but <b>detours kick in too strongly and the route
     * gets 26% longer</b> (214 → 270 steps). 3.0 cuts wasted up/down by 1/4 to 1/3 while keeping the step
     * count within 1-4%.
     */
    public static final double STEP_TRANSITION_TICKS = 3.0;

    public static final double ASCEND_ONE_BLOCK =
            Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK) + STEP_TRANSITION_TICKS;

    /**
     * Airtime when jumping across a gap (about 12.5 ticks). Takeoff to landing is exactly the airtime,
     * and since the rise to the 1.25-block apex and the descent are symmetric, it is computed as that round trip.
     *
     * <p>A jump that lands at the same height traces <b>exactly the same parabola</b> whether the gap is 1 or 3
     * blocks. Only the horizontal speed at takeoff differs, so the airtime is this value regardless of distance.
     * Per-distance differences are expressed by {@link #JUMP_REACH_PENALTY}.
     *
     * <p>It costs more than running two blocks (about 7 ticks), so it is never chosen on flat ground. It only
     * jumps when the detour would be 4 or more blocks, which is close to how a human decides.
     */
    public static final double JUMP_ACROSS_GAP = 2.0 * FallPhysics.ticksToFall(1.25);

    /**
     * Additional cost per extra block of gap width. Even with the same airtime, jumping farther requires
     * reaching top sprint speed before takeoff, which means re-taking a run-up or adjusting the takeoff spot.
     * A 3-block gap (landing 4 blocks ahead) is the very limit of a sprint jump; miss it and you fall.
     *
     * <p>Heavier than Baritone's {@code jumpPenalty} (default 2.0). Baritone controls the player itself
     * and can align the takeoff spot to the block, whereas we only tell a human "jump", and the human is
     * the one who falls on a miss. For the same distance, guidance that walks around is kinder, so the range
     * where detours are chosen is widened (3-block gap: jump 20.5 ≈ 5.7 blocks of walking; previously it
     * balanced at 4.6 blocks).
     */
    public static final double JUMP_REACH_PENALTY = 4.0;

    /**
     * Step down one block onto the adjacent cell. <b>Fall time is not counted.</b> After stepping off the edge,
     * horizontal speed holds at the airborne sprint steady state (0.2889 blocks/tick), which is <b>higher</b>
     * than ground sprint (0.2863), so as long as you keep running you move as fast as on flat ground. Measured
     * by running the non-fluid branch of {@code LivingEntity#travel} as-is: descending 64 blocks one at a time
     * took 3.45 ticks/block (flat ground is 3.48), with a maximum fall distance before landing of 2.69 blocks,
     * no damage.
     *
     * <p>Previously it was {@link #fallCost(int)}(1) = 9.321, <b>equivalent to 2.6 blocks of sprinting</b>.
     * Crossing a valley 12 deep was priced like a 20-block flat detour. The formula matched Baritone's
     * {@code MovementDescend}, but <b>Baritone is a bot executing one move at a time, which really does slow
     * down</b>. We only tell a human "keep running down", so the no-slowdown side is correct.
     *
     * <p>The horizontal component <b>must not go below</b> {@link #SPRINT_ONE_BLOCK}:
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic} uses sprint as the horizontal lower bound,
     * so going below it makes the heuristic inadmissible. The slightly faster measured value is rounded to
     * exactly sprint, and {@link #STEP_TRANSITION_TICKS} (the overhead of a single step up/down) is added.
     *
     * <p>A deliberate large {@code Fall} still uses {@link #fallCost(int)} as before; there the fall time
     * itself is the limiting factor, so nothing changes.
     */
    public static final double DESCEND_ONE_BLOCK = SPRINT_ONE_BLOCK + STEP_TRANSITION_TICKS;

    /**
     * Height that can be dropped without fall damage. Vanilla deals
     * {@code ceil((fall distance - SAFE_FALL_DISTANCE) × multiplier)} damage, and the
     * {@code SAFE_FALL_DISTANCE} attribute defaults to 3.
     */
    public static final int SAFE_FALL_BLOCKS = 3;

    /**
     * Climb one block of ladder/vines. From the vanilla climbing speed of 2.35 blocks/s.
     * Going down, {@code LivingEntity#handleOnClimbable} caps downward speed at 0.15 blocks/tick,
     * which is exactly 3 blocks/s.
     */
    public static final double LADDER_UP_ONE_BLOCK = 20.0 / 2.35;

    public static final double LADDER_DOWN_ONE_BLOCK = 20.0 / 3.0;

    /**
     * Takeoff speed multiplier, relative to sprint, when moving horizontally away from where you hold onto
     * vines/a ladder. {@code handleOnClimbable} also caps horizontal speed at ±0.15 blocks/tick, same as
     * {@link #LADDER_DOWN_ONE_BLOCK}, so the step away is limited by that clamp rather than by sprint.
     * Passed to {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#takeoffSpeedFactor} as a
     * "takeoff speed multiplier", like soul sand and honey.
     */
    public static final double CLIMBABLE_TAKEOFF_SPEED_FACTOR = SPRINT_ONE_BLOCK / LADDER_DOWN_ONE_BLOCK;

    /** Extra cost to open and pass a door/fence gate: stopping, turning, and opening. Doors span two blocks vertically. */
    public static final double OPEN_DOOR_OVERHEAD_TICKS = 5.0;

    /** Distance multiplier for one diagonal step (√2 blocks horizontally). Used by both Diagonal move costs and the heuristic. */
    public static final double DIAGONAL_DISTANCE = Math.sqrt(2.0);

    /**
     * Rise one block while moving forward underwater. Follows the same max model as {@link #ASCEND_ONE_BLOCK}:
     * "the slower of two components done simultaneously" (you move forward while floating up, so it is not additive).
     *
     * <p>As a result it equals rising straight up ({@link #SWIM_UP_ONE_BLOCK}), and <b>the horizontal progress
     * comes for free</b>. This mirrors how the land {@code Ascend} piggybacks one horizontal step on the jump time;
     * without it, only L-shaped routes ("straight up, then sideways") could be produced.
     */
    public static final double SWIM_ASCEND_ONE_BLOCK =
            Math.max(SWIM_UP_ONE_BLOCK, SWIM_ONE_BLOCK * DIAGONAL_DISTANCE);

    /**
     * Cost (ticks) of rising one level with one diagonal step. The diagonal version of
     * {@link #SWIM_ASCEND_ONE_BLOCK}; the horizontal displacement is √2 instead of 1, so the 3D displacement is √3.
     *
     * <p>Without it, rising is restricted to the four cardinal directions, and "moving diagonally" and "going up"
     * are paid as separate moves, so the route bends at right angles only on stretches heading to the surface.
     */
    public static final double DIAGONAL_SWIM_ASCEND_ONE_BLOCK =
            Math.max(SWIM_UP_ONE_BLOCK, SWIM_ONE_BLOCK * Math.sqrt(3.0));

    /**
     * Cost (ticks) of climbing one level with one diagonal step. Follows the same max model as
     * {@link #ASCEND_ONE_BLOCK}: "the larger of jump time and horizontal travel time" (you keep moving
     * horizontally while jumping, so it is not additive).
     *
     * <p>The horizontal side uses {@link #WALK_ONE_BLOCK} rather than {@link #SPRINT_ONE_BLOCK} to match
     * {@link #ASCEND_ONE_BLOCK}. Sprint cannot be maintained through repeated jumps up steps, so cardinal
     * ascents are estimated at walking speed; assuming sprint only for diagonals would erase the climbing
     * penalty (same cost as a diagonal move = free height). That would also zero out the entire ascent
     * component of {@link net.prason.xaeronav.pathfinding.astar.Heuristic}, needlessly widening the search
     * on routes heading up a mountain.
     */
    public static final double DIAGONAL_ASCEND_ONE_BLOCK =
            Math.max(ASCEND_ONE_BLOCK, WALK_ONE_BLOCK * DIAGONAL_DISTANCE + STEP_TRANSITION_TICKS);

    /**
     * Cost (ticks) of descending one level with one diagonal step.
     *
     * <p>Using {@link #SPRINT_ONE_BLOCK} on the horizontal side is the difference from
     * {@link #DIAGONAL_ASCEND_ONE_BLOCK}, and this asymmetry is correct. <b>Ascending jumps at every step,
     * so sprint cannot be maintained</b>, whereas <b>descending can be run straight through</b> (measured in
     * {@link #DESCEND_ONE_BLOCK}).
     */
    public static final double DIAGONAL_DESCEND_ONE_BLOCK =
            Math.max(DESCEND_ONE_BLOCK, SPRINT_ONE_BLOCK * DIAGONAL_DISTANCE + STEP_TRANSITION_TICKS);

    /**
     * For long falls, ticks per block approach terminal velocity (3.92 blocks/tick) and never go below it.
     * A safe lower bound used for the descent component of the A* heuristic.
     */
    public static final double FALL_ASYMPTOTIC_MIN_PER_BLOCK = 1.0 / 3.92;

    /**
     * Price (ticks) of a detour that shifts one block sideways and returns to the original heading. Two
     * diagonal sprint steps replace two straight steps, so {@code 2·(√2−1)·}{@link #SPRINT_ONE_BLOCK}.
     *
     * <p>This is used as <b>the unit for the effort of modifying terrain</b>: it lets
     * {@link #DIG_OVERHEAD_TICKS} and {@link #PLACE_BLOCK_OVERHEAD_TICKS} be set as "equivalent to how many
     * blocks of detour", so humans can judge whether the values are reasonable. The time itself is in the
     * same unit (ticks), so no conversion is needed.
     */
    public static final double SIDESTEP_ONE_BLOCK = 2.0 * (DIAGONAL_DISTANCE - 1.0) * SPRINT_ONE_BLOCK;

    /**
     * Effort of breaking one block, excluding the break time itself: stopping, turning, aiming, and
     * starting to run again after breaking.
     *
     * <p><b>The value is set by balancing "dig one cell or detour sideways".</b> The unit is
     * {@link #SIDESTEP_ONE_BLOCK} (2.95). Measuring a 2-high wall (can't be climbed, so it's dig or detour),
     * 22.0 tips toward digging at <b>an 8-block detour</b> (crossing over the wall pays
     * {@link #STEP_TRANSITION_TICKS} twice for the single up/down, so it is one block farther than the price
     * of one dig alone).
     *
     * <p>It tips slightly before the arithmetic ratio (22.0/2.95 = 7.5) because the search is weighted by
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT} and
     * systematically dislikes moves that first go away from the destination. <b>When moving the balance,
     * look at the measured tipping point, not the ratio</b> ({@code TerrainEditVersusDetourTest}).
     *
     * <p><b>If this balance is too low, natural terrain gets dug at every step.</b> 2-block steps and
     * 1-wide walls occur every few blocks, so at a value that tips toward digging at a 3-block detour (= 8.0)
     * most of them become digs; that is the user report "lots of pointless block digging while walking on the
     * surface". Raise it too much, though, and caves and cliffs that need just a little digging get wide
     * detour guidance.
     *
     * <p>The lower bound is satisfied automatically: stepping over a one-block bump costs
     * {@link #ASCEND_ONE_BLOCK} + {@link #DESCEND_ONE_BLOCK} = 8.20 ticks, while digging through costs
     * break time + this value + {@link #SPRINT_ONE_BLOCK}, so stepping over is always cheaper.
     *
     * <p><b>This is a per-cell price</b>, so a move that digs through both body cells pays it twice. That
     * overestimates breaking two at the same stop, but on the surface one cell is enough (a 2-high wall can be
     * stepped over after breaking only the upper cell), so it only affects routes tunneling through
     * ceilinged shafts. There, digging is often the only way, so the shape of the route doesn't change.
     *
     * <p>Keeping it lighter than {@link #PLACE_BLOCK_OVERHEAD_TICKS} is intentional, preserving the order
     * "if digging and placing look like the same effort, prefer digging". Placing is heavier because the
     * target is <b>a specific face of a specific block</b>, and it is done while backing toward the gap.
     */
    public static final double DIG_OVERHEAD_TICKS = 22.0;

    /**
     * Slowdown when digging with feet on the bottom and head underwater. {@code Player#getDigSpeed}
     * multiplies mining speed by {@code Attributes.SUBMERGED_MINING_SPEED} when {@code isEyeInFluid(WATER)}
     * (default 0.2 = 5x slower; becomes 1.0 with the Aqua Affinity enchantment, cancelling it out).
     */
    public static final double SUBMERGED_DIG_PENALTY = 5.0;

    /**
     * Slowdown when digging while swimming. In addition to the underwater check above,
     * {@code Player#getDigSpeed} applies <b>a further {@code f /= 5.0F} when {@code !onGround()}</b>, so
     * underwater without footing it is 25x slower in total.
     *
     * <p>Although footing makes a 5x difference, it used to be estimated at a flat 5x, so routes digging
     * through open ocean looked like 1/5 of their real cost. Guidance that digs its way forward is far more
     * expensive than "swim around".
     */
    public static final double SWIMMING_DIG_PENALTY = 25.0;

    /**
     * Surcharge for moving with the head underwater <b>without gaining height</b>. A weight to make routes
     * surface first and then cross, instead of crossing submerged. Only rising
     * ({@link #SWIM_ASCEND_ONE_BLOCK}) is exempt; it applies to horizontal moves and to sinking.
     *
     * <p>Rising is exempt because it is the horizontal moves that burn air, and rising is the remedy.
     * Applying it to everything would make "going up is expensive too", and the submerged route would be
     * no worse.
     *
     * <p><b>The value is bounded by two conditions.</b> Both come from how A* expands nodes, not from
     * a preference for weight. The default 1.3 is in the middle of the window:
     *
     * <ul>
     * <li><b>The lower bound is 1.0 (= there is a surcharge).</b> At 1.0 the route crosses submerged and
     *     only rises once directly below the destination (measured: aiming at the surface 58 blocks away from
     *     the bottom, it moved submerged for 57 steps). Raising it to 1.05 makes it start rising diagonally</li>
     * <li><b>The upper bound is {@link #DIAGONAL_DISTANCE} (√2 ≈ 1.414).</b> Above it, <b>the route can
     *     bounce up and down to dodge the surcharge</b>: since only diagonal rising is exempt, repeating
     *     "rise diagonally, sink diagonally" moves horizontally. The condition for the round trip not being
     *     cheaper than two horizontal moves is
     *     {@code SWIM_ASCEND_ONE_BLOCK + SWIM_ONE_BLOCK·P ≧ 2·SWIM_ONE_BLOCK·P},
     *     i.e. {@code P ≦ SWIM_ASCEND_ONE_BLOCK / SWIM_ONE_BLOCK}.
     *     Measured too: 1.4 is flat, bouncing starts at 1.45</li>
     * </ul>
     *
     * <p><b>The exemption is limited to rising within one horizontal block</b> ({@code AStarPathfinder#relax}).
     * Exempting up to {@link #DIAGONAL_SWIM_ASCEND_ONE_BLOCK} brings the same bounce back in diagonal form
     * (√3 + √2·P vs 2·√2·P) on stretches that should go diagonally, which this upper bound cannot suppress.
     *
     * <p>Where the surface can't be reached (flooded caves, ceilinged waterways), the surcharge applies
     * uniformly and the route shape doesn't change. "Except underwater caves" is satisfied naturally this way.
     */
    public static final double SUBMERGED_TRAVEL_PENALTY = 1.3;

    /**
     * Price (ticks) of the act of aiming and placing one block: stopping, turning toward <b>a specific face
     * of a specific block</b>, and aiming and placing.
     *
     * <p>The key here is that it <b>does not include the surcharge for "interrupting running"</b>
     * ({@link #TERRAIN_EDIT_INTERRUPTION_TICKS}). Three places need a value without it: bridges over the void
     * or lava ({@code AStarPathfinder#addBridge}), the water-bucket MLG placed while already falling
     * ({@link #MLG_WATER_OVERHEAD_TICKS}), and getting into and out of a boat
     * ({@link #BOAT_LAUNCH_TICKS}, {@link #BOAT_STOW_TICKS}).
     */
    public static final double PLACE_BLOCK_AIM_TICKS = 16.0;

    /**
     * Surcharge (ticks) for touching terrain mid-run: cutting the sprint, looking away from the heading,
     * and starting to run again when done. <b>This is always paid on top of the break time or the placing
     * action itself</b>.
     *
     * <p>It applies <b>only to placing</b> ({@link #PLACE_BLOCK_OVERHEAD_TICKS}, {@code addPillar}).
     * Digging folds the surcharge for the same reason into {@link #DIG_OVERHEAD_TICKS}; that one is charged
     * per cell and balanced against something different, so sharing them would let one side's measurements
     * move the other. The value was measured and set on the {@link #PLACE_BLOCK_OVERHEAD_TICKS} side.
     *
     * <p><b>Not applied to bridges over the void or lava.</b> There, the upper bound of the bridge price is
     * governed not by human preference but by <b>whether the search can reach the bridge at all</b>, and
     * stepping outside the measured window ({@link #LAVA_BRIDGE_PENALTY_TICKS}) makes the route disappear
     * entirely. The intent to encourage detours buys nothing there either, since the detour isn't inside the
     * search box.
     */
    public static final double TERRAIN_EDIT_INTERRUPTION_TICKS = 20.0;

    /**
     * Aiming/placing overhead when crossing a gap by placing blocks (horizontal version of Pillar).
     *
     * <p>Advancing while bridging repeats "stop, turn toward the edge at your feet, aim and place", so it only
     * reaches about 1/3 of running speed.
     *
     * <p><b>The value is set by the same balance as {@link #DIG_OVERHEAD_TICKS}.</b> Measuring a 2-block step
     * climbed with a single pillar, 36.0 tips toward placing at <b>a 10-block detour</b> (crossing a 4-wide
     * trench with a bridge is two placements plus one jump, so a 21-block detour).
     * Detours are allowed to go farther than for digging (8 blocks) because placing consumes blocks from the
     * inventory, and the placed footing stays as terrain (the terrain is different next time you pass);
     * digging works the other way, gaining materials.
     *
     * <p><b>Split into two components.</b> {@link #PLACE_BLOCK_AIM_TICKS} is the placing action itself, and
     * {@link #TERRAIN_EDIT_INTERRUPTION_TICKS} is the surcharge for cutting the run. Bridges over the void or
     * lava don't pay the latter ({@code AStarPathfinder#addBridge}); the reason is explained there.
     */
    public static final double PLACE_BLOCK_OVERHEAD_TICKS =
            PLACE_BLOCK_AIM_TICKS + TERRAIN_EDIT_INTERRUPTION_TICKS;

    /**
     * Additional penalty per point of damage (0.5 hearts) when fall damage is allowed. Like
     * {@link #JUMP_REACH_PENALTY}, a weight to "prefer a cheap detour if there is one". This mod's own estimate.
     */
    public static final double FALL_DAMAGE_PENALTY_PER_POINT = 2.0;

    /**
     * Aiming/placing/pickup overhead of a water-bucket MLG (placing water just before landing and picking
     * it up right after to cancel fall damage).
     *
     * <p>Same as {@link #PLACE_BLOCK_AIM_TICKS} (the act of aiming and placing itself).
     * {@link #TERRAIN_EDIT_INTERRUPTION_TICKS} is excluded because the MLG is done <b>while already
     * falling</b>, not started by interrupting a run.
     */
    public static final double MLG_WATER_OVERHEAD_TICKS = PLACE_BLOCK_AIM_TICKS;

    /**
     * Effort to place a boat, get in, and paddle up to speed. Paid by the single move that places the boat
     * from the shore (or the water surface).
     *
     * <p>Breakdown: aiming at the water and placing ({@link #PLACE_BLOCK_AIM_TICKS}) + getting in (the placed
     * boat is already under the crosshair, so no re-aim, just the 4-tick right-click interval
     * {@code Minecraft#rightClickDelay}) + the lag of accelerating from rest. Speed rises from 0 via the
     * {@link #PADDLE_ONE_BLOCK} recurrence v' = 0.9v + 0.04, so compared to steady state it falls behind by
     * Σ 0.4·0.9^n = 4 blocks = 10 ticks.
     *
     * <p><b>Must not include {@link #TERRAIN_EDIT_INTERRUPTION_TICKS}.</b> Applying a "per-block
     * interruption" to this cost, paid once at the stretch's entrance, would count the interruption twice.
     */
    public static final double BOAT_LAUNCH_TICKS = PLACE_BLOCK_AIM_TICKS + 4.0 + 10.0;

    /**
     * Effort to break and pick up the boat after getting out. Paid by the move that gets out of the boat
     * ({@code AStarPathfinder#relax}). It is paid on exit even if the search started while already in a boat,
     * so it is kept separate from the entrance cost {@link #BOAT_LAUNCH_TICKS}.
     *
     * <p>Breakdown: turning toward the boat ({@link #PLACE_BLOCK_AIM_TICKS}) + 28 ticks to break it bare-handed
     * + 10 ticks until it can be picked up.
     * <ul>
     *   <li>Breaking: {@code VehicleEntity#hurtServer} adds attack damage ×10 per hit, and it breaks above 40.
     *       The accumulated value drops by 1 per tick in {@code AbstractBoat#tick}. Right after placing the boat
     *       the hand is empty, so assuming bare-handed hits (attack damage 1, attack speed 4 = full charge in
     *       5 ticks, charge f gives damage ×(0.2+0.8f²)) every 4 ticks (5/s): 8 hits, 28 ticks. A sword would
     *       take one hit, but switching items is not assumed</li>
     *   <li>Pickup: the drop from breaking is made unpickable for 10 ticks by {@code Entity#spawnAtLocation}
     *       via {@code setDefaultPickUpDelay}</li>
     * </ul>
     *
     * <p>Together with {@link #BOAT_LAUNCH_TICKS}, 84 ticks (before {@link #BOAT_DOCK_TICKS} and
     * {@link #MODE_SWITCH_PENALTY_TICKS}, which move it to roughly 60 blocks) set the break-even: swimming
     * ({@link #SWIM_ONE_BLOCK}) and boating ({@link #PADDLE_ONE_BLOCK}) differ by about 3 ticks per block, so
     * the boat is chosen only when the water stretch exceeds roughly 28 blocks. For ponds or river widths,
     * placing and breaking the boat costs more.
     */
    public static final double BOAT_STOW_TICKS = PLACE_BLOCK_AIM_TICKS + 28.0 + 10.0;

    /**
     * Bringing a moving boat to the bank and getting onto land, paid on top of {@link #BOAT_STOW_TICKS} by the move
     * that gets out.
     *
     * <p>Breakdown: a boat at full speed (0.4 blocks/tick) only loses 10% of its speed per tick once forward is let
     * go, so it glides about 4 blocks / 10 ticks; lining it up with the exact cell of the bank takes about as long
     * again; getting out (sneak) and stepping or jumping up onto the bank, which usually stands a block above the
     * water, about 10 more.
     */
    public static final double BOAT_DOCK_TICKS = 30.0;

    /**
     * Surcharge on every change of how you travel: launching a boat, leaving it, getting off a mount.
     *
     * <p>Not time but preference, like the risk surcharges: each change is a moment of fiddling where things go wrong
     * (the boat drifts off, a misclick breaks the wrong block, the horse wanders), and a route that hops out of the
     * boat onto every small island it passes is tiresome to follow even when it is a few seconds shorter on paper.
     * At 40 ticks per change, leaving the water for an island and getting back in costs about 190 ticks in all, so a
     * route only crosses an island when going round it would be some 75 blocks longer.
     */
    public static final double MODE_SWITCH_PENALTY_TICKS = 40.0;

    /**
     * Additional penalty per block for crossing lava by placing footing. A single missed placement is death,
     * so it is heavier than normal placement, but <b>kept within the range where the detailed search can find
     * the bridge on a realistic budget</b>.
     *
     * <p>This upper bound is set by the search algorithm. A* expands cheaper edges first, so if one bridge
     * block is worth {@code n} blocks of walking, before reaching a route that crosses {@code m} blocks of lava
     * it exhausts "{@code n×m} blocks of walking worth of land". When this was set to 28 blocks of walking
     * (penalty 80), crossing 20 blocks of lava first required expanding the equivalent of a 559-block radius,
     * and in the Nether's 3D maze it never reached the bridge even after burning 200k nodes (confirmed in-game).
     *
     * <p>Equal to {@link #PLACE_BLOCK_AIM_TICKS}, one bridge block is about 35.6 ticks = 10 blocks of walking.
     * Crossing 20 blocks of lava balances against a 200-block walking detour, which is just the right weight:
     * the search tries the detours that fit in the detailed search box (render distance) before choosing the
     * bridge.
     *
     * <p><b>Must not include {@link #TERRAIN_EDIT_INTERRUPTION_TICKS}.</b> That surcharge is set by the
     * balance "place once or detour sideways", and its upper bound is governed by human preference.
     * This one's upper bound is governed by a different axis, <b>whether the search can reach the bridge</b>,
     * so tying them together would push this out of the measured window above whenever the other is moved for
     * human-side reasons.
     *
     * <p>Whether a larger detour should be taken is decided by layer 1 ({@code CoarseRouter.BridgePolicy}).
     * Detours beyond render distance are invisible to the detailed search anyway, so don't try to express
     * them here.
     *
     * <p>"Give up and detour if the bridge gets too long" is expressed not as a cost but by
     * {@code CellSource#maxBridgeRunBlocks()} (a limit beyond which moves aren't generated). Trying to express
     * it by making this heavier just wastes expanded nodes, for the reason above.
     */
    public static final double LAVA_BRIDGE_PENALTY_TICKS = PLACE_BLOCK_AIM_TICKS;

    /**
     * Additional penalty per block for crossing a bottomless void (the End's void, caverns deeper than the
     * search range) by placing footing. Equal to {@link #LAVA_BRIDGE_PENALTY_TICKS}: <b>a single missed
     * placement has the same outcome</b> (instant death + loss of all items), so there is no reason to weight
     * them differently.
     *
     * <p>The argument in {@link #LAVA_BRIDGE_PENALTY_TICKS} that the upper bound is set by the search algorithm
     * applies here as is. "Give up if the bridge gets too long" is handled by
     * {@code CellSource#maxVoidBridgeRunBlocks()} (a limit beyond which moves aren't generated).
     *
     * <p>Between End islands <b>almost every bridge falls under this</b>, so the value barely changes route
     * shape there (no detour exists). It matters in deep overworld ravines and caves, where it encourages
     * "walk around rather than cross".
     */
    public static final double VOID_BRIDGE_PENALTY_TICKS = LAVA_BRIDGE_PENALTY_TICKS;

    /**
     * Surcharge (ticks) per walking step on a cell bordered by lava, void, or a lethal drop. Humans drift
     * sideways from sprint momentum or lag, so the shortest path hugging the edge is actually dangerous.
     *
     * <p><b>A small price, not a ban.</b> On narrow Nether paths and End island edges, walking along the edge
     * is often the only way, and making it impassable would erase the route or force a long detour. Moving one
     * block away from the edge costs two diagonal steps (about 0.83 blocks), so at half a block only stretches
     * running along the edge for 2+ steps shift one block inward.
     */
    public static final double EDGE_HAZARD_PENALTY_TICKS = SPRINT_ONE_BLOCK * 0.5;

    /**
     * Hazard price (ticks) based on how far you fall if you miss your footing. Shared by bridging one block
     * and jumping one block across a gap. The drop {@code dropBlocks} is "the number of empty cells from one
     * below the foot height down to the floor"; if there is no bottom, pass {@code fatalFallBlocks} or more.
     *
     * <p><b>The key is a slope, not a binary.</b> It used to be "{@link #VOID_BRIDGE_PENALTY_TICKS} at or above
     * the lethal drop, otherwise 0", so a 3-deep dip and a 16-deep canyon had the same price. In the real End
     * at (2481,-488), a route bridged 7 blocks in a row over a valley with floor 11-16 blocks below, and then
     * continued straight into the void. The consequence of falling is continuous in the drop, so the price is
     * continuous too.
     *
     * <p>Both ends are fixed as before: 0 at or below {@link #SAFE_FALL_BLOCKS} (filling a one-block dip is
     * cheap), and {@link #VOID_BRIDGE_PENALTY_TICKS} at or above the lethal drop (same as the void). <b>Only the
     * range in between changes</b>, so neither existing behavior, "fill shallow dips" nor "the void is
     * expensive", moves.
     *
     * <p>Passability (the {@code maxVoidBridgeRunBlocks} limit, the dig ban) is still decided by <b>the binary
     * lethal-or-not</b> as before. Only the price is sloped; making feasibility continuous too would leave the
     * search unable to tell where a "bridge that can be completed" ends.
     */
    public static double dropRiskPenalty(int dropBlocks, int fatalFallBlocks) {
        if (dropBlocks <= SAFE_FALL_BLOCKS) {
            return 0.0;
        }
        if (dropBlocks >= fatalFallBlocks) {
            return VOID_BRIDGE_PENALTY_TICKS;
        }
        return VOID_BRIDGE_PENALTY_TICKS * (dropBlocks - SAFE_FALL_BLOCKS)
                / (double) (fatalFallBlocks - SAFE_FALL_BLOCKS);
    }

    /**
     * Minimum cost (ticks) per block of descent when drops are capped at {@code maxDrop} blocks.
     * Used as the lower bound of the descent component of {@link net.prason.xaeronav.pathfinding.astar.Heuristic}.
     *
     * <p>{@code fallCost(d)/d} is monotonically decreasing in {@code d} (approaching terminal velocity), so the
     * value at the <b>largest</b> drop that can be generated is the lower bound. <b>This monotonicity is the
     * key</b>: relaxing the fall damage allowance extends {@code maxDrop} and always lowers the bound, so if the
     * relaxed search keeps receiving the old bound, the estimate exceeds the real cost (= inadmissible).
     *
     * <p>Ladders ({@link #LADDER_DOWN_ONE_BLOCK}) can exceed this, but are compared explicitly so they are not
     * mistaken for the lower bound.
     */
    public static double descentBoundForMaxDrop(int maxDrop) {
        return Math.min(fallCost(maxDrop) / maxDrop, LADDER_DOWN_ONE_BLOCK);
    }

    public static final double INFEASIBLE = Double.POSITIVE_INFINITY;

    private ActionCosts() {
    }

    /** Step off an edge, fall {@code blocks} blocks, land, and return to the center of the cell. */
    public static double fallCost(int blocks) {
        return fallCost(blocks, 1.0);
    }

    /**
     * Version that factors in the horizontal speed multiplier at the takeoff point.
     *
     * <p>Only <b>the 0.8 blocks of stepping off the edge</b> slow down. The time spent falling and returning
     * to the center after landing has nothing to do with the block underfoot, so the multiplier isn't applied.
     */
    public static double fallCost(int blocks, double speedFactor) {
        return WALK_OFF_BLOCK / speedFactor + Math.max(FallPhysics.ticksToFall(blocks), CENTER_AFTER_FALL);
    }

    /**
     * One-step ascent factoring in the horizontal speed multiplier at the takeoff point ({@code speedFactor},
     * 1.0 or less).
     *
     * <p>The jump time itself is unaffected by the multiplier (vanilla's {@code jumpFactor} is a separate value
     * and isn't set for soul sand). Only the horizontal component slows down, so only the horizontal side of
     * the max is divided.
     *
     * <p><b>Don't pass a multiplier above 1.0 (ice)</b>. {@link net.prason.xaeronav.pathfinding.astar.Heuristic}
     * puts the ascent lower bound at the cheaper of this value and {@link #SWIM_UP_ONE_BLOCK}, so going below it
     * makes the heuristic inadmissible.
     */
    public static double ascendOneBlock(double speedFactor) {
        return Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK / speedFactor) + STEP_TRANSITION_TICKS;
    }

    /**
     * One-step descent version of {@link #ascendOneBlock}. As in {@link #DESCEND_ONE_BLOCK}, fall time is not
     * counted; it is just horizontal movement plus {@link #STEP_TRANSITION_TICKS}.
     */
    public static double descendOneBlock(double speedFactor) {
        return SPRINT_ONE_BLOCK / speedFactor + STEP_TRANSITION_TICKS;
    }

    /** Diagonal version of {@link #ascendOneBlock}. */
    public static double diagonalAscendOneBlock(double speedFactor) {
        return Math.max(ascendOneBlock(speedFactor),
                WALK_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor + STEP_TRANSITION_TICKS);
    }

    /** Diagonal version of {@link #descendOneBlock}. */
    public static double diagonalDescendOneBlock(double speedFactor) {
        return Math.max(descendOneBlock(speedFactor),
                SPRINT_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor + STEP_TRANSITION_TICKS);
    }

    /** Cost of jumping across a {@code gapBlocks}-wide gap. The landing point is {@code gapBlocks + 1} blocks ahead. */
    public static double jumpAcrossGap(int gapBlocks) {
        return JUMP_ACROSS_GAP + (gapBlocks - 1) * JUMP_REACH_PENALTY;
    }
}
