# How the supported targets (MC version × loader) are built

XaeroNav builds one jar per supported loader and version from a single source tree.
This document covers how that works and where to make changes when adding targets.

For the design invariants, see [ADR-003: Loader, Xaero hook, and distribution contracts](architecture/003-platform-integration.md).
This document covers the concrete steps for adding and maintaining each node.

## Current targets

| Node | Minecraft | Loader |
|---|---|---|
| `26.3-fabric` | 26.3 | Fabric Loader 0.19.0+ / Fabric API 0.161.0+ |
| `26.3-forge` | 26.3 | Forge 66.0.9+ |
| `26.2-fabric` | 26.2 | Fabric Loader 0.19.0+ / Fabric API 0.161.0+ |
| `26.2-forge` | 26.2 | Forge 65.1.3+ |
| `26.2-neoforge` | 26.2 | NeoForge 26.2.0.88+ |
| `26.1.2-fabric` | 26.1.2 (also 26.1, 26.1.1) | Fabric Loader 0.19.0+ / Fabric API 0.155.3+ |
| `26.1.2-forge` | 26.1.2 | Forge 64.1.3+ |
| `26.1.2-neoforge` | 26.1.2 | NeoForge 26.1.2.112+ |
| `1.21.11-fabric` | 1.21.11 | Fabric Loader 0.17.3+ / Fabric API 0.141.6+ |
| `1.21.11-forge` | 1.21.11 | Forge 61.2.1+ |
| `1.21.11-neoforge` | 1.21.11 | NeoForge 21.11.45+ |
| `1.21.10-fabric` | 1.21.10 (also 1.21.9) | Fabric Loader 0.17.0+ / Fabric API 0.138.4+ |
| `1.21.10-forge` | 1.21.10 | Forge 60.1.15+ |
| `1.21.10-neoforge` | 1.21.10 | NeoForge 21.10.64+ |
| `1.21.8-fabric` | 1.21.8 (also 1.21.6, 1.21.7) | Fabric Loader 0.16.13+ / Fabric API 0.136.1+ |
| `1.21.8-forge` | 1.21.8 | Forge 58.1.22+ |
| `1.21.8-neoforge` | 1.21.8 | NeoForge 21.8.54+ |
| `1.21.5-fabric` | 1.21.5 | Fabric Loader 0.16.10+ / Fabric API 0.128.2+ |
| `1.21.5-forge` | 1.21.5 | Forge 55.0.24+ |
| `1.21.5-neoforge` | 1.21.5 | NeoForge 21.5+ |
| `1.21.4-fabric` | 1.21.4 | Fabric Loader 0.16.9+ / Fabric API 0.119.4+ |
| `1.21.4-forge` | 1.21.4 | Forge 54.1.5+ |
| `1.21.4-neoforge` | 1.21.4 | NeoForge 21.4+ |
| `1.21.3-fabric` | 1.21.3 | Fabric Loader 0.15.11+ / Fabric API 0.114.1+ |
| `1.21.3-forge` | 1.21.3 | Forge 53.1.2+ |
| `1.21.3-neoforge` | 1.21.3 | NeoForge 21.3+ |
| `1.21.1-neoforge` | 1.21.1 (also 1.21) | NeoForge 21.0+ |
| `1.21.1-fabric` | 1.21.1 (also 1.21) | Fabric Loader 0.15.11+ / Fabric API 0.102.0+ |
| `1.21.1-forge` | 1.21.1 | Forge 52.1.2+ |
| `1.20.6-fabric` | 1.20.6 (also 1.20.5) | Fabric Loader 0.15.11+ / Fabric API 0.97.8+ |
| `1.20.6-forge` | 1.20.6 | Forge 50.2.1+ |
| `1.20.6-neoforge` | 1.20.6 | NeoForge 20.6+ |
| `1.20.4-neoforge` | 1.20.4 | NeoForge 20.4+ |
| `1.20.4-fabric` | 1.20.4 (also 1.20.3) | Fabric Loader 0.15.11+ / Fabric API 0.91.1+ |
| `1.20.4-forge` | 1.20.4 | Forge 49.1.10+ |
| `1.20.2-fabric` | 1.20.2 | Fabric Loader 0.15.11+ / Fabric API 0.91.6+ |
| `1.20.2-forge` | 1.20.2 | Forge 48+ |
| `1.20.1-fabric` | 1.20.1 (also 1.20) | Fabric Loader 0.15.11+ / Fabric API |
| `1.20.1-forge` | 1.20.1 (also 1.20) | Forge 46+ |
| `1.19.2-fabric` | 1.19.2 | Fabric Loader 0.15.11+ / Fabric API 0.77.0+ |
| `1.19.2-forge` | 1.19.2 | Forge 43+ |
| `1.18.2-fabric` | 1.18.2 | Fabric Loader 0.15.11+ / Fabric API 0.77.0+ |
| `1.18.2-forge` | 1.18.2 | Forge 40+ |
| `1.16.5-fabric` | 1.16.5 | Fabric Loader 0.15.11+ / Fabric API 0.42.0+ (Java 8) |
| `1.16.5-forge` | 1.16.5 | Forge 36.2.39+ (Java 8) |

