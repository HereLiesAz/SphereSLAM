# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

# Declares and names the project.
project("sphereslam")

# Include directories
include_directories(
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/SLAM
    ${CMAKE_SOURCE_DIR}/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/Thirdparty/glm
    ${CMAKE_SOURCE_DIR}/../../../../libs/opencv/sdk/native/jni/include # Adjust path as needed
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
             VulkanCompute.cpp
             DepthAnyCamera.cpp
             Densifier.cpp
             MobileGS.cpp
             SLAM/System.cpp
             SLAM/Tracking.cpp
             SLAM/Frame.cpp
             SLAM/ORBextractor.cpp
             SLAM/MapPoint.cpp
             SLAM/KeyFrame.cpp
             SLAM/Map.cpp
             SLAM/LocalMapping.cpp
             SLAM/LoopClosing.cpp
             SLAM/KeyFrameDatabase.cpp
             SLAM/Optimizer.cpp
             SLAM/Settings.cpp
             SLAM/Initializer.cpp
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
                       android
                       EGL
                       GLESv3
                       vulkan )
