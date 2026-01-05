cmake_minimum_required(VERSION 3.22.1)
project(sphereslam_ios)

# Set iOS specific flags (can be passed via toolchain file usually)
# set(CMAKE_SYSTEM_NAME iOS)

include_directories(
    ${CMAKE_SOURCE_DIR}/../core/include
    ${CMAKE_SOURCE_DIR}/../core/src
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/glm
)

# Sources from Core
file(GLOB_RECURSE CORE_SOURCES "${CMAKE_SOURCE_DIR}/../core/src/SLAM/*.cpp")

# Add Library
add_library(sphereslam_ios STATIC
    ${CORE_SOURCES}
    PlatformIOS.mm
)

# Link generic libs (in real iOS build, you'd link Foundation etc.)
target_link_libraries(sphereslam_ios "-framework Foundation")
