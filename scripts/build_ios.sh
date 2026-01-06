#!/bin/bash
set -e

# Conceptual Build Script for iOS
echo "Building SphereSLAM iOS Module..."

mkdir -p ios_lib/build
cd ios_lib/build

# cmake -G Xcode -DCMAKE_SYSTEM_NAME=iOS ..
# cmake --build . --config Release

echo "Build complete (simulated). Static library would be in ios_lib/build/Release-iphoneos/"
echo "Please link libsphereslam_ios.a in your Xcode project."

echo "Done."
