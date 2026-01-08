# **Correction Protocol: SphereSLAM-Android**

**Status:** EMERGENCY RECOVERY
**Objective:** Systematically replace "Stub" code with functional implementations.
**Constraint:** All features, especially **Photosphere Creation**, must be production-ready, not conceptual.
**Execution Order:** Sequential (Phase 1 -> Phase 10).

---

## **Phase 1: Fix Frontend Crash & Memory Safety**
*Target: `MainActivity.kt`, `native-lib.cpp`*

* [ ] **Fix Pointer Passing (Kotlin):** In `MainActivity.kt`, modify `processFrame` to:
    * Get the `ByteBuffer` from `image.planes[0]`.
    * Pass `buffer.address()` (direct memory address) to JNI instead of `0L`.
* [ ] **Fix Race Condition (Kotlin):**
    * **Stop** calling `image.close()` immediately in the camera callback.
    * Implement a `SafeImageReader` queue that waits for a JNI signal (`releaseFrame()`) before closing the image.
* [ ] **Fix Native Cast (C++):** In `native-lib.cpp`:
    * Accept `jlong matAddr`.
    * Cast to `uint8_t*` (raw bytes), **not** `cv::Mat*`.
    * Construct a wrapping `cv::Mat(h, w, CV_8UC1, ptr)`.
    * **Clone** the data (`faces.push_back(im.clone())`) to persist it for the background SLAM thread.

## **Phase 2: Build System & Dependencies**
*Target: `sphereslam/build.gradle.kts`, `sphereslam/src/main/cpp/CMakeLists.txt`*

* [ ] **Retain SDK 36:** Keep `compileSdk = 36` (Android 16).
* [ ] **Enable Prefab:** Ensure `buildFeatures { prefab = true }` is active in `sphereslam/build.gradle.kts`.
* [ ] **Link Real Libraries (CMake):**
    * Remove hardcoded paths to `libs/opencv...`.
    * Use `find_package(OpenCV REQUIRED)` (or `libopencv_java4` via Prefab).
    * Use `find_package(TensorFlowLiteC REQUIRED)` (via `com.google.ai.edge.litert`).
    * Link these targets to `sphereslam`.

## **Phase 3: Real Neural Inference (Depth)**
*Target: `DepthAnyCamera.cpp`, `DepthAnyCamera.h`*

* [ ] **Un-Mock TFLite:** Delete the `extern "C"` mock block.
* [ ] **Real Headers:** `#include "tensorflow/lite/c/c_api.h"`.
* [ ] **Implement `initialize()`:**
    * Load model using `TfLiteModelCreateFromFile`.
    * Enable GPU Delegate (`TfLiteGpuDelegateV2Create`).
    * Create `TfLiteInterpreter`.
* [ ] **Implement `estimateDepth()`:**
    * Resize input `cv::Mat` to 512x256.
    * Normalize floats (0.0 - 1.0).
    * `TfLiteTensorCopyFromBuffer` -> `TfLiteInterpreterInvoke` -> Read Output.

## **Phase 4: Densification (The Bridge)**
*Target: `Densifier.cpp`*

* [ ] **Implement Back-Projection:** In `DensifyKeyFrame`:
    * Loop through Depth Map pixels.
    * **Unproject:** `CubeMapCamera::Unproject(u,v)` -> Unit Vector.
    * **Scale:** `Vector * Depth`.
    * **Transform:** `KeyFrame Pose Inverse * Point` -> World Coordinate.
* [ ] **Generate Gaussians:**
    * Store World Position.
    * Calculate Scale (heuristic based on depth).
    * Sample Color from KeyFrame.

## **Phase 5: SLAM Initialization**
*Target: `Initializer.cpp`*

* [ ] **Implement Solvers:**
    * Run `cv::findHomography` and `cv::findFundamentalMat` (RANSAC) on matches.
* [ ] **Model Selection:** Score H vs F to detect planar scenes.
* [ ] **Reconstruction:**
    * Decompose best matrix to R, t.
    * Triangulate initial MapPoints.
    * **Commit:** Set `R21`, `t21`, `vP3D` and return `true` only on success.

## **Phase 6: The Optimizer (Bundle Adjustment)**
*Target: `Optimizer.cpp`*

* [ ] **Implement `LocalBundleAdjustment`:**
    * Setup `g2o::SparseOptimizer` (BlockSolver_6_3, Levenberg-Marquardt).
    * **Vertices:** `VertexSE3Expmap` (KeyFrames), `VertexSBAPointXYZ` (MapPoints).
    * **Edges:** `EdgeSE3ProjectXYZ` (Observations).
    * **Optimize:** 2-Stage optimization (Filter Outliers via Chi2 test).
    * **Update:** Write optimized values back to KeyFrames/MapPoints.

## **Phase 7: Loop Closing**
*Target: `LoopClosing.cpp`, `ORBVocabulary.h`*

* [ ] **Load Vocabulary:** Implement `ORBVocabulary::loadFromTextFile` (using DBoW2).
* [ ] **Detect Loop:** Query `KeyFrameDatabase` for BoW candidates.
* [ ] **Correct Loop:**
    * Compute **Sim3** (7DoF) alignment.
    * Fuse duplicated points.
    * Perform Essential Graph Optimization.

## **Phase 8: Vulkan Pipeline**
*Target: `VulkanCompute.cpp`*

* [ ] **Implement `processImage`:**
    * Copy input `cv::Mat` data to a Staging Buffer -> Device Local `VkImage`.
* [ ] **Descriptor Binding:**
    * Update `VkDescriptorImageInfo` with the new input view.
    * `vkUpdateDescriptorSets` to bind to Binding 0.
* [ ] **Pipeline Barriers:** Ensure correct layout transitions (`TRANSFER_DST` -> `SHADER_READ_ONLY`) before Dispatch.

## **Phase 9: MobileGS Rendering**
*Target: `MobileGS.cpp`*

* [ ] **Sorting:** Implement CPU-based Depth Sorting in `draw()` (Back-to-Front).
* [ ] **Vertex Shader:** Write actual EWA Splatting math (Covariance -> Screen Space Bounds/Axes) in the shader string.
* [ ] **Blending:** Enable `GL_BLEND` with `SRC_ALPHA`, `ONE_MINUS_SRC_ALPHA`.

## **Phase 10: Photosphere Creation & Export**
*Target: `System.cpp`, `System.h`*

* [ ] **Implement Stitching Engine:** Replace the `hconcat` stub in `SavePhotosphere`.
    * If `mSensor == MONOCULAR`: Implement a **Renderer** pass that projects the dense Gaussian Splat map (or KeyFrame mesh) onto a virtual Equirectangular sphere.
    * If `mSensor == 360_INPUT`: Ensure the faces are re-projected to Equirectangular (Inverse CubeMap) correctly.
* [ ] **Automation:** Ensure `savePhotosphere` can be called at any time (even during tracking) and produces a valid 2:1 JPG image.
* [ ] **Verify API:** Ensure `SphereSLAM::savePhotosphere` JNI binding handles the file I/O and threading correctly (blocking the UI thread is forbidden; use a future/callback).
