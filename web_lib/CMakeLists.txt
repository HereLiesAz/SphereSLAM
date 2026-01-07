cmake_minimum_required(VERSION 3.22.1)
project(sphereslam_web)

# Check if compiling for Emscripten
if(NOT EMSCRIPTEN)
    message(FATAL_ERROR "This CMakeLists.txt is intended for Emscripten builds only.")
endif()

# Handle OPENCV_DIR variable
if(DEFINED OPENCV_DIR)
    message(STATUS "Web Build: OPENCV_DIR is set to ${OPENCV_DIR}, but local libraries are for Android. Attempting to use Emscripten contrib port.")
endif()

# Core Includes
include_directories(
    ${CMAKE_SOURCE_DIR}/../core/include
    ${CMAKE_SOURCE_DIR}/../core/src
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/glm
)

# Use Emscripten's contrib OpenCV port
# Replaces deprecated -s USE_OPENCV=4 with --use-port=contrib.opencv
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --use-port=contrib.opencv")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} --use-port=contrib.opencv")

# Core Sources
file(GLOB_RECURSE CORE_SOURCES "${CMAKE_SOURCE_DIR}/../core/src/SLAM/*.cpp")

# Add Executable (Wasm module)
add_executable(sphereslam_web
    ${CORE_SOURCES}
    PlatformWeb.cpp
    Bindings.cpp
)

# Emscripten Linker Flags
set_target_properties(sphereslam_web PROPERTIES LINK_FLAGS "-s WASM=1 -s ALLOW_MEMORY_GROWTH=1 --bind -s MODULARIZE=1 -s EXPORT_NAME='createSphereSLAMModule'")
