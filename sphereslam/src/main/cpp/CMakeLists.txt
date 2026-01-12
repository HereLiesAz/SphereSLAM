# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project.
project("sphereslam")

# Find Packages
find_package(OpenCV REQUIRED)

# Include directories
include_directories(
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/../../../../core/include
    ${CMAKE_SOURCE_DIR}/../../../../core/src
    ${CMAKE_SOURCE_DIR}/../../../../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../../../../core/Thirdparty/glm
)

add_library( # Sets the name of the library.
             sphereslam

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             native-lib.cpp
             PlatformAndroid.cpp
             VulkanCompute.cpp
             DepthAnyCamera.cpp
             Densifier.cpp
             MobileGS.cpp

             # Core SLAM
             ../../../../core/src/SLAM/System.cpp
             ../../../../core/src/SLAM/Tracking.cpp
             ../../../../core/src/SLAM/Frame.cpp
             ../../../../core/src/SLAM/ORBextractor.cpp
             ../../../../core/src/SLAM/MapPoint.cpp
             ../../../../core/src/SLAM/KeyFrame.cpp
             ../../../../core/src/SLAM/Map.cpp
             ../../../../core/src/SLAM/LocalMapping.cpp
             ../../../../core/src/SLAM/LoopClosing.cpp
             ../../../../core/src/SLAM/KeyFrameDatabase.cpp
             ../../../../core/src/SLAM/Optimizer.cpp
             ../../../../core/src/SLAM/Settings.cpp
             ../../../../core/src/SLAM/Initializer.cpp
             ../../../../core/src/SLAM/PhotosphereStitcher.cpp

             # LightCycle Implementation (Reconstructed)
             ../../../../core/src/SLAM/Mosaic.cpp
             ../../../../core/src/SLAM/Align.cpp
             ../../../../core/src/SLAM/db_CornerDetector.cpp
             ../../../../core/src/SLAM/db_Matcher.cpp
             ../../../../core/src/SLAM/CDelaunay.cpp
             ../../../../core/src/SLAM/GlobalOptimizer.cpp
             ../../../../core/src/SLAM/Blend.cpp
             )

find_library( log-lib log )
find_library( android-lib android )
find_library( android-asset android )

target_link_libraries( # Specifies the target library.
                       sphereslam
                       ${log-lib}
                       ${android-lib}
                       ${android-asset}
                       ${OpenCV_LIBS}
                       EGL
                       GLESv3
                       vulkan )
