# v1289 — yt-dlp كمحرك تحميل (أول خطوة)
## تحميل
1. [ميزة جديدة] yt-dlp (youtubedl-android: yt-dlp + ffmpeg) مدموج كمحرك تحميل. بيتسجّل في مركز المهام بتقدّم ونسبة، والملف بيتحفظ في Download/Subtitler.
2. [ميزة جديدة] في المتصفح: في قايمة العدسة زرار أزرق «⬇ yt-dlp: نزّل الصفحة دي» يبعت لينك التبويب الحالي لـyt-dlp (أي موقع بيدعمه).
## ملاحظات
1. ده أول جزء بس: لسه ما استبدلتش مركز التحميل القديم، ولا ربط الكليبورد، ولا شارة «1» الزرقا (العدسة فيها عدّاد بالفعل).
2. الـ32-bit متحفظ عليه (armeabi-v7a). الحجم هيزيد بسبب مكتبات yt-dlp/ffmpeg.
3. ما اتعملش compile ولا اتجرّب على جهاز؛ لو الـCI وقع هصلّح في الباتش اللي بعده.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1289.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/build.gradle.kts
✏️ عدّل:   app/kt_manifest.txt
✏️ عدّل:   app/proguard-rules.pro
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Browser.kt
➕ أضاف:  app/src/main/java/com/tttt/subtitler/YtDlp.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1288-run100` (للرجوع: Actions ← Rollback)
