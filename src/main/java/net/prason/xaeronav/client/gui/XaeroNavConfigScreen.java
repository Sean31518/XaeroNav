package net.prason.xaeronav.client.gui;

//? if >=1.19.3 {
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
//?} else {
/*import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
*///?}
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.client.AutoWalk;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.TextCompat;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
*///?}

/**
 * Settings screen for {@link XaeroNavConfig}.
 *
 * <p>From 1.21 on it is split into pages: the first page holds the route profile (the setting changed most) and one
 * button per {@link Category}; each category opens its own page. A single list of every switch had grown past twenty
 * items, with route, terrain, travel and display settings interleaved. Older versions keep the single list.
 *
 * <p>Numeric parameters such as search range, deviation threshold and surface height, and the extra list of no-dig blocks, aren't here.
 * They're rarely touched, and editing the TOML directly is enough.
 *
 * <p>{@link OptionsSubScreen} provides the same foundation as vanilla's video settings screen and others (single-column layout, scrolling,
 * Done button). The {@code options} argument is a hook used only when touching vanilla's {@link net.minecraft.client.Options},
 * and this mod doesn't use it.
 *
 * <p>1.20.1's {@code OptionsSubScreen} (directly under {@code net.minecraft.client.gui.screens}, a different
 * package from 1.21.1) has no {@code addOptions()} hook, so the list and Done button must be assembled in our
 * own {@code init()} ({@code SimpleOptionsSubScreen} isn't used because it forces a fixed two-column (addSmall)
 * layout; Japanese labels are long and require a single column (addBig)).
 */
//? if >=1.19.3 {
public final class XaeroNavConfigScreen extends OptionsSubScreen {

    /** The pages of the settings screen, in the order their buttons appear. */
    public enum Category {
        /** Where the route may go: digging and placing blocks. */
        TERRAIN("gui.xaeronav.config.category.terrain"),
        /** How you get there: swimming, boats, jumps, falls, flying. */
        TRAVEL("gui.xaeronav.config.category.travel"),
        /** Auto-walk, only where it exists. */
        AUTO_WALK("gui.xaeronav.config.category.auto_walk"),
        /** What is drawn on screen and on the map. */
        DISPLAY("gui.xaeronav.config.category.display"),
        /** Search behaviour most people never touch. */
        ADVANCED("gui.xaeronav.config.category.advanced");

        private final String titleKey;

        Category(String titleKey) {
            this.titleKey = titleKey;
        }

        public String titleKey() {
            return titleKey;
        }

        /** Whether this page is offered at all. */
        public boolean available() {
            return this != AUTO_WALK || AutoWalk.SUPPORTED;
        }
    }

    //? if <1.21 {
    /*private OptionsList list;
    *///?}

    /** The page shown; {@code null} for the first page. */
    private final @Nullable Category category;

    public XaeroNavConfigScreen(Screen parent) {
        this(parent, null);
    }

    private XaeroNavConfigScreen(Screen parent, @Nullable Category category) {
        super(parent, Minecraft.getInstance().options, Component.translatable(
                category == null ? "gui.xaeronav.config.title" : category.titleKey()));
        this.category = category;
    }

    //? if >=1.21 {
    @Override
    protected void addOptions() {
        if (category != null) {
            addCategoryOptions(XaeroNavConfig.INSTANCE, category, this.list::addBig);
            return;
        }
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;
        this.list.addBig(routeProfileOption(cfg.routeProfile(), cfg::setRouteProfile));
        // Category buttons two per row; the labels are short enough for two columns even in Japanese
        List<Button> buttons = new ArrayList<>();
        for (Category page : Category.values()) {
            if (page.available()) {
                buttons.add(Button.builder(Component.translatable(page.titleKey()), button -> openPage(page)).build());
            }
        }
        for (int i = 0; i < buttons.size(); i += 2) {
            if (i + 1 < buttons.size()) {
                this.list.addSmall(buttons.get(i), buttons.get(i + 1));
            } else {
                this.list.addSmall(buttons.get(i), null);
            }
        }
    }

