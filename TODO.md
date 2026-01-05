# **Implementation Protocol: SphereSLAM-Android**

**A Granular Engineering Roadmap for Real-Time Spherical SLAM and Dense Neural Reconstruction on Mobile Architecture**

**Abstract**
This document serves as a comprehensive implementation guide (build protocol) for **SphereSLAM**, an Android-native system capable of ingesting user-captured photospheres (equirectangular panoramas), establishing 6-DoF tracking via a modified ORB-SLAM3 backend, and densifying the sparse map into a photorealistic 3D Gaussian Splatting (3DGS) scene using on-device NPU inference. The protocol is structured as a sequential engineering checklist, moving from environment configuration to kernel-level optimization.

---

## **Phase 1: Development Environment & Toolchain Configuration**

The foundation requires a hybrid build system capable of linking Kotlin (UI/Camera), C++ (SLAM/Vulkan), and Python (Model Export).

### **1.1 Android Studio Native Setup**

* [x] **Install NDK Side-by-Side:** via SDK Manager, install **NDK version r26b** (LTS). Avoid r27+ for now due to breaking changes in legacy C++ standard headers used by ORB-SLAM.
* [x] **CMake Configuration:** Install **CMake 3.22.1**.
* [x] **Project Structure:** Create a new project with "Native C++" template.
* `app/src/main/cpp`: Will host SLAM and Renderer code.
* `app/src/main/assets`: Will host TFLite models and ORB vocabulary.
* `app/src/main/java`: Camera2 API and UI logic.


* [x] **Dependencies (build.gradle):**
```groovy
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17 -frtti -fexceptions -O3"
                arguments "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters 'arm64-v8a' // Drop 32-bit support for performance
        }
    }
}
dependencies {
    implementation 'dev.romainguy:kotlin-math:1.5.3' // Efficient vector math
    implementation 'com.google.ai.edge.litert:litert:1.0.1' // For Depth/GS inference
    implementation 'com.google.ai.edge.litert:litert-gpu:1.0.1'
}

```



### **1.2 Third-Party Library Compilation (Pre-Build)**

*Note: Do not try to compile these as submodules inside Android Studio. Pre-compile them into `.so` (shared object) files using CLI CMake.*

* [x] **OpenCV 4.10 Android SDK:** Download pre-built Android SDK. Extract to `libs/opencv`.
* [x] **Eigen 3.4.0:** Header-only. Copy to `app/src/main/cpp/Thirdparty/eigen`.
* [x] **Sophus:** Clone commit `a621ff` (template-based Lie Algebra). Copy to `Thirdparty`.
* [x] **DBoW2:** Clone standard repo. Modify `CMakeLists.txt` to remove `march=native` (causes SIGILL on Android). Build static library.
* [x] **g2o:** Clone standard repo. Critical modification: In `g2o/core/sparse_block_matrix.h`, ensure `Eigen::aligned_allocator` is used to prevent memory misalignment crashes on ARM64.

---

## **Phase 2: The Spherical Vision Pipeline (Frontend)**

User input is an equirectangular image. SLAM prefers pinhole. We must bridge this gap via GPU compute.

### **2.1 Camera2 API Integration**

* [x] **Manifest:** Request `CAMERA`, `HIGH_SAMPLING_RATE_SENSORS`.
* [x] **Surface Setup:** Create two surfaces:
1. `ImageReader` (YUV_420_888): For SLAM tracking (grayscale). Resolution: ~1280x720 equivalent.
2. `ImageReader` (JPEG/RAW): For user photosphere capture (4K+).


* [x] **Sensor Sync:** Register `SensorEventListener` for **GYROSCOPE** and **ACCELEROMETER**.
* [x] Timestamp Synchronization: Convert `sensorEvent.timestamp` (nanoseconds boot time) to match `cameraCaptureResult.sensorTimestamp`.
* [x] **Critical:** Store IMU measurement in a lock-free ring buffer (e.g., `boost::circular_buffer` equivalent) to pass to the C++ SLAM thread.



### **2.2 Vulkan Compute Shader: Equirectangular to CubeMap**

CPU conversion is too slow (100ms+). Use Vulkan.

* [x] **Shader (comp.spv):** Write a GLSL compute shader.
* **Inputs:** `sampler2D inputEquirect` (The photosphere).
* **Outputs:** `image2D outputFaces[1]` (The 6 faces of the cube).
* **Logic:**
```glsl
// For a pixel (u,v) on Face F:
vec3 dir = GetDirectionVector(faceIndex, u, v); // standard cubemap math
vec2 sphericalUV = CartesianToSpherical(dir);
imageStore(outputFaces[faceIndex], ivec2(u,v), texture(inputEquirect, sphericalUV));

```




* [x] **Vulkan Pipeline:**
* [x] Allocate `AHardwareBuffer` for zero-copy access.
* [x] Bind the Input Bitmap as a texture.
* [x] Dispatch Compute (Group size 16x16).
* [x] Fence sync: Wait for GPU completion before signaling SLAM thread.



---

## **Phase 3: Sparse SLAM Engine (Modified ORB-SLAM3)**

We are stripping ORB-SLAM3 down to a "CubeMap-VIO" engine.

### **3.1 Architecture Pruning**

* [x] **Remove Viewer:** Delete `Pangolin` dependency completely. We will render our own visualization.
* [x] **Remove Atlas:** We only need the active map for this tool. Disable multi-map merging to save RAM.

### **3.2 Implementing the CubeMap Camera Model**

ORB-SLAM3 has `Pinhole` and `KannalaBrandt`. You must add `CubeMap`.

