package net.prason.xaeronav.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Measures how fast the player is actually moving.
 *
 * <p>The cost A* accumulates is an estimate for "moving as fast as possible", so walking or taking detours shifts
 * the arrival time accordingly. Re-dividing by the measured speed makes the display follow how the player actually
 * moves (it works the same way when faster, e.g. on vehicles or ice roads).
 *
 * <p>Nothing is re-measured while stopped. It would divide by zero, and an arrival time that balloons to infinity
 * after a brief pause is useless as guidance. While stopped, the previous speed is kept.
 */
final class NavPace {

    static final NavPace INSTANCE = new NavPace();

    /**
     * Weight of one tick's contribution. About half is replaced in roughly 3 seconds. Player movement is broken
     * up finely by jumps, pauses, and turning around, so making it shorter makes the display jittery.
     */
    private static final double SMOOTHING = 0.012;

    /** Ticks that moved less than this are considered "stopped" and not mixed into the measurement (blocks/tick). */
    private static final double MOVING_THRESHOLD = 0.01;

    /**
     * Moving more than this in one tick isn't movement (teleports, dimension changes, position corrections from chunk loading).
     * Even at top elytra speed it's about 2 blocks per tick.
     */
    private static final double TELEPORT_THRESHOLD = 8.0;

    /** Default before anything has been measured. Equivalent to sprinting. */
    private static final double DEFAULT_BLOCKS_PER_TICK = 5.612 / 20.0;

    private double blocksPerTick = DEFAULT_BLOCKS_PER_TICK;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean tracking;

    private NavPace() {
    }

    void onClientTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            tracking = false;
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (!tracking) {
            lastX = x;
            lastY = y;
            lastZ = z;
            tracking = true;
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        lastX = x;
        lastY = y;
        lastZ = z;

        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moved < MOVING_THRESHOLD || moved > TELEPORT_THRESHOLD) {
            return;
        }
        blocksPerTick += (moved - blocksPerTick) * SMOOTHING;
    }

    /** Most recently measured speed (blocks/tick). */
    double blocksPerTick() {
        return blocksPerTick;
    }
}