**There is no NeoForge node for 1.20.1** (intentionally). NeoForge at that point was jar-level compatible
with Forge (NeoForge itself recommended using Forge on 1.20.1), and Xaero doesn't ship a 1.20.1 build
for "neoforge" either (only from 1.20.4 on). Users running NeoForge on 1.20.1 use the `1.20.1-forge` jar.

Node names are `<MC version>-<loader>`. The split is done with [Stonecutter](https://stonecutter.kikugie.dev/)
(Architectury is not used).

## File roles

| File | Contents |
|---|---|
| `settings.gradle.kts` | The node list. **Adding a node is one line here** |
| `stonecutter.properties.toml` | Per-node dependency versions. **Adding a node adds one table here** |
| `stonecutter.gradle.kts` | Entry points shared by all nodes (`buildAll` / `collectJars` / `printNodes`) and spotless |
| `build.neoforge.gradle.kts` / `build.fabric.gradle.kts` / `build.forge.gradle.kts` | Per-loader builds. Only grows when a loader is added |
| `buildSrc/src/main/kotlin/xaeronav.common.gradle.kts` | Build settings shared by all nodes (Java toolchain, tests, jar name, mixin registration via Fletching Table). The Java version branches on the MC version (17 below 1.20.5, 21 from then on) |
| `src/main/java/net/prason/xaeronav/platform/` | Per-loader startup and event wiring |
| `src/main/resources/xaeronav.accesswidener` | Fabric only. Some nested classes in Mojang's official mappings (`RenderType.CompositeState` etc.) declare different access in their own class file than in the InnerClasses attribute, so referencing them from outside requires widening (the Fabric counterpart of NeoForge/Forge's `accesstransformer.cfg`) |
| `build.forge-legacy.gradle.kts` | Forge nodes for 1.18.2, 1.19.2 and 1.20.1. A different toolchain from 1.21.1-forge (`net.neoforged.moddev.legacyforge`, not ForgeGradle) |
| `build.forge-116.gradle.kts` | Dedicated to the 1.16.5 Forge node. Architectury Loom (the only option that handles 1.16.5 Forge with official mappings) |
| `buildSrc/src/main/kotlin/ForgeCoremodNames.kt` | For 1.16.5-forge dev runs. Rewrites the SRG names in Xaero's coremods to the dev-environment names |

`gradle.properties` only holds the mod's own metadata (id, name, version).
`stonecutter.properties.toml` is the single source of truth for the Minecraft / loader / Xaero versions,
and they flow from there into `neoforge.mods.toml` / `fabric.mod.json` / `mods.toml` (Forge).

Xaero alone has two versions. `deps.xaero_worldmap` / `deps.xaero_minimap` are the versions used for compilation and the dev client;
`deps.xaero_worldmap_min` / `deps.xaero_minimap_min` are the "works from" lower bounds written into the mod metadata. NeoForge / Forge
enforce lower bounds even on optional dependencies and refuse to launch the game with an older Xaero installed. Bumping the
compile version to the latest does not move the lower bound.
When lowering the lower bound, build against that version and confirm that the references to Xaero (method and field types as
seen with `javap`, and the refmap) match a build against the current version.

## Generating the mixin list

