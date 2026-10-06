package net.prason.xaeronav.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.util.GameCompat;

/**
 * Auto-walk: steers the player along the shown route by holding the movement keys for them.
 *
 * <p>The steering itself is decided by {@link AutoWalkSteer}; this class only reads the player, applies the result to the
 * keys and decides when to stop. On foot it only ever presses forward and jump (and sets sprinting), so any other movement
 * key the player presses, or turning the camera, is unambiguously the player taking over, and stops it.
 *
 * <p>While it's on, routes are planned for what it can follow ({@link MovementOptions#forAutoWalk}: no swimming, placing,
 * gap jumps or painful falls), so switching it on or off plans the route again.
 *
 * <p>In a boat it steers the boat with the left/right keys along the water part of the route and stops at the shore,
 * where the player gets out and picks the boat up. On a horse (or donkey, mule, camel) it looks along the route and
 * holds forward; the animal follows the rider's view, and auto-walk stops where the route gets off.
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
    /** The boat's heading last tick, for its turn rate ({@link AutoWalkSteer.Player#turnRate}); NaN when not in one. */
    private float lastBoatYaw = Float.NaN;

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
        if (player.isPassenger() && !ChunkView.ridingBoat(player) && ChunkView.ridingMount(player) == null) {
            GameCompat.tell(player, TextCompat.translatable("hud.xaeronav.autowalk.unsupported_vehicle"), true);
            return;
        }
        enabled = true;
        holding = false;
        lastYaw = GameCompat.yaw(player);
        setRouting(true);
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
            reset();
            return;
        }
        Options options = mc.options;
        if (ClientCompat.screen(mc) != null) {
            // Paused, not stopped: opening the map or chat mid-walk is normal. Key presses go to the screen meanwhile
            release(options);
            lastYaw = GameCompat.yaw(player);
            return;
        }
        boolean inBoat = ChunkView.ridingBoat(player);
        boolean onMount = !inBoat && ChunkView.ridingMount(player) != null;
        String reason = stopReason(player, options, inBoat, onMount);
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

        float heading = inBoat ? GameCompat.yaw(player.getVehicle()) : GameCompat.yaw(player);
        float turnRate = inBoat && !Float.isNaN(lastBoatYaw) ? AutoWalkSteer.wrapDegrees(heading - lastBoatYaw) : 0.0F;
        lastBoatYaw = inBoat ? heading : Float.NaN;
        AutoWalkSteer.Command command = AutoWalkSteer.steer(result.steps(), PathProgress.INSTANCE.indexFor(result),
                new AutoWalkSteer.Player(player.getX(), player.getY(), player.getZ(), heading,
                        GameCompat.onGround(player), player.isInWater(), player.horizontalCollision, inBoat,
                        onMount, turnRate),
                XaeroNavConfig.INSTANCE.autoWalkSprint());
        switch (command.stop()) {
            case NONE -> apply(player, options, command, inBoat);
            case END -> {
                // At the destination the route state notices the arrival itself (stopReason picks it up next tick);
                // at the end of a partial route the rest is being searched. Either way, stand still meanwhile
                release(options);
                lastYaw = GameCompat.yaw(player);
            }
            case MANUAL_STEP -> stop(mc, "hud.xaeronav.autowalk.manual_step");
            case DANGER -> stop(mc, "hud.xaeronav.autowalk.danger");
            case SHORE -> stop(mc, "hud.xaeronav.autowalk.shore");
            case DISMOUNT -> stop(mc, "hud.xaeronav.autowalk.dismount");
        }
    }

    /** Translation key of why auto-walk must stop now, or {@code null} to keep going. */
    private String stopReason(LocalPlayer player, Options options, boolean inBoat, boolean onMount) {
        PathfindingState state = PathfindingState.INSTANCE;
        if (state.arrived()) {
            return "hud.xaeronav.autowalk.arrived";
        }
        if (state.goal() == null) {
            return "hud.xaeronav.autowalk.route_ended";
        }
        if (state.stuckReason() != null) {
            return "hud.xaeronav.autowalk.no_walkable_route";
        }
        if (state.flying() || player.isFallFlying()) {
            return "hud.xaeronav.autowalk.manual_step";
        }
        if (player.isPassenger() && !inBoat && !onMount) {
            return "hud.xaeronav.autowalk.unsupported_vehicle";
        }
        if (options.keyDown.isDown() || options.keyShift.isDown()) {
            return "hud.xaeronav.autowalk.player_input";
        }
        // In a boat auto-walk holds left/right itself, and the boat turning also turns the rider's camera, so neither
        // tells the player apart from auto-walk there; back and sneak (which gets out) still do
        if (!inBoat) {
            if (options.keyLeft.isDown() || options.keyRight.isDown()) {
                return "hud.xaeronav.autowalk.player_input";
            }
            if (Math.abs(AutoWalkSteer.wrapDegrees(GameCompat.yaw(player) - lastYaw)) > TAKEOVER_TURN) {
                return "hud.xaeronav.autowalk.player_input";
            }
        }
        if (player.hurtTime > 0) {
            return "hud.xaeronav.autowalk.hurt";
        }
        if (player.getHealth() <= XaeroNavConfig.INSTANCE.autoWalkStopHealth()) {
            return "hud.xaeronav.autowalk.low_health";
        }
        return null;
    }

    private void apply(LocalPlayer player, Options options, AutoWalkSteer.Command command, boolean inBoat) {
        if (inBoat) {
            options.keyLeft.setDown(command.left());
            options.keyRight.setDown(command.right());
            lastYaw = GameCompat.yaw(player);
        } else {
            GameCompat.setYaw(player, command.yaw());
            lastYaw = command.yaw();
        }
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
        if (PathfindingState.INSTANCE.arrived()) {
            // Nothing left to plan; replanning now would restart the route that just ended
            XaeroNavConfig.INSTANCE.setAutoWalkRouting(false);
        } else {
            // Back to the route the player walks themselves
            setRouting(false);
        }
        if (mc.player != null) {
            GameCompat.tell(mc.player, TextCompat.translatable(messageKey), true);
        }
    }

    /** Switches the movement options routes are planned with, and plans the current route again under them. */
    private static void setRouting(boolean autoWalk) {
        XaeroNavConfig.INSTANCE.setAutoWalkRouting(autoWalk);
        PathfindingState.INSTANCE.replan();
    }

    /** Lets go of the keys auto-walk holds. Only if it holds them, so it never cancels a key the player is pressing. */
    private void release(Options options) {
        if (!holding) {
            return;
        }
        release(options.keyUp);
        release(options.keyJump);
        release(options.keyLeft);
        release(options.keyRight);
        holding = false;
    }

    private static void release(KeyMapping key) {
        key.setDown(false);
    }

    /** Stops without a message or replan, e.g. when leaving the world (the route is cleared anyway). */
    void reset() {
        Minecraft mc = Minecraft.getInstance();
        release(mc.options);
        enabled = false;
        XaeroNavConfig.INSTANCE.setAutoWalkRouting(false);
    }
}
