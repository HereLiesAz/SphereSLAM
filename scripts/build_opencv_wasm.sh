#!/bin/bash
set -e

# Check for emcmake
if ! command -v emcmake &> /dev/null; then
    echo "Error: emcmake is not found. Please ensure the Emscripten SDK is activated."
    echo "Try: source path/to/emsdk/emsdk_env.sh"
    exit 1
fi

# Define paths
SOURCE_DIR="$(pwd)/libs/opencv-source"
INSTALL_DIR="$(pwd)/libs/opencv-wasm"

echo "Building OpenCV for WebAssembly..."

# Create source directory if it doesn't exist
mkdir -p "$SOURCE_DIR"

# Clean install directory to ensure a fresh build artifact
rm -rf "$INSTALL_DIR"

# Clone OpenCV if not present in the persistent source directory
if [ ! -d "$SOURCE_DIR/opencv" ]; then
  echo "Cloning OpenCV 4.12.0..."
  git clone --depth 1 --branch 4.12.0 https://github.com/opencv/opencv.git "$SOURCE_DIR/opencv"
fi

# Create and enter build directory
BUILD_DIR="$SOURCE_DIR/build"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Clean build directory contents to ensure cmake runs fresh
rm -rf *

# Configure CMake
# We disable as much as possible to keep build time low and artifact size small.
echo "Configuring OpenCV..."
emcmake cmake "$SOURCE_DIR/opencv" \
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
  -DBUILD_OPENJPEG=ON \
  -DBUILD_opencv_video=OFF \
  -DBUILD_opencv_videoio=OFF \
  -DBUILD_opencv_highgui=OFF \
  -DBUILD_opencv_objdetect=OFF \
  -DBUILD_opencv_dnn=OFF \
  -DBUILD_opencv_ml=OFF \
  -DBUILD_opencv_photo=OFF \
  -DBUILD_opencv_stitching=OFF \
  -DBUILD_opencv_gapi=OFF \
  -DBUILD_PROTOBUF=OFF \
  -DWITH_PROTOBUF=OFF \
  -DWITH_PTHREADS_PF=OFF \
  -DWITH_IPP=OFF \
  -DCV_ENABLE_INTRINSICS=OFF \
  -DWITH_IPP=OFF \
  -DWITH_1394=OFF \
  -DWITH_CUDA=OFF \
  -DWITH_OPENCL=OFF \
  -DWITH_TIFF=OFF \
  -DWITH_JASPER=OFF \
  -DBUILD_opencv_imgcodecs=ON \
  -DCMAKE_BUILD_TYPE=Release

# Build
echo "Compiling OpenCV..."
emmake make -j$(nproc)

# Install
echo "Installing OpenCV..."
emmake make install

echo "OpenCV Wasm build complete."
echo "Listing installed libraries:"
find "$INSTALL_DIR" -name "*.a"
