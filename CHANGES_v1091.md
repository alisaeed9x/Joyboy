# Joyboy v1091 — دمج: قاعدة v1068 + ⚖ متوازن (FastTr من v1090)

> ما اتعملش compile ولا اتجرّب على جهاز — Actions هو الحكم.

- القاعدة: v1068 (MeeTube كامل + Glass + أنيميشن + ⚡ سريع + ٧٥/٢٥ + 🎯 الأدق).
- جديد: وضع رابع «⚖ متوازن» = المحرك البسيط FastTr (من v1090): كل مفتاح باتش، يفرّغه فيظهر فورًا، وبعدين يترجمه.
  - مفتاح الإعداد: `tr_bal` (1 = متوازن). أي اختيار تاني من القايمة بيصفّره.
  - في قايمة جيميناي (Player.geminiAsk) وفي قايمة MeeTube (MtTranslate) + أمر ريموت `tr_gem_bal`.
  - بيشتغل على ترجمة جيميناي الصوتية (WAV/AAC) بس؛ الباقي على المحرك القديم.
  - ⏸/▶ والإلغاء والنسبة (٪) والإشعار متوصّلين بيه.
- رجّعت `.github/workflows/build.yml` (v1068 ما كانش فيه workflow).
- ملفات: `FastTr.kt` (جديد + kt_manifest) · `Player.kt` · `MtTranslate.kt`.
- ما اتنقلش: FAST_SPEED=0.5 (محتاج AudioEnc v1070) · TrSlider/TrProfile · إصلاحات Engine v1088.
