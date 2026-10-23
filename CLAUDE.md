# CLAUDE.md — Vanilla TV Launcher
**المصدر الحقيقي للحالة:** `docs/project-continuity/` (ابدأ بـ NEXT_SESSION.md ثم PROJECT_STATE.md).

## التعريف
Launcher لتلفزيون TCL (Android/Google TV) مبني على مكتبة الفيديو المحلية + YouTube + مشغل. مشروع جديد منفصل؛ بنكيّف أجزاء (ثيمات/NewPipe/مشغّل) من Subtitler v476 (Views برمجية).

## القواعد
- افحص قبل ما تعدّل. ما تقولش «اشتغل/نجح» إلا لو اتنفذ فعلًا. مرحلة واحدة في المرة.
- ما تحذفش ملفات المستخدم، ولا تحط أسرار/مفاتيح في الريبو، ولا git مدمّر.
- مفيش تغيير معماري بصمت: كل قرار جديد في DECISIONS.md.

## البناء والاختبار
- البناء الفعلي: GitHub Actions (`.github/workflows/build.yml` → `gradle assembleDebug`). لسه ما اتأكدش.
- فحص سريع: `sh dev-tests/run_tests.sh` (kotlinc) + `sh dev-tests/compile_check.sh /path/android.jar` (كل كود Android ماعدا YtNp على stubs media3 — مش بديل Gradle).
- الحزمة `com.vanilla.tvlauncher` (مؤقتة، D-005). مرجع الكود القديم: Subtitler v476.

## المحظورات
لا إعادة كتابة كاملة بدون سبب موثق · لا مفاتيح توقيع جديدة بدون إذن · لا ادعاء تحسين YouTube/إعلانات · لا حساب/كوكيز يوتيوب (D-013).

## الاستئناف
اقرأ: PROJECT_MASTER → PROJECT_STATE → NEXT_SESSION → DECISIONS → REQUIREMENTS_TRACEABILITY، وبعدين قارن بالملفات الفعلية.
