package net.prason.xaeronav.config;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Where config entries are declared. {@link XaeroNavConfig} writes its 37 entries against this one surface only,
 * and the actual storage (NeoForge's {@code ModConfigSpec} / Fabric's night-config) is handled by the
 * {@link NavConfigStore} implementation.
 *
 * <p>The method shapes intentionally mirror NeoForge's {@code ModConfigSpec.Builder}.
 * Otherwise a default or range could drift while copying the 37 entries over without anyone noticing
 * ({@code ConfigSpecGoldenTest} checks that they match).
 */
public interface NavConfigSpec {

    /** Comment attached to the entry declared next (or to the section opened by {@link #push}). */
    NavConfigSpec comment(String... lines);

    NavConfigSpec push(String section);

    NavConfigSpec pop();

    BoolValue define(String path, boolean defaultValue);

    IntValue defineInRange(String path, int defaultValue, int min, int max);

    DoubleValue defineInRange(String path, double defaultValue, double min, double max);

    StringListValue defineStringList(String path, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator);

    interface BoolValue {
        boolean get();

        void set(boolean value);
    }

    interface IntValue {
        int get();

        void set(int value);
    }

    interface DoubleValue {
        double get();
    }

    interface StringListValue {
        List<? extends String> get();
    }
}
