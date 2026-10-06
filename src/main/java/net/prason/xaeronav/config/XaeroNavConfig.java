package net.prason.xaeronav.config;

import java.util.Collections;
import java.util.List;

import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.RouteProfile;
import net.prason.xaeronav.pathfinding.world.MovementOptions;

/**
 * Generated on the client as a TOML config file ({@code config/xaeronav-client.toml}).
 * Also edited from the config GUI screen ({@link net.prason.xaeronav.client.gui.XaeroNavConfigScreen}).
 */
public final class XaeroNavConfig {

    public static final XaeroNavConfig INSTANCE;

    private static final NavConfigStore STORE;

    static {
        NavConfigStore store = createStore();
        INSTANCE = new XaeroNavConfig(store.spec());
        store.build();
        STORE = store;
    }

    private static NavConfigStore createStore() {
        //? neoforge {
        return new ModConfigSpecStore();
        //?} forge {
        /*return new ForgeConfigSpecStore();
        *///?} fabric {
        /*return new NightConfigStore(net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("xaeronav-client.toml"));
        *///?}
    }

    /** Called from places that write out once after a batch of changes, such as the config screen. */
    public static void save() {
        STORE.save();
    }

    /** Because the loader-side registration ({@code ModContainer#registerConfig} on NeoForge) needs the actual storage. */
    public static NavConfigStore store() {
        return STORE;
    }

    private final NavConfigSpec.EnumValue<RouteProfile> routeProfile;
    private final NavConfigSpec.BoolValue swimmingEnabled;
    private final NavConfigSpec.BoolValue boatsEnabled;
    private final NavConfigSpec.BoolValue diggingEnabled;
    private final NavConfigSpec.BoolValue bridgingEnabled;
    private final NavConfigSpec.BoolValue jumpGapEnabled;
    private final NavConfigSpec.BoolValue blockBudgetEnabled;
    private final NavConfigSpec.IntValue blockBudgetReserve;
    private final NavConfigSpec.BoolValue lavaBridgingEnabled;
    private final NavConfigSpec.BoolValue deepLookAheadEnabled;
    private final NavConfigSpec.BoolValue costToGoGuideEnabled;
    private final NavConfigSpec.BoolValue fallDamageToleranceEnabled;
    private final NavConfigSpec.BoolValue avoidRiskyJumps;
    private final NavConfigSpec.BoolValue strictLimits;
    private final NavConfigSpec.IntValue detailHorizonBlocks;
    private final NavConfigSpec.IntValue maxBridgeRunBlocks;
    private final NavConfigSpec.IntValue maxLavaBridgeRunBlocks;
    private final NavConfigSpec.IntValue maxVoidBridgeRunBlocks;
    private final NavConfigSpec.IntValue maxSubmergedTicks;
    private final NavConfigSpec.IntValue searchHorizontalMargin;
    private final NavConfigSpec.IntValue searchVerticalMargin;
    private final NavConfigSpec.DoubleValue deviationThresholdBlocks;
    private final NavConfigSpec.DoubleValue arrivalRadiusBlocks;
    private final NavConfigSpec.IntValue groundLevelY;
    private final NavConfigSpec.IntValue recalcIntervalTicks;
    private final NavConfigSpec.IntValue maxExpandedNodes;
    private final NavConfigSpec.DoubleValue heuristicWeight;
    private final NavConfigSpec.BoolValue flightRoutingEnabled;
    private final NavConfigSpec.IntValue elytraFlyingMinGroundClearanceBlocks;
    private final NavConfigSpec.IntValue flightCellBlocks;
    private final NavConfigSpec.DoubleValue flightDeviationThresholdBlocks;
    private final NavConfigSpec.IntValue flightRecalcIntervalTicks;
    private static final int FLIGHT_CLEARANCE_DETOUR_DEFAULT = 12;

