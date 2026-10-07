import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.plugins.JavaPluginExtension

// SphereSLAM root build. AGP 9 provides built-in Kotlin support, so no kotlin-android plugin is
// applied; per-module builds set the Kotlin JVM target via the KotlinCompile task.
plugins {
    alias(libs.plugins.android.library) apply false
}

// JitPack coordinates: com.github.HereLiesAz.SphereSLAM:<module>:<tag>. JitPack overrides version
// with the git tag at build time; this is the fallback for local publishToMavenLocal.
allprojects {
    group = "com.github.HereLiesAz.SphereSLAM"
    version = "0.22.0"
}

// Publishing convention: every Android library module gets a maven-publish `release` publication,
// so JitPack (and `publishToMavenLocal`) emit a consumable AAR per module.
subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
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
