cmake_minimum_required(VERSION 3.22.1)
project(rockflash LANGUAGES CXX)

add_library(
    rockflash
    SHARED
    native_bridge.cpp
)

target_compile_features(rockflash PRIVATE cxx_std_17)
target_link_libraries(rockflash android log)
