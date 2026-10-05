plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.kikugie.dev/releases") {
        name = "KikuGie"
        content { includeGroupByRegex("dev\\.kikugie(?:\\..*)?") }
    }
    maven("https://maven.kikugie.dev/snapshots") {
        name = "KikuGie Snapshots"
        content { includeGroupByRegex("dev\\.kikugie(?:\\..*)?") }
    }
}

dependencies {
    // Apply the idea-ext used by ModDevGradle once, from buildSrc's parent class loader.
    // If each Stonecutter NeoForge node loaded it separately, the same `settings` extension would be
    // registered twice during IntelliJ sync.
    implementation("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:1.2")

    // So the convention plugins can read node names and per-node properties (stonecutter.properties.toml)
    implementation("dev.kikugie:stonecutter:0.9.7")

    // Detects Java @Mixin classes at compile time and registers them in the mixin config automatically.
    // Only alpha releases exist so far, so pin a verified version rather than a dynamic one for reproducibility.
    implementation("dev.kikugie.fletching-table:fletching-table:0.2.0-alpha.9")

    // For rewriting class names written as strings in Xaero's class files in 1.16.5-forge dev runs (ForgeCoremodNames.kt)
    implementation("org.ow2.asm:asm:9.10.1")
}
