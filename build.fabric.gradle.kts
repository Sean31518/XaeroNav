import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("xaeronav.common")
    id("fabric-loom") version "1.17.20"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1" apply false
}

stonecutter.properties.tags(stonecutter.current.version, "fabric")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")
if (minecraftVersion.startsWith("1.16.")) {
    pluginManager.apply("xyz.wagyourtail.jvmdowngrader")

    // The LWJGL 3.3.2 that 1.16.5 resolves by default throws
    // `GLFW error 65548: Cocoa: Regular windows do not have icons on macOS` on macOS (especially Apple Silicon)
    // and Minecraft.<init> stops (known issue, LWJGL/lwjgl3#695). Force a newer version.
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.lwjgl") {
                useVersion("3.3.3")
                because("1.16.5's default LWJGL throws when setting the window icon on macOS")
            }
        }
    }
}

// Runtime requirement passed to the "java" dependency in fabric.mod.json.
// The 1.16.5 prototype node is planned to be compiled with Java 21 and then converted to Java 8.
val javaVersion = javaVersionFor(minecraftVersion)
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

repositories {
    maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" }
}

// What's opened is RenderType.CompositeState etc. (NavRenderTypes); in 1.21.11 the whole class is gone
val usesAccessWidener = !stonecutter.eval(minecraftVersion, ">=1.21.11")

// Place a file with the same name even on versions with nothing to open. Varying fabric.mod.json's "accessWidener" by version
// makes the template unreadable as JSON, and Loom warns on every configuration. Loom reads this file at configuration time, so write it here
val emptyAccessWidener: File = layout.buildDirectory.file("generated/emptyAccessWidener/xaeronav.accesswidener").get().asFile.also {
    if (!usesAccessWidener) {
        it.parentFile.mkdirs()
        it.writeText("accessWidener v2 named\n")
    }
}

// RenderType.create became public in 1.20. On 1.18 and 1.19 the 7-argument version is private, so access is opened only for those versions.
// Adding it to the file shared by all nodes makes AW application fail on 1.16.5 and 1.21.x, where the method signature differs
val opensRenderTypeCreate = minecraftVersion.startsWith("1.18.") || minecraftVersion.startsWith("1.19.")
val generatedAccessWidenerDir = layout.buildDirectory.dir("generated/accessWidener")
val nodeAccessWidener: File = generatedAccessWidenerDir.get().file("xaeronav.accesswidener").asFile.also {
    if (opensRenderTypeCreate) {
        it.parentFile.mkdirs()
        it.writeText(rootProject.file("src/main/resources/xaeronav.accesswidener").readText().trimEnd() + "\n" +
            "accessible method net/minecraft/client/renderer/RenderType create " +
            "(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/VertexFormat\$Mode;" +
            "IZZLnet/minecraft/client/renderer/RenderType\$CompositeState;)" +
            "Lnet/minecraft/client/renderer/RenderType\$CompositeRenderType;\n")
    }
}

loom {
    accessWidenerPath = when {
        !usesAccessWidener -> emptyAccessWidener
        opensRenderTypeCreate -> nodeAccessWidener
        else -> rootProject.file("src/main/resources/xaeronav.accesswidener")
    }

    // The run directory stays at loom's default under the node (versions/<node>/run).
    // The contents of mods differ per loader, so sharing NeoForge's run/ would mix in
    // Xaero built for the other loader and fail to load.
    runs {
        named("client") {
            client()
            configName = "Fabric Client (${stonecutter.current.project})"
            // Same hook as the NeoForge node (e.g. for running CI's runtime hook probe locally)
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { vmArg(it) }
        }
        // This mod doesn't use the server run configuration loom provides by default (client-only mod).
        // Even if removed from the runs container, loom registers it again later, so the name can't be avoided
    }
}

