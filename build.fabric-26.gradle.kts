plugins {
    id("xaeronav.common")
    // 26.1 and later are unobfuscated, so use the non-remapping Loom (the traditional fabric-loom requires mappings)
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

stonecutter.properties.tags(stonecutter.current.version, "fabric")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")

// Runtime requirement passed to the "java" dependency in fabric.mod.json
val javaVersion = javaVersionFor(minecraftVersion)
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

repositories {
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

// What's widened is RenderType.CompositeState etc. (NavRenderTypes), and 26.1 has no such structures.
// "accessWidener" in fabric.mod.json always points at this name, so put an empty file with only the header
val emptyAccessWidener: File = layout.buildDirectory.file("generated/emptyAccessWidener/xaeronav.accesswidener").get().asFile.also {
    it.parentFile.mkdirs()
    it.writeText("accessWidener v2 official\n")
}

loom {
    accessWidenerPath = emptyAccessWidener

    // The run directory stays Loom's default under the node (versions/<node>/run).
    runs {
        named("client") {
            client()
            configName = "Fabric Client (${stonecutter.current.project})"
            // `-Pxaeronav.quickPlay=<world name>` skips the title screen and enters an existing world (for local testing)
            providers.gradleProperty("xaeronav.quickPlay").orNull?.let { programArgs("--quickPlaySingleplayer", it) }
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { vmArg(it) }
        }
    }
}

val xaeroModules = xaeroModuleCoordinates(
    "fabric", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Whether to put Xaero on the dev run (runClient). Can be left out with `./gradlew runClient -Pwith_xaero=false`.
val withXaero = withXaeroProperty()

// Xaero needs to be loaded as a mod, so it goes into run/mods rather than the runtime classpath.
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")

    implementation("net.fabricmc:fabric-loader:${dep("fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${dep("fabric_api")}")

    // Fabric has no MixinExtras built in, so bundle it (mixin @Local / @WrapOperation depend on it)
    implementation("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")
    include("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")

    // TOML read/write for settings (NightConfigStore)
    implementation("com.electronwill.night-config:core:${dep("night_config")}")
    implementation("com.electronwill.night-config:toml:${dep("night_config")}")
    include("com.electronwill.night-config:core:${dep("night_config")}")
    include("com.electronwill.night-config:toml:${dep("night_config")}")

    // Integration that only lets the settings screen open from the Mods list. Without it installed, the entry point
    // simply isn't called, so it's included in neither the distribution nor the runtime dependencies
    compileOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        exclude(group = "eu.pb4", module = "placeholder-api")
    }
    runtimeOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        exclude(group = "eu.pb4", module = "placeholder-api")
    }

    // Xaero is an optional integration in fabric.mod.json. Only needed for compilation
    xaeroModules.forEach { compileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Copy rather than Sync, so other mods added by hand aren't deleted.
val installXaeroMods = tasks.register<Copy>("installXaeroMods") {
    from(xaeroRuntimeMods)
    into(layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

// The set handed to CI's launch smoke test (mc-runtime-test). Collects the distribution jar and Xaero in one place
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "fabric_loader_range" to dep("fabric_loader_range"),
        "fabric_api_range" to dep("fabric_api_range"),
        "fabric_api_mod_id" to fabricApiModIdFor(minecraftVersion),
        "java_version" to javaVersion.toString()
    )

    inputs.properties(replaceProperties)

    // NeoForge/Forge mod definitions and AT definitions aren't needed in the Fabric jar
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/mods.toml")
    exclude("META-INF/accesstransformer.cfg")

    // Drop the widening lines, leaving only the header (same contents as emptyAccessWidener)
    filesMatching("xaeronav.accesswidener") {
        filter { line -> if (line.startsWith("accessWidener ")) "accessWidener v2 official" else "" }
    }

    filesMatching("fabric.mod.json") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}

tasks.named("configureLaunch") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
