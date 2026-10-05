plugins {
    id("xaeronav.common")
    // The legacyforge plugin itself already provides jar-in-jar (the jarJar configuration).
    // Also applying net.minecraftforge.jarjar for FG6/7 conflicts with it ("jarJar configuration already exists")
    id("net.neoforged.moddev.legacyforge") version "2.0.146"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")

val mcVersion = dep("minecraft")
// Same boundary as the toolchain branch in xaeronav.common.gradle.kts. This node is only for 1.17-1.20.1, so currently always Java 17
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(mcVersion)
val packFormat = packFormatFor(mcVersion)

legacyForge {
    // Touching mods/runs etc. (members of the outer extension) inside enable{} gets them evaluated before enable() itself
    // has finished "enabling", and it fails with "Mod development has not been enabled yet".
    // Put only the version in enable{}, and write mods/runs after the enable() call has completed
    enable {
        forgeVersion = "$mcVersion-${dep("forge")}"
    }

    mods {
        create(modProperty("mod_id")) {
            sourceSet(sourceSets["main"])
        }
    }

    // Client-only mod. No run configuration for the dedicated server
    runs {
        create("client") {
            client()
            // Sharing root/run across all nodes would also load Xaero jars for other MC versions and loaders
            // at the same time, applying mixins to a different Minecraft. Keep each node fully separate.
            gameDirectory = rootProject.layout.projectDirectory.dir("run/${stonecutter.current.project}")
        }
    }

    // Make Minecraft's plain value types (BlockPos etc.) usable from unit tests
    // (same reason as the NeoForge node; night-config comes along here too since Forge itself bundles it)
    addModdingDependenciesTo(sourceSets["test"])
}

// Forge 1.20.1's FML does not read [[mixins]] in mods.toml and only looks at MixinConfigs in the MANIFEST.
// Production also runs on SRG names, so a refmap mapping the injection target strings (render, endBatch, etc.) to SRG is needed.
// If either is missing, not a single Xaero integration mixin applies in production
mixin {
    add(sourceSets["main"], "${modProperty("mod_id")}.refmap.json")
    config("${modProperty("mod_id")}-xaero.mixins.json")
}

// mixin.config() only affects the dev run arguments and is not written to the distributed jar's MANIFEST
tasks.named<Jar>("jar") {
    manifest.attributes("MixinConfigs" to "${modProperty("mod_id")}-xaero.mixins.json")
}

val xaeroModules = xaeroModuleCoordinates(
    "forge", mcVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

// Whether to load Xaero into the dev run (runClient). Disable with `./gradlew runClient -Pwith_xaero=false`.
val withXaero = withXaeroProperty()

val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")

    // The published jars use the SRG namespace, so with plain compileOnly/runtime copies Xaero's own mixins
    // (@Shadow f_...) fail in the named dev environment. Remap them to named via MDG's mod configuration.
    xaeroModules.forEach { modCompileOnly(it) }
    if (withXaero) {
        xaeroModules.forEach { modRuntimeOnly(it) }
        // stageRuntimeTestMods gets the same unremapped jars as at distribution.
        xaeroModules.forEach { xaeroRuntimeMods(it) }
    }

    // @WrapOperation and @ModifyReturnValue are injections the core Mixin AP doesn't know, so mixinextras-common
    // must also be on the AP or they don't make it into the refmap. On 1.21.1-forge, putting it on the AP stopped the build
    // because official mappings yield "no mapping"; that doesn't happen on this node, which passes SRG
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    implementation("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
    "jarJar"("io.github.llamalad7:mixinextras-forge:${dep("mixinextras")}")
}

val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    // The jar task outputs the named devlibs jar; the distributed jar remapped to production SRG names is reobfJar's output.
    // Passing jar results in NoSuchFieldError in production
    from(tasks.named("reobfJar"))
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

// The dedicated server production smoke test gets no Xaero, only the jar shipped to users.
tasks.register<Sync>("stageServerTestMod") {
    from(tasks.named("reobfJar"))
    into(rootProject.layout.buildDirectory.dir("server-test/${stonecutter.current.project}/mods"))
}

tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        mcVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
    )

    inputs.properties(replaceProperties)

    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")

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

// Same reason as the NeoForge node (ModDevGradle's createMinecraftArtifacts has an implicit dependency)
tasks.named("createMinecraftArtifacts") {
    dependsOn(tasks.named("stonecutterGenerate"))
}
