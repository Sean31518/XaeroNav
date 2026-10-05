import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** The mod's own metadata, kept in `gradle.properties`. Same value for every node. */
fun Project.modProperty(key: String): String =
    findProperty(key) as String? ?: error("Property `$key` not set.")

/** Minimum Java version users need for each Minecraft version. */
fun javaVersionFor(minecraftVersion: String): Int = when {
    minecraftVersion.startsWith("1.16.") -> 8
    minecraftVersion == "1.20.5" || minecraftVersion == "1.20.6" -> 21
    minecraftVersion.startsWith("1.18.") || minecraftVersion.startsWith("1.19.") || minecraftVersion.startsWith("1.20.") -> 17
    minecraftVersion.startsWith("26.") -> 25
    else -> 21
}

// The 1.16.5 sources use modern features such as records, so they are compiled with Java 21 and
// the distributed jar is then converted for Java 8. The runtime requirement is expressed by javaVersionFor.
fun compileJavaVersionFor(minecraftVersion: String): Int =
    if (minecraftVersion.startsWith("1.16.")) 21 else javaVersionFor(minecraftVersion)

// Mixin decides CompatibilityLevel not by the runtime requirement (javaVersionFor) but by the language
// features the mixin classes' own bytecode requires. 1.16.5 is compiled with Java 21 and converted to
// Java 8 at distribution time, so the mixin classes right after compilation (= the dev build runClient uses)
// contain Java 11+ features such as NESTING. Passing javaVersionFor's 8 as-is makes Mixin
// reject it ("JAVA_8 cannot handle NESTING") and the game does not start.
//
// Only 1.16.5 is kept at 18 instead of 21. The Mixin fork bundled with Forge 1.16.5
// (architectury mixin-patched 0.8.4.12) only has `CompatibilityLevel` constants up to JAVA_18,
// and passing JAVA_21 makes Mixin initialization itself throw before startup (a value not in the enum).
// The only feature the 1.16.5 mixin classes actually need is NESTING (Java 11+), so 18 is enough.
// Other versions keep their original values; changing them here (21 works with Fabric's newer Mixin)
// would change behavior unintentionally.
fun mixinCompatibilityLevelFor(minecraftVersion: String): String {
    val compileVersion = compileJavaVersionFor(minecraftVersion)
    val level = when {
        minecraftVersion.startsWith("1.16.") -> minOf(compileVersion, 18)
        // Forge's Mixin does not know JAVA_25 (crashes before startup with "not recognised"), and the features this mod's mixins need are covered by Java 21
        minecraftVersion.startsWith("26.") -> minOf(compileVersion, 21)
        // Mixin 0.8.5 in Forge 50 (1.20.6) only knows up to JAVA_18, and startup stops as soon as it reads a JAVA_21 config
        minecraftVersion == "1.20.5" || minecraftVersion == "1.20.6" -> minOf(compileVersion, 17)
        else -> compileVersion
    }
    return "JAVA_$level"
}

/**
 * The mod id claimed by the Fabric API core module. The 0.42.0 line from the 1.16.5 era is still "fabric"
 * (the rename to "fabric-api" came in later versions); getting the dependency key wrong makes mod
 * resolution fail with "fabric-api is missing" even though it is installed.
 */
fun fabricApiModIdFor(minecraftVersion: String): String =
    if (minecraftVersion.startsWith("1.16.")) "fabric" else "fabric-api"

/** Resource pack pack_format. `pack_version.resource_major` in the client jar's version.json. */
fun packFormatFor(minecraftVersion: String): Int = when (minecraftVersion) {
    "1.16.5" -> 6
    "1.18.2" -> 8
    "1.19.2" -> 9
    "1.20.1" -> 15
    "1.20.2" -> 18
    "1.20.4" -> 22
    "1.20.6" -> 32
    "1.21.1" -> 34
    "1.21.3" -> 42
    "1.21.4" -> 46
    "1.21.5" -> 55
    "1.21.8" -> 64
    "1.21.10" -> 69
    "1.21.11" -> 75
    "26.1.2" -> 84
    "26.2" -> 88
    "26.3" -> 97
    else -> error("pack_format is not registered for Minecraft $minecraftVersion. Add it from the client jar's version.json")
}

/**
 * Dependency coordinates of Xaero's 3 modules (lib/worldmap/minimap). Only the loader name part of the artifactId
 * (fabric/forge/neoforge) differs between the 4 nodes.
 *
 * <p>Xaero builds for versions that stopped receiving updates (1.20.6 etc.) are not on Xaero's Maven, only on Modrinth. Versions written
 * as `modrinth:<Modrinth version name>` are fetched from Modrinth's Maven. Xaero from that era does not use xaerolib, so set `xaerolibVersion` to `none`.
 */
