# v1054 — إصلاح فشل بيلد v1053
**السبب (من لوج Actions):** `TrLibUi.kt:165 Unresolved reference 'VidPick'` — الملف الجديد VidPick.kt (من v1052) ماكانش مسجّل في `app/kt_manifest.txt` فمهمة pruneStaleKt مسحته قبل الكومبايل (الأخطاء التانية في اللوج ناتجة عنه).
**الإصلاح:** اتضاف `com/tttt/subtitler/VidPick.kt` للمانيفست. `bash dev-tests/check_manifest.sh` ← OK.
**ملاحظة:** ما اتعملش compile هنا. باقي اللوج مفيهوش أخطاء تانية.
