package net.prason.xaeronav.config;

//? neoforge {
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Checks that the config definitions (path, type, default, range, comment) have not changed unintentionally.
 *
 * <p>Moving the config definitions to a loader-independent description means copying 38 entries by hand.
 * Getting a single default or range wrong still compiles, no other test fails, and the setting just
 * silently becomes a different value on the user's machine. This comparison prevents that.
 */
class ConfigSpecGoldenTest {

    /** The actual contents. Written to the working directory (build/test-run) so clean removes it too. */
    private static final Path ACTUAL = Path.of("config-spec.actual");

    @Test
    void specMatchesGolden() throws IOException {
        String actual = dump(((ModConfigSpecStore) XaeroNavConfig.store()).modConfigSpec().getSpec());
        Files.writeString(ACTUAL, actual, StandardCharsets.UTF_8);
        assertEquals(golden(), actual, "Config definitions differ from the golden file. Compare against " + ACTUAL.toAbsolutePath() + " for the diff");
    }

    private static String golden() throws IOException {
        try (InputStream in = ConfigSpecGoldenTest.class.getResourceAsStream("/config-spec.golden")) {
            if (in == null) {
                return "<golden file missing>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String dump(UnmodifiableConfig spec) {
        List<String> lines = new ArrayList<>();
        collect(spec, "", lines);
        return String.join("\n", lines) + "\n";
    }

    private static void collect(UnmodifiableConfig config, String prefix, List<String> out) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                collect(nested, path, out);
            } else if (value instanceof ModConfigSpec.ValueSpec valueSpec) {
                out.add(describe(path, valueSpec));
            } else {
                out.add(path + " | unknown node: " + value.getClass().getName());
            }
        }
    }

    private static String describe(String path, ModConfigSpec.ValueSpec spec) {
        Object defaultValue = spec.getDefault();
        ModConfigSpec.Range<?> range = spec.getRange();
        return path
                + " | class=" + spec.getClass().getSimpleName()
                + " | type=" + (defaultValue == null ? "null" : defaultValue.getClass().getSimpleName())
                + " | default=" + defaultValue
                + " | range=" + (range == null ? "-" : range)
                + " | comment=" + String.valueOf(spec.getComment()).replace("\n", "\\n");
    }
}
//?}
