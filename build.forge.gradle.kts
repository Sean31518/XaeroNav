import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("xaeronav.common")
    id("net.minecraftforge.gradle") version "7.0.40"
    id("net.minecraftforge.renamer") version "1.1.5"
    // In FG7, jar-in-jar was split out into a separate plugin (built in up to FG6)
    id("net.minecraftforge.jarjar") version "0.2.3"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val minecraftVersion = dep("minecraft")
val needsRuntimeReobfuscation = stonecutter.eval(minecraftVersion, "<1.20.5")

// Same boundary as the toolchain branch in xaeronav.common.gradle.kts (this node is currently always 1.21.x, so fixed to JAVA_21)
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)

repositories {
    // Minecraft 1.21.1's macOS LWJGL includes a `natives-macos-patch` classifier that isn't on
    // Maven Central. Register the official Minecraft library repository with an artifact pattern too,
    // so normal Maven metadata resolution doesn't pin it to Central.
    exclusiveContent {
        forRepository {
            ivy {
                name = "MojangLibraryArtifacts"
                url = uri("https://libraries.minecraft.net")
                patternLayout {
                    artifact("[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]")
                    setM2compatible(true)
                }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("org.lwjgl", "lwjgl-freetype") }
    }
    mavenCentral()
    maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
    maven("https://libraries.minecraft.net") { name = "Mojang" } // com.mojang:text2speech etc. only exist here
    minecraft.mavenizer(this) // local repo created by FG7
}

// The annotation processor is used to validate mixin annotations. From 1.20.5 on the runtime uses official
// mappings so no refmap is generated (same reason as NeoForge), but the compile-time check itself is needed.
// 1.20.4 and earlier use an SRG runtime, so a refmap is also produced from the mappings passed by enableMixinRefmaps below
dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
}

// Client-only mod (@Mod alone; Forge has no dist argument). No run configuration is provided for dedicated servers
minecraft {
    // NeoForge itself opens the RenderStateShard constants with a wildcard AT, but Forge itself only opens
    // the inner classes (see the comment in NavRenderTypes.java). Add the same opening.
    // From 26.1 on, the RenderStateShard/RenderType structure the AT opened no longer exists (NavRenderTypes),
    // and AT processing (a tool that runs on Java 8) crashes on the 26.x client jar, so apply it only to nodes that need it
    if (!stonecutter.eval(minecraftVersion, ">=26.1")) {
        accessTransformer = rootProject.files("src/main/resources/META-INF/accesstransformer.cfg")
    }

    runs {
        configureEach {
            // Don't mix Xaero jars for different MC versions/loaders in the same mods folder.
            workingDir = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.project}")
        }

        // Whether the IDE module binding mismatch hit when adding the NeoForge node (see xaeronav-multiloader-plan)
        // also occurs with FG7 is unverified. Create it with defaults first; if it breaks in-game, look for a disableIdeRun equivalent
        register("client") {
            mods {
                create(modProperty("mod_id")) {
                    sources(sourceSets["main"])
                }
            }
            // FG7's Slime Launcher doesn't carry this macOS argument from Minecraft's version metadata over to
            // runClient. Without it, GLFW stops right after launch on the first-thread check.
            if (System.getProperty("os.name").startsWith("Mac")) {
                jvmArgs("-XstartOnFirstThread")
            }
            // `-Pxaeronav.quickPlay=<world name>` skips the title screen and enters an existing world (for local checks)
            providers.gradleProperty("xaeronav.quickPlay").orNull?.let { args("--quickPlaySingleplayer", it) }
            // Same hook as the NeoForge node (e.g. for running CI's runtime hook probe locally)
            providers.gradleProperty("xaeronav.clientJvmArgs").orNull?.split(" ")?.filter { it.isNotBlank() }
                ?.forEach { jvmArgs(it) }
            if (!needsRuntimeReobfuscation) {
                // Dev runs load the mod from the classes directory, so the MANIFEST's MixinConfigs doesn't exist
                // (for nodes using Renamer, enableMixinRefmaps below adds the same argument)
                args("--mixin.config", "${modProperty("mod_id")}-xaero.mixins.json")
            }
        }
    }
}

