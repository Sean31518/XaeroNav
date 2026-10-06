package net.prason.xaeronav.client;

import java.util.function.Consumer;

//? if <26.3 {
import org.lwjgl.glfw.GLFW;
//?}

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
//? if >=1.21.9 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.util.GameCompat;

/**
 * Key bindings. All are unbound by default: these aren't actions that compete with other mods,
 * so it's less error-prone for whoever wants them to bind them to a free key themselves.
 *
 * <p>"Pathfind to the block you're looking at" is the only reasonable way to specify a destination without Xaero
 * (otherwise the only option is typing coordinates with {@code /xaeronav goto <coords>}).
 */
public final class XaeroNavKeys {

    //? if >=1.21.9 {
    /*// The category display name is looked up from the translation key `key.category.<namespace>.<path>`
    //? if neoforge {
    // NeoForge deprecates vanilla's Category.register; register through RegisterKeyMappingsEvent#registerCategory
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "main"));
    //?} else {
    /^private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "main"));
    ^///?}
    *///?} else {
    private static final String CATEGORY = "key.categories.xaeronav";
    //?}

    public static final KeyMapping GOTO_LOOKING_AT = unbound("key.xaeronav.goto_looking_at");
    public static final KeyMapping CLEAR = unbound("key.xaeronav.clear");
    public static final KeyMapping TOGGLE_HUD = unbound("key.xaeronav.toggle_hud");
    public static final KeyMapping OPEN_CONFIG_SCREEN = unbound("key.xaeronav.open_config_screen");
    /** Only registered where auto-walk is supported ({@link AutoWalk#SUPPORTED}). */
    public static final KeyMapping TOGGLE_AUTO_WALK = unbound("key.xaeronav.toggle_auto_walk");

    /**
     * Pathfind-to-cursor key that only works while Xaero's world map screen ({@code GuiMap}) is open.
     * It's a normal KeyMapping set from the Controls screen, like Xaero's own in-map shortcuts (B = create waypoint, etc.),
     * but it isn't handled in {@link #handleInput} (the loop consumed every tick during normal play):
     * the map screen intercepts Minecraft's key events itself, so the check is done directly with {@code matches}
     * by {@code mixin.xaero.GuiMapKeyMixin} via an injection into GuiMap#keyPressed.
     */
    public static final KeyMapping GOTO_MAP_CURSOR = unbound("key.xaeronav.goto_map_cursor");

    private XaeroNavKeys() {
    }

    private static KeyMapping unbound(String name) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    }

    /** Passes them to each loader's registration point ({@code RegisterKeyMappingsEvent} on NeoForge, {@code KeyBindingHelper} on Fabric). */
    public static void register(Consumer<KeyMapping> sink) {
        sink.accept(GOTO_LOOKING_AT);
        sink.accept(CLEAR);
        sink.accept(TOGGLE_HUD);
        sink.accept(OPEN_CONFIG_SCREEN);
        sink.accept(GOTO_MAP_CURSOR);
        if (AutoWalk.SUPPORTED) {
            sink.accept(TOGGLE_AUTO_WALK);
        }
    }

    /**
     * Processes as many presses as occurred. {@code consumeClick} takes one from the queue, so
     * holding the key doesn't fire every tick.
     */
    static void handleInput() {
        Minecraft mc = Minecraft.getInstance();

        // Opening the settings screen doesn't need player/world state, so consume it before the guard below.
        // If placed after the guard, presses made while nothing is loaded (e.g. on the title screen) stay in the queue
        // and the screen opens the moment you enter a world (without pressing anything)
        while (OPEN_CONFIG_SCREEN.consumeClick()) {
            ClientCompat.setScreen(mc, new XaeroNavConfigScreen(ClientCompat.screen(mc)));
        }

        if (mc.player == null || mc.level == null) {
            drainWorldKeys();
            return;
        }
        while (GOTO_LOOKING_AT.consumeClick()) {
            gotoLookingAt(mc);
        }
        while (CLEAR.consumeClick()) {
            PathfindingState.INSTANCE.clear();
            GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.cleared"), true);
        }
        while (TOGGLE_HUD.consumeClick()) {
            boolean enabled = !XaeroNavConfig.INSTANCE.hudEnabled();
            XaeroNavConfig.INSTANCE.setHudEnabled(enabled);
            XaeroNavConfig.save();
            GameCompat.tell(mc.player, TextCompat.translatable(enabled
                    ? "hud.xaeronav.hud_on"
                    : "hud.xaeronav.hud_off"), true);
        }
        while (TOGGLE_AUTO_WALK.consumeClick()) {
            AutoWalk.INSTANCE.toggle();
        }
    }

    /** Allows targeting up to roughly the render distance, not the block-interaction reach (about 4.5-5 blocks) */
    private static final double LOOK_PICK_DISTANCE = 512.0;

    private static void gotoLookingAt(Minecraft mc) {
        HitResult hit = mc.player.pick(LOOK_PICK_DISTANCE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            GameCompat.tell(mc.player, TextCompat.translatable("hud.xaeronav.no_block_in_view"), true);
            return;
        }
        // We want to stand on top of the targeted block, not inside it. The usual use is to point at the ground,
        // so pass one block above (StanceFinder resnaps to where you can actually stand)
        BlockPos resolved = PathfindingState.INSTANCE.setGoal(blockHit.getBlockPos().above());
        if (resolved != null) {
            GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.goal_walk",
                    resolved.toShortString()), true);
        }
        XaeroNav.LOGGER.debug("XaeroNav: pathfinding to the block being looked at {}", blockHit.getBlockPos());
    }

    /** Don't carry world-dependent key presses made on the title/loading screen over to the next join. */
    private static void drainWorldKeys() {
        while (GOTO_LOOKING_AT.consumeClick()) {
            // drain
        }
        while (CLEAR.consumeClick()) {
            // drain
        }
        while (TOGGLE_HUD.consumeClick()) {
            // drain
        }
        while (TOGGLE_AUTO_WALK.consumeClick()) {
            // drain
        }
    }
}
