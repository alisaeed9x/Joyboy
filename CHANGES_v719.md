# v719

## إصلاح فشل البيلد
- `VidMark.kt`: الـ constructor كان بيستدعي `FrameLayout(context)` والبارامتر اسمه `ctx` → اتصلح لـ `FrameLayout(ctx)`.
- التأكيد إن `DlQuality.kt` (v716) و`VidMark.kt` (v717) و`TvRecvPill.kt` (v718) موجودين في الريبو (كانوا سبب أخطاء Unresolved reference).
- `versionName` اتحدّث لـ 7.19.

## الملفات
`VidMark.kt` · `app/build.gradle.kts` · `CHANGES_v719.md`
