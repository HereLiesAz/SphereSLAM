import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hereliesaz.graffitixr.nativebridge"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Consume the OpenCV Maven artifact's Prefab part from CMake (find_package(OpenCV)).
    buildFeatures {
        prefab = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:common"))
    // OpenCV from Maven Central. Its Prefab part exposes the native C++ world to CMake
    // (find_package(OpenCV) -> OpenCV::opencv_java5) and auto-packages libopencv_java5.so.
    implementation(libs.opencv)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// `NativeMethodAritySignatureTest` reads GraffitiJNI.cpp as text at test time, so the C++ source is
// an explicit test input (otherwise the task stays UP-TO-DATE when only the C++ changes).
tasks.withType<Test>().configureEach {
    inputs.file("src/main/cpp/GraffitiJNI.cpp")
        .withPropertyName("jniSourceForAritySignatureTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
