# v1254 — ستريمر (صفحة بث جديدة)
## ستريمر
1. صفحة جديدة «🎞 ستريمر» في المزيد: بتتصفح قوايم وحلقات من «سورسات» وتشغّل في مشغّل Joyboy نفسه وتحمّل بمدير التحميل بتاع Joyboy (بتظهر في المهام). من غير إعلانات ومن غير أي مكتبة جديدة (الإضافة ملف Streamer.kt واحد).
2. سورس مدمج قانوني: Internet Archive (أفلام وكرتون وأنمي عام) عشان الدورة كلها (بحث ← حلقات ← تشغيل ← تحميل) تشتغل من أول مرة.
3. إضافة سورس خارجي: زرار «➕ إضافة سورس (JSON)» بتلصق وصف السورس (name وbase وsearch.url وsearch.item وepisodes.item وstream.item اختياري، كلها regex بأسماء url/title/img). لو stream مش متحدد بيدوّر لوحده على لينكات m3u8/mp4 في صفحة الحلقة وفي iframe.
4. السورسات المضافة بتتخزن على الجهاز بس، وفيه زرار «احذف السورس ده».
5. مش بيحمّل إضافات Aniyomi الجاهزة (دي محتاجة مكتبات كتير: OkHttp وrxjava1 وjsoup وinjekt وkotlinx).
⚠ ما اتعملش compile ولا اتجرّب على جهاز: الـCI هو اللي هيتأكد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1254.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/kt_manifest.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
➕ أضاف:  app/src/main/java/com/tttt/subtitler/Streamer.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1253-run65` (للرجوع: Actions ← Rollback)
