#!/usr/bin/env python3
# Reproduces models/src/main/assets/midas_v21_small_256_fp16.tflite from the official MiDaS release:
#   curl -L -o model_opt.tflite https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite
#   python3 tflite_fp16_weights.py model_opt.tflite midas_v21_small_256_fp16.tflite
# Needs `pip install tensorflow` (for the TFLite flatbuffer schema helpers). See docs/MODELS.md.
"""Rewrite a float32 TFLite flatbuffer into float16-weight form, the same layout the TFLite
converter emits for `optimizations=[DEFAULT], target_spec.supported_types=[float16]`: every float32
constant tensor is stored as FLOAT16 and fed through a DEQUANTIZE op to its original consumers.
The GPU delegate folds those DEQUANTIZEs and uses the fp16 weights directly; CPU (XNNPACK)
dequantizes once at init."""
import sys
import numpy as np
from tensorflow.lite.tools import flatbuffer_utils as fu
from tensorflow.lite.python import schema_py_generated as s

src, dst = sys.argv[1], sys.argv[2]
m = fu.read_model(src)
g = m.subgraphs[0]
assert len(m.subgraphs) == 1
deq_code = None
for i, c in enumerate(m.operatorCodes):
    if max(c.builtinCode, c.deprecatedBuiltinCode) == s.BuiltinOperator.DEQUANTIZE:
        deq_code = i
if deq_code is None:
    oc = s.OperatorCodeT()
    oc.builtinCode = s.BuiltinOperator.DEQUANTIZE
    oc.deprecatedBuiltinCode = s.BuiltinOperator.DEQUANTIZE
    oc.version = 3
    m.operatorCodes.append(oc)
    deq_code = len(m.operatorCodes) - 1

new_ops = []
converted = 0
max_abs_err = 0.0
for ti in range(len(g.tensors)):
    t = g.tensors[ti]
    if t.type != s.TensorType.FLOAT32 or t.buffer == 0:
        continue
    buf = m.buffers[t.buffer]
    if buf.data is None or len(buf.data) == 0:
        continue
    w32 = np.frombuffer(bytes(bytearray(buf.data)), dtype=np.float32)
    w16 = w32.astype(np.float16)
    assert np.all(np.isfinite(w16)), f"{t.name} overflows fp16"
    max_abs_err = max(max_abs_err, float(np.max(np.abs(w16.astype(np.float32) - w32))) if w32.size else 0.0)
    nb = s.BufferT()
    nb.data = np.frombuffer(w16.tobytes(), dtype=np.uint8)
    m.buffers.append(nb)
    ht = s.TensorT()
    ht.shape = t.shape
    ht.type = s.TensorType.FLOAT16
    ht.buffer = len(m.buffers) - 1
    ht.name = (t.name or b"") + b"_fp16"
    g.tensors.append(ht)
    # The original tensor becomes the DEQUANTIZE output (no constant data any more).
    buf.data = None
    op = s.OperatorT()
    op.opcodeIndex = deq_code
    op.inputs = np.array([len(g.tensors) - 1], dtype=np.int32)
    op.outputs = np.array([ti], dtype=np.int32)
    new_ops.append(op)
    converted += 1
g.operators = new_ops + list(g.operators)
m.description = b"MiDaS v2.1 Small 256 (isl-org/MiDaS v2_1 model_opt.tflite), float16 weights"
fu.write_model(m, dst)
print(f"converted {converted} float tensors; max weight rounding error {max_abs_err:.3g}")
