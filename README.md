# SphereSLAM-Droid

**SphereSLAM-Droid** is a lightweight, on-device Monocular Spherical SLAM and Dense Reconstruction system for Android. It is designed to convert user-captured 360° photospheres (equirectangular projection) into navigable 3D world scenes using heterogeneous computing.

## Features

*   **Spherical SLAM:** Modified ORB-SLAM3 backend adapted for CubeMap projection to handle 360° field of view without severe polar distortion.
*   **Vulkan Compute:** GPU-accelerated conversion of Equirectangular images to CubeMap faces.
*   **Neural Depth:** Integration structure for **Depth Any Camera (DAC)** foundation models (TFLite) for zero-shot metric scale recovery.
*   **Mobile Gaussian Splatting:** Real-time visualization using a lightweight 3D Gaussian Splatting renderer (`MobileGS`).
*   **Heterogeneous Compute:** Distributes workloads across CPU (Tracking), GPU (Pre-processing/Rendering), and NPU (Depth Inference).

## Architecture

The system is built as a standard Android application with a heavy native C++ backend.

| Component | Language | Description |
| :--- | :--- | :--- |
| **Android Frontend** | Kotlin | Handles Camera2 API, IMU Sensors, Permissions, and UI. |
| **JNI Bridge** | C++ | Bridges Java calls to the native System controller. |
| **SLAM Core** | C++ | `System`, `Tracking`, `LocalMapping`, `LoopClosing`. Implements the sparse tracking and mapping logic. |
| **GeometricCamera** | C++ | Implements `CubeMapCamera` projection logic (3D <-> 6 Faces). |
| **VulkanCompute** | C++/GLSL | Handles GPU image transformations. |
| **Densifier** | C++ | Converts sparse KeyFrames and dense depth maps into Gaussian Splats. |
| **MobileGS** | C++/GLES | Renders the 3D Gaussian Splats to a `SurfaceView`. |

## Prerequisites & Build Instructions

**Important:** This repository contains **Mock/Stub Headers** for external dependencies to allow the project structure to be compiled and reviewed without downloading gigabytes of binary SDKs. To build a functional application, you must replace these mocks with the actual libraries.

### 1. OpenCV Android SDK
*   **Current State:** `libs/opencv` contains a stub `core.hpp` to satisfy the compiler.
*   **Action Required:**
    1.  Download the **OpenCV 4.x Android SDK**.
    2.  Extract it.
    3.  Replace the `libs/opencv` folder (or update `CMakeLists.txt` paths) with the actual SDK content.
    4.  Ensure `libopencv_java4.so` is available for linking.

### 2. Eigen & GLM
*   **Current State:** `app/src/main/cpp/Thirdparty` contains stub headers for `Eigen` and `glm`.
*   **Action Required:**
    1.  Download **Eigen 3.4.0** and **GLM 0.9.9**.
    2.  Replace the contents of `app/src/main/cpp/Thirdparty/eigen` and `app/src/main/cpp/Thirdparty/glm` with the real headers.

### 3. TensorFlow Lite (LiteRT)
*   **Current State:** `DepthAnyCamera.cpp` contains stub implementations of the TFLite C API to prevent linker errors.
*   **Action Required:**
    1.  Remove the `extern "C"` stubs in `DepthAnyCamera.cpp`.
    2.  Add the official TensorFlow Lite AAR or C API dependencies to your `build.gradle` and `CMakeLists.txt` (via Prefab or manual linking).

### 4. Building
Once dependencies are resolved:
1.  Open the project in **Android Studio**.
2.  Sync Gradle.
3.  Build and Run on an Android Device (Android 10+ recommended).

## Usage

1.  **Grant Permissions:** Allow Camera and Storage access.
2.  **Start Scanning:** The app will automatically start processing frames.
3.  **Visualization:**
    *   The screen displays the real-time Gaussian Splat rendering of the map.
    *   **Touch:** Drag to orbit the virtual camera around the current SLAM pose.
4.  **Reset:** Press the "Reset" button to clear the map and restart the tracker.
5.  **Stats:** Monitor KeyFrame (KF) and MapPoint (MP) counts in the top-left corner.

## License

[License Information Here]
