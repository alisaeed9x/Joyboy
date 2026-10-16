cmake_minimum_required(VERSION 3.22.1)
project(subwhisper LANGUAGES C CXX)

# (v253) whisper.cpp بيتنزّل وقت البناء (GitHub Actions) — نسخة ثابتة عشان البناء يفضل متكرر
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper_src
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(whisper_src)

add_library(subwhisper SHARED jni.cpp)
target_link_libraries(subwhisper whisper log)
