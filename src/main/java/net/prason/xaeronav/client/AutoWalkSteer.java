package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Decides how auto-walk moves this tick: where to look, and which movement keys to hold.
 *
 * <p>It only reads the route and a snapshot of the player, and doesn't touch {@code Minecraft}, so it can be tested on its own.
 * {@link AutoWalk} applies the result to the real input.
 *
 * <p>Auto-walk only does what walking can do. Steps that need the player's hands (digging, placing a bridge block, boarding a
 * boat) and steps where a small steering error is fatal (gap jumps, lava edges, void, fall damage) stop it a few steps ahead,
 * so the player takes over before reaching them instead of in the middle of them.
 */
final class AutoWalkSteer {

    /** How many steps ahead are checked for steps auto-walk can't do. Enough to stop at walking speed before reaching them. */
    static final int STOP_LOOKAHEAD = 3;

    /** How many collinear steps ahead may be aimed at directly. Aiming further smooths out the wobble between block centers. */
    static final int AIM_LOOKAHEAD = 4;

    /** Straight steps needed ahead before sprinting. Sprinting into a corner overshoots it. */
    static final int SPRINT_STRAIGHT_STEPS = 4;

    /** Largest turn per tick (degrees). Snapping the camera is disorienting; slower than this overshoots corners. */
    static final float MAX_TURN_PER_TICK = 45.0F;

    /** Don't walk forward while facing further off than this (degrees). Turn on the spot first. */
    static final float WALK_ANGLE = 60.0F;

    /** Close enough horizontally to the last step to count as there (blocks). */
    static final double END_RADIUS = 0.5;

    /** Height above the feet from which the next step counts as a step up that needs a jump (blocks). */
    private static final double STEP_UP = 0.6;
    /** Only jump for a step up once this close to it horizontally (blocks). Jumping earlier lands short of the ledge. */
    private static final double JUMP_REACH = 1.5;

    enum Stop {
        NONE,
        /** Stood on the last step of the route. */
        END,
        /** A step ahead needs the player's hands (digging, placing, a boat, a gap jump). */
        MANUAL_STEP,
        /** A step ahead is dangerous to walk without care (lava, void, fall damage, drowning). */
        DANGER,
        /** In a boat, and the route goes ashore ahead: the player gets out and picks the boat up themselves. */
        SHORE,
        /** Riding, and the route gets off ahead: the player gets off where the animal can't go on. */
        DISMOUNT
    }

    /**
     * What to do this tick. {@code yaw} is the yaw to set (already limited to {@link #MAX_TURN_PER_TICK}); in a boat it's
     * left alone and the boat is turned with {@code left}/{@code right} instead.
     */
    record Command(float yaw, boolean forward, boolean jump, boolean sprint, boolean left, boolean right, Stop stop) {

        static Command stop(float yaw, Stop stop) {
            return new Command(yaw, false, false, false, false, false, stop);
        }
    }

    /**
     * The player as auto-walk sees it this tick.
     *
     * @param x            feet X
     * @param y            feet Y
     * @param z            feet Z
     * @param yaw          current yaw (degrees, Minecraft convention: 0 = +Z, 90 = -X)
     * @param onGround     standing on a block
     * @param inWater      in water
     * @param bumped       walked into a wall last tick ({@code horizontalCollision})
     * @param inBoat       riding a boat; {@code yaw} is then the boat's heading
     * @param onMount      riding an animal; it follows where the rider looks, and steps up by itself
     */
    record Player(double x, double y, double z, float yaw, boolean onGround, boolean inWater, boolean bumped,
                  boolean inBoat, boolean onMount) {

        Player(double x, double y, double z, float yaw, boolean onGround, boolean inWater, boolean bumped) {
            this(x, y, z, yaw, onGround, inWater, bumped, false, false);
        }

        Player(double x, double y, double z, float yaw, boolean onGround, boolean inWater, boolean bumped,
               boolean inBoat) {
            this(x, y, z, yaw, onGround, inWater, bumped, inBoat, false);
        }
    }

    /** Heading error (degrees) a boat tolerates before it's turned. Boats drift, so a tighter value only zigzags. */
    static final float BOAT_TURN_DEADBAND = 8.0F;
    /** Only paddle forward while the boat faces within this of the target (degrees); turn on the spot beyond it. */
    static final float BOAT_PADDLE_ANGLE = 45.0F;

    private AutoWalkSteer() {
    }