fun xaeroModuleCoordinates(
    loader: String,
    minecraftVersion: String,
    xaerolibVersion: String,
    worldmapVersion: String,
    minimapVersion: String,
): List<String> = listOfNotNull(
    xaerolibVersion.takeIf { it != "none" }?.let { "xaero.lib:xaerolib-$loader-$minecraftVersion:$it" },
    xaeroCoordinate("xaero.map:xaeroworldmap-$loader-$minecraftVersion", "xaeros-world-map", worldmapVersion),
    xaeroCoordinate("xaero.minimap:xaerominimap-$loader-$minecraftVersion", "xaeros-minimap", minimapVersion),
)

private fun xaeroCoordinate(xaeroModule: String, modrinthProject: String, version: String): String =
    if (version.startsWith("modrinth:")) "maven.modrinth:$modrinthProject:${version.removePrefix("modrinth:")}"
    else "$xaeroModule:$version"

/** Shared check for dev runs so Xaero can be excluded with `./gradlew runClient -Pwith_xaero=false`. */
fun Project.withXaeroProperty(): Boolean = (findProperty("with_xaero") as String?)?.toBoolean() ?: true

/**
 * Xaero has to be loaded as a mod, so it goes into run/mods rather than onto the runtime classpath
 * (the reason is shared by all 4 nodes; see the comments in each node's build.*.gradle.kts).
 */
fun Project.createXaeroRuntimeModsConfiguration(): Configuration =
    configurations.create("xaeroRuntimeMods") { isTransitive = false }

/**
 * For in-game debugging, appends the short git hash to the version (e.g. "0.1.2+f118060").
 * This lets `/xaeronav version` tell whether a "still not fixed" report is due to a rebuild not being picked up.
 *
 * <p>Not added for release builds (`-Prelease`). The distributed version should match the tag name.
 * Falls back to no hash if git is missing or outside a repository (e.g. just an extracted GitHub source zip):
 * failing here would prevent people from rebuilding the release jar from source.
 */
fun Project.stampedModVersion(): String {
    val base = modProperty("mod_version")
    if (hasProperty("release")) {
        return base
    }
    return gitCommitHash()?.let { "$base+$it" } ?: base
}

/**
 * Version used in file and artifact names. The semver build-metadata separator `+` is ill-suited
 * to file names (it gets URL-encoded, and tools disagree on how to handle it), so use `-`.
 *
 * <p><b>The mod metadata side keeps using `+` via {@code stampedModVersion}</b>: that is
 * parsed as semver, so changing the build-metadata separator would turn it into a different version.
 *
 * <p>Writing it separately where the artifact name is built and where it is picked up ({@code collectJars}) means changing only one
 * <b>goes green while picking up nothing</b>. That actually happened: jars were built with `-` while the collector
 * looked for `+`, so {@code build/libs} was empty, and the CI jar upload ({@code if-no-files-found: error}) and
 * the release were failing. Keeping it in one place ensures they never diverge again.
 */
fun Project.archiveModVersion(): String = stampedModVersion().replace('+', '-')

/**
 * The latter part of the distributed jar file name `<mod_id>-<this value>.jar`.
 * `<mod_version>-<loader>-<MC version>[-<git hash>]`.
 *
 * <p>Used both by the jar task's {@code archiveVersion} and by {@code collectJars}'s include pattern.
 * Changing only one leaves {@code build/libs} empty while CI goes green (see the {@code archiveModVersion} comment).
 */
fun Project.archiveVersionFor(loader: String, minecraftVersion: String): String {
    val version = modProperty("mod_version")
    val hashSuffix = archiveModVersion().removePrefix(version)
    return "$version-$loader-$minecraftVersion$hashSuffix"
}

private fun Project.gitCommitHash(): String? {
    val output = runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }
    }.getOrNull() ?: return null
    val exitValue = runCatching { output.result.get().exitValue }.getOrNull()
    if (exitValue != 0) {
        return null
    }
    return output.standardOutput.asText.get().trim().ifEmpty { null }
}

/**
 * Shared values injected into both loaders' mod definition files (`neoforge.mods.toml` / `fabric.mod.json`).
 * Loader-specific values (such as the loader version range) are added by each build script.
 */
fun Project.modResourceProperties(): Map<String, String> = mapOf(
    "mod_id" to modProperty("mod_id"),
    "mod_name" to modProperty("mod_name"),
    "mod_version" to stampedModVersion(),
    "mod_authors" to modProperty("mod_authors"),
    "mod_description" to modProperty("mod_description"),
    "mod_license" to modProperty("mod_license"),
    "mod_display_url" to modProperty("mod_display_url"),
    "mod_issue_tracker_url" to modProperty("mod_issue_tracker_url")
)

/**
 * Table of nodes whose jar also runs on earlier Minecraft versions (values are the lower versions, oldest first).
 * All are hotfix releases with effectively identical mappings and protocol. Even where the earlier version's Xaero is an older, no longer updated line (World Map 1.39.x,
 * Minimap 25.x), the injection targets were the same as the current version.
 * Ones that cannot be added: Forge 1.21 (Forge 51 has no event for injecting into the HUD), Forge 1.20.3 (Forge 49.0.x has no
 * ClientTickEvent.Post), Forge on 26.1.x (xaerolib requires Forge 64+), NeoForge 1.20.3, 26.1, and 26.1.1 (beta only).
 */
