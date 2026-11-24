# v1281 — حذف فتح كلام + قسم التلفزيون + شريط الشفافية والبلر
## ميزات
1. [ميزة جديدة] قسم «📺 التلفزيون» في الإعدادات الرئيسية، تحت المتصفح ويوتيوب مباشرة (فيه إعدادات التلفزيون الموجودة أصلًا).
2. [ميزة جديدة] شريط «🪟 الشفافية والبلر» في المظهر (قسم الشكل): 50 = الشكل الحالي، أكتر = أشفّ وأبلر، أقل = أعتم وأخف.
## حذف
1. [حذف] ميزة «فتح كلام» اتشالت بالكامل: التبويب والبلاطة وصفحاتها والإعدادات والأسئلة المدمجة (3745 سؤال) والكود.
## ملاحظات
1. الشفافية والبلر بتأثر على الشيتات والكروت الزجاجية، وبتظهر في الشيتات الجاية أو بعد قفل وفتح الصفحة. بعض عناصر الزجاج المرسومة من موارد MeeTube نفسها ممكن ماتتأثرش.
2. الترتيب: اللغة ← المتصفح ← يوتيوب ← التلفزيون ← تخصيص البلاطات ← باقي الأقسام.
⚠ ما اتعملش compile ولا اتجرّب على جهاز: الـCI هو اللي هيتأكد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1281.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/kt_manifest.txt
🗑️ حذف:   app/src/main/assets/fk/seed.json
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/FkData.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/FkGen.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/FkUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtGlass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/mtui/SheetGlass.java
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1280-run92` (للرجوع: Actions ← Rollback)