    private final NavConfigSpec.IntValue flightClearanceDetourBlocks;
    /** Keeps the user's tuned non-zero value even while it's temporarily disabled in the GUI. */
    private int lastFlightClearanceDetourBlocks = FLIGHT_CLEARANCE_DETOUR_DEFAULT;
    private final NavConfigSpec.IntValue flightMaxExpandedNodes;
    private final NavConfigSpec.IntValue flightExtendMaxExpandedNodes;
    private final NavConfigSpec.DoubleValue flightHeuristicWeight;
    private final NavConfigSpec.StringListValue diggableBlocks;
    private final NavConfigSpec.StringListValue forbiddenBlocks;
    private final NavConfigSpec.BoolValue hudEnabled;
    private final NavConfigSpec.BoolValue straightLineEnabled;
    private final NavConfigSpec.BoolValue goalMarkerEnabled;
    private final NavConfigSpec.BoolValue dangerDashedEnabled;

    // package-private: tests check that both storage backends produce the same config file from the same definition
    XaeroNavConfig(NavConfigSpec spec) {
        spec.comment("XaeroNav pathfinding settings").push("pathfinding");

        routeProfile = spec
                .comment("What routes are optimised for",
                        "BALANCED: the default trade-off between travel time and risk",
                        "FASTEST: shortest travel time. Jumps, fall damage, edges and drops are weighed half as much,",
                        "so routes cut closer to danger to save time",
                        "SAFEST: avoids risk even at the cost of longer routes. Jumps, fall damage and walking along deadly",
                        "edges are weighed 4x, drops and bridges over lava or the void 2x, and staying underwater a little more.",
                        "Also always avoids risky jumps (avoidRiskyJumps) and never takes fall damage",
                        "(fallDamageToleranceEnabled), whatever those two are set to",
                        "RESOURCE_SAVING: places and breaks as few blocks as possible. Placing a block and digging",
                        "one are weighed 3x, so routes walk further around obstacles instead")
                .defineEnum("routeProfile", RouteProfile.BALANCED);

        diggingEnabled = spec
                .comment("Allow routes to include digging (false searches only for routes reachable on foot)")
                .define("diggingEnabled", true);

        bridgingEnabled = spec
                .comment("Allow routes to include placing blocks to cross gaps and climb cliffs",
                        "Even when true, no placement is suggested when the hotbar has no placeable blocks, or next to water")
                .define("bridgingEnabled", true);

        lavaBridgingEnabled = spec
                .comment("Allow routes to cross lava by placing blocks on it (also requires bridgingEnabled)",
                        "A last resort for when no route avoiding lava exists at all. It carries a very high cost, so",
                        "a detour that avoids lava is chosen whenever one exists, even a long one",
                        "If false, destinations cut off by lava get no route and you are stuck")
                .define("lavaBridgingEnabled", true);

        blockBudgetEnabled = spec
                .comment("Cap the total number of blocks a route may place at the number of blocks in your inventory",
                        "The bridge length limit (maxBridgeRunBlocks) only caps how far a single bridge may run, so",
                        "it cannot stop a route that builds many short bridges. If you run out of blocks partway, the",
                        "guidance past that point cannot be followed and you end up digging anyway",
                        "If true, routes you can finish with the blocks you carry are preferred. Only when no route",
                        "fits does the search retry without the cap, and that route is marked with a shortage warning",
                        "Never counted in creative mode regardless of this setting, since placing blocks does not use them up",
                        "If false, block counts are ignored as before (a single block in your inventory allows bridges of any length)")
                .define("blockBudgetEnabled", true);

        blockBudgetReserve = spec
                .comment("Number of blocks to hold back from the budget above",
                        "If a route uses up exactly what you carry, a single missed placement or side trip leaves you short",
                        "Higher values give routes more margin, but leave fewer situations where a bridge can be built")
                .defineInRange("blockBudgetReserve", 0, 0, 512);

        jumpGapEnabled = spec
                .comment("Allow routes to include jumping over gaps (up to 3 blocks wide)",
                        "If false, gaps that could be jumped are instead crossed by detouring or by placing blocks (bridgingEnabled)",
                        "A missed landing means a fall, so turn this off if you are unsure of your jumps or falling would be dangerous")
                .define("jumpGapEnabled", true);

        swimmingEnabled = spec
                .comment("Allow routes to swim or wade through water",
                        "If false, routes go around water instead (or cross it by boat when boatsEnabled is true and you carry one)",
                        "You can still swim out if you are already in water, and a destination placed in water is still reached")
                .define("swimmingEnabled", true);

        boatsEnabled = spec
                .comment("Allow routes to cross water by boat when you carry one",
                        "Launching and stowing the boat takes time, so it only pays off on longer stretches of water (about 28 blocks or more)",
                        "If false, a boat in your inventory is ignored")
                .define("boatsEnabled", true);

        avoidRiskyJumps = spec
                .comment("Avoid jumps over the bottomless void (the End's abyss) and over drops that would kill you at your",
                        "current health if missed. Gaps with lava below are never jumped, regardless of this setting",
                        "Even when true, this is not \"never jump\": jumping is unlocked to escape a dead end only once",
                        "it is known that no way around exists at all. Within one island, going around the edge is safer,",
                        "but between islands jumping is the only way; this one setting covers both cases",
                        "Sections that require such a jump are shown in a warning color (never unlocked if strictLimits is true)",
                        "This deliberately differs from fallDamageToleranceEnabled. When that one is off, it is not unlocked even",
                        "to escape a dead end (it is a preference against painful falls, so once declined no fallback is needed),",
                        "whereas here the only fallback would be \"no route\"",
                        "If false, routes jump over the void and high gaps normally, as before")
                .define("avoidRiskyJumps", true);

        strictLimits = spec
                .comment("Never loosen the limits on bridge length, time underwater, fall damage, risky jumps and carried blocks,",
                        "even when no route can be found at all",
                        "If false, the limits are loosened only when no route exists within them, and a route with warnings is shown",
                        "(a long bridge, a dive that needs a breath, or a jump over the void is better than being stuck)",
                        "If true, routes beyond the limits are never shown, and the HUD reports that no way exists within the limits")
                .define("strictLimits", false);

        fallDamageToleranceEnabled = spec
                .comment("Allow routes to include descents that deal fall damage",
                        "Tolerated damage is up to 1/3 of your health when the route is computed (at full health, 3 hearts = a 9-block fall)",
                        "With a water bucket, descents that cancel the damage by placing water just before landing (MLG) are also considered",
                        "If false, routes only descend as far as is safe (3 blocks)")
                .define("fallDamageToleranceEnabled", false);

        deepLookAheadEnabled = spec
                .comment("While walking, keep extending the route ahead up to the edge of the loaded chunks",
                        "If true, the route drawn ahead grows longer as you go, with no gaps while waiting for the next section",
                        "If false, only the current section plus the next one are kept (a shorter drawn route, but a lighter search)",
                        "Either way, the part of the route you are already walking on is never redrawn")
                .define("deepLookAheadEnabled", true);

        costToGoGuideEnabled = spec
                .comment("Also guide the detailed search with an estimate of the remaining cost to the destination",
                        "The estimate comes from a navigation graph of the loaded area, built with the same moves as the search,",
                        "and lets the search aim straight at the destination without intermediate waypoints (building it uses spare CPU cores and up to about 270 MB of memory; if the Java heap limit is below 2.5 GB, a smaller area is used, about 150 MB)",
                        "Until the navigation graph is ready, the estimate from layer 1, the 3D coarse layer (coarse map), is used",
                        "If false, neither the navigation graph nor the layer 1 estimate is used, falling back to plain straight-line distance (for comparison, or to reduce load)")
                .define("costToGoGuideEnabled", true);

        detailHorizonBlocks = spec
                .comment("Maximum horizontal distance (blocks) the detailed search targets at once. Destinations farther than this",
                        "get intermediate waypoints from the long-distance route, and the path is extended from its end",
                        "A fixed value independent of terrain. It used to be measured from how far recent searches actually reached,",
                        "but a value measured on explored terrain around the player was also applied to searches extending from the end into unexplored terrain,",
                        "so successes and failures alternated without converging, and each time the target moved and the route was redrawn",
                        "The default 96 matches measurements in the Nether (70-90 blocks with 100,000 nodes). The overworld",
                        "is easier to solve, so raise it if you want fewer searches")
                .defineInRange("detailHorizonBlocks", 96, 24, 512);

        maxBridgeRunBlocks = spec
                .comment("How many consecutive blocks a bridge placed in midair may run before it is abandoned for a detour (0 = unlimited)",
                        "In terrain where detours are long, like the Nether's lava seas, cost weighting alone keeps bridges",
                        "being chosen. Bridges beyond this limit are never generated as moves, so the search only considers",
                        "detours from the start. Unlike holding them back with a heavy cost, this uses no expanded nodes at all",
                        "(the run length resets as soon as you step on a single block of land)",
                        "Only when no detour exists within range and no route can be found at all does the search retry without the limit",
                        "(a long bridge is better than being stuck)",
                        "The default 96 covers the gaps measured between End islands (47-81 blocks of void, measured from saved data).",
                        "Over lava, maxLavaBridgeRunBlocks separately holds bridges to 30, so this has no effect there")
                .defineInRange("maxBridgeRunBlocks", 96, 0, 256);

        maxLavaBridgeRunBlocks = spec
                .comment("Of those, how many blocks a bridge over lava may run (0 = unlimited)",
                        "This is kept separate from bridges over open air because a missed placement ends differently:",
                        "over open air you just fall, but over lava you die instantly",
                        "The bridge run length itself is shared with maxBridgeRunBlocks, so the smaller of the two applies",
                        "The default is 30; lowering it shortens how far routes cross the Nether's lava seas",
                        "(if no crossing remains, layer 1 re-plans a wide detour around the lava)")
                .defineInRange("maxLavaBridgeRunBlocks", 30, 0, 256);

        maxVoidBridgeRunBlocks = spec
                .comment("Of those, how many blocks a bridge over the bottomless void (the End's abyss, or huge caverns deeper than the search range)",
                        "may run (0 = unlimited)",
                        "This is kept separate from lava because the two are met at very different rates: in the End",
                        "nearly every bridge falls under this, so tightening it removes travel between islands entirely",
                        "The bridge run length itself is shared with maxBridgeRunBlocks, so the smaller of the two applies",
                        "If the limit leaves no route, a mechanism loosens it step by step and retries, but that only kicks in",
                        "once it can prove that no route exists within range at all. Between End islands the expanded node",
                        "cap is hit first, so it cannot be relied on. That is why this starts at a value that covers the measured gaps",
                        "Basis for the default 96: the void between End islands, measured from saved data (DIM1), is 47-81 blocks.",
                        "Even at 30, a crossing route is still found once the relaxation ladder opens (RealEndTerrainTest)")
                .defineInRange("maxVoidBridgeRunBlocks", 96, 0, 256);

        maxSubmergedTicks = spec
                .comment("How many ticks a route may keep your head underwater (0 = unlimited)",
                        "Air runs out after 300 ticks, after which you take damage every second. The default 250 is 5/6 of that,",
                        "leaving margin for not starting the dive with full air and for not swimming as fast as the guidance assumes",
                        "The unit is ticks rather than blocks because each kind of underwater movement has its own speed: swimming (5.6),",
                        "rising (7.4), sinking (5.4), mining (dozens per block). Counting blocks would underestimate the time spent rising or mining",
                        "and allow routes you cannot hold your breath for",
                        "This is the physical limit; the preference to \"stay out of the water where possible\" is handled on the cost side",
                        "(SUBMERGED_TRAVEL_PENALTY)",
                        "Only when no route at all can be found without diving does the search retry without the limit",
                        "(a dive that needs a breath is better than being stuck). Such sections are shown in a warning color")
                .defineInRange("maxSubmergedTicks", 250, 0, 1200);

        searchHorizontalMargin = spec
                .comment("Horizontal margin of the search area (blocks)")
                .defineInRange("searchHorizontalMargin", 64, 8, 256);

        searchVerticalMargin = spec
                .comment("Vertical margin of the search area (blocks)")
                .defineInRange("searchVerticalMargin", 32, 4, 128);

        deviationThresholdBlocks = spec
                .comment("Recalculate once the player is this far (blocks) or more from the route",
                        "The route is not redrawn as long as you walk within this distance, so higher values keep the line steadier",
                        "The default tolerates drifting 2-3 blocks to the side of the line")
                .defineInRange("deviationThresholdBlocks", 4.0, 1.0, 16.0);

        arrivalRadiusBlocks = spec
                .comment("Count as arrived once within this distance (blocks) of the destination (both horizontally and vertically)",
                        "For destinations that cannot be reached even by digging, the closest point actually reached is used instead")
                .defineInRange("arrivalRadiusBlocks", 3.0, 1.0, 16.0);

        groundLevelY = spec
                .comment("Places at this height (Y) or above with open sky overhead count as the surface",
                        "When heading from under a roof (where the sky is not visible) to a destination on the surface, instead of digging straight toward the destination,",
                        "the route first finds a way out to the nearest surface (at or above this height, under open sky) and then heads to the destination",
                        "Paths that need no digging are searched first, so caves and mine tunnels are used if there are any",
                        "Places with a view of the sky count as the surface even below this height (riverbeds, valley floors, coasts)",
                        "Inactive in dimensions without sky or with a ceiling (the Nether, the End)",
                        "The default 60 is slightly below sea level")
                .defineInRange("groundLevelY", 60, -64, 320);

        recalcIntervalTicks = spec
                .comment("Route recheck interval (ticks). While the player is not moving, only block changes along the route are checked at this interval")
                .defineInRange("recalcIntervalTicks", 40, 20, 1200);

        maxExpandedNodes = spec
                .comment("Maximum number of nodes expanded per search. This is the ceiling at which a search that cannot reach the goal gives up;",
                        "the search ends as soon as a route is found, so raising it does not change the time taken for routes that do reach",
                        "Searches run on a worker thread, so they do not directly affect the frame rate",
                        "Lowering it makes routes that should reach get cut off short")
                .defineInRange("maxExpandedNodes", AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES, 1_000, 500_000);

        heuristicWeight = spec
                .comment("How strongly the search favors getting closer to the goal",
                        "1.0 guarantees the shortest path, but where the real cost far exceeds the estimate, such as digging or swimming,",
                        "the search spreads out in all directions and the cap runs out a few dozen blocks ahead, so the route never reaches",
                        "Higher values reach farther but may include roundabout routes (raising it helps when crossing oceans or over long distances)")
                .defineInRange("heuristicWeight", AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 1.0, 3.0);

        flightRoutingEnabled = spec
                .comment("Compute an aerial route while gliding or flying",
                        "If false, only a straight (dotted) line to the destination is shown (the old behavior)",
                        "Spectators pass through blocks, so they always get a straight line regardless of this setting")
                .define("flightRoutingEnabled", true);

        elytraFlyingMinGroundClearanceBlocks = spec
                .comment("Minimum height (blocks) from your feet to the ground for an elytra glide to count as flying",
                        "Jumping repeatedly while wearing an elytra can register a glide for a single tick,",
                        "which at that moment drops the ground route for an aerial one, then switches back on the tick after landing,",
                        "so the route is rebuilt from scratch on every bounce. Requiring a height keeps the ground route while bouncing",
                        "In addition to the height, the glide must last 0.5 seconds (a glide registered by a bounce is shorter than that)",
                        "Once counted as flying, you may drop to half this height (otherwise going back and forth across the threshold",
                        "would rebuild the route each time)",
                        "0 ignores the height (only the 0.5-second duration condition remains)",
                        "Creative and spectator flight count as flying immediately regardless of this setting",
                        "(you are genuinely airborne, so there is no point in a grace period)")
                .defineInRange("elytraFlyingMinGroundClearanceBlocks", 4, 0, 32);

        flightCellBlocks = spec
                .comment("Side length (blocks) of the grid cells used to solve the aerial route. Only cells whose blocks are all empty are used",
                        "This coarseness doubles as clearance: there is no point asking an elytra flying 30 blocks per second to thread",
                        "a 1-block gap, so only space you can pass through with room to spare is considered for the route",
                        "Only when it turns out no passable gap exists at this coarseness is the route solved again at half the cell size",
                        "This is the most effective setting for reaching farther: the cell count is inversely proportional to the cube of the side length, so",
                        "going from 4 to 6 alone makes the same budget cover 3.4 times the volume. In exchange, narrow passages become impassable")
                .defineInRange("flightCellBlocks", 6, 2, 16);

        flightDeviationThresholdBlocks = spec
                .comment("Redraw the route once you are this far (blocks) or more from it while gliding",
                        "Kept separate from deviationThresholdBlocks for walking. An elytra drifts constantly, so",
                        "using the same width as walking would redraw the route the whole time you fly",
                        "Vertically, up to 1.5 times this is tolerated (vertical wobble is larger than horizontal)")
                .defineInRange("flightDeviationThresholdBlocks", 24.0, 4.0, 64.0);

        flightRecalcIntervalTicks = spec
                .comment("Interval (ticks) at which the route is redrawn while gliding",
                        "An elytra flies at 1.5 blocks/tick, so with the walking recalcIntervalTicks (40)",
                        "you would travel 60 blocks between redraws")
                .defineInRange("flightRecalcIntervalTicks", 20, 5, 200);

        flightClearanceDetourBlocks = spec
                .comment("How many blocks of horizontal detour passing through a fully enclosed cell is weighed against",
                        "This expresses \"do not guide me through tight spots, even if shorter\". Higher values prefer more open space",
                        "It is a surcharge rather than a ban so that routes do not vanish entirely in terrain where that is the only way",
                        "0 disables it (purely shortest)",
                        "Having one plane blocked (9 of the 26 neighbors) does not count as tight:",
                        "that is just flying with room to spare over the ground or over a ceiling, and counting it as tight",
                        "would make routes fly high for no reason even in open areas")
                .defineInRange("flightClearanceDetourBlocks", FLIGHT_CLEARANCE_DETOUR_DEFAULT, 0, 128);

        flightMaxExpandedNodes = spec
                .comment("Maximum number of cells expanded per aerial route search",
                        "Kept separate from maxExpandedNodes for walking. The air uses a 3D grid with 26 neighbors per cell,",
                        "so the same number means a very different search extent",
                        "Raising it reaches farther, but each computation takes proportionally longer (measured: 100,000 in the Nether takes about 2 seconds)",
                        "No new search is issued while one is running, so longer computations mean longer intervals between route updates")
                .defineInRange("flightMaxExpandedNodes", 150_000, 1_000, 1_000_000);

        flightExtendMaxExpandedNodes = spec
                .comment("Maximum number of cells expanded when extending the route further from its end",
                        "Extensions join many short sections, so allowing flightMaxExpandedNodes for each one",
                        "would spend 2 seconds every time in cluttered terrain for little progress, falling behind your flight speed",
                        "Smaller values extend less per step, but extend more often")
                .defineInRange("flightExtendMaxExpandedNodes", 60_000, 1_000, 1_000_000);

        flightHeuristicWeight = spec
                .comment("How strongly the aerial route search favors getting closer to the goal",
                        "Higher values make the search faster but may include detours. 1.0 is shortest",
                        "The search stops at the edge of the loaded area, so raising this does not make it reach farther")
                .defineInRange("flightHeuristicWeight", 1.5, 1.0, 5.0);

        diggableBlocks = spec
                .comment("Additional blocks that may be dug through (e.g. \"minecraft:cobblestone\")",
                        "By default, only naturally generated terrain can be dug (stone, dirt, sand, ores, leaves, netherrack, etc.);",
                        "processed blocks (cobblestone, stone bricks, planks...) and blocks with contents (chests, furnaces,",
                        "modded machines) are assumed to be placed by someone and are not dug. Unknown blocks are not dug either",
                        "If modded stone or dirt blocks a route, add its block ID here")
                .defineStringList("additionalDiggableBlocks", Collections.emptyList(),
                        () -> "minecraft:cobblestone", o -> o instanceof String);

        forbiddenBlocks = spec
                .comment("Blocks that must never be dug (e.g. \"minecraft:diamond_ore\"). Takes priority over the list above",
                        "Use it to exclude individual naturally generated terrain blocks you do not want broken")
                .defineStringList("additionalForbiddenBlocks", Collections.emptyList(),
                        () -> "minecraft:diamond_ore", o -> o instanceof String);

        spec.pop();
        spec.comment("XaeroNav display settings").push("display");

        hudEnabled = spec
                .comment("Show guidance at the top of the screen (next turn, remaining distance, estimated time)")
                .define("hudEnabled", true);

        straightLineEnabled = spec
                .comment("Show a dotted line to the destination for sections with no known route (such as beyond unloaded chunks)")
                .define("straightLineEnabled", true);

        goalMarkerEnabled = spec
                .comment("Place a pin on the destination in Xaero's world map and minimap")
                .define("goalMarkerEnabled", true);

        dangerDashedEnabled = spec
                .comment("Draw dangerous sections (lava, void, drowning, fall damage, etc.) as dashed lines",
                        "A cue besides color, for cases where color alone is hard to tell apart due to color vision differences or screen color adjustments")
                .define("dangerDashedEnabled", true);

        spec.pop();
    }

