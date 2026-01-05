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

**Important:** This repository requires the installation of third-party libraries (OpenCV, Eigen, GLM) to compile. The dependencies are git-ignored to prevent repository bloat. Follow the instructions below to install them.

### 1. OpenCV Android SDK
*   **Action Required:**
    1.  Download the **OpenCV 4.10 Android SDK**.
    2.  Extract `OpenCV-android-sdk` to `libs/opencv` (so that `libs/opencv/sdk/native` exists).

### 2. Eigen & GLM
*   **Action Required:**
    1.  Download **Eigen 3.4.0** and extract to `app/src/main/cpp/Thirdparty/eigen` (header-only).
    2.  Download **GLM 1.0.1** and extract to `app/src/main/cpp/Thirdparty/glm` (header-only).

### 3. TensorFlow Lite (LiteRT)
*   **Status:** Configured via Gradle Prefab. `DepthAnyCamera` uses the real C API.

### 4. Building
Once dependencies are resolved:
1.  Open the project in **Android Studio**.
2.  Sync Gradle.
3.  Build and Run on an Android Device (Android 10+ recommended).

## Usage

1.  **Grant Permissions:** Allow Camera access.
2.  **Start Scanning:** The app will automatically start processing frames.
3.  **Visualization:**
    *   The screen displays the real-time Gaussian Splat rendering of the map.
    *   **Touch:** Drag to orbit the virtual camera around the current SLAM pose.
4.  **Reset:** Press the "Reset" button to clear the map and restart the tracker.
5.  **Stats:** Monitor KeyFrame (KF) and MapPoint (MP) counts in the top-left corner.

## License

This project is licensed under the [MIT License](LICENSE).
