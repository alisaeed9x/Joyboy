# v1315 — إصلاح بيلد v1314

## الإصلاح
- HideBox.kt: `File.copyTo` في كوتلن بيرجّع File مش Boolean — كان مكتوب `copyTo(...) && delete()` فكسر الكومبايل (سطر 59 و78). بقوا سطرين متتاليين: `copyTo(...); delete()`.

## الملفات
- HideBox.kt


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1315.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/HideBox.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1314-run126` (للرجوع: Actions ← Rollback)
