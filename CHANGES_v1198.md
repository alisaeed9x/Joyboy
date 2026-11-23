# Joyboy v1198 — تنضيف صفحة الميديا + المهام ومركز التحميل صفحة واحدة (فوق v1197)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## 🗑️ اتشال
- من هيدر صفحة الميديا: زرار **▣ التحديد** (بيظهر وقت التحديد بس — التحديد بالضغطة المطوّلة على أي صف)، و**🔗 اللينك**، و**📂 الفولدرات**، و**🔄 الريفريش**. فاضل **📋 المهام** و⚙️ و☰. اللينك لسه موجود في القايمة الجانبية ☰، والريفريش بالسحب لتحت.

## ✨ جديد
- **المهام ومركز التحميل بقوا صفحة واحدة**: اسمها «📋 المهام» (في الشريط وفي تبويب المخزن). فوق: طابور ترجمة الخلفية (بيظهر لو فيه حاجة)، وتحتيه التحميلات والنقل. زرار 📋 في الميديا بيفتح نفس الصفحة بدل النافذة المنفصلة.
- **🎧 تنزيل الباتشات المرقمة** بقى بيتسجّل في المهام: بتشوف التقدّم (الباتش كام من كام)، وتقدر تلغيه أو تعيده لو فشل، وبتلاقي مكان الملف (Download/Joyboy_Batches) لما يخلص.

## ⚠️ خد بالك
- لسه فاضل المرحلة 3: صفحة الميديا تفتح على آخر بيانات محفوظة فورًا وتتحدّث في الخلفية.

<details><summary>التفاصيل التقنية</summary>
LibraryUi.kt (الهيدر) · QueueUi.kt (content) · TasksUi.kt (bgSec + bgq) · Main.kt (onQueue → showTasksDialog + label) · MediaSwitch.kt (label) · BatchExport.kt (TaskCenter.add kind=export + work()).
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1198.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BatchExport.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/LibraryUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MediaSwitch.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/QueueUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TasksUi.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1197-run9` (للرجوع: Actions ← Rollback)
