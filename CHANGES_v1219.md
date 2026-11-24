# Joyboy v1219 — مفتاح الشكل قديم/جديد (فوق v1218)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- **مفتاح الشكل**: في «المزيد ← النظام والتحديث» زرار «الشكل: قديم/جديد». **الافتراضي القديم**: ألوان وحركات v1217 زي ما كانت بالظبط. الجديد (داكن أبيض بنعناعي/بنفسجي، مؤشر متدرج) بيشتغل لما تختاره.

## ⚠️ خد بالك
- v1218 كان بيغيّر الداكن لوحده؛ من v1219 ده بقى وراء المفتاح، فالداكن بيرجع زي القديم لحد ما تختار الجديد.
- جرّب تبديل الشكل: التطبيق بيعيد فتح الشاشة.

<details><summary>التفاصيل التقنية</summary>
Theme.kt: object Design (Cfg ui_new، الافتراضي false) + كاش الثيم بيراعيه. BottomNav.kt: بند الشكل + المؤشر المتدرج بس في الجديد. Glass.kt/Motion.kt: قيم v1217 لما الشكل قديم.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1219.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Glass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Motion.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Theme.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1218-run30` (للرجوع: Actions ← Rollback)
