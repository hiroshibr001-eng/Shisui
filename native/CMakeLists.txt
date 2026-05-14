cmake_minimum_required(VERSION 3.18.1)

project(brunolsfg LANGUAGES C CXX)

add_library(
    brunolsfg
    SHARED
    brunolsfg_native.cpp
)

target_compile_features(
    brunolsfg
    PRIVATE
    cxx_std_17
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

if(NOT vulkan-lib)
    message(FATAL_ERROR "libvulkan not found. Use ANDROID_PLATFORM android-24 or higher.")
endif()

target_link_libraries(
    brunolsfg
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
