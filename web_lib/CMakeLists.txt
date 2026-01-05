cmake_minimum_required(VERSION 3.22.1)
project(sphereslam_web)

# Emscripten specific setup would go here if running standalone
# But usually you run `emcmake cmake .`

include_directories(
    ${CMAKE_SOURCE_DIR}/../core/include
    ${CMAKE_SOURCE_DIR}/../core/src
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/eigen
    ${CMAKE_SOURCE_DIR}/../core/Thirdparty/glm
)

# Sources from Core
file(GLOB_RECURSE CORE_SOURCES "${CMAKE_SOURCE_DIR}/../core/src/SLAM/*.cpp")

# Add Executable (Wasm module)
add_executable(sphereslam_web
    ${CORE_SOURCES}
    PlatformWeb.cpp
)

# Emscripten flags
# set_target_properties(sphereslam_web PROPERTIES LINK_FLAGS "-s WASM=1 -s ALLOW_MEMORY_GROWTH=1 --bind")