    public RouteProfile routeProfile() {
        return routeProfile.get();
    }

    public void setRouteProfile(RouteProfile value) {
        routeProfile.set(value);
    }

    public boolean swimmingEnabled() {
        return swimmingEnabled.get();
    }

    public void setSwimmingEnabled(boolean value) {
        swimmingEnabled.set(value);
    }

    public boolean boatsEnabled() {
        return boatsEnabled.get();
    }

    public void setBoatsEnabled(boolean value) {
        boatsEnabled.set(value);
    }

    public boolean diggingEnabled() {
        return diggingEnabled.get();
    }

    public void setDiggingEnabled(boolean value) {
        diggingEnabled.set(value);
    }

    public boolean bridgingEnabled() {
        return bridgingEnabled.get();
    }

    public void setBridgingEnabled(boolean value) {
        bridgingEnabled.set(value);
    }

    public int detailHorizonBlocks() {
        return detailHorizonBlocks.get();
    }

    public int maxBridgeRunBlocks() {
        return maxBridgeRunBlocks.get();
    }

    public int maxLavaBridgeRunBlocks() {
        return maxLavaBridgeRunBlocks.get();
    }

    public int maxVoidBridgeRunBlocks() {
        return maxVoidBridgeRunBlocks.get();
    }

    public int maxSubmergedTicks() {
        return maxSubmergedTicks.get();
    }

