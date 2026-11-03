# v728

## إصلاح فشل البيلد
- `TvEdit.active()` كانت ناقصة (مستخدمة في `TvLookPad.kt` سطر 309 و310) → اتضافت: `fun active(): Boolean = Cfg.tvOv != null`.
- بمب الفيرجن: 7.27 → 7.28.

## الملفات
`TvEdit.kt` · `app/build.gradle.kts` (7.28)