The Java annotation processor of [Fletching Table](https://stonecutter.kikugie.dev/wiki/fletching-table)
detects `@Mixin` classes when each node compiles and registers them automatically in the `client` list of
`xaeronav-xaero.mixins.json`. This JSON is also the template for the generated file; everything other than
the class list, such as `required`, `minVersion`, `package`, `refmap` and `injectors`, is still managed here.
Leave the template's `client` empty and don't add class names by hand. Additions, renames and removals are
reflected in the generated output from the `@Mixin` annotations on the Java side.

Generation runs before `processResources`, so the per-version expansion of `${mixin_compatibility_level}` is preserved.
Forge-specific refmap generation and `MixinConfigs` registration in the MANIFEST are outside Fletching Table's scope,
so don't remove the settings in `build.forge.gradle.kts` / `build.forge-legacy.gradle.kts`.

## Adding a node

1. Add one line to `match(...)` in `settings.gradle.kts` (e.g. `match("1.21.5", "neoforge", "fabric")`)
2. Add a `[<loader>."<MC version>"]` table to `stonecutter.properties.toml`
   (verify that each dependency version actually exists before writing it down; don't guess)
3. Run `./gradlew build` to compile all nodes

**If you're only adding one more loader to an existing MC version, that's all** (as when 1.21.1-forge was added).
**Adding a new MC version needs more** (found when adding 1.20.1-fabric; see "Where versions differ" below for details):

- Whether the Java toolchain branch in `buildSrc/.../xaeronav.common.gradle.kts` needs a new boundary
  (Java 17 below MC 1.20.5, Java 21 from then on; if it grows by two or more versions, rethink how the branch is written)
- The `pack.mcmeta` format (`packFormatFor` in `buildSrc`. The build stops for unregistered versions. Add it from `pack_version` in the client jar's `version.json`)
- `compatibilityLevel` in `xaeronav-xaero.mixins.json` (same as above, the `mixinCompatibilityLevel` variable)
- The `java` dependency in `fabric.mod.json` (Fabric only, the `java_version` variable)

**Forge nodes register the mixin config through a different path.** Only NeoForge reads `[[mixins]]` from mods.toml;
on Forge, regardless of version, Mixin itself only looks at `MixinConfigs` in the jar's MANIFEST. If it's missing, the
Xaero integration silently applies nothing in production (and doesn't crash either, since `required=false`). After building,
check `MixinConfigs` with `unzip -p <jar> META-INF/MANIFEST.MF`.

- Distribution jar: `manifest.attributes("MixinConfigs" to ...)` on the `jar` task (carried over into the jarJar output)
- Dev runs: the mod is loaded from class directories, so there is no MANIFEST. ForgeGradle passes it via `args("--mixin.config", ...)`,
  ModDevGradle legacyforge via `mixin { config(...) }`
- Forge 1.20.x and earlier, which run on SRG names in production, also need a refmap (`mixin { add(sourceSets["main"], ...) }`).
  1.21.1-forge, which runs on official mappings, doesn't

CI builds the node list from `printNodes`, so the workflows don't need to be edited
(the decision to narrow the `runtime` job to the canonical node on PRs is based on file paths, so a PR that touches any of
`stonecutter.properties.toml`, `settings.gradle.kts` or `mixin/` automatically expands to all nodes).

## Where versions differ

The version differences actually hit when adding `1.20.1-fabric` (1.20.1 ⇔ 1.21.1). Even before the full
`RenderPipeline` / `GpuBuffer` rework in 1.21.5, there are this many differences.

- **GUI screen base class** (`client/gui/XaeroNavConfigScreen`). 1.21.1's `OptionsSubScreen` lives in the
  `net.minecraft.client.gui.screens.options` package and has an `addOptions()` hook, but the 1.20.1 class of the
  same name sits directly under `net.minecraft.client.gui.screens`, has no `addOptions()`, and requires writing
  `init()` yourself (including creating the `OptionsList` and placing the Done button)
- **Vertex buffer API** (`vertex`/`line` in `client/PathRenderer`). 1.21.1 has the new API starting from
  `addVertex(pose,x,y,z)` (no endVertex needed); 1.20.1 has the old API that starts from `vertex(x,y,z)`, chains
  `color`/`normal`, and finalizes with `endVertex()`
- **Access to `RenderType.CompositeState` / `RenderStateShard.LineStateShard`**
  (`client/NavRenderTypes`). In both versions the declaration in the class's own file is `public`, but the declaration
  in the InnerClasses attribute of the outer class (`RenderType`/`RenderStateShard`) is `protected`;
  javac resolves against the latter, so this needs access widening rather than a gate. What NeoForge/Forge
  widen in `accesstransformer.cfg` becomes `xaeronav.accesswidener` on Fabric
  (`accessible class ...`)
- The Xaero internals touched by `mixin/xaero/` (`CustomRenderTypes` / `MapRenderHelper` / the ordinal of
  `endBatch()` in `GuiMap#render`). These **change with Xaero updates, not Minecraft updates**

### Don't gate on JDK version differences

1.20.1 requires Java 17 (1.21.1 uses Java 21). Standard library APIs added in JDK 21, such as `Math.clamp` and
`List#getLast()`, are not branched with `//?`; instead **they are replaced with our own implementation so both versions
share the same code** (`util/MathSupport`, `list.get(list.size() - 1)` in test code, etc.). Version gates are only for
API differences in Minecraft itself.

## 1.18.2 and 1.19.2

`>=1.17` gates actually split on the version where each individual API was introduced, not on "after 1.16.5". When adding
1.18.2 and 1.19.2, the boundaries were moved to the real versions (without changing what evaluates true on the 1.16.5 and 1.20.1 sides).

| Boundary | What it gates |
|---|---|
| 1.19 | `Component.translatable/literal` (`TextCompat`), Forge 41+'s `RegisterKeyMappingsEvent`, `ConfigScreenHandler`, `RegisterGuiOverlaysEvent`, `ClientPlayerNetworkEvent.LoggingIn/Out`, `EnchantmentHelper.getTagEnchantmentLevel`, Fabric API client commands v2 |
| 1.19.3 | `OptionInstance` (before that, the config screen is the same custom `Screen` as 1.16.5), `BuiltInRegistries`, `org.joml`, `SoundEvents` becoming Holders |
| 1.19.4 | `BlockPos.containing` |
| 1.20 | `GuiGraphics`, `RenderType.debugQuads` and `RenderType.create` becoming public, `BlockState.canBeReplaced()`, `DoorBlock.type()`, `CommandSourceStack.sendSuccess(Supplier, boolean)`, `BlockPosArgument.getBlockPos`, the ordinal of Xaero's `endBatch()` |

- **The ordinal of Xaero's `endBatch()` is 1 on 1.18.2 and 1.19.2** (0 on 1.20+). At the start of `GuiMap#render` and
  `renderChunksToFBO` there is one extra call that flushes leftovers from the previous frame (same as 1.16.5). Verified by
  comparing the bytecode of Xaero's jars for 1.18.2, 1.19.2 and 1.20.1. With ordinal 0 there is no exception, but it draws into
  a different buffer and the route doesn't show on the map.
- **`RenderType.create` (7 arguments) is private before 1.20**. Adding it to the shared `xaeronav.accesswidener` would make the
  AW fail to apply on 1.16.5 and 1.21.x, where the method has a different shape, so for the 1.18 and 1.19 Fabric nodes only,
  `build.fabric.gradle.kts` generates an AW with the extra widening and puts it in the jar (on Forge the AT already widens
  `RenderType *`). The `RenderStateShard` constants aren't widened by Fabric API before 1.20 either, so they are read from an
  inner class of `NavRenderTypes` (a subclass of `RenderStateShard`).
- 1.18.2 has no Fabric API client commands v2 (v1's `ClientCommandManager.DISPATCHER`), and Forge 40 lacks
  `RegisterKeyMappingsEvent`, `ConfigScreenHandler`, `RegisterGuiOverlaysEvent` and `ClientPlayerNetworkEvent.LoggingIn`
  (`ClientRegistry`, `ConfigGuiHandler`, `RenderGameOverlayEvent.Post` and `LoggedInEvent` are used instead).
- 1.18.2 lacks the cave replacement tags added in 1.19 (`*_carver_replaceables`), `#sculk_replaceable`, `SCULK` and
  `MANGROVE_ROOTS`, so `DiggableBlocks` approximates the same set with tags for stone, dirt, sand, terracotta, nylium etc. plus explicitly listed blocks.

## 1.20.4

Has all three loaders: Fabric, Forge and NeoForge. The game-side API is close to 1.20.1, but the build and loader APIs differ as follows.

- From 1.20.3, the `OptionsList` constructor no longer takes a row-height argument. The config screen branches on this boundary
- From Forge 49.1.10, client tick is `ClientTickEvent.Post`. Before that (including Forge 48 on 1.20.2) it checks `phase == END`
- Forge 1.20.4 builds with FG7, but production still runs on SRG names. The jar, after MixinExtras is jar-in-jarred, is
  converted to SRG with Renamer Gradle and only that output is distributed. Xaero's `GuiMap#keyPressed` is also targeted by its SRG name
  - The map for the conversion task (`renameJarJar`) is left to Renamer's default (`renamer.mappings`). Adding one by hand results in two files and is rejected
  - The refmap that resolves injection-target strings to SRG ends up empty for `@WrapOperation` unless mixinextras-common is also on the
    annotation processor path. Its name also matches `"refmap"` in the mixin config (`xaeronav.refmap.json`). `verifyDistribution` checks its contents
  - For dev runs, a file that maps the SRG names in Xaero's bundled refmap to named is passed in the `srg` format that Mixin 0.8.5 can read
    (the `tsrg` that Renamer passes is silently ignored)
- NeoForge 20.4 uses an older API than 21.x. Config registration is `ModLoadingContext`, the config screen is
  `ConfigScreenHandler.ConfigScreenFactory`, client tick is the old `TickEvent`, and `ModConfigSpec#defineListAllowEmpty` takes 3 arguments
- NeoForge 20.4's FML only reads the mod definition from `META-INF/mods.toml` (`neoforge.mods.toml` is from 20.5).
  It goes into the jar under the name `mods.toml`. With the wrong name the whole mod isn't loaded (mixin `[[mixins]]` still works on 20.4)
- The resource pack format in `pack.mcmeta` is 22

## 1.21.4

Has all three loaders: Fabric, Forge and NeoForge. It sits between 1.21.1 and 1.21.5, and some of the APIs gated on `>=1.21.5`
actually changed in 1.21.2, so those boundaries were moved to 1.21.2 (without changing what evaluates true on 1.21.1, 1.21.5 and 1.21.11).

| Boundary | What it gates |
|---|---|
| 1.21.2 | `LevelHeightAccessor` heights (`getMinY`, `getMaxY`, `getMinSectionY`), `RegistryAccess#lookupOrThrow` and how Holders are obtained (efficiency enchantment), `Registry#getValue` (`Registry#get` returns an Optional), how Fabric's `CommandSourceStack` is built (`LocalPlayer#createCommandSourceStack` was removed), Forge's world-rendering entry point |
| 1.21.5 | `RenderPipeline` for rendering, the lambda arguments of Forge's `LevelRenderer` |

- **Forge 54 has no `RenderLevelStageEvent`**, because rendering became a frame graph in 1.21.2. As on 1.21.5, `ForgeLevelRendererMixin`
  injects at the end of the main-pass lambda in `LevelRenderer`. The lambda's (`lambda$addMainPass$1`) arguments differ between 1.21.4 and 1.21.5,
  so the signature is split by version. On 1.21.4 RenderSystem applies modelView at draw time, so the `PoseStack` passed in stays identity
  (1.21.5 passes it with modelView pushed). Pushing it would rotate twice and send the lines off screen. NeoForge 21.4 does have `RenderLevelStageEvent`.
- Rendering uses the same `RenderSystem` path as 1.21.1 (`RenderPipeline` starts at 1.21.5).
- NeoForge 21.4's FML reads `META-INF/neoforge.mods.toml` (only 20.4 is limited to `mods.toml`).
- The resource pack format in `pack.mcmeta` is 46.
- Xaero (World Map, Minimap) has dedicated 1.21.4 jars for all three loaders. The `endBatch()` ordinals in `GuiMap#render` and
  `MinimapFBORenderer#renderChunksToFBO` and the `@Local` variable names are the same as 1.21.1 (verified in bytecode).

## Using one jar across multiple Minecraft versions

Versions that are patch releases of one another don't get extra nodes; instead the existing jar's supported range is extended downward.

| jar | Lower versions |
|---|---|
| `26.1.2-fabric` | 26.1, 26.1.1 |
| `1.21.10-fabric` | 1.21.9 |
| `1.21.8-fabric` | 1.21.6, 1.21.7 |
| `1.21.1-neoforge`, `1.21.1-fabric` | 1.21 |
| `1.20.6-fabric` | 1.20.5 |
| `1.20.4-fabric` | 1.20.3 |
| `1.20.1-fabric`, `1.20.1-forge` | 1.20 |

The mapping lives in `minecraftCompatFor` (`buildSrc/src/main/kotlin/XaeroNavBuild.kt`); its values are the lower versions in ascending order.
It determines both the Minecraft range in the mod metadata (`minecraft_range_fabric` / `minecraft_range_maven`) and
the supported versions attached on Modrinth and CurseForge.

- **Xaero for the lower versions can be from an older line.** Xaero stopped updating some versions (1.21.6, 1.21.7 and 1.21.9 stay at World Map 1.39.x /
  Minimap 25.2.x; 1.21 and 1.20.3 go up to Minimap 25.3.2), but XaeroNav's injection targets (`GuiMap` fields, `render`, `keyPressed`,
  `getRightClickOptions`, the number of `endBatch()` calls, `@Local` variable names, `renderChunksToFBO`) were the same as the current version.
  The only difference from older Xaero that actually mattered was the return type of `RightClickOption#getDisplayName()` (`String` in older versions);
  `RightClickOptionAccessor` reads the `String` field `name` on all versions.
- **What can't be added:** versions with no Xaero (1.21.2), versions with no stable loader release (NeoForge 1.20.3, 1.20.5,
  1.21.6, 1.21.7, 26.1, 26.1.1), loader versions missing an API XaeroNav uses (Forge 51 on 1.21, Forge 49.0.x on 1.20.3 and
  Forge 57 on 1.21.7 lack a hook for the HUD, rendering or tick), and loader versions Xaero's jar rejects (Forge 1.21.6, 26.1, 26.1.1).
- The loader-side lower bounds (`neoforge_range` / `forge_loader_range` / `fabric_api_range`) are lowered to the version that works on the lower Minecraft version.
  `fabric_api_range` is a separate key from the `fabric_api` used for development. Confirm that compilation passes on the lowered version by temporarily
  swapping the node's `deps.minecraft` and `deps.fabric_api` to the lower version.
- Fabric API for 1.21.9 has no `WorldRenderEvents`, so for `1.21.10-fabric` only, `FabricLevelRendererMixin` draws
  at the same spot as Fabric API's `END_MAIN` (the last `endBatch()` in `LevelRenderer#method_62214`).
- Forge 46 (1.20.0) lacks the following two things that Forge 47 has, so `<1.21` nodes are aligned to 46.
  - Injection of `FMLJavaModLoadingContext` into the `@Mod` class constructor (use `get()` with no arguments)
  - `ForgeConfigSpec.Builder#defineListAllowEmpty(String, List, Predicate)` (use the overload taking `List<String>` and `Supplier`)
- NeoForge 21.0.x doesn't automatically pick the bus that an `@EventBusSubscriber` subscribes to, so mod-bus events are
  registered with `modEventBus.addListener`.
- In-game verification is `tools/compat_check.py`. It creates Prism Launcher instances with the distribution jar plus that version's
  Xaero and Fabric API, and with `--auto` adds mc-runtime-test and the runtime hook probe and launches them in turn.

## 1.20.2, 1.20.6 and 1.21.3

- **Where Xaero comes from.** Xaero for 1.20.2 and 1.20.6 isn't on Xaero's Maven, only on Modrinth. Writing `deps.xaero_worldmap` etc. as
  `modrinth:<Modrinth version name>` pulls it from Modrinth's Maven; Xaero of that era doesn't use xaerolib, so set `deps.xaerolib = "none"`.
  Xaero for 1.21.3 is on Xaero's Maven (xaerolib 1.0.45).
- **The 1.20.5 boundary.** 1.20.5 changed the following: the arguments of `BlockState#isPathfindable`, the name of the efficiency enchantment (`EFFICIENCY`),
  `VertexConsumer#normal` taking a `Pose`, `OptionsSubScreen` having a header and footer layout, NeoForge's
  `IConfigScreenFactory`, `ClientTickEvent` and `ModContainer#registerConfig`, and Forge's RenderLevelStageEvent carrying a `Matrix4f`.
  On 1.20.5-1.20.6 no loader has `getTagEnchantmentLevel`, so efficiency is read from the data component (`DataComponents.ENCHANTMENTS`).
- **Java.** 1.20.5 and later use Java 21. However, Mixin 0.8.5 in Forge 50 (1.20.6) only knows up to `JAVA_18`, so for 1.20.5 and 1.20.6
  the mixin config's `compatibilityLevel` is `JAVA_17`.
- **Forge lower bounds.** The lower bound is the version that introduced `AddGuiOverlayLayersEvent`, which the HUD hooks into (50.2.1, 52.1.2, 53.1.2, 54.1.5, 55.0.24).
  Forge 48's (1.20.2) `ConfigScreenFactory` only comes in the form taking `(Minecraft, Screen)`.
- **Forge 1.21.3 rendering.** As on 1.21.4, there is no `RenderLevelStageEvent` and `ForgeLevelRendererMixin` injects instead.
  The arguments of `lambda$addMainPass$1` are the same as 1.21.4.
- The resource pack format in `pack.mcmeta` is 18 for 1.20.2, 32 for 1.20.6 and 42 for 1.21.3.

## 26.1, 26.2 and 26.3

The unobfuscated generation (Java 25). Things change right down to the build foundation. NeoForge 26.3 has no stable release yet (beta only), so it isn't added.

- Runs on Java 25 (`javaVersionFor`). Mixin's `compatibilityLevel` is capped at 21 because Forge doesn't know `JAVA_25`.
  Pack formats are the values from the official client's `version.json` (26.1.2 is resource 84 / data 101, 26.2 is 88 / 107, 26.3 is 97 / 121).
- Fabric uses `build.fabric-26.gradle.kts` (the non-remapping `net.fabricmc.fabric-loom`). There are no mappings, `mod*` dependencies or `remapJar`.
  Key registration is `KeyMappingHelper`, world rendering is `LevelRenderEvents.END_MAIN` (`poseStack()`).
- Forge needs ForgeGradle 7.0.40 or later (earlier versions' AT tool crashes on the 26.x client jar). The AT isn't applied on 26.x
  (the RenderStateShard and RenderType structures it widened are gone). Xaero's jars on its Maven only carry `META-INF/jarjar/metadata.json`
  without the nested xaerolib, so anything put on the dev run has its metadata stripped by `stripXaeroJarJar`.
  `ModList` is static (26.1). The third argument of `PassDefinition#extracts` is `LevelRenderState` (26.3).
- NeoForge needs ModDevGradle 2.0.148 or later (2.0.146 fails recompiling Minecraft 26.2).
- 26.1: `GuiGraphics` is `GuiGraphicsExtractor`, `drawCenteredString` is `centeredText`, `Screen#render` is `extractRenderState`
  (also the injection target in Xaero's GuiMap), depth settings are `DepthStencilState`, `ChunkPos.asLong` is `pack`, and `displayClientMessage` is
  `sendOverlayMessage` / `sendSystemMessage`. `LevelRenderState` moved to `renderer.state.level`.
- 26.2: `MultiBufferSource` is gone, and `NavBuffers` builds a `getBuffer` → `endBatch` flow on top of `StagedVertexBuffer`.
  `Minecraft#screen` and `#setScreen` moved under `gui`, `Options#hideGui` is `Hud#isHidden`, and `GameRenderer#getMainCamera` is
  `mainCamera` (`ClientCompat`). The ore tag constants for coal, lapis, redstone, diamond and emerald were removed (the tags themselves remain).
- 26.3: the GPU abstraction moved to `com.mojang.renderpearl`, and SDL replaced GLFW (key constants are in `InputConstants`).
  `PreparedRenderType#drawFromBuffer` takes a render pass, so `NavBuffers` opens one itself.
  Caves now use `#uncarvable` (bedrock only) instead of replacement tags, so the natural terrain in `DiggableBlocks` is approximated
  with tags for stone, dirt, grass, mud, moss, sand, terracotta and nylium plus explicitly listed blocks.
- Local testing: `-Pxaeronav.quickPlay=<world name>` skips the title screen and enters an existing world
  (without `options.txt` it stops at the initial accessibility screen).

## 1.21.8 and 1.21.10

- All three loaders have dedicated nodes and run on Java 21. Pack formats use the values checked in the official client's `version.json`:
  resource 64 for 1.21.8, and resource 69 / data 88 for 1.21.10. Forge and NeoForge on 1.21.10 declare data 88.
- Forge's EventBus 7 starts at 1.21.6. It uses `ForgeMod` and `ForgeClientSetup`, and registers route rendering with `AddFramePassEvent`.
  On 1.21.8 key and HUD registration go on the mod bus; on 1.21.10 they register on each event's `BUS`.
  `PassDefinition#executes` takes no arguments on 1.21.8 and a `LevelRenderState` on 1.21.10.
  `ForgeLevelRendererMixin` is kept only for 1.21.4 and 1.21.5, which have no official render pass API.
- NeoForge's `RenderLevelStageEvent` becomes per-stage subclasses from 1.21.6.
  On 1.21.10 the camera can't be obtained from the event, so Minecraft's main camera is used.
- From 1.21.9 key input is `KeyEvent` and keybinding categories are `KeyMapping.Category`.
  The world map key injection into Xaero and the runtime probe branch on the same boundary.
- Fabric on 1.21.10 uses `rendering.v1.world.WorldRenderEvents.END_MAIN` and `HudElementRegistry`.
  1.21.8 uses the existing `WorldRenderEvents.AFTER_TRANSLUCENT`.
- On 1.21.8 and 1.21.10 lines are drawn to the main target. Forge's extra pass runs after Fabulous! compositing, so
  drawing to the item_entity target used by vanilla `RenderType.lines()` never gets composited onto the screen.
- Xaero lower bounds match the World Map 1.46.0 / Minimap 26.5.0 actually used in the build.
  When extending a jar's supported range to an intermediate Minecraft version, separately verify that it launches and that the hooks run.

## 1.21.11

The biggest differences from 1.21.1 are in rendering, Forge/NeoForge events and input.

- **Classes that were only renamed are absorbed by Stonecutter replacements** (`replacements` in `stonecutter.gradle.kts`).
  `ResourceLocation` → `Identifier`, and the package move of `Boat`. Replacements are bidirectional, so don't write the replaced name directly in source
- **Depth testing is owned by the RenderPipeline**. Layers shown through terrain are built from custom pipelines that drop only the depth test
  from the standard pipelines (`client/NavRenderTypes`). Lines carry their width per vertex (`setLineWidth`)
- **Lines are drawn to the main target** (vanilla `RenderTypes.lines()` uses item_entity). Forge's extra pass runs after Fabulous! compositing,
  so drawing to item_entity never gets composited onto the screen
- **Entry points for world rendering**: Fabric uses `WorldRenderEvents.END_MAIN` (the `rendering.v1.world` package). Forge 61 has no
  `RenderLevelStageEvent`, and adds a render pass with `AddFramePassEvent`. NeoForge splits `RenderLevelStageEvent` into per-stage
  events and uses `AfterTranslucentBlocks`. All of them already have the view pushed onto modelView, so `PathRenderer` gets an identity matrix
- **Forge 61 uses EventBus 7**. Each event has its own `BUS`, which differs from annotation-based subscription, so the entry points are
  split into `platform/forge/ForgeMod` and `ForgeClientSetup` (`ForgeEntry` and `ForgeEvents` are for 1.21.1 and earlier)
- **Heights come from `getMinY`/`getMaxY`, and the top is inclusive**. `util/GameCompat` keeps returning the traditional "top-exclusive" value.
  The Nether check (formerly `ultraWarm`) is the environment attribute `WATER_EVAPORATES`. It's an attribute that varies by biome, so it is read with a position
  (`getDimensionValue` throws in NeoForge dev runs)
- **Keybinding categories** are `KeyMapping.Category`. The display name is `key.category.xaeronav.main`. On NeoForge, vanilla's
  `Category.register` is deprecated, so the category is registered with `RegisterKeyMappingsEvent#registerCategory`
- **Xaero's render target** changed to `xaero.lib.client.graphics.XaeroBufferProvider`. The mixin injects into its `endBatch()`
- **`pack.mcmeta`** is written with `min_format`/`max_format`. Forge and NeoForge also validate the same file as a data pack,
  so it declares the data format (94), the same as Forge itself (`packFormatFields`)
- Fabric on 1.21.11 needs no access widener (the classes it widened are gone entirely)

## 1.16.5 (Java 8)

As with the other nodes, the source is written in Java 21 syntax and compiled with Java 21, then
converted to Java 8 class files with [JvmDowngrader](https://github.com/unimined/JvmDowngrader).
The distribution jar is the converted one (`java8Jar`, no classifier); the pre-conversion jar is kept with the `-java21` classifier.

- **The mixin config's `compatibilityLevel` is rewritten to `JAVA_8` only inside the distribution jar** (`registerJava8Jar`).
  Dev runs load the classes still in Java 21 form, so the dev-side value must allow Java 11+ features (NESTING).
  `verifyDistribution` checks that no jar contains classes or a `compatibilityLevel` that the user's Java can't read
- **Forge 1.16.5 doesn't bundle MixinExtras and has no jar-in-jar**. `mixinextras-common` is relocated to
  `net.prason.xaeronav.shadow.mixinextras`, put into the distribution jar, and bootstrapped via a mixin config plugin
  (`mixin/MixinExtrasBootstrapPlugin`). Only 1.16.5-forge's `processResources` adds the plugin line
- **Production Forge 1.16.5 runs on SRG names**. The refmap is generated by Architectury Loom's Mixin AP (`useLegacyMixinAp`)
- **Xaero's 1.16.5 Forge build hard-codes SRG names inside its coremods (JavaScript)**. That's fine in production, but
  in the dev environment, which runs on Mojang names, it fails to launch with `NoClassDefFoundError: ToggleableKeyBinding`.
  `fixXaeroCoremods` (which runs before `runClient`) rewrites the coremods inside Loom's remapped jar to the dev-environment names
- Xaero 1.46.0/26.5.0 for 1.16.5 calls `endBatch()` one extra time on a different buffer near the start of `GuiMap#render` and
  `MinimapFBORenderer#renderChunksToFBO`. The ordinal of the mixin injection target shifts by one (`//? if <1.17`)
- The LWJGL that 1.16.5 uses by default can't create windows on recent macOS, so dev runs alone are bumped to LWJGL 3.3.3.
  This doesn't affect the distribution jar or users' environments

## Rules to follow

### Don't write `//?` in `pathfinding/` (the only exception is vanilla API signature differences)

Pathfinding tests only run on the canonical node (`canonical_test_node` in `stonecutter.properties.toml`).
If version branches get into `pathfinding/`, the canonical node's tests will miss bugs on other nodes.
If you need a version branch, build a layer on the `client/` or `platform/` side that absorbs the difference.

**The only exception**: when **how a vanilla API is called (its signature) differs by version** but **the meaning
doesn't change**. Supporting 1.20.1 produced exactly two real cases:
`BlockStateBase#isPathfindable` in `pathfinding/world/CellData.java` (1.20.1 takes the old signature
`(BlockGetter, BlockPos, PathComputationType)`; the check doesn't look at the level, so passing an
empty probe value gives the same result), and fetching the efficiency enchantment level in `pathfinding/world/ChunkView.java`
(1.20.1 uses the old model that passes static fields directly from `Enchantments` rather than a
registry-backed `Holder<Enchantment>`). **The logic itself doesn't branch**, so what the canonical node's tests verify
is unchanged. When in doubt, first consider whether a portable approach, as with JDK differences, can let both versions
share the same code (the missing `Inventory#contains(Predicate)` on 1.20.1 was solved without a gate by
replacing it with a hand-written loop: `ChunkView.hasItem`).

### Put loader-specific imports inside the gate

spotless's `removeUnusedImports` deletes imports that are only used in currently inactive branches.
Stonecutter turns inactive branches into comments, so imports written inside the gate are left alone.

```java
//? neoforge {
import net.neoforged.fml.ModList;
//?} fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?}
```

If an entire file is loader-specific, wrap everything from after the `package` line to the end in a single gate
(`platform/fabric/FabricEntry.java` takes that form).

### Restore the active node before committing

Stonecutter rewrites `src/` to match the active node. If you take a diff with a different node
still active, every file will appear changed.

```bash
./gradlew "Reset active project"
```

## What CI checks

- The `build` job (a per-node matrix, `:<node>:build`): compilation, tests on the canonical node, spotless
- The `runtime` job (actually launches the client per node and enters a world, `headlesshq/mc-runtime-test`).
  Normal PRs narrow it to the canonical node only; pushes to main, the weekly schedule, and PRs touching node definitions or mixins
  expand it to all nodes (so the total number of runtime jobs doesn't grow linearly as nodes are added)
- No XaeroNav mixin application failures in the startup log
- The `server` job (the distribution jar of Forge nodes must not prevent a dedicated server from starting)

`runtime` and `server` run Minecraft on the same Java as users (`java` from `printNodes`: 8 for 1.16.5, 17 for 1.20.1, 21 otherwise).
Gradle itself runs on Java 21 for every node.

The third item is needed because `xaeronav-xaero.mixins.json` is `required=false`. Even if an injection target changes,
no exception is thrown; users just see "no line on the map". It only shows up in the log, so CI reads it.

The runtime state can be checked with `/xaeronav debug hooks`. If a rendering injection point is never hit
while the world map is open, a warning also appears on the HUD.
