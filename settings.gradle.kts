pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "xaeronav"

// The state committed to git. Stonecutter rewrites src/, so
// taking a diff while a different node is active makes every file appear changed.
val vcsNode = "1.21.1-neoforge"

// `-Pxaeronav.onlyNodes=<node>[,<node>...]` configures only those nodes (plus the canonical node).
// Each CI job uses only one node, but configuring all nodes makes every job fetch maven and Minecraft for all loaders,
// and a temporary hiccup in any one of them fails the job. The canonical node is Stonecutter's active node, so it can't be excluded
val onlyNodes = providers.gradleProperty("xaeronav.onlyNodes").orNull
    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
// So `printNodes` can still print all nodes when narrowed with onlyNodes, unconfigured nodes are also kept as `<node>|<MC version>`
val allNodes = mutableListOf<String>()

// Node names are `<MC version>-<loader>`. There's one build script per loader,
// so adding an MC version only takes one more line here (dependency versions go in stonecutter.properties.toml).
stonecutter {
    create(rootProject) {
        fun node(project: String, minecraft: String, buildscript: String) {
            allNodes += "$project|$minecraft"
            if (onlyNodes == null || project in onlyNodes || project == vcsNode) {
                version(project, minecraft).buildscript(buildscript)
            }
        }
        fun match(minecraft: String, vararg loaders: String) = loaders.forEach {
            node("$minecraft-$it", minecraft, "build.$it.gradle.kts")
        }

        // NeoForge 26.3 isn't added until a stable (non-beta) release is out
        match("26.3", "forge")
        node("26.3-fabric", "26.3", "build.fabric-26.gradle.kts")
        match("26.2", "neoforge", "forge")
        node("26.2-fabric", "26.2", "build.fabric-26.gradle.kts")
        match("26.1.2", "neoforge", "forge")
        node("26.1.2-fabric", "26.1.2", "build.fabric-26.gradle.kts")
        match("1.21.11", "neoforge", "fabric", "forge")
        match("1.21.10", "neoforge", "fabric", "forge")
        match("1.21.8", "neoforge", "fabric", "forge")
        match("1.21.5", "neoforge", "fabric", "forge")
        match("1.21.4", "neoforge", "fabric", "forge")
        match("1.21.3", "neoforge", "fabric", "forge")
        match("1.21.1", "neoforge", "fabric", "forge")
        match("1.20.6", "neoforge", "fabric", "forge")
        match("1.20.4", "neoforge", "fabric", "forge")
        // NeoForge 20.2 only has betas
        match("1.20.2", "fabric", "forge")
        match("1.20.1", "fabric")
        match("1.19.2", "fabric")
        match("1.18.2", "fabric")
        // Neither ForgeGradle nor ModDevGradle can handle 1.16.5 Forge with official mappings, so a dedicated Architectury Loom script is used
        match("1.16.5", "fabric")
        node("1.16.5-forge", "1.16.5", "build.forge-116.gradle.kts")
        // 1.20.1 uses ModDevGradle's legacyforge plugin instead of ForgeGradle 7
        // (for 1.17-1.20.1; upstream also recommends migrating to it), so a dedicated build script is used.
        // NeoForge 1.20.1 is skipped: that version of NeoForge is jar-level compatible with Forge
        // (NeoForge itself recommends using Forge on 1.20.1), and Xaero doesn't ship a
        // 1.20.1 build for "neoforge" either (only from 1.20.4)
        node("1.20.1-forge", "1.20.1", "build.forge-legacy.gradle.kts")
        // 1.18.2 and 1.19.2 Forge are built with the same legacyforge plugin (for 1.17-1.20.1)
        node("1.19.2-forge", "1.19.2", "build.forge-legacy.gradle.kts")
        node("1.18.2-forge", "1.18.2", "build.forge-legacy.gradle.kts")

        vcsVersion.set(vcsNode)
    }
}

gradle.extensions.extraProperties.set("xaeronav.allNodes", allNodes.toList())

gradle.beforeProject {
    if (name == "1.16.5-forge") {
        extensions.extraProperties.set("loom.platform", "forge")
    }
}
