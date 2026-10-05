plugins {
    id("xaeronav.common")
    id("net.neoforged.moddev") version "2.0.148"
}

stonecutter.properties.tags(stonecutter.current.version, "neoforge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

// Same boundary as the toolchain branch in xaeronav.common.gradle.kts
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

// Don't mix Xaero jars for different MC versions in the same mods folder. Only 1.21.1 keeps using run/ itself,
// which it has used since it was the first node (it holds the world, the config, and Xaero's map data)
val runDir = rootProject.layout.projectDirectory.dir(
    if (stonecutter.current.project == "1.21.1-neoforge") "run" else "run/${stonecutter.current.project}")

neoForge {
    version = dep("neoforge")

    mods {
        create(modProperty("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    // Lets unit tests use Minecraft's plain value types (BlockPos, Vec3, Mth).
    // Without this, Minecraft isn't on testCompileClasspath and not a single line of pathfinding-core test
    // can be written (which is why the existing tests were limited to Minecraft-independent classes like Heuristic).
    // Note that this only adds the classpath; touching Blocks/BuiltInRegistries requires Bootstrap.
    // Keep tests within what runs without starting the registries.
    addModdingDependenciesTo(sourceSets["test"])

    // Client-only mod (@Mod(dist = Dist.CLIENT)), so no dedicated-server run config is provided
    runs {
        create("client") {
            client()
            gameDirectory = runDir
            // `-Pxaeronav.quickPlay=<world name>` skips the title screen and enters an existing world (for local checks)
            providers.gradleProperty("xaeronav.quickPlay").orNull?.let { programArguments.addAll("--quickPlaySingleplayer", it) }
            // With the default INFO, the generated log4j config's Root is INFO, so XaeroNav's DEBUG, which the release NeoForge writes to debug.log,
            // shows up nowhere in the dev client. latest.log stays at INFO, so output matches the release build
            logLevel = org.slf4j.event.Level.DEBUG
            // The default heap is 1/4 of physical memory, which is 6GB on the dev machine. Issues that occur with the launcher's default 2GB
            // don't reproduce unless you cap it with `-Pxaeronav.clientHeap=2G`
            providers.gradleProperty("xaeronav.clientHeap").orNull?.let { jvmArgument("-Xmx$it") }
            // The official launcher starts with G1 tuning flags, so GC-related slowness can't be measured under production conditions without passing them
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { jvmArgument(it) }
            providers.gradleProperty("xaeronav.jfr").orNull?.let {
                jvmArgument("-XX:StartFlightRecording=settings=$it,filename=${rootProject.projectDir}/run/xaeronav.jfr,dumponexit=true")
            }

            // The IDE run configs ModDevGradle generates have no module binding. With a single loader,
            // "the whole project's classpath" is just that loader's, but once Stonecutter adds
            // fabric nodes, IntelliJ passes every module's classpath, NeoForge's and Fabric's
            // Minecraft end up side by side, and FML crashes before startup (Found multiple copies of MinecraftServer.class).
            // From the IDE, launch via the neoforge node's runClient task.
            disableIdeRun()
        }
    }
}

// The artifactId is "-neoforge-", not "-forge-". chocolateminecraft.com's maven has both, and
// the "-forge-" build is ignored at NeoForge runtime as "for Forge/old NeoForge, cannot be loaded".
val xaeroModules = xaeroModuleCoordinates(
    "neoforge", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Whether to put Xaero into the dev run (runClient). `./gradlew runClient -Pwith_xaero=false` removes it.
// This mod is designed to work with in-world rendering alone even without Xaero, so keep a way to actually verify that
// (xaeronav-xaero.mixins.json is required=false, so without Xaero only the map integration is silently disabled).
val withXaero = withXaeroProperty()

// Xaero must be loaded as a mod, so it goes into run/mods rather than the runtime classpath.
// Putting it on additionalRuntimeClasspath makes it appear on the classpath, but FML doesn't detect it as a mod,
// and only Xaero's classes land in "a layer that can't resolve Minecraft's classes". That creates
// a mismatch where ModList says it isn't installed yet Class.forName succeeds, and the moment it's touched
// the whole game crashes with NoClassDefFoundError.
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    // Xaero is an optional integration in mods.toml. Needed only for compilation; not included in the release or as a runtime dependency.
    // Using implementation would put it on runtimeClasspath, and development would carry on without "works without Xaero"
    // ever being verified.
    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Copy rather than Sync so manually added mods aren't deleted. Bumping the version leaves the old jar
// behind, but clearing mods/ and reinstalling takes care of it.
val installXaeroMods = tasks.register<Copy>("installXaeroMods") {
    from(xaeroRuntimeMods)
    into(runDir.dir("mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods, "prepareClientRun")
}

// The bundle passed to CI's startup smoke test (mc-runtime-test). Collects the release jar and Xaero in one place
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "neoforge_loader_version_range" to dep("neoforge_loader_range"),
        "neoforge_version_range" to dep("neoforge_range"),
    ) + if (packFormat >= 65) {
        // Like Forge, NeoForge also validates the mod's pack.mcmeta as a data pack (see packFormatFields)
        mapOf("pack_format_fields" to packFormatFields(packFormat, dataPackFormatFor(minecraftVersion)))
    } else {
        emptyMap()
    }

    inputs.properties(replaceProperties)

    // The Fabric/Forge mod definitions aren't needed in the NeoForge jar
    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/mods.toml")
    // Forge-only AT (see the comment in NavRenderTypes.java). NeoForge already opens up RenderStateShard's
    // constants, so it isn't needed
    exclude("META-INF/accesstransformer.cfg")

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
    // NeoForge 20.4's FML reads the mod definition only from META-INF/mods.toml (neoforge.mods.toml is from 20.5 on).
    // With the wrong name, XaeroNav isn't loaded at all.
    // rename isn't counted as a Gradle input, so without declaring it the task is UP-TO-DATE and stale output remains
    if (stonecutter.eval(minecraftVersion, "<1.20.5")) {
        rename("neoforge.mods.toml", "mods.toml")
        inputs.property("modsTomlName", "mods.toml")
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}

tasks.named("createMinecraftArtifacts") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
