# **Correction Protocol: SphereSLAM-Android**

**Status:** EMERGENCY RECOVERY
**Objective:** Systematically replace "Stub", "Mock", and "Conceptual" code with functional implementations.
**Execution Order:** Sequential (Phase 1 -> Phase 9). Do not skip phases.

---

## **Phase 1: Stop the Crashing (Frontend & Memory)**
*Target: `MainActivity.kt`, `native-lib.cpp`*

* [ ] **Fix Memory Passing:** Modify `MainActivity.kt` to extract the `ByteBuffer` address (`buffer.address()`) from `image.planes[0]` instead of passing `0L`.
* [ ] **Fix Race Condition:** Remove `image.close()` from the immediate callback. Implement a thread-safe queue or a `releaseFrame()` JNI callback to close the image only after C++ has processed it.
* [ ] **Fix Native Pointer Cast:** In `native-lib.cpp`, stop casting the `jlong` address to `cv::Mat*`. Wrap the raw bytes in a new `cv::Mat` header: `cv::Mat(height, width, CV_8UC1, (void*)addr)`.
* [ ] **Data Persistence:** Clone the wrapped Mat before pushing it to the processing queue to prevent data corruption when the Java GC collects the buffer.

## **Phase 2: Build System & Dependencies**
*Target: `build.gradle.kts`, `CMakeLists.txt`*

* [ ] **Fix SDK Hallucination:** Downgrade `compileSdk` from **36** to **34** in both `app/build.gradle.kts` and `sphereslam/build.gradle.kts`.
* [ ] **Enable Prefab:** Ensure `buildFeatures { prefab = true }` is set in the `sphereslam` module.
* [ ] **Fix CMake Linking:** In `sphereslam/src/main/cpp/CMakeLists.txt`:
    * Remove hardcoded `include_directories` pointing to missing `libs/opencv...`.
    * Use `find_package` to locate `libtensorflowlite_c` and `libtensorflowlite_gpu_delegate` (via Prefab).
    * Link these real libraries to the `sphereslam` target.

## **Phase 3: Real Neural Inference (Depth)**
*Target: `DepthAnyCamera.cpp`, `DepthAnyCamera.h`*

* [ ] **Remove Mocks:** Delete the `extern "C"` block in `DepthAnyCamera.cpp` that mocks the TFLite C API.
* [ ] **Include Headers:** `#include "tensorflow/lite/c/c_api.h"` and `<tensorflow/lite/delegates/gpu/delegate.h>`.
* [ ] **Implement Initialization:**
    * Use `TfLiteModelCreateFromFile` to load the actual `.tflite` model.
    * Initialize `TfLiteInterpreterOptions` with 4 threads and the GPU delegate.
* [ ] **Implement `estimateDepth`:**
    * Resize input to model resolution (512x256).
    * Normalize pixel data (0.0 - 1.0).
    * Copy to input tensor: `TfLiteTensorCopyFromBuffer`.
    * Invoke Interpreter.
    * Read output tensor to `std::vector<float>`.

## **Phase 4: Densification Logic (The Bridge)**
*Target: `Densifier.cpp`, `Densifier.h`*

* [ ] **Implement Back-Projection:** In `Densifier::DensifyKeyFrame`:
    * Iterate over depth map pixels.
    * **Unproject:** Use `CubeMapCamera::Unproject` to get direction vectors.
    * **Scale:** Multiply vector by metric depth `d`.
    * **Transform:** Apply KeyFrame Pose `Tcw` (Inverse) to get World Point.
* [ ] **Generate Gaussians:**
    * Create `Gaussian` structs with calculated World Position.
    * Derive `Scale` from depth (heuristic).
    * Sample `Color` from KeyFrame image.

## **Phase 5: SLAM Initialization (The Spark)**
*Target: `Initializer.cpp`*

* [ ] **Implement Solvers:** Implement `cv::findHomography` and `cv::findFundamentalMat` (RANSAC) in `Initialize()`.
* [ ] **Model Selection:** Compute scoring to choose between Planar (H) and General (F) initialization.
* [ ] **Triangulation:**
    * Decompose the best matrix into Motion (R, t).
    * Triangulate 3D points for matches.
    * Check chirality (points must be in front of cameras).
* [ ] **Commit:** Set `R21`, `t21`, and `vP3D` only if a stable solution is found.

## **Phase 6: The Optimizer (The Brain)**
*Target: `Optimizer.cpp`*

* [ ] **Implement Local Bundle Adjustment:**
    * Initialize `g2o::SparseOptimizer` with `BlockSolver_6_3` and Levenberg-Marquardt.
    * Add `VertexSE3Expmap` for KeyFrames (Local Window).
    * Add `VertexSBAPointXYZ` for MapPoints.
    * Add `EdgeSE3ProjectXYZ` for observations.
* [ ] **Execution:**
    * Run 5 iterations.
    * Cull outliers (Chi2 test).
    * Run 10 more iterations.
    * Write optimized poses/points back to data structures.

## **Phase 7: Loop Closing & Vocabulary**
*Target: `ORBVocabulary.h`, `LoopClosing.cpp`*

* [ ] **Real Vocabulary:** Implement `ORBVocabulary::loadFromTextFile` to actually load DBoW2/FBoW file.
* [ ] **Loop Detection:** Query `KeyFrameDatabase` using Bag-of-Words vectors in `DetectLoop`.
* [ ] **Loop Correction:**
    * Implement **Sim3 Optimization** (Scale+Rotation+Translation) in `CorrectLoop`.
    * Fuse duplicate MapPoints.
    * Trigger Pose Graph Optimization.

## **Phase 8: Vulkan Compute (The Pipeline)**
*Target: `VulkanCompute.cpp`*

* [ ] **Fix Input Binding:** In `processImage`:
    * Implement logic to copy input buffer to a `VkImage` (Staging -> Device Local).
* [ ] **Update Descriptors:**
    * Create/Update `VkImageView`.
    * Update `VkDescriptorImageInfo`.
    * Call `vkUpdateDescriptorSets` to bind the new image to Binding 0.
* [ ] **Barriers:** Record `vkCmdPipelineBarrier` to transition layout to `SHADER_READ_ONLY_OPTIMAL` before dispatch.

## **Phase 9: MobileGS Rendering (The Display)**
*Target: `MobileGS.cpp`*

* [ ] **Sorting:** Implement Depth Sorting in `draw()` (CPU `std::sort` or GPU Radix).
* [ ] **Vertex Shader:** Update `gsVertexShader` string to implement EWA Splatting (Covariance -> Screen Space Bounds).
* [ ] **Blending:** Enable OpenGL Alpha Blending (`GL_SRC_ALPHA`, `GL_ONE_MINUS_SRC_ALPHA`).
