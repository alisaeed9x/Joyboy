# Joyboy v1197 — وضع «🔑 مفتاح واحد» في الترجمة (فوق v1196)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- محطة جديدة في سلايدر الترجمة: **أسرع ← سريع ← 🔑 مفتاح واحد ← متوازن ← دقيق**. كمان ظهرت في قايمة «نظام الترجمة».
- **المفتاح الأول (المترجم):** بياخد الباتشات واحد ورا التاني بالتوالي (مش بالتوازي)، يفرّغ ويترجم، وبعدين يرتاح (8 ثواني من بداية الباتش، تتغيّر من `onekey_rest_ms`) عشان ما يوصلش لخطأ 429.
- **المفتاح التاني (التأكيدي):** كل ما باتش جديد يخلص، بياخد التفريغ الأصلي لكل الباتشات اللي خلصت (القديم والجديد، لحد آخر 300 جملة) ويبعته لجيميناي يترجمه **بالسياق**، وبعدين يحط النتيجة مكان الترجمات القديمة.
- **لو باتش وقع:** بيروح لمفتاح فاضي تاني لو موجود، ولو مفيش بيستنى المفتاح التأكيدي يفضى ويستخدمه. في الآخر بيعيد الباتشات اللي وقعت مرة كمان.
- لو فيه مفتاح واحد بس: المراجعة بتحصل مرة واحدة في الآخر على نفس المفتاح بعد راحة.

## ⚠️ خد بالك
- التأكيدي هو أول مفتاح من (مفاتيح التنقيح ← الاحتياطي ← باقي الأساسي) غير المترجم.
- الباتش الأول مفيهوش «خطوات تصاعدية» في الوضع ده (عشان ما نزودش الطلبات).
- المراحل الجاية: تنضيف أزرار صفحة الميديا ودمج المهام مع مركز التحميل، وبعدها كاش صفحة الميديا.

<details><summary>التفاصيل التقنية</summary>
FastTr.kt: oneKey + runOneKey / confirmLoop / confirmBatches / oneKeyPick · TrSlider.kt: ONE zone + 5 stations · Player.kt: ft.oneKey + slider UI + remote tr_gem_one · MtTranslate.kt: menu item + tr_one=0 on other modes · TrProfile.kt: tr_one saved with the profile.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1197.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/FastTr.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtTranslate.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Player.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrProfile.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrSlider.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1196-run8` (للرجوع: Actions ← Rollback)
