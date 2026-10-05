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
    // OpenCV Java API: ORB, BFMatcher, Calib3d.solvePnPRansac — the whole reloc in Kotlin, no JNI.
    implementation(libs.opencv)
    implementation(libs.androidx.core.ktx)
    // Optional: MiDaS depth for off-plane (sphere) placement. Depend on the perception module.
    implementation(project(":models"))
    testImplementation(libs.junit)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
