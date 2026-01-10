# SphereSLAM
[![](https://jitpack.io/v/HereLiesAz/sphereslam.svg)](https://jitpack.io/#HereLiesAz/sphereslam)

**SphereSLAM** is a lightweight, on-device Monocular Spherical SLAM and Dense Reconstruction library for Android, with experimental support for iOS and Web (Wasm). It converts user-captured 360° photospheres (equirectangular projection) into navigable 3D world scenes using heterogeneous computing (CPU, GPU, NPU).

## Features

*   **Spherical SLAM:** Modified ORB-SLAM3 backend adapted for CubeMap projection to handle 360° field of view without severe polar distortion.
*   **Vulkan Compute:** GPU-accelerated conversion of Equirectangular images to CubeMap faces.
*   **Neural Depth:** Integration structure for **Depth Any Camera (DAC)** foundation models (TFLite) for zero-shot metric scale recovery.
*   **Mobile Gaussian Splatting:** Real-time visualization using a lightweight 3D Gaussian Splatting renderer (`MobileGS`).
*   **Photosphere Capture:** Capture 360-degree environment maps (as PPM files) from the SLAM system in all supported platforms.
*   **Map Persistence:** Robust Save/Load functionality for SLAM maps (Poses and Sparse Point Cloud), with Android UI integration.
*   **Cross-Platform Core:** Shared C++ core (`core/`) used by Android, iOS, and Web modules.

## Installation

Add the JitPack repository to your build file:

**settings.gradle.kts:**
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**build.gradle.kts (app):**
```kotlin
dependencies {
    implementation("com.github.HereLiesAz:SphereSLAM:1.0.0")
}
```

## Usage

### Android

#### 1. Initialize SphereSLAM
In your Activity or Fragment:

```kotlin
import com.hereliesaz.sphereslam.SphereSLAM

class MainActivity : AppCompatActivity() {
    private lateinit var sphereSLAM: SphereSLAM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the library
        sphereSLAM = SphereSLAM(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up native resources
        sphereSLAM.cleanup()
    }
}
```

#### 2. Process Frames
Pass camera frames and timestamps to the system. The library provides a `SphereCameraManager` helper, or you can implement your own camera logic.

```kotlin
cameraManager = SphereCameraManager(this) { image ->
    // Pass the image timestamp
    sphereSLAM.processFrame(0L, image.timestamp.toDouble())
    image.close()
}
```

#### 3. Rendering
SphereSLAM renders directly to a `Surface`. Pass the Surface from a `SurfaceView` or `TextureView`.

```kotlin
override fun surfaceCreated(holder: SurfaceHolder) {
    sphereSLAM.setNativeWindow(holder.surface)
}

override fun doFrame(frameTimeNanos: Long) {
    // Call renderFrame() in your Choreographer callback or render loop
    sphereSLAM.renderFrame()
}
```

### Web (Experimental)

The web module uses Emscripten to compile the core C++ logic to WebAssembly.
See `web_app/` for a simple HTML/JS integration example.

To build:
```bash
./scripts/build_web.sh
```

### iOS (Experimental)

The iOS module uses an Objective-C++ bridge to link the C++ core with Swift.
See `ios_app/` for the Xcode project structure.

## Architecture

| Component | Language | Description |
| :--- | :--- | :--- |
| **Android Frontend** | Kotlin | Handles Camera2 API, IMU Sensors, Permissions, and UI. |
| **JNI Bridge** | C++ | Bridges Java calls to the native System controller. |
| **SLAM Core** | C++ | `System`, `Tracking`, `LocalMapping`, `LoopClosing`. Implements the sparse tracking and mapping logic. |
| **VulkanCompute** | C++/GLSL | Handles GPU image transformations. |
| **MobileGS** | C++/GLES | Renders the 3D Gaussian Splats to a `SurfaceView`. |

## Requirements

*   **Android SDK:** API Level 29 (Android 10) or higher.
*   **NDK:** Version 29.0.14206865.
*   **Hardware:** Device with Vulkan and NPU support recommended (Snapdragon 8 Gen 2+).

## License

This project is licensed under the [MIT License](LICENSE).
