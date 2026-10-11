# :models consumer ProGuard/R8 rules — shipped in the AAR so a consuming app needs no rules of its own.

# --- LiteRT (TensorFlow Lite) + GPU delegate (MiDaS depth) ---
# The runtime is found by reflection (TensorFlowLite loads org.tensorflow.lite.InterpreterFactoryImpl
# by name, GpuDelegateFactory loads org.tensorflow.lite.gpu.GpuDelegate by name) and its JNI code calls
# back into Java classes and fields. LiteRT's own AAR rules only cover @UsedByReflection members, and
# litert-gpu ships none, so keep the (small) package whole. Without this an R8 build fails at runtime
# to create the interpreter or the GPU delegate.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# --- ONNX Runtime (SuperPoint, Zero-DCE) ---
# ORT's JNI resolves ai.onnxruntime.OrtException (which declares no natives) with FindClass when a
# session fails to open; if R8 strips it the catchable exception becomes a fatal JniAbort.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