    /** Opens a category page. What was changed here so far is kept and saved first, so the page starts from it. */
    private void openPage(Category page) {
        this.list.applyUnsavedChanges();
        XaeroNavConfig.save();
        ClientCompat.setScreen(Minecraft.getInstance(), new XaeroNavConfigScreen(this, page));
    }
    //?} else if >=1.20.5 {
    /*// In 1.20.5 OptionsSubScreen gained a header and footer layout (including the Done button).
    // That layout decides positions, so add the list and then leave it to super.init()
    @Override
    protected void init() {
        this.list = this.addRenderableWidget(new OptionsList(this.minecraft, this.width, this.height, this));
        addAllOptions(XaeroNavConfig.INSTANCE, this.list::addBig);
        super.init();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        this.list.updateSize(this.width, this.layout);
    }
    *///?} else {
    /*@Override
    protected void init() {
        // The itemHeight argument was removed in 1.20.3.
        //? if >=1.20.3 {
        this.list = new OptionsList(this.minecraft, this.width, this.height, 32, this.height - 32);
        //?} else {
        /^this.list = new OptionsList(this.minecraft, this.width, this.height, 32, this.height - 32, 25);
        ^///?}
        addAllOptions(XaeroNavConfig.INSTANCE, this.list::addBig);
        this.addWidget(this.list);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());
    }
    *///?}

    /**
     * Every option on every page, in page order (the route profile first). Used by the single-list screen of older
     * versions and by tests.
     *
     * <p>cfg is a parameter for tests (XaeroNavConfigScreenOptionsTest); production just passes XaeroNavConfig.INSTANCE.
     * It's public because the test that builds a loaded XaeroNavConfig via NightConfigStore lives in the config package.
     */
    public static void addAllOptions(XaeroNavConfig cfg, Consumer<OptionInstance<?>> addBig) {
        addBig.accept(routeProfileOption(cfg.routeProfile(), cfg::setRouteProfile));
        for (Category page : Category.values()) {
            if (page.available()) {
                addCategoryOptions(cfg, page, addBig);
            }
        }
    }

