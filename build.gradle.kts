import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

// SphereSLAM root build. AGP 9 provides built-in Kotlin support, so no kotlin-android plugin is
// applied; per-module builds set the Kotlin JVM target via the KotlinCompile task.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// Single source of the library version: version.properties (major.minor.patch; versionBuild is
// tooling metadata and is not part of the published version).
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val libraryVersion = listOf("versionMajor", "versionMinor", "versionPatch").joinToString(".") { key ->
    requireNotNull(versionProps.getProperty(key)) { "version.properties is missing $key" }.trim()
}

// JitPack coordinates: com.github.HereLiesAz.SphereSLAM:<module>:<tag>. The project version always
// comes from version.properties (JitPack does not rewrite it): JitPack builds whatever version that
// file states and publishes the artifacts under the requested tag, so tag releases only after
// version.properties matches the tag.
allprojects {
    group = "com.github.HereLiesAz.SphereSLAM"
    version = libraryVersion
}

/**
 * Copies LICENSE, NOTICE and third_party_licenses/ into META-INF/sphereslam/<module>/ as Java
 * resources, so each published AAR's classes.jar carries them under a module-unique path (no
 * duplicate-resource clash when a consumer packages several SphereSLAM AARs).
 */
/**
 * The public API of a published module, as `javap -public` prints its release classes (sorted, one
 * class per block). [apiDump] writes it to `<module>/api/<module>.api`; [apiCheck] fails when the
 * compiled API no longer matches the committed dump — the 1.0 compatibility gate (an API change must
 * be made on purpose, with a re-dump in the same change). Synthetic and `$WhenMappings`-style
 * compiler classes are left out.
 */
abstract class SphereSlamApiDump : DefaultTask() {
    @get:InputFiles
    abstract val classesDirs: ConfigurableFileCollection

    @get:Input
    abstract val javap: Property<String>

    @get:Internal
    abstract val dumpFile: RegularFileProperty

    @get:Input
    abstract val check: Property<Boolean>

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val classes = classesDirs.files.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { f ->
                f.isFile && f.extension == "class" && !f.name.contains("\$\$") &&
                    !f.name.endsWith("\$WhenMappings.class") && !f.name.startsWith("R\$") && f.name != "R.class" &&
                    f.name != "BuildConfig.class"
            }.toList()
        }.sortedBy { it.path }
        val text = StringBuilder()
        for (chunk in classes.chunked(200)) {
            val out = java.io.ByteArrayOutputStream()
            exec.exec {
                commandLine(listOf(javap.get(), "-public") + chunk.map { it.path })
                standardOutput = out
            }
            text.append(out.toString(Charsets.UTF_8))
        }
        val blocks = text.split(Regex("(?m)^Compiled from \".*\"\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
            .filter { it.lineSequence().first().let { head -> head.startsWith("public ") } }
            .map { block -> block.lines().map { it.trimEnd() }.joinToString("\n") }
            .sorted()
        val dump = blocks.joinToString("\n\n") + "\n"
        val file = dumpFile.get().asFile
        if (check.get()) {
            val committed = if (file.exists()) file.readText() else ""
            if (committed != dump) {
                throw GradleException(
                    "Public API of ${file.parentFile.parentFile.name} changed (${file.path}). If intended, run " +
                        "`./gradlew apiDump` and commit the dump with the change."
                )
            }
        } else {
            file.parentFile.mkdirs()
            file.writeText(dump)
        }
    }
}

abstract class SphereSlamLicenseResources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFiles: ConfigurableFileCollection

    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val target = root.resolve("META-INF/sphereslam/${moduleName.get()}")
        target.mkdirs()
        licenseFiles.files.forEach { f ->
            if (f.isDirectory) f.copyRecursively(target.resolve(f.name), overwrite = true) else f.copyTo(target.resolve(f.name), overwrite = true)
        }
    }
}

// Publishing convention: every Android library module gets a maven-publish `release` publication,
// so JitPack (and `publishToMavenLocal`) emit a consumable AAR per module.
subprojects {
    plugins.withId("com.android.library") {
        // Only touch the Java extension if a Java base plugin actually registered it, so this never
        // fails on an Android module whose plugin set does not include one.
        plugins.withType<JavaBasePlugin> {
            extensions.findByType<JavaPluginExtension>()?.toolchain {
                languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
            }
        }
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            publishing { singleVariant("release") { withSourcesJar() } }
        }
        val licenseResources = tasks.register<SphereSlamLicenseResources>("sphereslamLicenseResources") {
            licenseFiles.from(
                rootProject.file("LICENSE"),
                rootProject.file("NOTICE"),
                rootProject.file("third_party_licenses"),
            )
            moduleName.set(project.path.removePrefix(":").replace(':', '-'))
            outputDir.set(layout.buildDirectory.dir("generated/sphereslamLicenses"))
        }
        extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension>("androidComponents") {
            onVariants { variant ->
                variant.sources.resources?.addGeneratedSourceDirectory(licenseResources, SphereSlamLicenseResources::outputDir)
            }
        }
        // Public API gate (see SphereSlamApiDump): apiDump writes api/<module>.api, apiCheck runs with check.
        // The JDK running Gradle (pinned to libs.versions.jdk by CI and JitPack) supplies javap.
        val javapPath = providers.systemProperty("java.home").map { "$it/bin/javap" }
        val releaseClasses = layout.buildDirectory.dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")
        val apiFile = layout.projectDirectory.file("api/${project.name}.api")
        tasks.register<SphereSlamApiDump>("apiDump") {
            dependsOn("compileReleaseKotlin")
            classesDirs.from(releaseClasses)
            javap.set(javapPath)
            dumpFile.set(apiFile)
            check.set(false)
        }
        val apiCheck = tasks.register<SphereSlamApiDump>("apiCheck") {
            dependsOn("compileReleaseKotlin")
            classesDirs.from(releaseClasses)
            javap.set(javapPath)
            dumpFile.set(apiFile)
            check.set(true)
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(apiCheck) }
        apply(plugin = "maven-publish")
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                        pom {
                            licenses {
                                license {
                                    name.set("Apache License, Version 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                    distribution.set("repo")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
