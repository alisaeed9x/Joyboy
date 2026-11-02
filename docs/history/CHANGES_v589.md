# v589 — T4 (اختبارات في CI) + M3 (شارة التلفزيون)

## اللي اتعمل
- **T4:** اختبارات المنطق اتنقلت من `dev-tests/*Test.kt` لـ `app/src/test/java/devtests/<اسم>/` (كل ملف بقى في package لوحده عشان `fun main()` ما تتعارضش).
  - `LogicTestsRunner` (JUnit) بيشغّل كل اختبار في JVM مستقلة (لأنها بتنادي `System.exit`) ويفشل لو الخروج ≠ 0 أو في سطر بيبدأ بـ `FAIL`.
  - `app/build.gradle.kts`: `junit` + `org.json:json` (الحقيقي بدل stub بتاع android.jar) + `isReturnDefaultValues` + متغير `SUBTITLER_ASSETS`.
  - `build.yml`: job جديد `tests` (`gradle testDebugUnitTest`) — جانبي زي `verify` (`continue-on-error`) ومش بيوقّف الـ APK، وبيرفع `test-report`.
  - `verify`: فحص `kt_manifest` بقى على `app/src/main` بس (عشان ملفات `app/src/test` ما تتحسبش).
  - `dev-tests/run_tests.sh`: نسخة Kotlin بتتقرا من `build.gradle.kts` (كانت 1.9.24 ثابتة) + تنبيه إنه قديم (Engine/Core بقوا معتمدين على باقي التطبيق، فالتشغيل لوحده بقى مش بيعمل compile). اتشال `LibraryTest.kt` (ملف فاضي).
- **M3:** كبسولة الفيديو على الموبايل بقت «📺 ▶ الاسم ✕» (علامة إن الفيديو شغّال على التلفزيون مش على الموبايل).
- versionCode 589 / 5.89 (كان فاضل 586).

## اللي ما اتعملش (ومحتاج جهاز أو قرار)
- **T2/T3** (TvNav وتجميع `isTv`): محتاجين TCL، ومينفعش من غير compile/تجربة.
- **C3** (مفاتيح/توكن بدون TLS): محتاج قرار تصميم (تبادل مفاتيح أو TLS) وهيكسر الريموتات المقترنة.
- **M6** (نقل الترجمة للتلفزيون): الموضع بيوصل (`startms`) لكن الترجمة محتاجة حقل جديد في `/open` + استقباله في المشغّل. M4/M5/M7 اختياري.

## ملاحظات
- لازم تتأكد إن `MlKitTr.kt` و`SherpaStt.kt` اتمسحوا من الريبو (رفع zip مش بيمسح).
- ماجرّبتش Gradle ولا جهاز. اتجرّبوا هنا بـ kotlinc 2.4.0: `GifTest` و`LockTest` (PASS). باقي الاختبارات أول تشغيل ليها في CI هو الاختبار الحقيقي — ممكن تفشل لو كانت قديمة (Engine/Core اتغيّروا من v177).
