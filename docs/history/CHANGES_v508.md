# v5.08 — إصلاح بناء v507
- السبب: task باسم pruneStaleKt في app/build.gradle.kts بيمسح أي ملف .kt مش مكتوب في app/kt_manifest.txt. TvScale.kt (ملف جديد في v507) ما كانش في القايمة فاتمسح وكل مراجع TvScale بقت Unresolved.
- الحل: إضافة com/tttt/subtitler/TvScale.kt لـ kt_manifest.txt.
- قاعدة للمستقبل: أي ملف .kt جديد لازم يتضاف لـ app/kt_manifest.txt (تأكدت إن باقي الملفات كلها مسجّلة).
