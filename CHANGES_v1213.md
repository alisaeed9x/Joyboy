# Joyboy v1213 — إصلاح فشل البيلد (فوق v1212)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## 🐞 اتصلح
- **البيلد كان بيفشل بـ121 خطأ** (من v1206): في ملف `MtGlass.kt` تعليق كان مكتوب في نص سطر الكبسولة العايمة وبلع قفلة القوس بتاعة الكود، فباقي الملف اتقرا غلط وطلعت أخطاء في Motion وGlass وLibraryUi وControllerPad وغيرهم. القوس رجع مكانه والتعليق اتنقل لآخر السطر.

## ⚠️ خد بالك
- مفيش تغيير في الشكل أو الوظايف؛ سطر واحد بس.

<details><summary>التفاصيل التقنية</summary>
MtGlass.floatPill سطر 84: `val base = GradientDrawable().apply { setColor(...)   // (v1206) ...; cornerRadius = radiusPx }` ← `setColor(...); cornerRadius = radiusPx }   // (v1206) ...`.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1213.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtGlass.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1212-run24` (للرجوع: Actions ← Rollback)
