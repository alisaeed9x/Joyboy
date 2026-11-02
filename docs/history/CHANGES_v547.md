# v547 — إصلاح فشل البناء (Kotlin: Unclosed comment)
السبب: كتابة `/lib/*` جوه تعليق KDoc في 3 ملفات (LibraryCore.kt سطر 5، RemoteClient.kt سطر 374، RemoteLibrary.kt سطر 13). Kotlin بيدعم تعليقات متداخلة، فـ`/*` فتحت تعليق جديد محدش قفله → "Unclosed comment" وبقية الأخطاء (getText / cmd / component1) كانت ناتجة عنه.
الحل: `/lib/*` ← `/lib/…` في التعليقات الثلاثة. اتفحصت كل ملفات .kt بسكربت يعدّ عمق التعليقات: مفيش تعليق مفتوح تاني.
ماجرّبتش compile (مفيش Gradle في الجلسة).
