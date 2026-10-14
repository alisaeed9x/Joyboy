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

# (v307) جسر ONNX Runtime لفصل الصوت عن الموسيقى (StemCore.kt) — بيتنزّل الهيدر بس وقت البناء؛
# libonnxruntime.so نفسها جاية مع sherpa-onnx AAR وبتتحمّل بـ dlopen (stemort.cpp) فمفيش نسختين في الـ APK
set(ORT_HDR_DIR ${CMAKE_BINARY_DIR}/ort_hdr)
set(ORT_HDR ${ORT_HDR_DIR}/onnxruntime_c_api.h)
if(NOT EXISTS ${ORT_HDR})
    file(DOWNLOAD https://raw.githubusercontent.com/microsoft/onnxruntime/v1.17.3/include/onnxruntime/core/session/onnxruntime_c_api.h
         ${ORT_HDR} STATUS ort_st TLS_VERIFY ON)
    list(GET ort_st 0 ort_code)
    if(NOT ort_code EQUAL 0)
        file(REMOVE ${ORT_HDR})
        message(FATAL_ERROR "تنزيل onnxruntime_c_api.h فشل: ${ort_st}")
    endif()
endif()
add_library(substem SHARED stemort.cpp)
target_include_directories(substem PRIVATE ${ORT_HDR_DIR})
target_link_libraries(substem log ${CMAKE_DL_LIBS})
target_link_options(substem PRIVATE "-Wl,-z,max-page-size=16384")
