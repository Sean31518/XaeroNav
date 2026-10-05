package net.prason.xaeronav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Consistency of translation keys.
 *
 * <p>Adding a string means writing it in three places at once, "one in the code, one in en_us, one in ja_jp", so
 * one of them is easy to miss. A miss throws no exception; the raw key string ({@code hud.xaeronav.foo}) just
 * appears on screen, so it goes unnoticed unless you run the game and reproduce the situation.
 *
 * <p>No library is used for JSON parsing. The only targets are flat string dictionaries we wrote ourselves, and
 * there's no need to add a test dependency for that.
 */
class LanguageKeyTest {

    /**
     * The test's working directory is a throwaway location, so the source tree location is received from the build
     * (making the working directory the repository root makes Minecraft's log4j configuration write logs
     * there, and files keep piling up).
     */
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("xaeronav.projectRoot", "."));
    private static final Path LANG_DIR = PROJECT_ROOT.resolve("src/main/resources/assets/xaeronav/lang");
    private static final Path SOURCE_DIR = PROJECT_ROOT.resolve("src/main/java");

    /** Picks up only the left-hand side of {@code "key": "value"}. */
    private static final Pattern JSON_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");

    /**
     * String literals in the source that look like translation keys.
     *
     * <p>They're picked up by namespace rather than narrowed by call form ({@code Component.translatable(...)}, the {@code RightClickOption}
     * constructor, ternary branches). Ways of passing keys keep growing, and if this regex had to be fixed each time,
     * it would quietly slip back into a state where the test passes but isn't actually looking.
     */
    private static final Pattern USED_KEY = Pattern.compile(
            "\"((?:gui|hud|commands|key|xaeronav)\\.[a-zA-Z0-9_.]+)\"");

    private static Set<String> keysOf(String fileName) {
        String json = read(LANG_DIR.resolve(fileName));
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = JSON_KEY.matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read: " + path.toAbsolutePath(), e);
        }
    }

    @Test
    void everyLanguageFileHasTheSameKeys() {
        Set<String> english = keysOf("en_us.json");
        Set<String> japanese = keysOf("ja_jp.json");

        assertTrue(!english.isEmpty(), "Could not read a single key from en_us.json (a problem in the test itself)");

        Set<String> missingInJapanese = new TreeSet<>(english);
        missingInJapanese.removeAll(japanese);
        Set<String> missingInEnglish = new TreeSet<>(japanese);
        missingInEnglish.removeAll(english);

        assertTrue(missingInJapanese.isEmpty(), "Keys missing from ja_jp.json: " + missingInJapanese);
        assertTrue(missingInEnglish.isEmpty(), "Keys missing from en_us.json: " + missingInEnglish);
    }

    @Test
    void everyKeyUsedInCodeIsTranslated() {
        Set<String> declared = keysOf("en_us.json");
        Set<String> used = usedKeys();

        assertTrue(!used.isEmpty(), "Could not pick up a single translation key from the source (a problem in the test itself)");

        Set<String> undeclared = new TreeSet<>(used);
        undeclared.removeAll(declared);
        // Xaero's translation keys ("gui.xaero_..."), held by GuiMapRightClickMixin for comparison to identify
        // Xaero's own menu items. Not something to declare in our lang files
        // (our actual keys are "gui.xaeronav...", with no underscore in between, so they don't collide)
        undeclared.removeIf(key -> key.startsWith("gui.xaero_"));
        // Translation keys Minecraft itself already has, used by the 1.16.5 XaeroNavConfigScreen (the stonecutter branch
        // for the old Screen API without OptionInstance etc., `//? if <1.17`).
        // Not something to duplicate into our lang files
        undeclared.removeAll(Set.of("gui.back", "gui.next", "gui.done"));

        assertTrue(undeclared.isEmpty(),
                "Keys referenced in code but missing from the lang files: " + undeclared);
    }

    @Test
    void everyTranslatedKeyIsUsedSomewhere() {
        Set<String> declared = keysOf("en_us.json");
        Set<String> used = usedKeys();

        Set<String> unused = new TreeSet<>(declared);
        unused.removeAll(used);
        // NeoForge looks up config screen entry names and descriptions by convention as xaeronav.configuration.*,
        // so they don't appear as strings in the source
        unused.removeIf(key -> key.startsWith("xaeronav.configuration."));
        // NeoForge also looks up key bindings from the KeyMapping's registered name
        unused.removeIf(key -> key.startsWith("key."));

        assertTrue(unused.isEmpty(), "Keys in lang but not referenced from code: " + unused);
    }

    private static Set<String> usedKeys() {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_DIR)) {
            List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : javaFiles) {
                Matcher matcher = USED_KEY.matcher(read(file));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan the sources", e);
        }
        return keys;
    }
}
