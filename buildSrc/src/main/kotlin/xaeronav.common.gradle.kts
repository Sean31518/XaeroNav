import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

plugins {
    id("java-library")
    id("dev.kikugie.fletching-table")
}

// ModDevGradle applies idea-ext to the root project from each NeoForge node. Apply the same plugin from buildSrc's
// classloader to the root first, preventing double registration per node.
if (!rootProject.pluginManager.hasPlugin("org.jetbrains.gradle.plugin.idea-ext")) {
    rootProject.pluginManager.apply("org.jetbrains.gradle.plugin.idea-ext")
}

// Stonecutter applies its own build plugin to each node first, so it can be referenced here.
val node = extensions.getByType<StonecutterBuildExtension>()
val loader = node.current.project.substringAfterLast('-')
val minecraftVersion = node.current.version

group = modProperty("mod_group_id")
version = stampedModVersion()

// The jar name is `<mod_id>-<mod_version>-<loader>-<MC version>[-<git hash>].jar`.
// Without the loader/MC version in the file name, identically named jars line up in build/libs, and at
// distribution it's impossible to tell which is for which node (build/libs is separate per node).
base {
    archivesName = modProperty("mod_id")
}

// The 1.16.5 prototype node compiles keeping newer Java syntax, and is planned to be converted to Java 8 before distribution.
val javaVersion = compileJavaVersionFor(minecraftVersion)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

// Always print the origin of deprecated API usage to the normal log. The summary "used in some places" can't pinpoint what to update.
// Zero warnings has been confirmed, so -Werror catches any recurrence as a build failure.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
    options.compilerArgs.add("-Werror")
    if (minecraftVersion.startsWith("1.16.")) {
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
    }
}

repositories {
    mavenCentral()
    maven("https://chocolateminecraft.com/maven") { name = "Xaero's Maven" }
    // Fetch only old Xaero versions not on Xaero's Maven (xaeroModuleCoordinates' `modrinth:`) from here
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

// When Fletching Table initializes the mixin config, it auto-adds mavenLocal and KikuGie Snapshots, enabled for
// all dependencies. If the latter is slow, even Fabric API waits on it, and since Gradle configures all
// Stonecutter nodes, Forge/NeoForge jobs get dragged down too.
// Restrict them to Fletching Table's own group, and send other dependencies straight to their proper repositories.
repositories.withType<MavenArtifactRepository>().configureEach {
    if (name == "MavenLocal" || name == "KikuGie Snapshots") {
        content {
            includeGroupByRegex("dev\\.kikugie(?:\\..*)?")
        }
    }
}

// Collect @Mixin classes with Java APT and register them in the client list using the existing config as a template.
// The template's ${'$'}{mixin_compatibility_level} is a valid JSON string, so each loader's processResources
// expansion can still be applied as-is after Fletching Table generates the list.
// Forge 1.20.1's refmap generation and MANIFEST registration are separate responsibilities, so each loader keeps its own setup.
fletchingTable {
    mixins.configure("main") {
        mixin("xaeronav-xaero.mixins.json") {
            env("client")
        }
    }
}

// Only annotations expressing null contracts in types. They're marker annotations with no annotation processing,
// so they aren't added to annotationProcessor (they don't fall into the trap, like mixinextras, of stopping the
// build on the official mapping runtime).
dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("6.1.3")

            // Tests that run 600k-node searches on real save data take around 8 seconds each.
            // Exclude them from the default `test` run continuously during development and leave them to `slowTest` (which `check` depends on)
            targets.all {
                testTask.configure {
                    useJUnitPlatform { excludeTags("slow", "bench") }
                }
            }
        }
    }
}

/**
 * Runs only tests tagged `@Tag("slow")`. They guard against "holes that only appear at large scale" on real End
 * terrain, which synthetic terrain structurally can't reproduce.
 */
val slowTest = tasks.register<Test>("slowTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs heavy pathfinding tests that use real world save data"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    // The Nether fixture is almost entirely solid by volume; even 512 blocks square is 16.9M cells
    // (FakeCells is a sparse table without air, so it's orders of magnitude more than the same area of the
    // overworld). With the default heap it hits OutOfMemoryError while loading the terrain
    maxHeapSize = "3g"
    // Recreate the JVM per test class. Running in one JVM piles up the large terrain each class loads until
    // the heap is exhausted (it actually crashed along with the JVM without leaving a single test result).
    // Startup makes it slower, but the heavy tests take tens of seconds each anyway
    forkEvery = 1
    // Run serially, the three heavy ones (Nether{WideRoute,LiveWalk,DetourBreakdown}Test, about 14 minutes total)
    // pile up to over 30 minutes overall (measured in CI). Classes are independent per JVM, so parallelizing
    // works straightforwardly. One core is left for Gradle itself and other tasks. It needs 3g (maxHeapSize) ×
    // parallelism of memory, so it is capped at 4 (3 in parallel fits on GitHub Actions' default 4 vCPU/16 GB runner)
    maxParallelForks = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
}