    /**
     * @param steps   the route being shown
     * @param index   the step the player is mapped to ({@link PathProgress#indexFor})
     * @param sprint  whether sprinting is allowed at all
     */
    static Command steer(List<PathStep> steps, int index, Player player, boolean sprint) {
        int last = steps.size() - 1;
        if (last < 0) {
            return Command.stop(player.yaw(), Stop.END);
        }
        index = Math.max(0, Math.min(index, last));

        Stop ahead = player.onMount() ? leavesMountAhead(steps, index) : blockedAhead(steps, index, player.inBoat());
        if (ahead != Stop.NONE) {
            return Command.stop(player.yaw(), ahead);
        }

        int target = targetIndex(steps, index, player);
        BlockPos targetPos = steps.get(target).pos();
        if (target == last && horizontalDistance(targetPos, player) <= END_RADIUS) {
            return Command.stop(player.yaw(), Stop.END);
        }
        if (player.inBoat()) {
            return paddle(steps, target, player);
        }

        BlockPos aim = steps.get(aimIndex(steps, target)).pos();
        float wanted = yawTowards(player, aim);
        float yaw = turnTowards(player.yaw(), wanted);
        boolean facing = Math.abs(wrapDegrees(wanted - yaw)) <= WALK_ANGLE;

        PathStep next = steps.get(target);
        boolean climbingDown = next.climbing() && targetPos.getY() < player.y() - 0.1;
        // In water you can turn while swimming; on land walking while facing sideways leaves the route
        boolean forward = (facing || player.inWater()) && !climbingDown;

        boolean jump = false;
        if (player.onMount()) {
            // A horse steps up on its own; its jump key charges a leap that would overshoot the route
            return new Command(yaw, forward, false, false, false, false, Stop.NONE);
        }
        if (player.inWater()) {
            // Holding jump in water rises; releasing it sinks. Rise unless the route goes down
            jump = targetPos.getY() >= player.y() - 0.2;
        } else if (player.onGround() && forward && !next.climbing()) {
            boolean stepUp = targetPos.getY() >= player.y() + STEP_UP
                    && horizontalDistance(targetPos, player) <= JUMP_REACH;
            jump = stepUp || player.bumped();
        }

        boolean run = sprint && forward && facing && !player.inWater()
                && straightAhead(steps, target) >= SPRINT_STRAIGHT_STEPS;
        return new Command(yaw, forward, jump, run, false, false, Stop.NONE);
    }

    /**
     * Steers a boat. The boat turns with the left/right keys (about 1 degree per tick each way) and only moves forward
     * with the forward key, so heading and throttle are separate here; the player's own yaw is left to the camera.
     */
    private static Command paddle(List<PathStep> steps, int target, Player boat) {
        BlockPos aim = steps.get(aimIndex(steps, target)).pos();
        float error = wrapDegrees(yawTowards(boat, aim) - boat.yaw());
        // Left lowers the boat's yaw, right raises it (Boat#controlBoat)
        boolean left = error < -BOAT_TURN_DEADBAND;
        boolean right = error > BOAT_TURN_DEADBAND;
        boolean forward = Math.abs(error) <= BOAT_PADDLE_ANGLE;
        return new Command(boat.yaw(), forward, false, false, left, right, Stop.NONE);
    }

    /**
     * The step to walk toward. Normally the one after the mapped step, but if the player hasn't reached the mapped step yet
     * (it lies ahead of them along the route), walking past it would cut the corner it sits on.
     */
    static int targetIndex(List<PathStep> steps, int index, Player player) {
        int last = steps.size() - 1;
        if (index >= last) {
            return last;
        }
        BlockPos here = steps.get(index).pos();
        // "Reached" is measured along the direction the route arrives at the step from. The outgoing direction can't
        // tell: approaching a corner, the player is level with it along the leg that leaves it
        BlockPos from = index > 0 ? steps.get(index - 1).pos() : here;
        BlockPos to = index > 0 ? here : steps.get(index + 1).pos();
        double segX = to.getX() - from.getX();
        double segZ = to.getZ() - from.getZ();
        double length = Math.sqrt(segX * segX + segZ * segZ);
        if (length == 0.0 || horizontalDistance(here, player) < 0.3) {
            // Pure vertical steps (ladders, pillars) have no horizontal direction to be "past"; move on to the next one
            return index + 1;
        }
        double relX = player.x() - (here.getX() + 0.5);
        double relZ = player.z() - (here.getZ() + 0.5);
        double along = (segX * relX + segZ * relZ) / length;
        return along >= -0.3 ? index + 1 : index;
    }