val xaeroModules = xaeroModuleCoordinates(
    "fabric", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Whether to load Xaero into dev runs (runClient). Can be removed with `./gradlew runClient -Pwith_xaero=false`.
// This mod is designed to work with in-world rendering alone even without Xaero, so a way to actually verify that is kept.
val withXaero = withXaeroProperty()

// Xaero must be loaded as a mod, so it goes into run/mods rather than the runtime classpath.
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${dep("fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${dep("fabric_api")}")

    // NeoForge includes it in the base, but Fabric doesn't, so it's bundled (mixin's @Local / @WrapOperation depend on it)
    implementation("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")
    include("io.github.llamalad7:mixinextras-fabric:${dep("mixinextras")}")

    // TOML reading/writing for config. NeoForge's base includes the same library (night-config),
    // so the config definition can stay in one place regardless of loader.
    implementation("com.electronwill.night-config:core:${dep("night_config")}")
    implementation("com.electronwill.night-config:toml:${dep("night_config")}")
    include("com.electronwill.night-config:core:${dep("night_config")}")
    include("com.electronwill.night-config:toml:${dep("night_config")}")

    // An integration that only lets the config screen be opened from the Mods list. Without it, the entry point
    // just isn't called, so it's included neither in the distributable nor as a runtime dependency.
    // ModMenu 1.16.23 for 1.16.5 is an old format that directly lists fabric-loader in its own dependencies,
    // and Loom's remap treats it as distinct from the real fabric-loader (0.19.5), so runClient crashes with
    // "duplicate fabric loader classes". ModMenu itself doesn't load the loader via a mod, so excluding it
    // is safe.
    modCompileOnly("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        // An optional integration target of ModMenu 9.x. XaeroNav only uses the config screen API, so
        // there's no need to pull ModMenu's Placeholder API into the compile classpath.
        exclude(group = "eu.pb4", module = "placeholder-api")
    }
    modLocalRuntime("com.terraformersmc:modmenu:${dep("modmenu")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
        exclude(group = "eu.pb4", module = "placeholder-api")
    }

    // Xaero is an optional integration in fabric.mod.json. Needed only for compilation.
    // With compileOnly (the one without mod), Minecraft types stay in intermediary mappings and can't resolve.
    xaeroModules.forEach { modCompileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }
}

// Copy rather than Sync, so other manually added mods aren't deleted.
val installXaeroMods = tasks.register<Copy>("installXaeroMods") {
    from(xaeroRuntimeMods)
    into(layout.projectDirectory.dir("run/mods"))
}

tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(installXaeroMods)
}

// The set passed to CI's launch smoke test (mc-runtime-test). Gathers the distribution jar and Xaero in one place.
// What Fabric distributes is remapJar, mapped back to intermediary, not the plain jar
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(tasks.named(if (minecraftVersion.startsWith("1.16.")) "java8Jar" else "remapJar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "fabric_loader_range" to dep("fabric_loader_range"),
        // With "*", fabric-api lets the loader start even with an old API. Declare the lowest version verified to work as the lower bound
        "fabric_api_range" to dep("fabric_api_range"),
        "fabric_api_mod_id" to fabricApiModIdFor(minecraftVersion),
        "java_version" to javaVersion.toString()
    )

    inputs.properties(replaceProperties)

    // NeoForge/Forge mod and AT definitions aren't needed in the Fabric jar
    exclude("META-INF/neoforge.mods.toml")
    exclude("META-INF/mods.toml")
    exclude("META-INF/accesstransformer.cfg")
    if (opensRenderTypeCreate) {
        // The AW going into the jar is also the one with the added opening. Excluding and re-adding would make the exclude apply to the added one too
        doLast {
            nodeAccessWidener.copyTo(destinationDir.resolve("xaeronav.accesswidener"), overwrite = true)
        }
    }
    if (!usesAccessWidener) {
        // Drop the opening lines, leaving only the header (same contents as emptyAccessWidener)
        filesMatching("xaeronav.accesswidener") {
            filter { line -> if (line.startsWith("accessWidener ")) line else "" }
        }
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

if (minecraftVersion.startsWith("1.16.")) {
    // What's distributed is the Java 8-converted one (java8Jar). The pre-conversion jar is kept under a shifted name
    tasks.named<AbstractArchiveTask>("remapJar") { archiveClassifier.set("java21") }
    val downgraded = tasks.register<DowngradeJar>("downgradeRemapJar") {
        inputFile.set(tasks.named<AbstractArchiveTask>("remapJar").flatMap { it.archiveFile })
        archiveClassifier.set("java8-unshaded")
    }
    val shaded = tasks.register<ShadeJar>("shadeJava8Jar") {
        inputFile.set(downgraded.flatMap { it.archiveFile })
        archiveClassifier.set("java8-shaded")
    }
    val java8Jar = registerJava8Jar(shaded.flatMap { it.archiveFile })
    tasks.named("assemble") { dependsOn(java8Jar) }
}
