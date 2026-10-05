import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

plugins {
    id("xaeronav.common")
    id("dev.architectury.loom") version "1.17.493"
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
    id("com.gradleup.shadow") version "9.6.1"
}

stonecutter.properties.tags(stonecutter.current.version, "forge")

fun dep(key: String) = stonecutter.properties.get<String>("deps.$key")
val minecraftVersion = dep("minecraft")
val mixinCompatibilityLevel = mixinCompatibilityLevelFor(minecraftVersion)
val packFormat = packFormatFor(minecraftVersion)
val xaeroModules = xaeroModuleCoordinates(
    "forge", minecraftVersion, dep("xaerolib"), dep("xaero_worldmap"), dep("xaero_minimap"))

loom {
    silentMojangMappingsLicense()
    // Production Forge 1.16.5 runs on SRG names. Generate a refmap that resolves the mixin target descriptors (types like MatrixStack) to SRG
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("${modProperty("mod_id")}.refmap.json")
    }
    forge {
        mixinConfig("${modProperty("mod_id")}-xaero.mixins.json")
    }
}

// The ASM/LWJGL bundled with Forge 1.16.5 don't run on Java 21. Force versions that actually work on JDK 21
// (see the comments on https://github.com/architectury/architectury-loom/issues/320).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion("9.6")
            because("Force an ASM that runs on Java 21")
        }
        if (requested.group == "org.lwjgl") {
            useVersion("3.3.3")
            because("Force an LWJGL that runs on Java 21")
        }
    }
}

val mixinExtrasPlugin = "net.prason.xaeronav.mixin.MixinExtrasBootstrapPlugin"
val shadedMixinExtras: Configuration by configurations.creating { isTransitive = false }

// Whether to put Xaero on the dev run (runClient). Can be left out with `./gradlew runClient -Pwith_xaero=false`.
val withXaero = withXaeroProperty()

val xaeroRuntimeMods: Configuration = createXaeroRuntimeModsConfiguration()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    forge("net.minecraftforge:forge:$minecraftVersion-${dep("forge")}")
    // The POMs of Xaero's Minimap / World Map depend on XaeroLib's `dev` classifier (a dev jar built with MCP names).
    // Pulling the same module both with and without a classifier makes the classifier-less jar remapped by Loom empty (22 bytes).
    // XaeroLib's classifier-less distribution jar is added explicitly, so transitive dependencies are cut at both compile time and runtime
    xaeroModules.forEach { modCompileOnly(it) { isTransitive = false } }
    // stageRuntimeTestMods gets the same unremapped jars as distribution
    xaeroModules.forEach { xaeroRuntimeMods(it) }
    if (withXaero) {
        // The published jars use SRG names, so placing them raw in run/mods hides the classes from the dev environment (Mojang names).
        // Load them after converting to dev-environment names through Loom's mod remap (fixXaeroCoremods fixes the coremod contents)
        xaeroModules.forEach { modLocalRuntime(it) { isTransitive = false } }
    }
    compileOnly("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    // @WrapOperation and @ModifyReturnValue are injections Mixin's own AP doesn't know, so without this they don't make it into the refmap
    annotationProcessor("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    shadedMixinExtras("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
    // Dev runs use MixinExtras as-is, before relocation (MixinExtrasBootstrapPlugin bootstraps it)
    "localRuntime"("io.github.llamalad7:mixinextras-common:${dep("mixinextras")}")
}

// Forge 1.16.5 doesn't bundle MixinExtras and has no jar-in-jar. So that it doesn't clash with other mods bundling a different version,
// relocate it into our own package and put it in the distribution jar (bootstrapped by MixinExtrasBootstrapPlugin).
val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations.set(listOf(shadedMixinExtras))
    relocate("com.llamalad7.mixinextras", "net.prason.xaeronav.shadow.mixinextras")
    mergeServiceFiles()
    // MixinExtras's own annotation processor registration. Left in the distribution jar, it runs uninvited in builds that put this jar on the classpath
    exclude("META-INF/services/javax.annotation.processing.Processor")
    archiveClassifier.set("dev-shadow")
}
// The distributed jar is the one converted to Java 8 (java8Jar). The pre-conversion jar is kept under a shifted name
tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(shadowJar.flatMap { it.archiveFile })
    archiveClassifier.set("java21")
}
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

val stageRuntimeTestMods = tasks.register<Copy>("stageRuntimeTestMods") {
    from(xaeroRuntimeMods)
    from(java8Jar)
    into(rootProject.layout.buildDirectory.dir("runtime-test/${stonecutter.current.project}/mods"))
}

// The dedicated-server production smoke test gets only the jar distributed to users, without Xaero.
tasks.register<Sync>("stageServerTestMod") {
    from(java8Jar)
    into(rootProject.layout.buildDirectory.dir("server-test/${stonecutter.current.project}/mods"))
}

// Loom's remap only converts class references, so SRG names that Xaero holds as strings (the coremods' JavaScript, reflective
// Class.forName) remain and dev runs crash. Fix the contents of the remapped jar before launch (ForgeCoremodNames.kt)
val fixXaeroCoremods = tasks.register("fixXaeroCoremods") {
    val runtimeJars = configurations.named("runtimeClasspath").map { classpath ->
        classpath.files.filter { it.name.startsWith("xaero") }
    }
    doLast {
        val tiny = net.fabricmc.loom.LoomGradleExtension.get(project).mappingConfiguration.tinyMappingsWithSrg
        runtimeJars.get().forEach { jar ->
            val count = rewriteForgeCoremodNames(jar.toPath(), tiny)
            if (count > 0) {
                logger.lifecycle("${jar.name}: rewrote SRG names in ${count} files to dev-environment names")
            }
        }
    }
}
tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn(fixXaeroCoremods)
}

// Without this, META-INF/mods.toml and xaeronav-xaero.mixins.json in the distribution jar
// go in with unexpanded placeholders like `${'$'}{mod_id}`, and Forge can't read the mod definition and won't start
// (compileJava/assemble still pass, so the build alone won't reveal it; it surfaces in runClient).
tasks.named<ProcessResources>("processResources").configure {
    val replaceProperties = commonNodeResourceProperties(
        minecraftVersion, dep("xaero_worldmap_min"), dep("xaero_minimap_min"), mixinCompatibilityLevel, packFormat) + mapOf(
        "forge_loader_version_range" to dep("forge_loader_range")
    )

    inputs.properties(replaceProperties)
    inputs.property("mixin_plugin", mixinExtrasPlugin)

    exclude("fabric.mod.json")
    exclude("xaeronav.accesswidener")
    exclude("META-INF/neoforge.mods.toml")

    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
    filesMatching("xaeronav-xaero.mixins.json") {
        expand(replaceProperties)
        filter { line ->
            line.replace("\"package\":", "\"plugin\": \"$mixinExtrasPlugin\",\n  \"package\":")
        }
    }
    filesMatching("pack.mcmeta") {
        expand(replaceProperties)
    }
}