* [x] **Class `GeometricCamera.h`:** Add `CubeMapCamera` class.
* [x] **Projection Function (`Project`):**
* Input: 3D Point .
* Logic:
1. Determine Face ID: `max(abs(x), abs(y), abs(z))`.
2. Divide by depth (z') to get normalized coordinates.
3. Apply intrinsics  (fx, fy, cx, cy) for that face (usually 90 deg FOV).




* [x] **Unprojection Function (`Unproject`):**
* Input: Pixel  and Face ID.
* Logic: Inverse , normalize, rotate according to Face ID to get unit vector.



### **3.3 DSP Acceleration (Hexagon SDK)**

*Optional but recommended for >30FPS.*

* [x] **FastCV Integration:** Replace `ORBextractor.cc` Gaussian Blur and FAST corner detection calls with Qualcomm `FastCV` equivalents (`fcvPyramidCreate`, `fcvCornerFast10`).
* [x] **NEON Optimization:** If not using FastCV, ensure `ORBextractor.cc` is compiled with `-mfpu=neon`.

---

## **Phase 4: Dense Metric Reconstruction (NPU)**

Turning sparse points into a dense world using **Depth Any Camera (DAC)** foundation model.

### **4.1 Model Export (Python Host Side)**

* [x] **Download:** Metric3D or Depth-Any-Camera (ViT-Small variant).
* [x] **Export to ONNX:**
```python
dummy_input = torch.randn(1, 3, 512, 1024) # Equirect resolution
torch.onnx.export(model, dummy_input, "dac_360.onnx", opset_version=17)

```


* [x] **Quantize:** Use `onnx2tf` or `ai_edge_torch` to convert to TFLite Int8. *Crucial:* Do not use FP32 on mobile NPU; latency will be >1s. Int8 gets ~150ms.

### **4.2 Android Inference (LiteRT)**

* [x] **TFLite Interpreter:** Initialize `InterpreterApi` with `NnApiDelegate` (Android 10+) or `GpuDelegate`.
* [x] **Inference Loop:**
1. User captures Photosphere.
2. Downscale to 512x1024 (Model input).
3. Run Inference -> Get `OutputTensor` (Depth Map).
4. **Inverse Projection:** For every pixel  in depth map with depth :
*




* [x] **Scale Alignment:** Calculate the scale factor `s` by comparing SLAM feature depths with Model depths.
*
* Scale the entire SLAM trajectory by `s` to get real-world meters.



---

## **Phase 5: Rendering the Scene (Mobile 3DGS)**

Instead of meshing, we use Gaussian Splats for visualization.

### **5.1 Splatter Image (Instant Gen)**

* [x] **Input:** The single photosphere + Depth Map.
* [x] **Generation:** Instead of optimization (training), we procedurally generate Gaussians.
* **Position:** Unprojected pixel `(u,v)`.
* [x] **Color:** Pixel RGB.
* [x] **Scale:** Derived from local depth gradient (larger scale for distant pixels).
* [x] **Opacity:** 1.0 initially.



### **5.2 Vulkan Renderer (The Viewer)**

Implementation of a tiled gaussian rasterizer.

* [x] **Sorting (Radix Sort):**
* Implement a GPU Radix Sort compute shader.
* Sort Gaussians by Depth (distance to virtual camera). *Optimization:* Sort only every 3-4 frames on mobile.


* [x] **Rasterizer Shader:**
* **Vertex Shader:** Compute 2D covariance (screenspace bounds) of the Gaussian quad.
* **Fragment Shader:** Alpha blend sorted splats.


* [x] **Optimization - "Tile Culling":**
* Divide screen into 16x16 tiles.
* Compute shader pre-pass: List which Gaussians touch which tile.
* Rasterize only visible tiles.



---

## **Phase 6: Integration & App Logic (The "Glue")**

### **6.1 The Main Loop (Kotlin/C++ Interface)**

```java
// Conceptual Loop
void onDrawFrame() {
    // 1. Get latest Camera Pose from SLAM system
    float pose = sphereSlam.getPose();

    // 2. Update Virtual Camera in Vulkan
    vulkanRenderer.updateCamera(pose);

    // 3. Check for new Keyframe
    if (sphereSlam.isNewKeyframe()) {
        // Trigger background thread to densify this keyframe
        depthRunner.process(currentImage);
    }

    // 4. Render existing Splats
    vulkanRenderer.draw();
}

```

### **6.2 Memory Management**

* [x] **AHardwareBuffer:** Use strictly for passing image data between Camera -> Vulkan -> NPU. Never map to CPU memory (`ByteBuffer.get()`) if possible.
* [x] **Double Buffering:** Implement ring buffers for the Gaussian data upload to GPU to prevent UI stalling.

---

## **Phase 7: Build & Deployment**

### **7.1 AndroidManifest.xml Permissions**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.ar" android:required="true" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

```

### **7.2 Gradle Setup**

* [x] Enable `prefab` for native dependencies.
* [x] Set `minSdk 29` (Android 10) for AHardwareBuffer support.

### **7.3 Debugging Checks**

1. **SIGILL at startup:** Check your DBoW2/g2o compilation flags. Ensure `EPnP` solver uses aligned memory.
2. **Drift:** Verify IMU timestamps. Plot IMU accel norm; it should equal ~9.81 when static. If not, your scale or bias init is wrong.
3. **Thermal Throttling:** If FPS drops after 30s, reduce the "Active Map" size in ORB-SLAM3 or throttle the Dense Reconstruction thread.
