# SphereSLAM

**SphereSLAM** is a lightweight, on-device Monocular Spherical SLAM and Dense Reconstruction library for Android. It converts user-captured 360° photospheres (equirectangular projection) into navigable 3D world scenes using heterogeneous computing (CPU, GPU, NPU).

## Features

*   **Spherical SLAM:** Modified ORB-SLAM3 backend adapted for CubeMap projection to handle 360° field of view without severe polar distortion.
*   **Vulkan Compute:** GPU-accelerated conversion of Equirectangular images to CubeMap faces.
*   **Neural Depth:** Integration structure for **Depth Any Camera (DAC)** foundation models (TFLite) for zero-shot metric scale recovery.
*   **Mobile Gaussian Splatting:** Real-time visualization using a lightweight 3D Gaussian Splatting renderer (`MobileGS`).
*   **Photosphere Capture:** Capture 360-degree environment maps (as PPM files) from the SLAM system in all supported platforms.
*   **Easy Integration:** Available as a JitPack library for easy import into any Android project.

## Installation

Add the JitPack repository to your build file:

**settings.gradle:**
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**build.gradle (app):**
```groovy
dependencies {
    implementation 'com.github.HereLiesAz:SphereSLAM:0.4.0'
}
```

## Usage

### 1. Initialize SphereSLAM
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

### 2. Process Frames
Pass camera frames and timestamps to the system. The library provides a `SphereCameraManager` helper, or you can implement your own camera logic.

```kotlin
cameraManager = SphereCameraManager(this) { image ->
    // Pass the image timestamp (in seconds or nanoseconds depending on config)
    sphereSLAM.processFrame(0L, image.timestamp.toDouble())
    image.close()
}
```

### 3. Rendering
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
*   **Hardware:** Device with Vulkan and NPU support recommended (Snapdragon 8 Gen 2+).

## License

This project is licensed under the [MIT License](LICENSE).
