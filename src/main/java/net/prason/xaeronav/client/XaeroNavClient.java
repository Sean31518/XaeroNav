package net.prason.xaeronav.client;

import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.DiggableBlocks;

/**
 * The implementation called from loader events. Loader-specific entry points only
 * wire their own events to the three methods here.
 */
public final class XaeroNavClient {

    public static final PathRenderer PATH_RENDERER = new PathRenderer();
    public static final NavHud HUD = new NavHud();
    public static final ClientTickHandler TICK_HANDLER = new ClientTickHandler();

    private XaeroNavClient() {
    }

    /**
     * Only the dig-allow/deny block lists keep the result of resolving IDs to Blocks, so they must track
     * config file loads and reloads themselves (other config values are read on every access, so nothing is needed).
     */
    public static void reloadBlockLists() {
        DiggableBlocks.reloadFromConfig(XaeroNavConfig.INSTANCE.additionalDiggableBlocks(),
                XaeroNavConfig.INSTANCE.additionalForbiddenBlocks());
    }
}
