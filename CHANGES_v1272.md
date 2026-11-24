# v1272 — سجل التغييرات بالتصفح + إخفاء البلجن
## مركز التحديث
1. [ميزة جديدة] زرار «سجل التغييرات» في مركز التحديث: بيفتح على آخر إصدار، وزرار يمين للأقدم وزرار شمال للأحدث، وعرض بالكبسولات.
2. [إصلاح] مركز التحديث بقى يتجاهل أي Release خاص بالبلجن (plugin-aniyomi) في فحص التحديث والتنزيل وسجل التغييرات: بياخد آخر Release للتطبيق نفسه بس. (الـRelease نفسه لسه على GitHub زي ما هو.)
3. [إصلاح] فحص الـartifacts بيتجاهل أي ملف اسمه فيه plugin أو aniyomi.
## ملاحظات
1. [تعديل] لو علي لسه شايف البلجن بعد التحديث للنسخة دي، ابعتلي إيه اللي ظاهر بالظبط على الشاشة.
⚠ ما اتعملش compile ولا اتجرّب على جهاز: الـCI هو اللي هيتأكد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1272.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/kt_manifest.txt
➕ أضاف:  app/src/main/java/com/tttt/subtitler/Changelog.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/GhCi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Upd.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/UpdateSheet.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1271-run83` (للرجوع: Actions ← Rollback)
