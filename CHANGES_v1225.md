# Joyboy v1225 — شاشة «المزيد» (مركز الميزات) بالشكل الجديد

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد (في الشكل الجديد بس)
- شاشة «المزيد»: بلاطات أكبر، 3 في الصف بدل 4، وكل أيقونة جوه دايرة ملوّنة بلون التطبيق، وخط أوضح.
- الشكل القديم زي ما هو.

<details><summary>التفاصيل التقنية</summary>
BottomNav.kt: tileView() و moreSheet() بيفرّقوا على Design.isNew() (حجم الأيقونة ودايرتها، الـpadding، عدد الأعمدة).
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1225.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1224-run36` (للرجوع: Actions ← Rollback)
