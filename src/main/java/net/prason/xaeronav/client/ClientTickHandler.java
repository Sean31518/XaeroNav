package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import net.prason.xaeronav.xaero.XaeroHookRuntimeProbe;
import net.prason.xaeronav.xaero.XaeroHooks;

/** Drives the recompute triggers (deviation detection, periodic runs) and the measured speed for guidance display every tick. */
public final class ClientTickHandler {

    private static final boolean RUNTIME_HOOK_PROBE = Boolean.getBoolean(XaeroHookProbe.PROPERTY);

    /** Duration at which a tick is considered "slow". Equivalent to 1 tick (20 TPS). */
    private static final long SLOW_TICK_THRESHOLD_MILLIS = 50L;
    /** Interval before warning again while slow ticks continue. Warning every time floods the log. */
    private static final long SLOW_TICK_LOG_INTERVAL_MILLIS = 5_000L;

    /** Whether missing integration has been reported. Repeating it on every world join would make the user read an unfixable warning every time. */
    private boolean hookNoticeShown;

    private final ChangeGate<Boolean> slowTickGate = new ChangeGate<>();

    public void onClientTick() {
        long startMillis = MonotonicTime.millis();
        TickLaps.begin();
        long lap = TickLaps.start();
        XaeroNavKeys.handleInput();
        TickLaps.add("key input", lap);
        lap = TickLaps.start();
        PathfindingState.INSTANCE.onClientTick();
        TickLaps.add("route state", lap);
        lap = TickLaps.start();
        FullRoutePlanner.INSTANCE.onClientTick();
        TickLaps.add("full route", lap);
        lap = TickLaps.start();
        AutoWalk.INSTANCE.onClientTick();
        TickLaps.add("auto-walk", lap);
        lap = TickLaps.start();
        NavPace.INSTANCE.onClientTick();
        TickLaps.add("speed measurement", lap);
        lap = TickLaps.start();
        XaeroHookHealth.onClientTick();
        TickLaps.add("Xaero integration check", lap);
        // XaeroHookRuntimeProbe references Xaero types directly, so on a normal launch the class itself isn't loaded.
        if (RUNTIME_HOOK_PROBE) {
            XaeroHookRuntimeProbe.onClientTick();
        }
        TickLaps.end();
        long nowMillis = MonotonicTime.millis();
        long elapsedMillis = nowMillis - startMillis;
        if (elapsedMillis > SLOW_TICK_THRESHOLD_MILLIS
                && slowTickGate.changed(true, nowMillis, SLOW_TICK_LOG_INTERVAL_MILLIS)) {
            XaeroNav.LOGGER.warn("XaeroNav: slow tick ({}ms > {}ms, breakdown={})", elapsedMillis, SLOW_TICK_THRESHOLD_MILLIS,
                    TickLaps.summary());
        }
    }

    /**
     * Discards the route and destination when leaving a world.
     *
     * <p>{@link PathfindingState#onClientTick} just returns without doing anything when {@code level == null}, so
     * the goal and route survive a disconnect. On entering another world next, guidance toward the previous world's coordinates
     * would come back (the dimension difference is checked, but a different server in the same dimension can't be told apart).
     *
     * <p>The route's {@code ChunkView} holds chunk references for the search range, so
     * discarding it here also keeps it from blocking the world from unloading.
     */
    public void onLoggingOut() {
        AutoWalk.INSTANCE.reset();
        FullRoutePlanner.INSTANCE.clear();
        SavedChunks.INSTANCE.clear();
        PathfindingState.INSTANCE.clear();
    }

    public void onLoggingIn(LocalPlayer player) {
        reportMissingXaeroHooks(player);
    }

    /**
     * Reports, once per game launch, that Xaero is installed but the integration didn't apply.
     *
     * <p>A mixin that fails to apply disappears silently (required=false), so all the user sees is
     * "no line on the map". This happens with new Xaero versions that change the shape of the injection target, and even then
     * in-world rendering still works, so the user keeps using it without realizing it's broken.
     *
     * <p>It's reported on entering a world because that's the first chance to write to chat. The
     * {@code Class.forName} used for the check loads Xaero's classes, so it isn't done while mods are loading.
     */
    private void reportMissingXaeroHooks(LocalPlayer player) {
        if (hookNoticeShown) {
            return;
        }
        hookNoticeShown = true;
        List<XaeroHooks.Hook> missing = XaeroHooks.missing();
        for (XaeroHooks.Hook hook : XaeroHooks.Hook.values()) {
            if (ModPresence.isLoaded(hook.modId()) && XaeroHooks.applied(hook)) {
                // A marker so CI positively checks each hook's actual application, not just "no failure string".
                XaeroNav.LOGGER.info("XAERONAV_HOOK_APPLIED {}", hook.name());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        MutableComponent features = TextCompat.empty();
        for (XaeroHooks.Hook hook : missing) {
            if (!features.getSiblings().isEmpty()) {
                features.append(" / ");
            }
            features.append(TextCompat.translatable(hook.nameKey()));
            XaeroNav.LOGGER.warn("XaeroNav: Xaero integration mixin did not apply ({} / {}). "
                    + "The Xaero version may be outside the supported range", hook.modId(), hook.className());
        }
        GameCompat.tell(player, TextCompat.translatable("hud.xaeronav.hook_missing", features), false);
    }
}
