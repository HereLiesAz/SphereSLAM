import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// On-device test bench for SphereSLAM (docs/TESTAPP.md). Builds against the library source, is
// never published, and reports each run to this repository's issues through the workflows Worker.
plugins {
    alias(libs.plugins.android.application)
}

// The tested build: `git describe` of this checkout (e.g. 0.26.3-2-g8c2ae09), so a report names the
// exact library source it ran. The artoolkitx submodule stores CRLF files under `eol=lf`, so a fresh
// checkout always looks modified there; its working tree is not ours to dirty the name with.
val describe: String = providers.exec {
    commandLine("git", "-c", "submodule.third_party/artoolkitx.ignore=dirty", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

// Every build gets a higher versionCode (the commit count), so Android installs it as an update.
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

android {
    namespace = "com.hereliesaz.sphereslam.testapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.sphereslam.testapp"
        minSdk = 26
        targetSdk = 37
        versionCode = commitCount
        versionName = describe
        // Test phones are 64-bit ARM; every ABI made the debug APK ~190 MB.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "SPHERESLAM_BUILD", "\"${describe.replace(Regex("[^A-Za-z0-9._-]"), "_")}\"")
        buildConfigField("String", "SPHERESLAM_VERSION", "\"${describe.substringBefore('-')}\"")
        buildConfigField("String", "REPORT_URL", "\"https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM\"")
        // Ships in the APK: a spam filter for the Worker route, not a secret (see the workflows repo).
        buildConfigField("String", "REPORT_KEY", "\"sphereslam-test-reports-v1\"")
    }

    // One fixed key for every build, local or CI, so a new APK installs over the old one instead of
    // failing on a signature mismatch. A test-bench key in a public repo: it signs nothing else.
    signingConfigs {
        getByName("debug") {
            storeFile = file("testapp.keystore")
            storeType = "PKCS12"
            storePassword = "sphereslam-testapp"
            keyAlias = "sphereslam-testapp"
            keyPassword = "sphereslam-testapp"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // ScreenLayoutTest inflates the real resources (launcher icon) under Robolectric.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":reloc"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi")
    }
}

// Robolectric reaches into FileDescriptor internals (and jdk.internal.access), closed by default on JDK 17+.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
