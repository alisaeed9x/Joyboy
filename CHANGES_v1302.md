# الإصدار 13.02

- 📦 **تقسيم التحميل حسب المعمارية (ABI split)**: كل ريليز بقى فيه 3 نسخ APK من نفس البيلد بدل نسخة universal واحدة:
  - `arm64-v8a` — للموبايلات الحديثة (أصغر تحميل بكتير، من غير مكتبات 32-bit).
  - `armeabi-v7a` — لأجهزة 32-bit (التلفزيونات وبوكساتها القديمة) — زي ما هي شغّالة.
  - `universal` — نسخة احتياطية شغّالة على أي جهاز.
  كلهم في الريليز الواحد، والتحديث من جوه البرنامج بيختار نسخة جهازك تلقائي (مع رجوع للـuniversal لو لزم).
- ⬇ **الجودة والحجم قبل التحميل (yt-dlp)**: قايمة التحميل بتاعة العدسة بقت بتعرض الجودات الحقيقية المتاحة في المصدر وجنب كل واحدة الحجم التقريبي (فيديو + صوت) قبل ما تدوس — بتتجاب من معلومات yt-dlp من غير أي تحميل. لو الحجم مش متاح لمصدر معين بتظهر الجودة بس. القايمة بتفضل فيها «أعلى جودة» و«صوت بس (mp3)» بحجمه التقريبي.


## 🗂️ الملفات اللي اتغيّرت
```
➕ أضاف:  CHANGES_v1302.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/build.gradle.kts
✏️ عدّل:   .github/workflows/build.yml
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/UpdateSheet.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Upd.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtDlp.kt
```


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1302.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/build.gradle.kts
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Upd.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/UpdateSheet.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtDlp.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1301-run113` (للرجوع: Actions ← Rollback)