tasks.named("check") { dependsOn(slowTest) }

/**
 * Runs the measurements tagged `@Tag("bench")`. They're not guards, so they're excluded from `check`:
 * a measurement without assertions put in CI never turns red, so nobody looks at it.
 */
val bench = tasks.register<Test>("bench") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Measures pathfinding speed and quality (no assertions)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("bench") }
    // Same reason as slowTest. Only the closure-graph measurements need more, so it can be raised just for those
    maxHeapSize = providers.gradleProperty("xaeronav.heap").orNull ?: "3g"
    forkEvery = 1
    systemProperty("xaeronav.profileOut",
            layout.buildDirectory.dir("bench").get().asFile.absolutePath)
    // Measurement switches (-Pxaeronav.navGraphOnly=true etc.). They don't reach the test JVM unless passed explicitly
    listOf("xaeronav.navGraphOnly", "xaeronav.navGraphLag", "xaeronav.navGraphFarScale", "xaeronav.navGraphFar", "xaeronav.navGraphRefuseCut",
            "xaeronav.traceBudgetSeconds", "xaeronav.navGraphVerbose", "xaeronav.routeLimit", "xaeronav.routeSkip", "xaeronav.skipClosure", "xaeronav.reviewTicks", "xaeronav.walkTrace", "xaeronav.closure", "xaeronav.walkMode", "xaeronav.closureRadius", "xaeronav.window", "xaeronav.closureBox", "xaeronav.searchMargin", "xaeronav.blockLava", "xaeronav.voxelMargin", "xaeronav.keepFraction", "xaeronav.routes", "xaeronav.unknownMap", "xaeronav.sweepMin", "xaeronav.sweepMax", "xaeronav.sweepSeed", "xaeronav.sweepSpread", "xaeronav.sweepTag", "xaeronav.sweepBoxes", "xaeronav.sweepDir", "xaeronav.caveLayers", "xaeronav.farScales", "xaeronav.rounds", "xaeronav.warmup", "xaeronav.voxelFollow", "xaeronav.alongPoints", "xaeronav.flightCell", "xaeronav.flightWeight", "xaeronav.noReplan", "xaeronav.noVCut", "xaeronav.replanKeep").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// Tests run only on the canonical node. The pathfinding core depends on neither the loader nor the MC version
// (the iron rule of never writing `//?` in `pathfinding/`), so it gives the same result on any node, and
// running it on all nodes would just multiply CI time. Compilation runs on all nodes.
val canonicalNode = node.properties.get<String>("canonical_test_node")
val isCanonicalNode = node.current.project == canonicalNode
// Test helper classes are written against the canonical node's Minecraft API and can't compile on other versions.
// They won't run anyway, so compile only on the canonical node too
tasks.named<JavaCompile>("compileTestJava") {
    onlyIf("Tests are compiled and run only on the canonical node ($canonicalNode)") { isCanonicalNode }
}
tasks.withType<Test>().configureEach {
    onlyIf("Runs only on the canonical node ($canonicalNode)") { isCanonicalNode }

    // Run tests in a throwaway directory. If this were the repository root, Minecraft's log4j config on the
    // classpath would write to `logs/` directly under the root, and rotated logs would keep piling up every
    // time tests run
    workingDir = layout.buildDirectory.dir("test-run").get().asFile
    doFirst {
        workingDir.mkdirs()
    }

    // Base for tests that read the source tree (language files etc.). Written as a path relative to the
    // working directory, it breaks as soon as the working directory is moved as above
    systemProperty("xaeronav.projectRoot", rootProject.projectDir.absolutePath)
}

// The distribution jar's file name contains the version (+ the short git hash), so each build adds a jar
// with a different name. Gradle only tracks the task's single output "file", so nobody deletes the past
// generations left beside it, and they keep piling up in build/libs.
//
// Delete only "the same artifact, different version". Other kinds with the same version
// (-dev or -sources produced by loom) are kept.
tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion = archiveVersionFor(loader, minecraftVersion)

    doFirst {
        val directory = destinationDirectory.get().asFile
        val currentVersion = archiveVersion.get()
        val prefix = archiveBaseName.get() + "-"
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(".jar")
                    && !file.name.contains(currentVersion)
        }?.forEach { it.delete() }
    }
}
