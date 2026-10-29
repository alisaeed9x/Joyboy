# v644 — إصلاح فشل الـbuild
- `ControllerPad.kt` سطر 161: `isFakeBoldText` مش خاصية في TextView (بتاعة Paint) → اتغيّرت لـ `paint.isFakeBoldText = true` (ده كان سبب `Unresolved reference` في compileDebugKotlin).
- رفع الإصدار 644 / 6.44. ما اتكومبايلش محليًا.
- verify: `IrTv.kt` و`RemoteActivity.kt` و`TvPanic.kt` فاضلين على GitHub من إصدارات قديمة وناقصين من kt_manifest.txt → اتعملوا ملفات فاضية (package بس) واتسجّلوا في المانيفست (زي حل v181). للتنضيف النهائي: `git rm app/src/main/java/com/tttt/subtitler/{IrTv,RemoteActivity,TvPanic}.kt` ومسحهم من المانيفست.
- tests: فشل تحميل الـNDK على الـrunner (ZipFile unknown archive) — مؤقت، بيتحل بإعادة تشغيل الـjob.
