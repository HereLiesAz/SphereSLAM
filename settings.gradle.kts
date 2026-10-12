pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: JitPack's shared builders get rate-limited by Central
        // (HTTP 429), and Gradle disables a repository for the rest of the build after one such error.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first: JitPack's shared builders get rate-limited by Central
        // (HTTP 429), and Gradle disables a repository for the rest of the build after one such error.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "SphereSLAM"
include(":sphereslam")
include(":core:nativebridge")
include(":core:common")
include(":models")
include(":reloc")
include(":overlay")
