cmake_minimum_required(VERSION 3.22.1)
project(subwhisper LANGUAGES C CXX)

# (v572) المعمارية: Whisper (subwhisper) بيتبني على arm64-v8a بس (الـflags بتاعته armv8.2).
# التلفزيونات كتير منها 32 بت (armeabi-v7a): من v569 (بعد شيل sherpa/onnxruntime) الـAPK بقى فيه lib/arm64-v8a بس ← التلفزيون
# بيرفض التثبيت (INSTALL_FAILED_NO_MATCHING_ABIS). الحل: مكتبة صغيرة فاضية (subabi) على كل المعماريات عشان الـAPK يتثبّت على أي جهاز؛
# الأوفلاين (subwhisper) بيفضل arm64 بس، وعلى 32 بت WhisperLib.loaded = false زي ما كان مقصود.
add_library(subabi SHARED stub.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # (v253) whisper.cpp بيتنزّل وقت البناء (GitHub Actions) — نسخة ثابتة عشان البناء يفضل متكرر
    include(FetchContent)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    # (v450) بدون -march كان ggml بيتبني armv8.0 عادي من غير dotprod/fp16 ← الموديلات المضغوطة (q5) بطيئة جدًا. armv8.2+dotprod+fp16 موجودين في أي موبايل أرم 64 من ~2018.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
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
endif()
