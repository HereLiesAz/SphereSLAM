cmake_minimum_required(VERSION 3.22.1)
project(sphereslam_web)

# Check if compiling for Emscripten
if(NOT EMSCRIPTEN)
    message(FATAL_ERROR "This CMakeLists.txt is intended for Emscripten builds only.")
endif()

# Handle OPENCV_DIR variable
if(DEFINED OPENCV_DIR)
    # Resolve absolute path, handling relative paths from build directory (where cmake is run)
    get_filename_component(OPENCV_ABS "${OPENCV_DIR}" ABSOLUTE BASE_DIR "${CMAKE_BINARY_DIR}")

    # Check if it exists at the resolved path
    if(NOT EXISTS "${OPENCV_ABS}")
         # Try relative to source directory as fallback
         get_filename_component(OPENCV_ABS_SRC "${OPENCV_DIR}" ABSOLUTE BASE_DIR "${CMAKE_SOURCE_DIR}")
         if(EXISTS "${OPENCV_ABS_SRC}")
             set(OPENCV_ABS "${OPENCV_ABS_SRC}")
         endif()
    endif()

    message(STATUS "Web Build: OPENCV_DIR is set to ${OPENCV_DIR}. Resolved to ${OPENCV_ABS}. Using provided headers for compilation.")
    include_directories(${OPENCV_ABS}/include/opencv4)
    link_directories(${OPENCV_ABS}/lib)
    # Add common locations for 3rdparty static libs in OpenCV static builds
    link_directories(${OPENCV_ABS}/lib/opencv4/3rdparty)
    link_directories(${OPENCV_ABS}/share/OpenCV/3rdparty/lib)
else()
    # Fallback to hardcoded path if OPENCV_DIR is not set
    # Assuming standard project structure if not provided
    set(DEFAULT_OPENCV_PATH "${CMAKE_SOURCE_DIR}/../libs/opencv-wasm")
    if(EXISTS "${DEFAULT_OPENCV_PATH}")
        message(STATUS "Web Build: OPENCV_DIR not set. Defaulting to ${DEFAULT_OPENCV_PATH}.")
        include_directories(${DEFAULT_OPENCV_PATH}/include/opencv4)
        link_directories(${DEFAULT_OPENCV_PATH}/lib)
        link_directories(${DEFAULT_OPENCV_PATH}/lib/opencv4/3rdparty)
        link_directories(${DEFAULT_OPENCV_PATH}/share/OpenCV/3rdparty/lib)
    endif()
endif()

# Core Includes
include_directories(
    ${CMAKE_SOURCE_DIR}/../core/include
    ${CMAKE_SOURCE_DIR}/../core/src
    ${CMAKE_SOURCE_DIR}/../core/src/Utils
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/glm
)

# Emscripten specific flags
# We rely on OpenCV's built-in static libraries for PNG, ZLIB, JPEG, etc.
# set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -s USE_LIBPNG=1 -s USE_ZLIB=1")
# set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -s USE_LIBPNG=1 -s USE_ZLIB=1")

# Core Sources
file(GLOB_RECURSE CORE_SOURCES "${CMAKE_SOURCE_DIR}/../core/src/SLAM/*.cpp")

# Define sources explicitly to ensure CMake picks them up
set(WEB_SOURCES
    ${CORE_SOURCES}
    PlatformWeb.cpp
    Bindings.cpp
)

message(STATUS "Web Sources: ${WEB_SOURCES}")

# Add Executable (Wasm module)
add_executable(sphereslam_web ${WEB_SOURCES})

# Link OpenCV Static Libraries (Order matters!)
# Emscripten usually compiles to .a
target_link_libraries(sphereslam_web
    opencv_calib3d
    opencv_features2d
    opencv_flann
    opencv_imgcodecs
    opencv_imgproc
    opencv_core

    # 3rd Party Dependencies (OpenCV built-ins)
    # Note: OpenCV often names them liblibjpeg.a, libzlib.a, etc. in static builds.
    libopenjp2
    liblibjpeg
    liblibpng
    liblibwebp
    libzlib
)

# Emscripten Linker Flags
set_target_properties(sphereslam_web PROPERTIES LINK_FLAGS "-s WASM=1 -s ALLOW_MEMORY_GROWTH=1 --bind -s MODULARIZE=1 -s EXPORT_NAME='createSphereSLAMModule'")
