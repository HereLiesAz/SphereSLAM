# Bundled models (`:models`)

All three models ship as assets inside the `:models` AAR and are extracted to the app's
`filesDir/sphereslam-models/` on first load (re-extracted when the bundled size changes).

| Asset | Used by | Runtime | Size |
| --- | --- | --- | --- |
| `midas_v21_small_256_fp16.tflite` | `DepthEstimator` | LiteRT 1.4.2, **GPU delegate** (CPU/XNNPACK fallback) | 33,248,432 B |
| `superpoint.onnx` | `SuperPointDetector` | ONNX Runtime (CPU) | 5,208,885 B |
| `zerodce.onnx` | `LowLightEnhancer` | ONNX Runtime (CPU) | 320,693 B |

## MiDaS v2.1 Small 256 — `midas_v21_small_256_fp16.tflite`

- **Source:** official isl-org/MiDaS release `v2_1`, asset `model_opt.tflite`
  (<https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite>; the same URL the
  upstream Android sample's `mobile/android/models/download.gradle` uses). EfficientNet-Lite3
  encoder, small decoder, input NHWC `[1,256,256,3]` float32 (RGB, ImageNet mean/std normalized),
  output `[1,256,256,1]` float32 relative **inverse** depth. Ops: CONV_2D, DEPTHWISE_CONV_2D, ADD,
  RELU, RESIZE_BILINEAR — all GPU-delegate native.
  - upstream file: 66,338,288 B, sha256 `93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd`
- **License:** MIT, © 2019 Intel ISL (Intel Intelligent Systems Lab) — see
  <https://github.com/isl-org/MiDaS/blob/master/LICENSE>. The notice ships next to the model as
  `assets/midas_v21_small_256_fp16.LICENSE.txt`.
- **Conversion:** weights stored as float16 by `models/tools/tflite_fp16_weights.py` (TensorFlow
  2.21.0 flatbuffer helpers): every float32 constant becomes a FLOAT16 tensor feeding a DEQUANTIZE
  op — the layout the TFLite converter emits for float16 post-training quantization (the converter
  itself cannot ingest a `.tflite`). The GPU delegate consumes the fp16 weights directly; the
  XNNPACK CPU path dequantizes once at load. Deterministic: rerunning it on the upstream file gives
  the bundled bytes.
  - bundled file: 33,248,432 B, sha256 `207acda2e760ed3e6bf78fb36a4ce64dc2b9e23947a69c3836beb57cced09400`
- **Verification** (CPU reference runs, three images — an indoor room, `skimage` "coffee" and
  "rocket" — RGB and grey-luma input each):
  - fp16 weights vs upstream fp32 TFLite: mean relative difference ≤ 0.15 %, correlation ≥ 0.99999
    (maximum weight rounding error 0.0078).
  - Both TFLite files vs the PyTorch reference (`midas_v21_small_256.pt`, sha256 `70d6b9c8…`, run
    through the upstream `MidasNet_small`): correlation 0.990–0.999, affine-fit mean error 2.4–6 %.
  - The previously bundled `midas_v21_small_256_int8.onnx`, fed ImageNet-normalized input as the old
    ONNX `DepthEstimator` did, correlated only 0.55–0.99 with that reference (the release ONNX export
    appears to expect raw `[0,1]` input); the TFLite model fixes that as a side effect.

### Size change

The int8 ONNX MiDaS asset (17,278,839 B) was removed — nothing else in SphereSLAM or GraffitiXR
referenced it by name — and replaced by the fp16 TFLite model (33,248,432 B): **+15.97 MB** of model,
plus the LiteRT runtime (`libtensorflowlite_jni.so` ≈ 4.5 MB + `libtensorflowlite_gpu_jni.so`
≈ 2.6 MB per arm64 APK split). fp16 halves the 66 MB float32 upstream file with no measurable
accuracy cost on the GPU, which computes in fp16 anyway.