    // Japanese labels are long and get cut off in two columns (addSmall), so all items are laid out in one column (addBig).
    public static void addCategoryOptions(XaeroNavConfig cfg, Category page, Consumer<OptionInstance<?>> addBig) {
        switch (page) {
            case TERRAIN:
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.digging_enabled",
                        "gui.xaeronav.config.digging_enabled.tooltip", cfg.diggingEnabled(), cfg::setDiggingEnabled));
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.bridging_enabled",
                        "gui.xaeronav.config.bridging_enabled.tooltip", cfg.bridgingEnabled(), cfg::setBridgingEnabled));
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.lava_bridging_enabled",
                        "gui.xaeronav.config.lava_bridging_enabled.tooltip",
                        cfg.lavaBridgingEnabled(), cfg::setLavaBridgingEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.block_budget_enabled",
                        cfg.blockBudgetEnabled(), cfg::setBlockBudgetEnabled));
                break;
            case TRAVEL:
                addBig.accept(boolOption("gui.xaeronav.config.swimming_enabled",
                        cfg.swimmingEnabled(), cfg::setSwimmingEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.boats_enabled",
                        cfg.boatsEnabled(), cfg::setBoatsEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.jump_gap_enabled",
                        cfg.jumpGapEnabled(), cfg::setJumpGapEnabled));
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.fall_damage_tolerance_enabled",
                        "gui.xaeronav.config.fall_damage_tolerance_enabled.tooltip",
                        cfg.fallDamageToleranceEnabled(), cfg::setFallDamageToleranceEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.flight_routing_enabled",
                        cfg.flightRoutingEnabled(), cfg::setFlightRoutingEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.flight_clearance",
                        cfg.flightClearanceDetourBlocks() > 0, cfg::setFlightClearanceEnabled));
                break;
            case AUTO_WALK:
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.auto_walk_on_servers",
                        "gui.xaeronav.config.auto_walk_on_servers.tooltip",
                        cfg.autoWalkOnServers(), cfg::setAutoWalkOnServers));
                addBig.accept(boolOption("gui.xaeronav.config.auto_walk_sprint",
                        cfg.autoWalkSprint(), cfg::setAutoWalkSprint));
                addBig.accept(stopHealthOption(cfg.autoWalkStopHealth(), cfg::setAutoWalkStopHealth));
                break;
            case DISPLAY:
                addBig.accept(boolOption("gui.xaeronav.config.hud_enabled",
                        cfg.hudEnabled(), cfg::setHudEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.straight_line_enabled",
                        cfg.straightLineEnabled(), cfg::setStraightLineEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.goal_marker_enabled",
                        cfg.goalMarkerEnabled(), cfg::setGoalMarkerEnabled));
                addBig.accept(boolOption("gui.xaeronav.config.danger_dashed_enabled",
                        cfg.dangerDashedEnabled(), cfg::setDangerDashedEnabled));
                break;
            case ADVANCED:
                addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.strict_limits",
                        "gui.xaeronav.config.strict_limits.tooltip", cfg.strictLimits(), cfg::setStrictLimits));
                addBig.accept(boolOption("gui.xaeronav.config.deep_look_ahead_enabled",
                        cfg.deepLookAheadEnabled(), cfg::setDeepLookAheadEnabled));
                break;
            default:
                break;
        }
    }

    /** Cycles through the profiles; the tooltip explains the one currently selected. */
    private static OptionInstance<RouteProfile> routeProfileOption(RouteProfile initial, Consumer<RouteProfile> setter) {
        RouteProfile[] values = RouteProfile.values();
        return new OptionInstance<>("gui.xaeronav.config.route_profile",
                value -> Tooltip.create(Component.translatable(profileTooltipKey(value))),
                (caption, value) -> Component.translatable(profileKey(value)),
                new OptionInstance.Enum<>(List.of(values), Codec.INT.xmap(i -> values[i], RouteProfile::ordinal)),
                initial, setter::accept);
    }

    /** Slider in half-hearts; 0 reads "never". */
    private static OptionInstance<Integer> stopHealthOption(int initial, Consumer<Integer> setter) {
        return new OptionInstance<>("gui.xaeronav.config.auto_walk_stop_health",
                OptionInstance.cachedConstantTooltip(
                        Component.translatable("gui.xaeronav.config.auto_walk_stop_health.tooltip")),
                (caption, value) -> caption.copy().append(": ").append(value == 0
                        ? Component.translatable("gui.xaeronav.config.auto_walk_stop_health.never")
                        : Component.translatable("gui.xaeronav.config.auto_walk_stop_health.value",
                                value % 2 == 0 ? Integer.toString(value / 2) : value / 2 + ".5")),
                new OptionInstance.IntRange(0, 20), initial, setter::accept);
    }

    private static OptionInstance<Boolean> boolOption(String key, boolean initial, Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key, initial, setter::accept);
    }

    /**
     * A short note attached only to items affecting safety or inventory. Attaching it to every item makes them
     * all look equally weighted and get skimmed, so it's limited to items that actually change outcomes.
     */
    private static OptionInstance<Boolean> boolOptionWithTooltip(String key, String tooltipKey, boolean initial,
                                                                  Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                initial, setter::accept);
    }

    /**
     * {@link OptionsSubScreen#onClose} is called from both the Done button and Esc. Inside super,
     * {@code list.applyUnsavedChanges()} runs, and after every item has been {@code set} on {@link XaeroNavConfig},
     * it is written to disk once, all together.
     */
    @Override
    public void onClose() {
        super.onClose();
        XaeroNavConfig.save();
    }

    // Spelled out rather than built from the enum name, so LanguageKeyTest can see every key.
    // A classic switch: the pre-1.19.3 copy of this also compiles for Java 8 (1.16.5)
    private static String profileKey(RouteProfile profile) {
        switch (profile) {
            case FASTEST:
                return "gui.xaeronav.config.route_profile.fastest";
            case SAFEST:
                return "gui.xaeronav.config.route_profile.safest";
            case RESOURCE_SAVING:
                return "gui.xaeronav.config.route_profile.resource_saving";
            default:
                return "gui.xaeronav.config.route_profile.balanced";
        }
    }

    private static String profileTooltipKey(RouteProfile profile) {
        switch (profile) {
            case FASTEST:
                return "gui.xaeronav.config.route_profile.fastest.tooltip";
            case SAFEST:
                return "gui.xaeronav.config.route_profile.safest.tooltip";
            case RESOURCE_SAVING:
                return "gui.xaeronav.config.route_profile.resource_saving.tooltip";
            default:
                return "gui.xaeronav.config.route_profile.balanced.tooltip";
        }
    }
}
//?} else {
/*public final class XaeroNavConfigScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private final Screen parent;
    private final List<Toggle> toggles = new ArrayList<>();
    private int page;

    public XaeroNavConfigScreen(Screen parent) {
        super(TextCompat.translatable("gui.xaeronav.config.title"));
        this.parent = parent;
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;
        RouteProfile[] profiles = RouteProfile.values();
        toggles.add(new Toggle(() -> TextCompat.translatable("gui.xaeronav.config.route_profile").append(": ")
                .append(TextCompat.translatable(profileKey(cfg.routeProfile()))),
                () -> cfg.setRouteProfile(profiles[(cfg.routeProfile().ordinal() + 1) % profiles.length])));
        add("gui.xaeronav.config.digging_enabled", cfg::diggingEnabled, cfg::setDiggingEnabled);
        add("gui.xaeronav.config.bridging_enabled", cfg::bridgingEnabled, cfg::setBridgingEnabled);
        add("gui.xaeronav.config.lava_bridging_enabled", cfg::lavaBridgingEnabled, cfg::setLavaBridgingEnabled);
        add("gui.xaeronav.config.block_budget_enabled", cfg::blockBudgetEnabled, cfg::setBlockBudgetEnabled);
        add("gui.xaeronav.config.jump_gap_enabled", cfg::jumpGapEnabled, cfg::setJumpGapEnabled);
        add("gui.xaeronav.config.swimming_enabled", cfg::swimmingEnabled, cfg::setSwimmingEnabled);
        add("gui.xaeronav.config.boats_enabled", cfg::boatsEnabled, cfg::setBoatsEnabled);
        add("gui.xaeronav.config.fall_damage_tolerance_enabled", cfg::fallDamageToleranceEnabled, cfg::setFallDamageToleranceEnabled);
        add("gui.xaeronav.config.strict_limits", cfg::strictLimits, cfg::setStrictLimits);
        add("gui.xaeronav.config.deep_look_ahead_enabled", cfg::deepLookAheadEnabled, cfg::setDeepLookAheadEnabled);
        add("gui.xaeronav.config.flight_routing_enabled", cfg::flightRoutingEnabled, cfg::setFlightRoutingEnabled);
        add("gui.xaeronav.config.flight_clearance", () -> cfg.flightClearanceDetourBlocks() > 0, cfg::setFlightClearanceEnabled);
        add("gui.xaeronav.config.hud_enabled", cfg::hudEnabled, cfg::setHudEnabled);
        add("gui.xaeronav.config.straight_line_enabled", cfg::straightLineEnabled, cfg::setStraightLineEnabled);
        add("gui.xaeronav.config.goal_marker_enabled", cfg::goalMarkerEnabled, cfg::setGoalMarkerEnabled);
        add("gui.xaeronav.config.danger_dashed_enabled", cfg::dangerDashedEnabled, cfg::setDangerDashedEnabled);
    }

    private void add(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        toggles.add(new Toggle(() -> TextCompat.translatable(key).append(": " + (getter.getAsBoolean() ? "ON" : "OFF")),
                () -> setter.accept(!getter.getAsBoolean())));
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        for (int i = page * PAGE_SIZE; i < Math.min(toggles.size(), (page + 1) * PAGE_SIZE); i++) {
            Toggle toggle = toggles.get(i);
            int y = 38 + (i % PAGE_SIZE) * 25;
            addToggleWidget(new Button(left, y, 300, 20, toggle.label.get(), button -> {
                toggle.click.run();
                button.setMessage(toggle.label.get());
            }));
        }
        if (page > 0) {
            addToggleWidget(new Button(left, height - 52, 95, 20, TextCompat.translatable("gui.back"), button -> changePage(-1)));
        }
        if ((page + 1) * PAGE_SIZE < toggles.size()) {
            addToggleWidget(new Button(left + 205, height - 52, 95, 20, TextCompat.translatable("gui.next"), button -> changePage(1)));
        }
        addToggleWidget(new Button(width / 2 - 100, height - 27, 200, 20,
                TextCompat.translatable("gui.done"), button -> onClose()));
    }

    // addButton was renamed to addRenderableWidget in 1.17
    private void addToggleWidget(Button button) {
        //? if >=1.17 {
        addRenderableWidget(button);
        //?} else {
        /^addButton(button);
        ^///?}
    }

    private void changePage(int delta) {
        page += delta;
        init(minecraft, width, height);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        drawCenteredString(pose, font, title, width / 2, 15, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        XaeroNavConfig.save();
        ClientCompat.setScreen(minecraft, parent);
    }

    // Spelled out rather than built from the enum name, so LanguageKeyTest can see every key.
    // A classic switch: the pre-1.19.3 copy of this also compiles for Java 8 (1.16.5)
    private static String profileKey(RouteProfile profile) {
        switch (profile) {
            case FASTEST:
                return "gui.xaeronav.config.route_profile.fastest";
            case SAFEST:
                return "gui.xaeronav.config.route_profile.safest";
            case RESOURCE_SAVING:
                return "gui.xaeronav.config.route_profile.resource_saving";
            default:
                return "gui.xaeronav.config.route_profile.balanced";
        }
    }

    private static final class Toggle {
        final Supplier<Component> label;
        final Runnable click;

        Toggle(Supplier<Component> label, Runnable click) {
            this.label = label;
            this.click = click;
        }
    }
}
*///?}
