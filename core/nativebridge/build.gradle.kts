import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hereliesaz.sphereslam.nativebridge"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Native ABIs shipped. Other ABIs (x86/x86_64 emulators) get no libsphereslam.so;
        // KpmBridge.isAvailable() then reports false instead of crashing.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                // Static libc++: only JNI crosses libsphereslam's boundary, and a c++_shared build would
                // ship its own libc++_shared.so that collides with OpenCV's (exposed through :reloc's
                // api dependency) in a consumer's mergeNativeLibs.
                arguments("-DANDROID_STL=c++_static")
                // Opt-in only (see CMakeLists.txt): build KPM stubs when the artoolkitX submodule is absent.
                if (providers.gradleProperty("sphereslam.allowKpmStubs").orNull == "true") {
                    arguments("-DSPHERESLAM_ALLOW_KPM_STUBS=ON")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
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
    api(project(":core:common"))
    testImplementation(libs.junit)
    // On-device tests of the JNI session lifecycle (src/androidTest); need an arm64/armv7 device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=com.hereliesaz.sphereslam.common.InternalSphereSlamApi")
    }
}
