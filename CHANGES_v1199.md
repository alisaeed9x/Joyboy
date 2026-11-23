# Joyboy v1199 — تبسيط الترجمة: 3 أوضاع · لهجتين · أسلوبين (فوق v1198)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد / اتغيّر
- **سلايدر الترجمة بقى 3 محطات بس:** ⚡ سريع · ⚖ متوازن · 🎯 دقيق. «أسرع» و«سريع» اتدمجوا في «⚡ سريع» (على نفس إعدادات «سريع» القديم: برومبت خفيف + سباق مفتاحين، مع بلوكات الدقة بتاعته).
- **اللهجات:** فصحى + مصري بس. باقي اللهجات اتشالت من كل القوايم، وأي لهجة قديمة محفوظة (شامي/خليجي…) بتتقري كـ«مصري».
- **الأسلوب:** زرارين بس — **حرفي** (يترجم زي ما اتقال بالملي: نفس الألفاظ والشتايم من غير تخفيف ولا تنميق، وده دمج حرفي + شعبي + جرئ + تلقائي) و**مناسب للمشاهدة العائلية** (الألفاظ الخارجة بتتخفف: «ثدي» بدل اللفظ الصريح، و«يا ابن الكلب» بدل الشتيمة الثقيلة). أي أسلوب قديم محفوظ بيتقري «حرفي».
- **ألوان الجنس (الأزرق/الوردي) اتشالت:** الترجمة بقت بيضا عادي، **والأسامي بس بتبان صفرا**. (سطور المتحدثين المتداخلين لسه كل واحد بلون مختلف عن التاني من غير ما يعتمد على الجنس.)

## 🗑️ اتشال
- وضع **🔑 مفتاح واحد** (من السلايدر ومن قايمة «نظام الترجمة» ومن ريموت التلفزيون).
- أداة **🔞 صريح** (كانت بتزوّد شتايم وألفاظ من عندها).
- شدّة الأسلوب (خفيفة/متوسطة/شديدة) من قوايم الأسلوب — مبقاش ليها لازمة مع حرفي/عائلي.

## ⚠️ خد بالك
- الأسلوب (حرفي/عائلي) بقى بيتطبّق دايمًا مهما كان وضع السلايدر.
- كود المحرك بتاع «مفتاح واحد» لسه موجود جوه FastTr.kt بس مقفول ومش بيتشغّل (محدش بيوصله).
- لو فيه فيديو قديم متترجم بأسلوب/لهجة قديمة، هتتعرض في سجل اللوجات باسمها القديم لكن أي ترجمة جديدة بتمشي بالحرفي/العائلي والفصحى/المصري.

<details><summary>التفاصيل التقنية</summary>
TrSlider.kt (3 محطات + TrNorm: توحيد اللهجة/الأسلوب القديم) · Core.kt (Cfg.str بتعدّي على TrNorm + سطر «متحذفش») · Engine.kt (toneBlock: حرفي/عائلي، toneB دايمًا) · ModePrompts.kt (dialectEn/styleEn) · ExtTranslate.kt · SubFileTr.kt (styleOf) · Player.kt (شيل مفتاح واحد وصريح، أزرار الأسلوب، toneInstrOf، قايمة 🌐) · PlayerPad.kt · MtBar.kt · MtTranslate.kt · Prompts.kt · Main.kt · TrLib.kt · TrWizard.kt · SubStyle.kt/SubtitleView.kt (ألوان) · SubStyleTest.kt.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1199.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Core.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Engine.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/ExtTranslate.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/ModePrompts.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtBar.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtTranslate.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Player.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PlayerPad.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Prompts.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SubFileTr.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SubStyle.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SubtitleView.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrLib.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrSlider.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrWizard.kt
✏️ عدّل:   app/src/test/java/devtests/substyletest/SubStyleTest.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1198-run10` (للرجوع: Actions ← Rollback)