fun minecraftCompatFor(node: String): List<String> = when (node) {
    "1.21.1-neoforge", "1.21.1-fabric" -> listOf("1.21")
    "1.20.6-fabric" -> listOf("1.20.5")
    "1.20.4-fabric" -> listOf("1.20.3")
    "1.20.1-fabric", "1.20.1-forge" -> listOf("1.20")
    "1.21.8-fabric" -> listOf("1.21.6", "1.21.7")
    "1.21.10-fabric" -> listOf("1.21.9")
    "26.1.2-fabric" -> listOf("26.1", "26.1.1")
    else -> emptyList()
}

/** Minecraft version range written into the mod definition. The range syntax differs per loader (Fabric: space-separated AND; Forge family: Maven ranges). */
fun minecraftRangeProperties(minecraftVersion: String, node: String): Map<String, String> {
    val compat = minecraftCompatFor(node).firstOrNull()
    return mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_range_fabric" to if (compat == null) minecraftVersion else ">=$compat <=$minecraftVersion",
        "minecraft_range_maven" to if (compat == null) "[$minecraftVersion]" else "[$compat,$minecraftVersion]",
    )
}

/**
 * Resource replacement values shared by all 4 nodes ({@link #modResourceProperties} plus the minimum Xaero version that works and
 * pack_format/mixin compatibility level). Loader-specific keys (such as the loader version range) are
 * added by each build.<loader>.gradle.kts on the caller side.
 */
fun Project.commonNodeResourceProperties(
    minecraftVersion: String,
    worldmapMinVersion: String,
    minimapMinVersion: String,
    mixinCompatibilityLevel: String,
    packFormat: Int,
): Map<String, String> = modResourceProperties() + minecraftRangeProperties(minecraftVersion, name) + mapOf(
    "xaero_worldmap_min_version" to worldmapMinVersion,
    "xaero_minimap_min_version" to minimapMinVersion,
    "mixin_compatibility_level" to mixinCompatibilityLevel,
    "pack_format_fields" to packFormatFields(packFormat),
)

/**
 * Declaration of the `pack.mcmeta` format. From 1.21.9 (resource format 65) on, it is written with `min_format`/`max_format` instead of `pack_format`.
 *
 * <p>Forge and NeoForge also read the same `pack.mcmeta` as a data pack, and no form satisfies both the data side (claiming format 81 or lower requires `supported_formats`) and
 * the resource side (claiming 65 or higher forbids `supported_formats`). Like Forge itself,
 * it is declared in the data format. The mod's resources are loaded regardless of the compatibility check.
 */
fun packFormatFields(packFormat: Int, dataPackFormat: Int? = null): String = when {
    dataPackFormat != null -> "\"min_format\": $dataPackFormat,\n        \"max_format\": $dataPackFormat,"
    packFormat >= 65 -> "\"min_format\": $packFormat,\n        \"max_format\": $packFormat,"
    else -> "\"pack_format\": $packFormat,"
}

/** Data pack format (`pack_version.data_major` in the client jar's version.json). Used only by Forge and NeoForge on 1.21.9+. */
fun dataPackFormatFor(minecraftVersion: String): Int = when (minecraftVersion) {
    "1.21.10" -> 88
    "1.21.11" -> 94
    "26.1.2" -> 101
    "26.2" -> 107
    "26.3" -> 121
    else -> error("Data pack format is not registered for Minecraft $minecraftVersion. Add it from the client jar's version.json")
}

/**
 * Finishes the jar already converted to Java 8 into a distributable form. It is output under an unclassified name (same form as other nodes' distributed jars),
 * so give the pre-conversion remapJar a classifier to move its name out of the way.
 *
 * <p>The mixin config's `compatibilityLevel` is set for dev runs (classes still at Java 21), but
 * on a Java 8 JVM Mixin rejects anything above `JAVA_8` and crashes before startup. The classes are already converted
 * to Java 8, so rewrite it to `JAVA_8` only inside the distributed jar.
 */
fun Project.registerJava8Jar(shaded: Provider<RegularFile>): TaskProvider<Zip> =
    tasks.register<Zip>("java8Jar") {
        from(zipTree(shaded)) {
            filesMatching("*.mixins.json") {
                filter { line -> line.replace(Regex("\"JAVA_\\d+\""), "\"JAVA_8\"") }
            }
        }
        archiveBaseName.set(tasks.named<Jar>("jar").flatMap { it.archiveBaseName })
        archiveVersion.set(tasks.named<Jar>("jar").flatMap { it.archiveVersion })
        archiveClassifier.set("")
        archiveExtension.set("jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
