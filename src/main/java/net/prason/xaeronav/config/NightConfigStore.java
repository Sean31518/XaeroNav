package net.prason.xaeronav.config;

import net.prason.xaeronav.util.MathSupport;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Where settings are stored in places that lack NeoForge's {@code ModConfigSpec} (Fabric).
 *
 * <p>Reading and writing use night-config, the same library NeoForge's {@code ModConfigSpec} uses
 * internally, so the generated TOML has the same shape regardless of loader (down to the
 * {@code Default:} / {@code Range:} comment lines).
 *
 * <p>Touches no loader-specific classes; only the config file location comes from the caller.
 * That keeps this from becoming code that only compiles on the Fabric node, so canonical-node
 * unit tests can check the read/write behavior directly.
 */
public final class NightConfigStore implements NavConfigStore, NavConfigSpec {

    private static final Logger LOGGER = LogManager.getLogger();

    private final CommentedFileConfig file;
    private final Path path;
    private final List<Definition> definitions = new ArrayList<>();
    private final Deque<String> section = new ArrayDeque<>();

    private String pendingComment;

    public NightConfigStore(Path path) {
        this.path = path;
        this.file = CommentedFileConfig.builder(path)
                .sync()
                .preserveInsertionOrder()
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
    }

    @Override
    public NavConfigSpec spec() {
        return this;
    }

    @Override
    public void build() {
        try {
            file.load();
        } catch (RuntimeException parseError) {
            recoverBrokenFile(parseError);
        }
        for (Definition definition : definitions) {
            file.set(definition.path(), definition.correct(file.get(definition.path())));
            file.setComment(definition.path(), definition.comment());
        }
        file.save();
    }

    /** Falls back to an empty config that regenerates from defaults, keeping the original file with broken syntax. */
    private void recoverBrokenFile(RuntimeException parseError) {
        Path broken = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.move(path, broken, StandardCopyOption.REPLACE_EXISTING);
            file.clear();
            LOGGER.warn("XaeroNav: Moved the broken config file to {} and regenerating it with defaults", broken, parseError);
        } catch (java.io.IOException moveError) {
            moveError.addSuppressed(parseError);
            throw new IllegalStateException("Could not move the broken config file aside: " + path, moveError);
        }
    }

    @Override
    public void save() {
        file.save();
    }

    @Override
    public NavConfigSpec comment(String... lines) {
        pendingComment = String.join("\n", lines);
        return this;
    }

    @Override
    public NavConfigSpec push(String name) {
        section.addLast(name);
        List<String> path = List.copyOf(section);
        if (!file.contains(path)) {
            file.set(path, file.createSubConfig());
        }
        file.setComment(path, takeComment());
        return this;
    }

    @Override
    public NavConfigSpec pop() {
        section.removeLast();
        return this;
    }

    @Override
    public BoolValue define(String name, boolean defaultValue) {
        List<String> path = define(name, defaultValue, takeComment(),
                value -> value instanceof Boolean ? value : defaultValue);
        return new BoolValue() {
            @Override
            public boolean get() {
                return file.get(path);
            }

            @Override
            public void set(boolean value) {
                file.set(path, value);
            }
        };
    }

    @Override
    public IntValue defineInRange(String name, int defaultValue, int min, int max) {
        List<String> path = define(name, defaultValue, rangeComment(takeComment(), defaultValue, range(min, max)),
                value -> value instanceof Number number ? MathSupport.clamp(number.longValue(), min, max) : defaultValue);
        return new IntValue() {
            @Override
            public int get() {
                return ((Number) file.get(path)).intValue();
            }

            @Override
            public void set(int value) {
                file.set(path, value);
            }
        };
    }

    @Override
    public DoubleValue defineInRange(String name, double defaultValue, double min, double max) {
        List<String> path = define(name, defaultValue, rangeComment(takeComment(), defaultValue, min + " ~ " + max),
                value -> value instanceof Number number ? MathSupport.clamp(number.doubleValue(), min, max) : defaultValue);
        return () -> ((Number) file.get(path)).doubleValue();
    }

    @Override
    public StringListValue defineStringList(String name, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator) {
        List<String> path = define(name, defaultValue, takeComment(), value -> {
            if (!(value instanceof List<?> list)) {
                return defaultValue;
            }
            // If even one element is broken, reset the whole list to its default (same as ModConfigSpec).
            // Dropping only the broken elements would silently ignore part of a config the user thought they fixed
            return list.stream().allMatch(elementValidator) ? list : defaultValue;
        });
        return () -> file.<List<String>>get(path);
    }

    @Override
    public <E extends Enum<E>> EnumValue<E> defineEnum(String name, E defaultValue) {
        Class<E> type = defaultValue.getDeclaringClass();
        E[] constants = type.getEnumConstants();
        String allowed = Arrays.stream(constants).map(Enum::name).collect(Collectors.joining(", "));
        // Stored by name. Same "Allowed Values" line, default fallback and case-insensitive match as ModConfigSpec
        List<String> path = define(name, defaultValue.name(), takeComment() + "\nAllowed Values: " + allowed,
                value -> {
                    String text = value instanceof Enum<?> constant ? constant.name() : String.valueOf(value);
                    for (E constant : constants) {
                        if (constant.name().equalsIgnoreCase(text)) {
                            return constant.name();
                        }
                    }
                    return defaultValue.name();
                });
        return new EnumValue<>() {
            @Override
            public E get() {
                return Enum.valueOf(type, file.<String>get(path));
            }

            @Override
            public void set(E value) {
                file.set(path, value.name());
            }
        };
    }

    private List<String> define(String name, Object defaultValue, String comment, Function<Object, Object> corrector) {
        section.addLast(name);
        List<String> path = List.copyOf(section);
        section.removeLast();
        definitions.add(new Definition(path, defaultValue, comment, corrector));
        return path;
    }

    private String takeComment() {
        String comment = pendingComment;
        pendingComment = null;
        return comment;
    }

    /** Matches how ModConfigSpec appends the default and range to the end of the comment. */
    private static String rangeComment(String comment, Object defaultValue, String range) {
        return comment + "\n Default: " + defaultValue + "\n Range: " + range;
    }

    private static String range(int min, int max) {
        if (max == Integer.MAX_VALUE) {
            return "> " + min;
        }
        if (min == Integer.MIN_VALUE) {
            return "< " + max;
        }
        return min + " ~ " + max;
    }

    private record Definition(List<String> path, Object defaultValue, String comment,
            Function<Object, Object> corrector) {

        Object correct(Object value) {
            return value == null ? defaultValue : corrector.apply(value);
        }
    }
}
