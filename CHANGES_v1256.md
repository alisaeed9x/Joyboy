# v1256 — بلاجن (نظام إضافات منفصلة)
## بلاجن
1. صفحة جديدة «🧩 بلاجن» في المزيد: بتجيب قايمة البلاجن من ملف plugins/index.json في ريبو Joyboy، وبتنزّل الـAPK (من Releases بتاعة الريبو) وبتتأكد إنه موقّع بنفس مفتاح Joyboy قبل ما تفتح شاشة التثبيت، وبتعرض زرار «تحديث» لما يبقى فيه نسخة أحدث من المتثبّتة.
2. جسر Joyboy مع البلاجن: ContentProvider على الجهاز (authority "<package>.bridge") بيرد JSON؛ Joyboy بيرفض يكلّمه لو توقيعه مختلف. من غير أي مكتبة جديدة في الـAPK الأساسي.
3. ستريمر: لو بلاجن إضافات Aniyomi متثبّت، مصادره بتظهر في قايمة المصادر. ولو مش متثبّت فيه زرار يفتح صفحة البلاجن.
4. البلاجن نفسه (APK منفصل) لسه ما اتنشرش: القايمة بتعرضه «قريبًا». مفيش أي سورسات جوه النظام ده.
5. تعديل في AndroidManifest: سطر queries لاسم باكدج بلاجن Aniyomi (لازم لأندرويد 11+ عشان Joyboy يشوفه).
⚠ ما اتعملش compile ولا اتجرّب على جهاز: الـCI هو اللي هيتأكد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1256.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/kt_manifest.txt
✏️ عدّل:   app/src/main/AndroidManifest.xml
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
➕ أضاف:  app/src/main/java/com/tttt/subtitler/Plugins.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Streamer.kt
➕ أضاف:  plugins/index.json
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1255-run67` (للرجوع: Actions ← Rollback)