// Versions whose production uses SRG names need a refmap mapping injection target strings to SRG. In the dev
// environment, the SRG names in Xaero's bundled refmap also have to be read as named. Renamer's Mixin integration
// sets refMapRemappingFile on runClient, and also handles XaeroNav's own refmap generation and config registration for dev runs.
if (needsRuntimeReobfuscation) {
    renamer.enableMixinRefmaps {
        config("${modProperty("mod_id")}-xaero.mixins.json")
        // Match the name that "refmap" in mixins.json points to. With the default `main.refmap.json`, the refmap
        // isn't found in the distributed jar and not a single SRG-mapped injection target hits
        refMap.set("${modProperty("mod_id")}.refmap.json")
        // Renamer sets reverse=true for dependency conversion, but what Mixin needs at runtime is
        // SRG (Xaero's refmap) → named (dev Minecraft), so the output mapping is reversed too.
        generatedMappings {
            reverse.set(true)
        }
    }

    // Renamer has the refmap and mapping written into compile's temp directory but doesn't declare them as outputs. Without
    // declaring them, only the classes come back from the build cache, the refmap is missing, and not one injection into the jar's GuiMap etc. hits in production (measured in CI)
    tasks.named("compileJava") {
        outputs.file(layout.buildDirectory.file("tmp/compileJava/compileJava-refmap.json")).withPropertyName("mixinRefmap")
        outputs.file(layout.buildDirectory.file("tmp/compileJava/compileJava-mappings.tsrg")).withPropertyName("mixinMappings")
    }

    // Mixin 0.8.5's refMapRemappingFile only reads SRG format (MD:/FD: lines), and the tsrg passed by Renamer is
    // silently ignored line by line. Xaero's own mixins then fail in dev runs. Convert the same content to SRG
    // format, and replace the system property after Renamer's configuration is done.
    val mixinRefmapRemapSrg = renamer.convert("mixinRefmapRemapSrg", renamer.mixin.generatedMappings, "srg")
    tasks.matching { it.name == "runClient" }.configureEach { dependsOn(mixinRefmapRemapSrg) }
    afterEvaluate {
        minecraft.runs.named("client") {
            systemProperty("mixin.env.refMapRemappingFile", mixinRefmapRemapSrg.get().output.get().asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}"))
}

// Use the jarJar task's output (no classifier) as the distributable. The original jar task is moved to "slim"
// (without mixinextras) so collectJars (the root buildAll artifact aggregation) doesn't pick it up by mistake
jarJar.register {
    // 1.20.4 and earlier use SRG names in production, so the merged jar is renamed again before distribution.
    archiveClassifier = if (needsRuntimeReobfuscation) "mapped" else null
}

val distributionJar = if (needsRuntimeReobfuscation) {
    renamer.classes("renameJarJar", tasks.named<Jar>("jarJar")) {
        // The map comes in as the default from renamer.mappings(...) below. Adding it here makes two files and Renamer rejects it
        archiveClassifier = null
    }
} else {
    tasks.named<AbstractArchiveTask>("jarJar")
}

if (needsRuntimeReobfuscation) {
    tasks.named("assemble") {
        dependsOn(distributionJar)
    }
}

// Forge's FML doesn't read [[mixins]] from mods.toml (unlike NeoForge). Configs are picked up by Mixin itself,
// which only looks at the MANIFEST's MixinConfigs. Without it, not one Xaero integration mixin hits in production
tasks.named<Jar>("jar") {
    archiveClassifier = "slim"
    manifest.attributes("MixinConfigs" to "${modProperty("mod_id")}-xaero.mixins.json")
}

val xaeroModules = xaeroModuleCoordinates(
    "forge", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Whether to load Xaero into dev runs (runClient). Can be removed with `./gradlew runClient -Pwith_xaero=false`.
val withXaero = withXaeroProperty()

// Xaero must be loaded as a mod, so it goes into run/mods rather than the runtime classpath
// (same reason as the other two nodes; see the comment in NeoForgeEntry.java).
val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

// Published Forge Xaero jars for 1.20.4 and earlier still reference Minecraft by SRG names (f_... / m_...),
// so loaded as-is into the named dev environment, Xaero's own Mixin @Shadow can't resolve.
// Renamer's dependency path applies FG's mapping in reverse, converting to named only for dev runs and compilation.
// The runtime smoke test at distribution gets the unconverted jars from xaeroRuntimeMods below.
val xaeroDevelopmentModules = if (needsRuntimeReobfuscation) {
    renamer.mappings(minecraft.dependency.toSrg)
    xaeroModules.map { renamer.dependency(it) }
} else {
    // FG7 converts external mods on the same resolution configuration as the Minecraft dependency to named via mavenizer.
    // Copying the published jars directly into run/mods bypasses the conversion, and Xaero's own @Shadow f_... fails.
    xaeroModules
}

// For 26.1+, the Maven jars of Xaero (Forge) keep only META-INF/jarjar/metadata.json, without the nested xaerolib itself
// (the distributed jar does include it). FML's jar-in-jar resolution fails before launch with "nested jar not found",
// so strip the metadata only from what goes into dev runs. xaerolib is loaded as a separate jar too, so resolution is satisfied
val stripsXaeroJarJar = stonecutter.eval(minecraftVersion, ">=26.1")
val xaeroStrippedDir = layout.buildDirectory.dir("xaero-without-jarjar")
val stripXaeroJarJar = tasks.register("stripXaeroJarJar") {
    val source = configurations.detachedConfiguration(*xaeroModules.map { dependencies.create(it) }.toTypedArray())
        .apply { isTransitive = false }
    inputs.files(source)
    outputs.dir(xaeroStrippedDir)
    doLast {
        val outDir = xaeroStrippedDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        source.files.forEach { jar ->
            ZipFile(jar).use { zip ->
                ZipOutputStream(File(outDir, jar.name).outputStream()).use { out ->
                    zip.entries().asSequence().filterNot { it.name.startsWith("META-INF/jarjar/") }.forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

dependencies {
    xaeroDevelopmentModules.forEach { compileOnly(it) }
    if (withXaero && stripsXaeroJarJar) {
        runtimeOnly(fileTree(xaeroStrippedDir) { builtBy(stripXaeroJarJar) })
    } else if (withXaero) {
        xaeroDevelopmentModules.forEach { runtimeOnly(it) }
        // stageRuntimeTestMods gets the same unconverted jars as at distribution.
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }

    // Forge doesn't bundle MixinExtras (unlike NeoForge/Fabric). Bundle it via jar-in-jar
    // ("jarJar" is a Configuration name created by the net.minecraftforge.jarjar plugin, not a function).
    // Add mixinextras-common to the compile classpath with compileOnly (needed to resolve the annotations
    // themselves, such as @Local/@WrapOperation). Don't add it to annotationProcessor: doing so makes
    // mixinextras-common inject its own obfuscation resolution path into the Mixin AP, which on the official
    // mapping runtime (1.21.1) always detects "no mappings" and stops the build. The annotationProcessor only
    // needs Mixin itself (0.8.7), and @ModifyReturnValue/@WrapOperation expansion proceeds there
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    if (needsRuntimeReobfuscation) {
        // @WrapOperation/@ModifyReturnValue are injections unknown to Mixin's own AP, so on 1.20.4 and earlier,
        // where an SRG refmap is produced, add it to the AP too. Without it the refmap is empty (same as forge-legacy)
        annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    }
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

// The set passed to CI's launch smoke test (mc-runtime-test). Gathers the distribution jar and Xaero in one place.
// Uses the merged jar bundling mixinextras (the jarJar task's output); the plain jar task is "slim" and
// doesn't include mixinextras, so distributing/running it alone crashes at launch because MixinExtras isn't found
val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    if (stripsXaeroJarJar) {
        // For 26.1+, placing the Maven version as-is fails in jar-in-jar resolution, so place the metadata-stripped jars, same as dev runs
        from(stripXaeroJarJar)
    } else {
        from(xaeroRuntimeMods)
    }
    from(distributionJar)
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

// The dedicated server production smoke test gets no Xaero, only the merged jar distributed to users.
// Sharing the same stage directory as the client runtime would mix the optional Xaero dependency into the server and defeat the check.
tasks.register<Sync>("stageServerTestMod") {
    from(distributionJar)
    into(rootProject.layout.buildDirectory.dir("server-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
    ) + if (packFormat >= 65) {
        mapOf("pack_format_fields" to packFormatFields(packFormat, dataPackFormatFor(minecraftVersion)))
    } else {
        emptyMap()
    }

    inputs.properties(replaceProperties)

    if (stonecutter.eval(minecraftVersion, ">=26.1")) {
        exclude("META-INF/accesstransformer.cfg")
    }
    // The other two loaders' mod definitions aren't needed in the Forge jar
    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")
    // accesstransformer.cfg, conversely, is included (Forge itself applies the same transformation at runtime
    // in the user's environment). It is excluded on the NeoForge/Fabric side (build.neoforge/fabric.gradle.kts)

    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}
