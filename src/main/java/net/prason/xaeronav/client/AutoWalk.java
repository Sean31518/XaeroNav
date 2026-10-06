package net.prason.xaeronav.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.util.GameCompat;

/**
 * Auto-walk: steers the player along the shown route by holding the movement keys for them.
 *
 * <p>The steering itself is decided by {@link AutoWalkSteer}; this class only reads the player, applies the result to the
 * keys and decides when to stop. It only ever presses forward and jump (and sets sprinting), so any other movement key the
 * player presses, or turning the camera, is unambiguously the player taking over, and stops it.
 *
 * <p>Sprinting is set on the player rather than through the sprint key: with "Sprint: Toggle" in the controls that key is
 * a {@code ToggleKeyMapping}, whose {@code setDown(true)} flips the state, so holding it every tick would flicker.
 *
 * <p>Many multiplayer servers treat automated movement as cheating, so it only runs in singleplayer unless
 * {@code autoWalk.allowOnServers} is set.
 *
 * <p>Only available on Minecraft 26.3 and later ({@link #SUPPORTED}); on older versions the key isn't registered.
 */
public final class AutoWalk {

    public static final AutoWalk INSTANCE = new AutoWalk();

    //? if >=26.3 {
    /*public static final boolean SUPPORTED = true;
    *///?} else {
    public static final boolean SUPPORTED = false;
    //?}

    /**
     * Camera turn (degrees) between two ticks beyond what auto-walk itself set that counts as the player taking over.
     * Small enough to catch a deliberate look around, large enough to ignore rounding of the synced rotation.
     */
    private static final float TAKEOVER_TURN = 15.0F;

    private boolean enabled;
    /** Whether auto-walk is holding the keys right now (false while paused, e.g. a screen is open or the route is being computed). */
    private boolean holding;
    private float lastYaw;

    private AutoWalk() {
    }

    public boolean enabled() {
        return enabled;
    }

    /** Turns auto-walk on or off, telling the player why if it can't start. */
    public void toggle() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (enabled) {
            stop(mc, "hud.xaeronav.autowalk.off");
            return;
        }
        if (!SUPPORTED) {
            return;
        }
        boolean server = !mc.hasSingleplayerServer();
        if (server && !XaeroNavConfig.INSTANCE.autoWalkOnServers()) {
            GameCompat.tell(player, TextCompat.translatable("hud.xaeronav.autowalk.singleplayer_only"), false);
            return;
        }
        if (PathfindingState.INSTANCE.goal() == null) {
            GameCompat.tell(player, TextCompat.translatable("hud.xaeronav.autowalk.no_route"), true);
            return;
        }
        enabled = true;
        holding = false;
        lastYaw = GameCompat.yaw(player);
        GameCompat.tell(player, TextCompat.translatable(server
                ? "hud.xaeronav.autowalk.on_server"
                : "hud.xaeronav.autowalk.on"), !server);
    }

    /** Runs after the route state has been updated this tick, so it steers by the same mapping the HUD shows. */
    void onClientTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            enabled = false;
            holding = false;
            return;
        }
        Options options = mc.options;
        if (ClientCompat.screen(mc) != null) {
            // Paused, not stopped: opening the map or chat mid-walk is normal. Key presses go to the screen meanwhile
            release(options);
            lastYaw = GameCompat.yaw(player);
            return;
        }
        String reason = stopReason(mc, player, options);
        if (reason != null) {
            stop(mc, reason);
            return;
        }

        PathfindingState state = PathfindingState.INSTANCE;
        PathResult result = state.currentResult();
        if (result == null || !PathProgress.INSTANCE.tracking(result)) {
            // The route is still being computed or was just replaced; wait for it standing still
            release(options);
            lastYaw = GameCompat.yaw(player);
            return;
        }

        AutoWalkSteer.Command command = AutoWalkSteer.steer(result.steps(), PathProgress.INSTANCE.indexFor(result),
                new AutoWalkSteer.Player(player.getX(), player.getY(), player.getZ(), GameCompat.yaw(player),
                        GameCompat.onGround(player), player.isInWater(), player.horizontalCollision),
                XaeroNavConfig.INSTANCE.autoWalkSprint());
        switch (command.stop()) {
            case NONE -> apply(player, options, command);
            case END -> {
                // At the destination the route state notices the arrival itself (stopReason picks it up next tick);
                // at the end of a partial route the rest is being searched. Either way, stand still meanwhile
                release(options);
                lastYaw = GameCompat.yaw(player);
            }
            case MANUAL_STEP -> stop(mc, "hud.xaeronav.autowalk.manual_step");
            case DANGER -> stop(mc, "hud.xaeronav.autowalk.danger");
        }
    }

    /** Translation key of why auto-walk must stop now, or {@code null} to keep going. */
    private String stopReason(Minecraft mc, LocalPlayer player, Options options) {
        PathfindingState state = PathfindingState.INSTANCE;
        if (state.arrived()) {
            return "hud.xaeronav.autowalk.arrived";
        }
        if (state.goal() == null) {
            return "hud.xaeronav.autowalk.route_ended";
        }
        if (state.flying() || player.isFallFlying() || player.isPassenger()) {
            return "hud.xaeronav.autowalk.manual_step";
        }
        if (options.keyDown.isDown() || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyShift.isDown()) {
            return "hud.xaeronav.autowalk.player_input";
        }
        if (Math.abs(AutoWalkSteer.wrapDegrees(GameCompat.yaw(player) - lastYaw)) > TAKEOVER_TURN) {
            return "hud.xaeronav.autowalk.player_input";
        }
        if (player.hurtTime > 0) {
            return "hud.xaeronav.autowalk.hurt";
        }
        if (player.getHealth() <= XaeroNavConfig.INSTANCE.autoWalkStopHealth()) {
            return "hud.xaeronav.autowalk.low_health";
        }
        return null;
    }

    private void apply(LocalPlayer player, Options options, AutoWalkSteer.Command command) {
        GameCompat.setYaw(player, command.yaw());
        lastYaw = command.yaw();
        options.keyUp.setDown(command.forward());
        options.keyJump.setDown(command.jump());
        if (command.sprint()) {
            player.setSprinting(true);
        } else if (holding && player.isSprinting()) {
            // Only drop a sprint auto-walk could have started; on the first tick it may be the player's own
            player.setSprinting(false);
        }
        holding = true;
    }

    private void stop(Minecraft mc, String messageKey) {
        release(mc.options);
        enabled = false;
        if (mc.player != null) {
            GameCompat.tell(mc.player, TextCompat.translatable(messageKey), true);
        }
    }

    /** Lets go of the keys auto-walk holds. Only if it holds them, so it never cancels a key the player is pressing. */
    private void release(Options options) {
        if (!holding) {
            return;
        }
        release(options.keyUp);
        release(options.keyJump);
        holding = false;
    }

    private static void release(KeyMapping key) {
        key.setDown(false);
    }

    /** Stops without a message, e.g. when leaving the world. */
    void reset() {
        Minecraft mc = Minecraft.getInstance();
        release(mc.options);
        enabled = false;
    }
}
