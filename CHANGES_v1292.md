# Joyboy v1292 — إصلاح بناء v1291 + شريط الاشتراكات + مش مهتم + yt-dlp باختيار الجودة (فوق v1291)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## إصلاح
1. [إصلاح] خطأ compile في YtProUi.kt من v1291 (HorizontalScrollView.LayoutParams — كوتلن مابيشوفش الكلاسات المتداخلة من الكلاس الابن).

## ✨ جديد
- شريط دواير القنوات فوق صفحة الاشتراكات (زي يوتيوب الرسمي): صورة واسم كل قناة، ودوسة تفتح صفحتها.
- «🚫 مش مهتم» و«🚫 ماتقترحش القناة دي» في قايمة الضغطة الطويلة على أي فيديو — بتتخبّى من الرئيسية.
- yt-dlp بقى متربط بأي فيديو: قايمة التحميل فيها «yt-dlp — أعلى جودة mp4» و«yt-dlp — صوت mp3».
- زراير yt-dlp في المتصفح (الصفحة الحالية + اللينك المنسوخ) بتسأل الأول: أعلى جودة / 720p / 480p / صوت mp3.
- تابات قناة زيادة لو موجودة: الألبومات · بودكاست · الإصدارات.

## ⚠️ خد بالك
- «مش مهتم» لسه مالوش شاشة إدارة (تراجع) — لو خبّيت حاجة بالغلط قولّي أضيف صفحة ليها.

<details><summary>التفاصيل التقنية</summary>
YtProUi.kt: FrameLayout.LayoutParams بدل HorizontalScrollView.LayoutParams · شريط ythshelf:subs في bind() لتاب 1 · menu items 18/19 (مش مهتم) · dlMenu فيه yt-dlp. YtStore.kt: notInt/hide. YtDlp.kt: ask() + maxH. Browser.kt: زراير yt-dlp ← ask. YtNp.kt: تابات albums/podcasts/releases.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1292.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Browser.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtDlp.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtNp.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtProUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtStore.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1291-run103` (للرجوع: Actions ← Rollback)
