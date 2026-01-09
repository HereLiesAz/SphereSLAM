#!/bin/bash
set -e

# Define installation path
INSTALL_DIR="$(pwd)/libs/opencv-wasm"

# Check if already built
if [ -f "$INSTALL_DIR/lib/libopencv_core.a" ]; then
  echo "OpenCV Wasm already built at $INSTALL_DIR."
  # Forcing rebuild in CI if needed, but local check is fine.
  # The user likely needs to clear cache or we rely on the fact that if it failed, it might not have created the artifact?
  # Or we can just let the user handle clean up.
  exit 0
fi

echo "Building OpenCV for WebAssembly..."

# Create temp build directories
mkdir -p build_opencv_wasm_temp
cd build_opencv_wasm_temp

# Clone OpenCV
if [ ! -d "opencv" ]; then
  echo "Cloning OpenCV 4.12.0..."
  git clone --depth 1 --branch 4.12.0 https://github.com/opencv/opencv.git
fi

mkdir -p build
cd build

# Configure CMake
# We disable as much as possible to keep build time low and artifact size small.
# We need: core, features2d, imgproc, calib3d, flann, imgcodecs.
echo "Configuring OpenCV..."
emcmake cmake ../opencv \
  -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR" \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_opencv_apps=OFF \
  -DBUILD_EXAMPLES=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_PERF_TESTS=OFF \
  -DBUILD_DOCS=OFF \
  -DBUILD_ANDROID_PROJECTS=OFF \
  -DBUILD_JAVA=OFF \
  -DBUILD_opencv_python2=OFF \
  -DBUILD_opencv_python3=OFF \
  -DBUILD_ZLIB=ON \
  -DBUILD_PNG=ON \
  -DBUILD_JPEG=ON \
  -DBUILD_WEBP=ON \
  -DBUILD_opencv_video=OFF \
  -DBUILD_opencv_videoio=OFF \
  -DBUILD_opencv_highgui=OFF \
  -DBUILD_opencv_objdetect=OFF \
  -DBUILD_opencv_dnn=OFF \
  -DBUILD_opencv_ml=OFF \
  -DBUILD_opencv_photo=OFF \
  -DBUILD_opencv_stitching=OFF \
  -DBUILD_opencv_gapi=OFF \
  -DWITH_PTHREADS_PF=OFF \
  -DCV_ENABLE_INTRINSICS=OFF \
  -DWITH_IPP=OFF \
  -DWITH_1394=OFF \
  -DWITH_CUDA=OFF \
  -DWITH_OPENCL=OFF \
  -DWITH_TIFF=OFF \
  -DBUILD_opencv_imgcodecs=ON \
  -DCMAKE_BUILD_TYPE=Release

# Build
echo "Compiling OpenCV..."
emmake make -j$(nproc)

# Install
echo "Installing OpenCV..."
emmake make install

# Cleanup
cd ../..
rm -rf build_opencv_wasm_temp

echo "OpenCV Wasm build complete."
