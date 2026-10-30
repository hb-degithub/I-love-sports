cmake_minimum_required(VERSION 3.18)
project(vloczygisk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只构建 arm64（现代设备主流；armeabi-v7a 设备极少且 Zygisk 支持有限）
add_library(vloc SHARED src/module.cpp)
target_include_directories(vloc PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(vloc PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(vloc PRIVATE log)

# Zygisk 要求 .so 文件名与 ABI 目录名一致：zygisk/arm64-v8a.so
set_target_properties(vloc PROPERTIES
        PREFIX ""
        OUTPUT_NAME "arm64-v8a"
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
