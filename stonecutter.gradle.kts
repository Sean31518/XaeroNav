plugins {
    id("dev.kikugie.stonecutter")
    id("com.diffplug.spotless") version "8.10.1"
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

// A repository only for downloading the formatter spotless uses (google-java-format).
// Dependencies used by node builds live in each build.<loader>.gradle.kts.
repositories {
    mavenCentral()
}

// Record the active node in a file. Switching nodes makes Stonecutter rewrite src/, so
// "which node's sources are currently in place" needs to be known outside git.
stonecutter.active(file(".sc_active_version"))

stonecutter.parameters {
    // The suffix of a node name like `1.21.1-neoforge` is the loader name as-is. This switches each source's
    // `//? if neoforge {` / `//? if fabric {` / `//? if forge {`.
    constants.match(current.project.substringAfterLast('-'), "neoforge", "fabric", "forge")

    // Classes that only changed name are absorbed by replacements over the whole source, not split with `//?` at each use.
    // Replacements are bidirectional (switching the node back restores the original name), so never write the replaced name directly in source
    replacements {
        string(eval(current.version, ">=1.21.11")) {
            replace("ResourceLocation", "Identifier")
        }
        // In 26.1 GuiGraphics was renamed to GuiGraphicsExtractor. "getGuiGraphics" (the NeoForge event's method name) is
        // unchanged, so only the two spots that spell out the type name are replaced
        // In 26.3 the GPU abstraction moved to com.mojang.renderpearl and SDL replaced GLFW (key constants are in InputConstants)
        string(eval(current.version, ">=26.3")) {
            replace("com.mojang.blaze3d.pipeline.RenderPipeline;", "com.mojang.renderpearl.api.pipeline.RenderPipeline;")
        }
        string(eval(current.version, ">=26.3")) {
            replace("com.mojang.blaze3d.pipeline.DepthStencilState;", "com.mojang.renderpearl.api.pipeline.DepthStencilState;")
        }
        string(eval(current.version, ">=26.3")) {
            replace("com.mojang.blaze3d.platform.CompareOp;", "com.mojang.renderpearl.api.pipeline.CompareOp;")
        }
        string(eval(current.version, ">=26.3")) {
            replace("GLFW.GLFW_KEY_UNKNOWN", "InputConstants.UNKNOWN.getValue()")
        }
        string(eval(current.version, ">=26.3")) {
            replace("GLFW.GLFW_KEY_", "InputConstants.KEY_")
        }
        string(eval(current.version, ">=26.3")) {
            replace("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD")
        }
        // In 26.2 MultiBufferSource was removed, and NavBuffers takes over the same flow
        string(eval(current.version, ">=26.2")) {
            replace("import net.minecraft.client.renderer.MultiBufferSource;", "import net.prason.xaeronav.client.NavBuffers;")
        }
        string(eval(current.version, ">=26.2")) {
            replace("MultiBufferSource.BufferSource bufferSource", "NavBuffers bufferSource")
        }
        string(eval(current.version, ">=26.2")) {
            replace("mc.renderBuffers().bufferSource()", "NavBuffers.begin()")
        }
        string(eval(current.version, ">=26.1")) {
            replace("gui.GuiGraphics;", "gui.GuiGraphicsExtractor;")
        }
        string(eval(current.version, ">=26.1")) {
            replace("GuiGraphics graphics", "GuiGraphicsExtractor graphics")
        }
        string(eval(current.version, ">=26.1")) {
            replace("renderer.state.LevelRenderState;", "renderer.state.level.LevelRenderState;")
        }
        string(eval(current.version, ">=1.21.11")) {
            replace("net.minecraft.world.entity.vehicle.Boat;", "net.minecraft.world.entity.vehicle.boat.Boat;")
        }
    }
}

@Suppress("UNCHECKED_CAST")
val allNodes = (gradle.extensions.extraProperties["xaeronav.allNodes"] as List<String>)
    .map { it.substringBefore('|') to it.substringAfter('|') }

// When narrowed with -Pxaeronav.onlyNodes, collect and check only that node's jar.
// The canonical node is always configured due to Stonecutter, but it isn't collected unless specified (nodes are built in parallel)
val distributionNodes = providers.gradleProperty("xaeronav.onlyNodes").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
    .let { only -> stonecutter.versions.filter { only == null || it.project in only } }

// Publishing is one site and one node per job. Only failed jobs need re-running,
// and other nodes that already succeeded aren't posted twice. Normal builds register no publish target.
// The node is looked up from the list of all nodes, not the configured ones. Without configuring the published node,
// its jar can be posted without setting up that node's Minecraft
val publishTarget = providers.gradleProperty("publish_target").orNull
val publishNode = providers.gradleProperty("publish_node").orNull
if (publishTarget != null || publishNode != null) {
    check(publishTarget in setOf("modrinth", "curseforge") && publishNode != null) {
        "Publishing requires both -Ppublish_target=modrinth|curseforge and -Ppublish_node=<node>"
    }
    val minecraft = allNodes.singleOrNull { it.first == publishNode }?.second
        ?: error("Unknown publish node: $publishNode")
    val loader = publishNode!!.substringAfterLast('-')
    val releaseVersion = modProperty("mod_version")
    val releaseFile = layout.buildDirectory.file(
        "libs/${modProperty("mod_id")}-${archiveVersionFor(loader, minecraft)}.jar")

    val publishedMinecraftVersions = minecraftCompatFor(publishNode) + minecraft

    publishMods {
        file.set(releaseFile)
        // Several files are posted to the same project, so the version number on the site is made unique per node.
        version.set("$releaseVersion-$loader-$minecraft")
        displayName.set("XaeroNav $releaseVersion - $loader $minecraft")
        changelog.set(providers.fileContents(
            layout.projectDirectory.file("changelogs/$releaseVersion.md")).asText)
        type.set(STABLE)
        dryRun.set(providers.gradleProperty("publish_dry_run").map(String::toBoolean).orElse(false))
        modLoaders.add(loader)

        when (publishTarget) {
            "modrinth" -> modrinth {
                projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
                accessToken.set(providers.environmentVariable("MODRINTH_TOKEN"))
                minecraftVersions.addAll(publishedMinecraftVersions)
                environment.set(CLIENT_ONLY)
                if (loader == "fabric") requires("fabric-api")
            }
            "curseforge" -> curseforge {
                projectId.set(providers.environmentVariable("CURSEFORGE_PROJECT_ID"))
                accessToken.set(providers.environmentVariable("CURSEFORGE_TOKEN"))
                minecraftVersions.addAll(publishedMinecraftVersions)
                client.set(true)
                server.set(false)
                if (loader == "fabric") requires("fabric-api")
            }
        }
    }
}

// Entry point for running all nodes together. Adding nodes doesn't change the CI configuration.
tasks.register("buildAll") {
    group = "build"
    description = "Builds all nodes (MC version x loader)"
    dependsOn(stonecutter.tasks.named("build"))
}

// sync, not Copy. With Copy, jars from previous builds remain, and stale artifacts with a different version or commit hash
// could get attached to the release as-is (release.yml picks up build/libs/*.jar wholesale)
tasks.register<Sync>("collectJars") {
    group = "build"
    description = "Collects the distribution jars (only that node's with -Pxaeronav.onlyNodes) into the root build/libs"
    distributionNodes.forEach { dependsOn(":${it.project}:assemble") }
    // Jars from past builds also remain in each node's build/libs (the jar task doesn't delete old outputs).
    // Pick only this version's: the version carries git's short hash, so
    // this narrows it down to "jars this build produced"
    distributionNodes.forEach { node ->
        val loader = node.project.substringAfterLast('-')
        from(layout.projectDirectory.dir("versions/${node.project}/build/libs")) {
            include("${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar")
        }
    }
    into(layout.buildDirectory.dir("libs"))
}

// Check the contract of the collected artifacts before release. Beyond having files for every node, mechanically verify before publishing
// that each loader's metadata and the Xaero mixin config are in the right jars.
tasks.register("verifyDistribution") {
    group = "verification"
    description = "Checks the count, names, loader metadata and Mixin config of the distribution jars collected by collectJars"
    dependsOn(tasks.named("collectJars"))
    doLast {
        val expected = distributionNodes.associate { node ->
            val loader = node.project.substringAfterLast('-')
            "${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar" to loader
        }
        val javaVersions = distributionNodes.associate { node ->
            val loader = node.project.substringAfterLast('-')
            "${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar" to javaVersionFor(node.version)
        }
        val isBefore1205 = distributionNodes.associate { node ->
            val loader = node.project.substringAfterLast('-')
            "${modProperty("mod_id")}-${archiveVersionFor(loader, node.version)}.jar" to stonecutter.eval(node.version, "<1.20.5")
        }
        val directory = layout.buildDirectory.dir("libs").get().asFile
        val actual = directory.listFiles { file -> file.extension == "jar" }
            ?.associateBy { it.name } ?: emptyMap()
        check(actual.keys == expected.keys) {
            "Distribution jars don't match the expected set expected=${expected.keys.sorted()} actual=${actual.keys.sorted()}"
        }
        expected.forEach { (name, loader) ->
            java.util.jar.JarFile(actual.getValue(name)).use { jar ->
                // A single class unreadable by the user's Java prevents startup (especially on 1.16.5, which is converted to Java 8).
                // The class file major version for Java 8 is 52, increasing by 1 per version after that
                val maxMajor = 44 + javaVersions.getValue(name)
                jar.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                    .forEach { entry ->
                        val major = jar.getInputStream(entry).use { input ->
                            val header = input.readNBytes(8)
                            ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                        }
                        check(major <= maxMajor) { "$name: ${entry.name} can't be read by Java ${javaVersions.getValue(name)} (major $major)" }
                    }
                val mixinConfig = jar.getInputStream(jar.getEntry("xaeronav-xaero.mixins.json")).use { String(it.readBytes()) }
                check(Regex("\"JAVA_(\\d+)\"").find(mixinConfig)!!.groupValues[1].toInt() <= javaVersions.getValue(name)) {
                    "$name: the mixin config's compatibilityLevel is newer than the user's Java"
                }
                check(jar.getEntry("xaeronav-xaero.mixins.json") != null) { "$name: mixin config is missing" }
                val entryNames = jar.entries().asSequence().map { it.name }.toSet()
                when (loader) {
                    "fabric" -> {
                        check(jar.getEntry("fabric.mod.json") != null) { "$name: fabric.mod.json is missing" }
                        check(jar.getEntry("META-INF/mods.toml") == null
                                && jar.getEntry("META-INF/neoforge.mods.toml") == null) {
                            "$name: metadata from another loader is mixed in"
                        }
                        // Fabric bundles it as a nested jar in META-INF/jars/ via Loom's include()
                        // (mixinextras mixins like @Local/@WrapOperation depend on it; it isn't part of the main jar)
                        check(entryNames.any { it.startsWith("META-INF/jars/mixinextras-fabric-") }) {
                            "$name: mixinextras is not bundled"
                        }
                    }
                    "forge" -> {
                        check(jar.getEntry("META-INF/mods.toml") != null) { "$name: mods.toml is missing" }
                        check(jar.manifest.mainAttributes.getValue("MixinConfigs")
                                == "xaeronav-xaero.mixins.json") { "$name: invalid MixinConfigs manifest" }
                        // On Forge running SRG names in production (1.20.4 and earlier), a missing or empty refmap means not a single injection into GuiMap etc. applies
                        // (the config itself is required=false, so it only shows in the log)
                        if (isBefore1205.getValue(name)) {
                            val refmap = jar.getEntry("xaeronav.refmap.json")
                            check(refmap != null) { "$name: xaeronav.refmap.json is missing" }
                            check(jar.getInputStream(refmap).use { String(it.readBytes()) }.contains("GuiMapMixin")) {
                                "$name: xaeronav.refmap.json has no injection target for GuiMapMixin"
                            }
                        }
                        // Forge bundles it as a nested jar in META-INF/jarjar/ via FG7's jarJar (or legacyforge's mechanism of the same name)
                        // (Forge itself doesn't bundle mixinextras).
                        // 1.16.5, which has no jar-in-jar, relocates it into our own package and includes it directly
                        check(entryNames.any {
                            it.startsWith("META-INF/jarjar/mixinextras-forge-")
                                || it.startsWith("net/prason/xaeronav/shadow/mixinextras/")
                        }) {
                            "$name: mixinextras is not bundled"
                        }
                    }
                    "neoforge" -> {
                        // NeoForge 20.4's FML only reads META-INF/mods.toml. Get the name wrong and the whole mod isn't loaded
                        val metadata = if (isBefore1205.getValue(name)) "META-INF/mods.toml" else "META-INF/neoforge.mods.toml"
                        check(jar.getEntry(metadata) != null) { "$name: ${metadata.substringAfterLast('/')} is missing" }
                        val metadataText = jar.getInputStream(jar.getEntry(metadata)).use { String(it.readBytes()) }
                        check(metadataText.contains("[[mixins]]")) { "$name: ${metadata.substringAfterLast('/')} doesn't register the mixin config" }
                    }
                    // NeoForge itself already bundles mixinextras, so no bundling check is needed here
                }
            }
        }
    }
}

// Formatting is enforced only once, at the root. The source tree is shared by all nodes,
// so running it per node would just look at the same files over and over (spotless
// can't target files outside the project directory, so this is the only place it can go anyway).
//
// Only checks things where "fixing is beyond debate" (leftover unused imports, trailing whitespace,
// missing final newline). No formatter that rewrites existing formatting wholesale: having the diff touch every file
// and make meaningful review impossible does more harm than occasionally leaving broken formatting.
//
// Note: imports used only in inactive branches (the side Stonecutter commented out) get removed
// by removeUnusedImports. Always put loader-specific imports inside that branch's gate.
spotless {
    java {
        target("src/*/java/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        leadingTabsToSpaces(4)
    }
}

// A list for CI to build per-node jobs (startup smoke tests). So adding nodes doesn't
// require editing the workflow, nodes are defined in a single place, settings.gradle.kts.
tasks.register("printNodes") {
    group = "help"
    description = "Prints all nodes as a JSON array (for the CI matrix)"
    val nodes = allNodes
    val fabricApi = fabricApiVersions(file("stonecutter.properties.toml").readText())
    doLast {
        println(nodes.joinToString(",", "[", "]") { (project, version) ->
            val loader = project.substringAfterLast('-')
            // java is the user's runtime (the JVM used for the startup check). Builds always run on Gradle with Java 21
            """{"node":"$project","minecraft":"$version","loader":"$loader","java":"${javaVersionFor(version)}",""" +
                """"fabric_api":"${fabricApi["$loader.$version"] ?: "none"}"}"""
        })
    }
}

/** `deps.fabric_api` per `[<loader>."<MC version>"]`. Used by CI to pick the fabric-api distribution jar. */
fun fabricApiVersions(toml: String): Map<String, String> {
    val result = HashMap<String, String>()
    var table: String? = null
    for (line in toml.lines()) {
        Regex("""^\[(\w+)\."([^"]+)"]""").find(line.trim())?.let { table = "${it.groupValues[1]}.${it.groupValues[2]}" }
        Regex("""^deps\.fabric_api\s*=\s*"([^"]+)"""").find(line.trim())?.let { match ->
            table?.let { result[it] = match.groupValues[1] }
        }
    }
    return result
}
