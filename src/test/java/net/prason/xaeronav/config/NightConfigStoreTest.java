package net.prason.xaeronav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

/**
 * Checks that the Fabric-side store produces the same config file as NeoForge's {@code ModConfigSpec}.
 *
 * <p>The config definition (the 37 entries in {@link XaeroNavConfig}) lives in only one place, so any drift
 * would come from the store implementations. Compared against a golden (taken from the real NeoForge side).
 */
class NightConfigStoreTest {

    @Test
    void writesTheSameFileAsModConfigSpec(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        NightConfigStore store = new NightConfigStore(file);
        new XaeroNavConfig(store.spec());
        store.build();

        CommentedFileConfig written = CommentedFileConfig.builder(file).build();
        written.load();
        for (Map.Entry<String, Golden> entry : golden().entrySet()) {
            List<String> path = List.of(entry.getKey().split("\\."));
            // Take it as Object first, then stringify. Passing it straight to String.valueOf makes
            // night-config's generic get() resolve to the char[] overload and throw a ClassCastException
            Object value = written.get(path);
            assertEquals(entry.getValue().defaultValue(), String.valueOf(value),
                    entry.getKey() + " default value");
            assertEquals(entry.getValue().comment(), String.valueOf(written.getComment(path)).strip(),
                    entry.getKey() + " comment");
        }
    }

    @Test
    void correctsBrokenValuesOnLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        Files.writeString(file, """
                [pathfinding]
                blockBudgetReserve = 9999
                heuristicWeight = "not a number"
                diggingEnabled = false
                """, StandardCharsets.UTF_8);

        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        // Out-of-range values are clamped, wrong types fall back to the default, valid values are kept as-is
        assertEquals(512, config.blockBudgetReserve());
        assertEquals(1.5, config.heuristicWeight());
        assertEquals(false, config.diggingEnabled());
    }

    @Test
    void keepsChangesAcrossSaveAndReload(@TempDir Path dir) {
        Path file = dir.resolve("xaeronav-client.toml");
        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        config.setHudEnabled(false);
        store.save();

        NightConfigStore reopened = new NightConfigStore(file);
        XaeroNavConfig reloaded = new XaeroNavConfig(reopened.spec());
        reopened.build();
        assertTrue(!reloaded.hudEnabled());
    }

    @Test
    void backsUpMalformedTomlAndRegeneratesDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        String malformed = "[pathfinding\ndiggingEnabled = false";
        Files.writeString(file, malformed, StandardCharsets.UTF_8);

        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        assertTrue(config.diggingEnabled());
        assertTrue(Files.exists(file));
        try (var files = Files.list(dir)) {
            Path backup = files.filter(path -> path.getFileName().toString().startsWith(
                            "xaeronav-client.toml.broken-"))
                    .findFirst().orElseThrow();
            assertEquals(malformed, Files.readString(backup, StandardCharsets.UTF_8));
        }
    }

    @Test
    void flightClearanceToggleRestoresTheCustomValue(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        Files.writeString(file, "[pathfinding]\nflightClearanceDetourBlocks = 37\n", StandardCharsets.UTF_8);
        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        config.setFlightClearanceEnabled(false);
        assertEquals(0, config.flightClearanceDetourBlocks());
        config.setFlightClearanceEnabled(true);
        assertEquals(37, config.flightClearanceDetourBlocks());
    }

    private record Golden(String defaultValue, String comment) {
    }

    /** The same definition list {@code ConfigSpecGoldenTest} checks, taken from the real NeoForge side. */
    private static Map<String, Golden> golden() throws IOException {
        Map<String, Golden> golden = new LinkedHashMap<>();
        try (InputStream in = NightConfigStoreTest.class.getResourceAsStream("/config-spec.golden")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split(" \\| ", -1);
                String path = columns[0];
                String defaultValue = columns[3].substring("default=".length());
                String comment = columns[5].substring("comment=".length()).replace("\\n", "\n");
                golden.put(path, new Golden(defaultValue, comment));
            }
        }
        return golden;
    }
}
