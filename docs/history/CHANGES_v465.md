# v465 — إصلاح أخطاء الكومبايل (من لوج GitHub Actions)

- FileManager.kt: سطر «detailMeta» كان فيه نص مقطوع بسطر جديد حقيقي جوه الـ string — بقى `\n`.
- TvHome.kt: `VideoScan.load` → `Thumbs.load` (الدالة موجودة في Thumbs مش VideoScan).
- Glass.kt (GAlert): إضافة `create()` و`setMultiChoiceItems()` اللي TvHome بيستخدمهم.
- تحذير الـ C++ «unknown target CPU armv8.2-a+dotprod+fp16» تحذير من CMake مش خطأ بيوقف البناء.
