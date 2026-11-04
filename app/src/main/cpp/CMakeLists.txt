cmake_minimum_required(VERSION 3.22.1)
project(subwhisper LANGUAGES C CXX)

# (v696) مكتبة ggml واحدة بس: llama.cpp (ترجمة Hy-MT2) هو اللي بيجيب ggml، وwhisper.cpp بيتبني من سورسه بس (src/whisper.cpp)
# فوق نفس الـggml. قبل كده كانوا هيتبنوا بنسختين ggml متضاربتين. الاتنين جوه نفس المكتبة subwhisper (jni.cpp + llm_jni.cpp).
# التوافق اتفحص syntax-level على جهاز التطوير (whisper v1.9.5 فوق headers بتاعة llama b12034) — لسه ما اتعملش بناء أندرويد حقيقي.

# (v747) خيار بيلد خاص: JOY_STQ=ON ← llama.cpp من PR #22836 (kernel STQ1_0 بتاع Tencent) عشان موديل HY-MT1.5 1.25-bit (~440 ميجا) يشتغل.
# الافتراضي OFF: نفس llama.cpp المثبّت (b12034) والموديل الصغير مش هيتحمّل. لسه ما اتجرّبش بيلد الخيار ده (ممكن يحتاج تعديل في llm_jni.cpp لو الـAPI اختلف).
option(JOY_STQ "Build llama.cpp from the STQ1_0 PR (1.25-bit HY-MT)" OFF)

# مكتبة صغيرة فاضية على كل المعماريات عشان الـAPK يتثبّت على أي جهاز (زي v572)
add_library(subabi SHARED stub.cpp)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    include(FetchContent)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    # (v450) armv8.2+dotprod+fp16: بدونها الموديلات المضغوطة بطيئة جدًا
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

    if(JOY_STQ)
        FetchContent_Declare(llama_src
            GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
            GIT_TAG refs/pull/22836/head
            GIT_SHALLOW FALSE)
    else()
        FetchContent_Declare(llama_src
            GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
            GIT_TAG b12034
            GIT_SHALLOW TRUE)
    endif()
    FetchContent_Declare(whisper_src
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG v1.9.5
        GIT_SHALLOW TRUE)

    FetchContent_MakeAvailable(llama_src)
    FetchContent_GetProperties(whisper_src)
    if(NOT whisper_src_POPULATED)
        FetchContent_Populate(whisper_src)
    endif()

    add_library(whisper_core STATIC
        ${whisper_src_SOURCE_DIR}/src/whisper.cpp
        ${whisper_src_SOURCE_DIR}/src/aneforge/whisper-aneforge.cpp)
    target_include_directories(whisper_core PUBLIC ${whisper_src_SOURCE_DIR}/include PRIVATE ${whisper_src_SOURCE_DIR}/src)
    target_compile_definitions(whisper_core PRIVATE WHISPER_VERSION="1.9.5")
    target_compile_features(whisper_core PUBLIC cxx_std_17)
    set_target_properties(whisper_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_link_libraries(whisper_core PUBLIC ggml)

    add_library(subwhisper SHARED jni.cpp llm_jni.cpp)
    target_compile_features(subwhisper PRIVATE cxx_std_17)
    target_link_libraries(subwhisper whisper_core llama log)
    if(JOY_STQ)
        target_compile_definitions(subwhisper PRIVATE JOY_STQ=1)
    endif()
endif()