    public boolean lavaBridgingEnabled() {
        return lavaBridgingEnabled.get();
    }

    public void setLavaBridgingEnabled(boolean value) {
        lavaBridgingEnabled.set(value);
    }

    public boolean deepLookAheadEnabled() {
        return deepLookAheadEnabled.get();
    }

    public void setDeepLookAheadEnabled(boolean value) {
        deepLookAheadEnabled.set(value);
    }

    public boolean costToGoGuideEnabled() {
        return costToGoGuideEnabled.get();
    }

    public void setCostToGoGuideEnabled(boolean value) {
        costToGoGuideEnabled.set(value);
    }

    public boolean jumpGapEnabled() {
        return jumpGapEnabled.get();
    }

    public void setJumpGapEnabled(boolean value) {
        jumpGapEnabled.set(value);
    }

    public boolean fallDamageToleranceEnabled() {
        return fallDamageToleranceEnabled.get();
    }

    public boolean avoidRiskyJumps() {
        return avoidRiskyJumps.get();
    }

    public void setAvoidRiskyJumps(boolean value) {
        avoidRiskyJumps.set(value);
    }

    public boolean strictLimits() {
        return strictLimits.get();
    }

    public void setStrictLimits(boolean value) {
        strictLimits.set(value);
    }

    public void setFallDamageToleranceEnabled(boolean value) {
        fallDamageToleranceEnabled.set(value);
    }

