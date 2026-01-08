plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.hereliesaz.sphereslam"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -frtti -fexceptions -O3")
                arguments("-DANDROID_STL=c++_shared", "-DOpenCV_DIR=" + file("../libs/opencv-4.12.0/sdk/native/jni").absolutePath)
            }
        }
        ndk {
            abiFilters.add("arm64-v8a")
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
        prefab = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("../libs/opencv-4.12.0/sdk/native/libs")
        }
    }

    publishing {
        singleVariant("release") {}
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("dev.romainguy:kotlin-math:1.5.3")
    // Using TFLite 2.14.0 - known to support Prefab
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.HereLiesAz"
                artifactId = "SphereSLAM"
                version = "1.0.0"
            }
        }
    }
}
