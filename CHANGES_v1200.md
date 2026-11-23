# Joyboy v1200 — اللهجات التانية ترجع في الكود (مخفية بس) (فوق v1199)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## 🐞 اتصلح
- في v1199 كنت فرضت أي لهجة قديمة (شامي/خليجي/…) تتقري «مصري» وشلت تعريفاتها من برومبت اللهجة. دلوقتي رجعت زي ما كانت: **الزراير بتعرض فصحى ومصري بس، لكن باقي اللهجات لسه موجودة في الكود ومش بتتغيّر**.

<details><summary>التفاصيل التقنية</summary>
TrSlider.kt (TrNorm.dialect بقت pass-through) · ModePrompts.kt (dialectEn رجعت بكل اللهجات).
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1200.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/ModePrompts.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrSlider.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1199-run11` (للرجوع: Actions ← Rollback)