    public int searchHorizontalMargin() {
        return searchHorizontalMargin.get();
    }

    public int searchVerticalMargin() {
        return searchVerticalMargin.get();
    }

    public double deviationThresholdBlocks() {
        return deviationThresholdBlocks.get();
    }

    public double arrivalRadiusBlocks() {
        return arrivalRadiusBlocks.get();
    }

    public int groundLevelY() {
        return groundLevelY.get();
    }

    public int recalcIntervalTicks() {
        return recalcIntervalTicks.get();
    }

    public int maxExpandedNodes() {
        return maxExpandedNodes.get();
    }

    public double heuristicWeight() {
        return heuristicWeight.get();
    }

    public boolean flightRoutingEnabled() {
        return flightRoutingEnabled.get();
    }

    public void setFlightRoutingEnabled(boolean value) {
        flightRoutingEnabled.set(value);
    }

    public int elytraFlyingMinGroundClearanceBlocks() {
        return elytraFlyingMinGroundClearanceBlocks.get();
    }

    public int flightCellBlocks() {
        return flightCellBlocks.get();
    }

    public double flightDeviationThresholdBlocks() {
        return flightDeviationThresholdBlocks.get();
    }

    public int flightRecalcIntervalTicks() {
        return flightRecalcIntervalTicks.get();
    }

