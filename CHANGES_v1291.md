# Joyboy v1291 — صفحة «أنت» زي يوتيوب الرسمي + العدسة بالأزرق + اللينك المنسوخ لـyt-dlp (فوق v1290)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- صفحة «أنت» اتبنت من الأول زي صفحة You في يوتيوب الرسمي: كارت حسابك فوق (صورتك واسمك وإيميلك)، وتحته أرفف أفقية بالصور — سجل المشاهدة، أكمل المشاهدة، المحفوظات — وكل رف فيه «عرض الكل».
- زراير «مزامنة الاشتراكات» و«تسجيل الخروج» بقت في كارت الحساب نفسه بدل ما تكون سطور تحت.
- المتصفح: لو فيه لينك منسوخ في الكليبورد، هتلاقي زرار أزرق «📋 yt-dlp: نزّل اللينك المنسوخ» في قايمة العدسة — دوسة واحدة وينزّل بـyt-dlp.

## 🔧 اتظبّط
- عدّاد العدسة لما تلقط فيديو بقى **أزرق** (بدل الأحمر) زي ما طلبت.

## ⚠️ خد بالك
- جرّب صفحة «أنت» كويس: الرف بيتسكرول بالعرض، ودوسة على أي فيديو فيه بتشغّله، وضغطة طويلة بتفتح القايمة المعتادة.

<details><summary>التفاصيل التقنية</summary>
YtProUi.kt: إعادة بناء openYou — kind 7 (كارت الحساب: YHdrHold/youHeaderView) وkind 6 (رف أفقي: YShelfHold/youShelfView + youShelves). Adapter بقى فيه 6 view types. MtYt.kt: accountName/accountEmail/accountAvatar من كاش MeeTube. ClipWatch.kt: countColor قابل للتغيير. Browser.kt: العدّاد أزرق عند صيد فيديو + زرار اللينك المنسوخ في renderList.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1291.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Browser.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/ClipWatch.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtYt.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtProUi.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1290-run102` (للرجوع: Actions ← Rollback)
