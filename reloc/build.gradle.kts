import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hereliesaz.sphereslam.reloc"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // :reloc intentionally exposes OpenCV-backed types; publish OpenCV on the consumer compile classpath.
    api(libs.opencv)
    implementation(libs.androidx.core.ktx)
    // The pure-Kotlin photosphere map/grid/glow the session facade orchestrates and exposes in its
    // public API (one-way :reloc → :sphereslam), so consumers get those types transitively.
    api(project(":sphereslam"))
    testImplementation(libs.junit)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi")
    }
}
