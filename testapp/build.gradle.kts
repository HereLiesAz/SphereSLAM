import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// On-device test bench for SphereSLAM (docs/TESTAPP.md). Builds against the library source, is
// never published, and reports each run to this repository's issues through the workflows Worker.
plugins {
    alias(libs.plugins.android.application)
}

// The tested build: `git describe` of this checkout (e.g. 0.26.3-2-g8c2ae09), so a report names the
// exact library source it ran.
val describe: String = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

android {
    namespace = "com.hereliesaz.sphereslam.testapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.sphereslam.testapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1"
        // Test phones are 64-bit ARM; every ABI made the debug APK ~190 MB.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "SPHERESLAM_BUILD", "\"${describe.replace(Regex("[^A-Za-z0-9._-]"), "_")}\"")
        buildConfigField("String", "SPHERESLAM_VERSION", "\"${describe.substringBefore('-')}\"")
        buildConfigField("String", "REPORT_URL", "\"https://workflows.hereliesaz.workers.dev/test-report/SphereSLAM\"")
        // Ships in the APK: a spam filter for the Worker route, not a secret (see the workflows repo).
        buildConfigField("String", "REPORT_KEY", "\"sphereslam-test-reports-v1\"")
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