    public int flightClearanceDetourBlocks() {
        return flightClearanceDetourBlocks.get();
    }

    /** For config screen toggles. Keeps and restores the tuned value from before disabling. */
    public void setFlightClearanceEnabled(boolean value) {
        int current = flightClearanceDetourBlocks.get();
        if (!value) {
            if (current > 0) {
                lastFlightClearanceDetourBlocks = current;
            }
            flightClearanceDetourBlocks.set(0);
        } else if (current == 0) {
            flightClearanceDetourBlocks.set(lastFlightClearanceDetourBlocks);
        }
    }

    public int flightMaxExpandedNodes() {
        return flightMaxExpandedNodes.get();
    }

    public int flightExtendMaxExpandedNodes() {
        return flightExtendMaxExpandedNodes.get();
    }

    public double flightHeuristicWeight() {
        return flightHeuristicWeight.get();
    }

    public List<? extends String> additionalDiggableBlocks() {
        return diggableBlocks.get();
    }

    public List<? extends String> additionalForbiddenBlocks() {
        return forbiddenBlocks.get();
    }

    /**
     * The full set of "what is allowed" passed to the search. Reading each item and assembling it at the call site would mean
     * copying the same sequence every time another place submits a search.
     */
    public MovementOptions movementOptions() {
        return new MovementOptions(diggingEnabled(), bridgingEnabled(), jumpGapEnabled(), lavaBridgingEnabled(),
                maxBridgeRunBlocks(), maxLavaBridgeRunBlocks(), maxVoidBridgeRunBlocks(), maxSubmergedTicks(),
                fallDamageToleranceEnabled(), avoidRiskyJumps(), blockBudgetEnabled(), blockBudgetReserve(),
                strictLimits(), routeProfile(), swimmingEnabled(), boatsEnabled());
    }

