# Joyboy v1360 — سلاسة وأنيميشن خفيف (بطلب علي)

1. [ميزة جديدة] أنيميشن ظهور واختفاء ناعم لكل الشيتات (القوائم اللي بتطلع من تحت: الإعدادات والمتصفح والملفات وحالة المفاتيح وغيرها) — فيد + انزلاق خفيف 190ms بدل الظهور الفجائي. الملفات: HtmlUi.kt + res/anim/sheet_enter.xml + res/anim/sheet_exit.xml + res/values/joy_anims.xml
2. [ميزة جديدة] فيد خفيف 160ms عند فتح صفحة مشاهدة يوتيوب — الفتح لسه فوري وخفيف، بس الصفحة بتظهر بنعومة بدل الوميض اللحظي (بطلب علي: «أنيميشن بسيط بدون لاج زي فيدينج»). الملف: YtWatch.kt
3. [ميزة جديدة] تفعيل التنبؤ بزر الرجوع (Predictive Back) على أندرويد 13 وأحدث — لما تمسك زر/إيماءة الرجوع هتشوف لمحة من الشاشة اللي رايح لها قبل ما تكمّل. الملف: AndroidManifest.xml

ملاحظة السلاسة: Baseline Profile وتصغير R8 وفتح تردد الشاشة العالي متفعّلين أصلًا من v834 وv1028 وv988 — اتأكدنا إنهم شغالين، من غير ما نعيد بناء حاجة شغالة.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1360.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/AndroidManifest.xml
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/HtmlUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtWatch.kt
➕ أضاف:  app/src/main/res/anim/sheet_enter.xml
➕ أضاف:  app/src/main/res/anim/sheet_exit.xml
➕ أضاف:  app/src/main/res/values/joy_anims.xml
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1359-run169` (للرجوع: Actions ← Rollback)
