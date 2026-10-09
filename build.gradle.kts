import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import java.util.Properties

// SphereSLAM root build. AGP 9 provides built-in Kotlin support, so no kotlin-android plugin is
// applied; per-module builds set the Kotlin JVM target via the KotlinCompile task.
plugins {
    alias(libs.plugins.android.library) apply false
}

// Single source of the library version: version.properties (major.minor.patch; versionBuild is
// tooling metadata and is not part of the published version).
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val libraryVersion = listOf("versionMajor", "versionMinor", "versionPatch").joinToString(".") { key ->
    requireNotNull(versionProps.getProperty(key)) { "version.properties is missing $key" }.trim()
}

// JitPack coordinates: com.github.HereLiesAz.SphereSLAM:<module>:<tag>. JitPack overrides version
// with the git tag at build time; this is the fallback for local publishToMavenLocal.
allprojects {
    group = "com.github.HereLiesAz.SphereSLAM"
    version = libraryVersion
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
        apply(plugin = "maven-publish")
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        artifactId = project.name
                    }
                }
            }
        }
    }
}
