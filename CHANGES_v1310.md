# v1310 — إصلاح بيلد v1309

## إصلاحات
- 🛠 **YtWatch.kt**: تعليقين من v1308 كانوا آكلين أقواس قفل `}` في سطرين (قاعدة «مفيش // قبل قوس في نفس السطر» اتكسرت) — ده كسّر parsing الملف كله وخلّى كل تعريفاته (ChSeekBar/YtDock/YtKept/YtDockHold/YtDockCfg/sendComment…) «unresolved» في الملفات التانية. اتصلح القوسين ورجع التعليق لآخر السطر.
- 🛠 **MusicUi.kt**: علامات \s في الـRegex اتكتبت بباك سلاش واحد بدل اتنين (Unsupported escape sequence ×7) — اتصلحت.

مفيش ميزات جديدة — ده إصلاح بيلد خالص لـv1309 (اللي فيه: تنضيف التاجات + بادج التعارض + إخفاء الكبسولة + رقيب التجمّد + doNotStrip).

## الملفات
- YtWatch.kt · MusicUi.kt


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1310.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MusicUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtWatch.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1309-run121` (للرجوع: Actions ← Rollback)
