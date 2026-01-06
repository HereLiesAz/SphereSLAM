#!/bin/bash
set -e

# Conceptual Build Script for Web (Wasm)
echo "Building SphereSLAM Web Module..."

mkdir -p web_lib/build
cd web_lib/build

# emcmake cmake ..
# emmake make

echo "Build complete (simulated). Artifacts would be in web_lib/build/"
echo "Copying artifacts to web_app/..."

# cp sphereslam_web.js ../../web_app/
# cp sphereslam_web.wasm ../../web_app/

echo "Done."
