package net.prason.xaeronav.config;

//? forge {
/*import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraftforge.common.ForgeConfigSpec;

/^*
 * Forge-side storage. Reading, writing, file watching and correcting invalid values are all left to FML's
 * {@code ForgeConfigSpec}.
 ^/
public final class ForgeConfigSpecStore implements NavConfigStore, NavConfigSpec {

    private final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    private ForgeConfigSpec built;

    @Override
    public NavConfigSpec spec() {
        return this;
    }

    @Override
    public void build() {
        built = builder.build();
    }

    @Override
    public void save() {
        built.save();
    }

    /^* For passing to {@code ModLoadingContext#registerConfig}. ^/
    public ForgeConfigSpec forgeConfigSpec() {
        return built;
    }

    @Override
    public NavConfigSpec comment(String... lines) {
        builder.comment(lines);
        return this;
    }

    @Override
    public NavConfigSpec push(String section) {
        builder.push(section);
        return this;
    }

    @Override
    public NavConfigSpec pop() {
        builder.pop();
        return this;
    }

    @Override
    public BoolValue define(String path, boolean defaultValue) {
        ForgeConfigSpec.BooleanValue value = builder.define(path, defaultValue);
        return new BoolValue() {
            @Override
            public boolean get() {
                return value.get();
            }

            @Override
            public void set(boolean newValue) {
                value.set(newValue);
            }
        };
    }

    @Override
    public IntValue defineInRange(String path, int defaultValue, int min, int max) {
        ForgeConfigSpec.IntValue value = builder.defineInRange(path, defaultValue, min, max);
        return new IntValue() {
            @Override
            public int get() {
                return value.get();
            }

            @Override
            public void set(int newValue) {
                value.set(newValue);
            }
        };
    }

    @Override
    public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
        ForgeConfigSpec.DoubleValue value = builder.defineInRange(path, defaultValue, min, max);
        return value::get;
    }

    @Override
    public <E extends Enum<E>> EnumValue<E> defineEnum(String path, E defaultValue) {
        ForgeConfigSpec.EnumValue<E> value = builder.defineEnum(path, defaultValue);
        return new EnumValue<>() {
            @Override
            public E get() {
                return value.get();
            }

            @Override
            public void set(E newValue) {
                value.set(newValue);
            }
        };
    }

    // Forge's `defineListAllowEmpty` lacks the 4-argument version NeoForge has (taking a Supplier for new elements).
    // It only means Forge can't hold the initial value created by the GUI's "add element" button; the defaults and storage format don't change.
    // Below 1.21, align on the version taking List<String> and a Supplier (Forge 46 has no (String, List, Predicate) version)
    @Override
    public StringListValue defineStringList(String path, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator) {
        ForgeConfigSpec.ConfigValue<List<? extends String>> value =
                //? if >=1.21 {
                builder.defineListAllowEmpty(path, defaultValue, elementValidator);
                //?} else {
                /^builder.defineListAllowEmpty(List.of(path), () -> defaultValue, elementValidator);
                ^///?}
        return value::get;
    }
}
*///?}
