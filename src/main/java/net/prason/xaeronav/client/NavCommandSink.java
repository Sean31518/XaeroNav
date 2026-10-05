package net.prason.xaeronav.client;

import net.minecraft.network.chat.Component;

/**
 * Where {@code /xaeronav} replies are sent back to chat.
 *
 * <p>The commands themselves don't depend on the loader, but where the reply goes does
 * (NeoForge uses {@code CommandSourceStack}, Fabric uses {@code FabricClientCommandSource}).
 * This absorbs just that one point.
 */
public interface NavCommandSink {

    void success(Component message);

    void failure(Component message);
}
