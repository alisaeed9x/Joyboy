# v572 — إصلاح: التلفزيون كان بيرفض تثبيت الـAPK (النسخة بتتثبّت على الموبايل بس)
(ماعملتش compile ولا جهاز — Actions هو الاختبار)

## السبب
من v569 اتشالت sherpa-onnx و ML Kit، فمكتبة Whisper (`subwhisper`) بقت المكتبة native الوحيدة، وبتتبني arm64-v8a بس.
الـAPK بقى فيه `lib/arm64-v8a` من غير أي `lib/armeabi-v7a` ← أي تلفزيون 32 بت (كتير من TCL) بيرفضه: `INSTALL_FAILED_NO_MATCHING_ABIS`.
الموبايل 64 بت فبيثبّت عادي.

## الإصلاح
- `cpp/stub.cpp` جديد + `add_library(subabi ...)` على كل المعماريات (مكتبة فاضية) عشان الـAPK يبقى فيه armeabi-v7a.
- `CMakeLists.txt`: Whisper كله جوه `if(ANDROID_ABI STREQUAL "arm64-v8a")` (ما اتغيّرش شكله ولا إعداداته).
- `build.gradle.kts`: cmake abiFilters بقت arm64-v8a + armeabi-v7a. versionCode 572 / 5.72.
- على تلفزيون 32 بت الترجمة الأوفلاين (Whisper) مش هتشتغل (`WhisperLib.loaded = false` زي ما كان مقصود)؛ جيميناي وباقي التطبيق شغّالين.
- نفس إصلاح v571_fixed (giShowFn في Player.kt) موجود.