    /**
     * Furthest step from {@code target} on that is still on the same straight, level line. Diagonal staircases and corners
     * stop it, so aiming there never cuts across a block the route avoids.
     */
    static int aimIndex(List<PathStep> steps, int target) {
        int aim = target;
        if (target == 0) {
            return aim;
        }
        BlockPos from = steps.get(target - 1).pos();
        BlockPos to = steps.get(target).pos();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        // Walking and paddling run straight; anything else (steps up, ladders, swimming) is aimed at step by step
        MovementType kind = steps.get(target).movement();
        if (to.getY() != from.getY() || (dx == 0 && dz == 0)
                || (kind != MovementType.TRAVERSE && kind != MovementType.BOAT)) {
            return aim;
        }
        for (int i = target + 1; i < steps.size() && i <= target + AIM_LOOKAHEAD; i++) {
            BlockPos a = steps.get(i - 1).pos();
            BlockPos b = steps.get(i).pos();
            if (b.getX() - a.getX() != dx || b.getZ() - a.getZ() != dz || b.getY() != to.getY()
                    || steps.get(i).movement() != kind) {
                break;
            }
            aim = i;
        }
        return aim;
    }

    /** Number of level, same-direction walking steps starting at {@code target}. */
    static int straightAhead(List<PathStep> steps, int target) {
        if (target == 0) {
            return 0;
        }
        BlockPos from = steps.get(target - 1).pos();
        BlockPos to = steps.get(target).pos();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int count = 0;
        for (int i = target; i < steps.size(); i++) {
            BlockPos a = steps.get(i - 1).pos();
            BlockPos b = steps.get(i).pos();
            if (b.getX() - a.getX() != dx || b.getZ() - a.getZ() != dz || b.getY() != a.getY()
                    || steps.get(i).movement() != MovementType.TRAVERSE) {
                break;
            }
            count++;
        }
        return count;
    }

    /** While riding: {@link Stop#DISMOUNT} once a step ahead isn't ridden any more. */
    static Stop leavesMountAhead(List<PathStep> steps, int index) {
        int to = Math.min(steps.size() - 1, index + STOP_LOOKAHEAD);
        for (int i = index + 1; i <= to; i++) {
            if (!steps.get(i).riding()) {
                return Stop.DISMOUNT;
            }
        }
        return Stop.NONE;
    }

    /** Why the next few steps can't be walked automatically, or {@link Stop#NONE}. */
    static Stop blockedAhead(List<PathStep> steps, int index) {
        return blockedAhead(steps, index, false);
    }

    /**
     * @param inBoat whether the player is riding a boat. Then boat steps are what auto-walk does, and the first step off
     *               the water is where it hands back
     */
    static Stop blockedAhead(List<PathStep> steps, int index, boolean inBoat) {
        int to = Math.min(steps.size() - 1, index + STOP_LOOKAHEAD);
        for (int i = index + 1; i <= to; i++) {
            PathStep step = steps.get(i);
            if (inBoat) {
                if (!step.boating()) {
                    return Stop.SHORE;
                }
                continue;
            }
            if (step.digging() || step.bridging() || step.boating() || step.movement() == MovementType.JUMP) {
                return Stop.MANUAL_STEP;
            }
            if (dangerous(step)) {
                return Stop.DANGER;
            }
        }
        return Stop.NONE;
    }

    private static boolean dangerous(PathStep step) {
        if (step.movement() == MovementType.FALL_DAMAGE || step.movement() == MovementType.FALL_MLG) {
            return true;
        }
        PathRisk risk = step.risk();
        return risk != PathRisk.NONE && risk != PathRisk.WATER_INFLOW;
    }

    /** Minecraft yaw that faces the center of {@code pos} from the player. */
    static float yawTowards(Player player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.x();
        double dz = pos.getZ() + 0.5 - player.z();
        if (dx * dx + dz * dz < 1.0e-6) {
            return player.yaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** {@code current} turned toward {@code wanted} by at most {@link #MAX_TURN_PER_TICK}, without unwrapping the yaw. */
    static float turnTowards(float current, float wanted) {
        float delta = wrapDegrees(wanted - current);
        if (delta > MAX_TURN_PER_TICK) {
            delta = MAX_TURN_PER_TICK;
        } else if (delta < -MAX_TURN_PER_TICK) {
            delta = -MAX_TURN_PER_TICK;
        }
        return current + delta;
    }

    /** Angle wrapped into [-180, 180). */
    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        } else if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static double horizontalDistance(BlockPos pos, Player player) {
        double dx = pos.getX() + 0.5 - player.x();
        double dz = pos.getZ() + 0.5 - player.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
