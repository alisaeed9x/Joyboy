# v1312 — سلايدر البلور/الشفافية بيتطبق لحظيًا + البلور بيوصل للبوب-أب

## المطلوب (طلب علي 1:11)
- 🎚 **السلايدر بيشتغل لايف وهو بيتحرك**: من غير ما تقفل الإعدادات أو تعيد فتح الشاشة — أي كارت زجاجي على الشاشة بيتحدّث فورًا مع السحب (MtGlass.fireLevel + سجل liveGlass في Ui.card).
- 🪟 **البلور بيوصل للبوب-أب والحوارات**: نافذة أي GDialog بتتبلّر ورا المحتوى بدرجة محسوبة من مستوى السلايدر (blurBehindRadius ديناميكي)، وأي حوار مفتوح دلوقتي بيتحدّث ستايله لحظيًا مع تغيّر المستوى (سجل GDialog.showing + restyleLive).

## الملفات
- MtGlass.kt (fireLevel/onLevel/liveGlass)
- Glass.kt (blurBehindRadius ديناميكي + سجل الحوارات المفتوحة + restyleLive)
- Ui.kt (تسجيل الكروت للتحديث اللحظي)
- Main.kt (كولباك السلايدر + تلميح «بيتطبق لحظيًا…»)


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1312.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Glass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtGlass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Ui.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1311-run123` (للرجوع: Actions ← Rollback)
