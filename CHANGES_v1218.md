# Joyboy v1218 — الدفعة الأولى من الشكل الجديد: داكن AMOLED بأبيض وألوان حيّة (فوق v1217)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- **الداكن**: الخلفية لسه أسود AMOLED، بس النص بقى أبيض صريح، واللون الأساسي نعناعي (`#7CF3D0`) والثانوي بنفسجي (`#8B7CFF`) بدل الكريمي الباهت. التقدّم والتشغيل بنعناعي.
- **الشريط السفلي في الداكن**: مؤشر الصفحة النشطة بقى تدرّج نعناعي ← بنفسجي شفاف.
- **الفاتح ما اتغيّرش**: كريمي بالبني زي ما هو.
- ضغطة الأزرار أنعم ونبض زرار المهام أهدى.

## ⚠️ خد بالك
- دي دفعة أولى بس، من الملفات المركزية. التصميم الكامل (كروت كبيرة، قوائم بالطول، مكتبات زي BlurView وSmoothBottomBar) هييجي على دفعات. ما اتضافتش أي مكتبة في الدفعة دي عشان البيلد مش بيتجرّب قبل الرفع.
- جرّب الداكن على الجهاز: وضوح النص الأبيض واللون النعناعي على الأزرار والتحديد.

<details><summary>التفاصيل التقنية</summary>
Theme.kt: ثيم vanilla_dark (primary 7CF3D0 · accent 8B7CFF · text FFFFFF) و progress داكن 7CF3D0. BottomNav.kt: تدرّج المؤشر في الداكن. Glass.kt: pressable 0.97. Motion.kt: breathe 1.04 / 1200ms.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1218.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Glass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Motion.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Theme.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1217-run29` (للرجوع: Actions ← Rollback)
