# v473 — إصلاح بناء v472 (kt_manifest)

مبنية على v472 بالحرف، من غير أي تغيير في الكود.

- **السبب:** `kt_manifest.txt` كان ناقص 4 ملفات (`TaskRing.kt` · `YtLocale.kt` · `YtSnap.kt` · `YtThumbBtns.kt`)، فمهمة `pruneStaleKt` مسحتهم قبل الكومبايل وطلعت أخطاء `Unresolved reference` (YtLocale · TaskRingView · TaskAgg · YtThumbBtns).
- **الإصلاح:** اتضافوا في `app/kt_manifest.txt` (148 سطر، متطابق مع الملفات على القرص).
- `versionCode` = 473 و`versionName` = "4.73" (كانوا لسه 471 / 4.71).