    public boolean blockBudgetEnabled() {
        return blockBudgetEnabled.get();
    }

    public void setBlockBudgetEnabled(boolean value) {
        blockBudgetEnabled.set(value);
    }

    public int blockBudgetReserve() {
        return blockBudgetReserve.get();
    }

    /** Termination conditions for walking searches. Only the time limit isn't exposed in the config. */
    public SearchLimits searchLimits() {
        return new SearchLimits(maxExpandedNodes(), AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, heuristicWeight());
    }

    /**
     * The full set of values read together right before a search is submitted. Bundled for the same reason as {@link #movementOptions} and {@link #searchLimits}
     * (otherwise the same sequence gets copied every time a call site is added).
     */
    public NavigationTuning navigationTuning() {
        return new NavigationTuning(searchHorizontalMargin(), movementOptions(), searchLimits(),
                costToGoGuideEnabled());
    }

    public boolean hudEnabled() {
        return hudEnabled.get();
    }

    /**
     * Doesn't save here (it's called from both the GUI and keybindings, and the GUI wants to save multiple items at once).
     * The caller is responsible for saving {@link #SPEC}.
     */
    public void setHudEnabled(boolean value) {
        hudEnabled.set(value);
    }

    public boolean straightLineEnabled() {
        return straightLineEnabled.get();
    }

    public void setStraightLineEnabled(boolean value) {
        straightLineEnabled.set(value);
    }

    public boolean goalMarkerEnabled() {
        return goalMarkerEnabled.get();
    }

    public void setGoalMarkerEnabled(boolean value) {
        goalMarkerEnabled.set(value);
    }

    public boolean dangerDashedEnabled() {
        return dangerDashedEnabled.get();
    }

    public void setDangerDashedEnabled(boolean value) {
        dangerDashedEnabled.set(value);
    }
}
