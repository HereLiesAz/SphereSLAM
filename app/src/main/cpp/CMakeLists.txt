cmake_minimum_required(VERSION 3.22.1)

project("sphereslam")

# --- LiteRT (via Prefab) ---
find_package(litert REQUIRED CONFIG)
find_package(litert-gpu REQUIRED CONFIG)

# --- OpenCV ---
# CMAKE_SOURCE_DIR is app/src/main/cpp
# libs is at app/src/main/cpp/../../../../libs
set(OPENCV_ROOT ${CMAKE_SOURCE_DIR}/../../../../libs/opencv/sdk/native)
include_directories(${OPENCV_ROOT}/jni/include)

add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OPENCV_ROOT}/libs/${ANDROID_ABI}/libopencv_java4.so)

# --- Include Directories ---
include_directories(
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/SLAM
    ${CMAKE_SOURCE_DIR}/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/Thirdparty/glm
)

add_library(sphereslam SHARED
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

find_library(log-lib log)
find_library(android-lib android)
find_library(android-asset android)

target_link_libraries(sphereslam
                       ${log-lib}
                       ${android-lib}
                       ${android-asset}
                       android
                       EGL
                       GLESv3
                       vulkan
                       lib_opencv
                       litert::litert
                       litert::litert-gpu
                       )
