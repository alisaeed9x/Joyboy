# v1337 — تنضيف كود الموسيقى الميت + الألبوم من LRCLIB

## إصلاحات
1. [حذف] اتمسح الكود الميت فعليًا (~36 ألف حرف): نوافذ التصحيح القديمة («المعلومات غلط» و✏️ ونافذة الـcheckboxes و«البوستر غلط» واختيار الغلاف بإيدك)، التعرّف القديم الموزّع على المفاتيح (slowIdentify + SongId.smart + AudD)، قوائم اختيار الاسم والمرشحين، نافذة ⏱ توقيت الكلمات، وأزرار الفيديو المشلولة (🎬 و«مشاهدة الفيديو») — كلها كانت متشالة من الواجهة في v1330 وكودها فاضل شغّال في الخفاء.
2. [تحسين] الألبوم بيتملّى من LRCLIB: نتيجة الكلمات من LRCLIB فيها اسم الألبوم، ودلوقتي بيتحفظ مع الأغنية (لو فاضي) والبوستر بيتجاب بيه — بدل طلب جيميناي إضافي كان بيتعمل عشان الألبوم بس.
3. [تحسين] لو الألبوم وصل من LRCLIB بعد ما الغلاف اتجلب، الغلاف بيتجلب تاني أوتوماتيك باسم الألبوم الجديد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1337.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/FastId.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Lyrics.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MusicUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Poster.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SongId.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1336-run145` (للرجوع: Actions ← Rollback)
