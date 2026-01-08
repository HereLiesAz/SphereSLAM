# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project.
project("sphereslam")

# Find Packages
# find_package(TensorFlowLiteC REQUIRED) # Disabled due to Prefab failure
find_package(OpenCV REQUIRED)

# Include directories
include_directories(
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/../../../../core/include
    ${CMAKE_SOURCE_DIR}/../../../../core/src
    ${CMAKE_SOURCE_DIR}/../../../../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../../../../core/Thirdparty/glm
)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

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
             )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log )

find_library( # Android native window library
              android-lib
              android )

find_library( # AAssetManager
              android-asset
              android )

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
                       sphereslam

                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib}
                       ${android-lib}
                       ${android-asset}
                       ${OpenCV_LIBS}
                       TensorFlowLiteC::TensorFlowLiteC
                       android
                       EGL
                       GLESv3
                       vulkan )
