package net.prason.xaeronav.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.client.OptionInstance;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;

/**
 * Checks that the list of toggles registered on the settings screen isn't broken. Actual GUI rendering and layout are out of scope;
 * only the boundary of "registered items and their values" is checked.
 *
 * <p>{@code XaeroNavConfig.INSTANCE} only builds the spec in its static initializer and doesn't wait for the actual load (file read),
 * so calling getters that read values throws {@code IllegalStateException}. A loaded instance is created with the same steps as
 * {@link NightConfigStoreTest} (going through {@link NightConfigStore} up to build).
 */
class XaeroNavConfigScreenOptionsTest {

    @Test
    void everyOptionIsRegisteredWithoutThrowing(@TempDir Path dir) throws IOException {
        NightConfigStore store = new NightConfigStore(dir.resolve("xaeronav-client.toml"));
        XaeroNavConfig cfg = new XaeroNavConfig(store.spec());
        store.build();

        List<OptionInstance<?>> collected = new ArrayList<>();
        assertDoesNotThrow(() -> XaeroNavConfigScreen.addAllOptions(cfg, collected::add));
        // Must match the number of addBig.accept calls in XaeroNavConfigScreen.addAllOptions.
        // This catches it when items are added or removed.
        assertEquals(17, collected.size());
    }
}
