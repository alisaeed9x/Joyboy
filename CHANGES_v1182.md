# Joyboy v1182 — تصغير تاني للحجم (فوق v1181)

ما اتعملش compile ولا قياس APK. كل البنود من غير حذف ميزة.

1. **R8 على كود التطبيق نفسه** (~1–2 ميجا، أكبر بند): اتشال `-keep class com.tttt.subtitler.** { *; }` واتبدّل بقواعد محددة + `-dontobfuscate` (الأسماء ثابتة: اللوج وSharedPreferences وJNI زي ما هم). R8 بيشيل بس الكلاسات/الدوال اللي مفيش حد بيستدعيها. محفوظ صراحة: native methods · JavascriptInterface · enums · الـViews والـFragments اللي بتتعمل بالاسم · Serializable · mtui.**.
   لو حاجة وقعت في التشغيل: ارجع السطرين القديمين (متعلّقين كتعليق في أول القاعدة الجديدة في app/proguard-rules.pro).
2. **المكتبة native (whisper/llama)**: -ffunction-sections/-fdata-sections + `--gc-sections` + `--exclude-libs,ALL` (من غير تغيير مستوى التحسين فالسرعة زي ما هي).
3. **ملفات ملزقة في الـAPK**: استبعاد kotlin/*.kotlin_builtins وkotlin_module وDebugProbesKt.bin وMETA-INF/maven وproguard.
4. **باقي الـPNG (10 ملفات في mipmap/drawable) ← WebP lossless** (نفس البكسلات بالظبط، ~0.13 ميجا).
