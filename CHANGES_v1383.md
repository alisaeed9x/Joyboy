# Joyboy v1383 — إصلاح السبب الحقيقي لضياع أول الصوت (فوق v1382)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## 🐞 اتصلح
- الفك من 0 كان بيرجع «صفر فريمات» (حتى لفيديو ١٧١ث) بينما أي بداية بعد كده (٣٠ث، ٦٠ث…) كانت بتنجح — فأول جزء بس هو اللي بيضيع. السبب: الكود كان بيعتبر أي وقت سالب للعينة «نهاية ملف». أول عينة صوت في ملفات واتساب (AAC بـ edit-list) وقتها سالب صغير، فالفك كان بيقفل فورًا. دلوقتي السالب بيتعامل كـ٠ والنهاية الحقيقية بس لما مفيش عينات.
- المحاولة الطازجة التانية بتستخدم نوع seek مختلف.

## ✨ جديد
- لو الفك طلّع فاضي، اللوج بيقول سبب الوقف المبكر (`EOS مبكر: n=… t=…`).

<details><summary>التفاصيل التقنية</summary>

`AudioIO.kt` — `Decoder.decode`: شرط EOS بقى `n < 0 || t >= endUs+300ms`، و`queueInputBuffer` بوقت `max(t,0)`؛ `FileSource.wav`: `SEEK_TO_NEXT_SYNC` عند الإعادة. `AudioCore.kt` — حقل `PcmSink.dbgNote`.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1383.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/AudioCore.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/AudioIO.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1382-run192` (للرجوع: Actions ← Rollback)
