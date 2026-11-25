# v1308 — البيلد النهائي: التعليقات + yt-dlp بيحدّث نفسه + fallback للتحميل

## yt-dlp (بلاغ علي بالسكرين شوت: «النسخة أقدم من 90 يوم»)
- 🔄 **yt-dlp بيحدّث نفسه من جوّه التطبيق**: تحديث صامت في الخلفية كل 3 أيام (آخر نسخة رسمية من yt-dlp)، فمشكلة «أقدم من 90 يوم» مش هترجع.
- 🔁 **إصلاح فوري وقت الفشل**: لو تحميل فشل برسالة النسخة القديمة/خطأ، التطبيق بيحدّث yt-dlp ويعيد المحاولة لوحده.
- ⬇ **زرار «حدّث محرك التحميل (yt-dlp)»** في إعدادات يوتيوب للتحديث اليدوي بأي وقت.
- 🛟 **fallback تلقائي**: لو yt-dlp فشل نهائيًا، التحميل بيكمّل لوحده بالمحمّل العادي (الروابط المباشرة) في مهمة جديدة — التحميل مش بيتعطل خالص. (القص ✂ مستثنى عشان المحمّل العادي مبيعملش قص).
- ⚡ **قايمة الجودات بتفتح فورًا**: كل الجودات (أعلى جودة · 4K · 2K · 1080 · 720 · 480 · 360 · mp3) بتظهر على طول، وحجم كل جودة بيتحمّل ويظهر جنبها لما يجهز (تحميل كسول)، والجودات الأعلى من الفيديو نفسه بتتشال.

## التعليقات
- 💬 **شيت التعليقات القابل للسحب**: على الموبايل التعليقات بقت بتفتح شيت عايم فوق الفيديو بيتقفل بالسحب لتحت — زي يوتيوب الرسمي (على التلفزيون زي ما هو).
- ✔ **علامة التوثيق في التعليقات**: صاحب التعليق الموثّق بيظهر جنب اسمه ✔ (من محركي التعليقات NewPipe وMeeTube).

## أنيميشن فتح الفيديو (بند 4)
- اتأكدنا إنه موجود ومتوصل: الكارت بيتوسّع من مكانه لملء الشاشة عند فتح أي فيديو من القوايم (الرئيسية/البحث/الاشتراكات) — مفيش تعديل محتاج.

## الملفات
- YtDlp.kt · YtWatch.kt · YtNp.kt · YtAccount.kt · Main.kt · meetube CommentsItems.kt · CommentItem.java · CommentItemImpl.kt


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1308.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtAccount.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtDlp.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtNp.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtWatch.kt
✏️ عدّل:   meetube/MediaServiceCore/mediaserviceinterfaces/src/main/java/com/liskovsoft/mediaserviceinterfaces/data/CommentItem.java
✏️ عدّل:   meetube/MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/comments/gen/CommentsItems.kt
✏️ عدّل:   meetube/MediaServiceCore/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/comments/impl/CommentItemImpl.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1307-run119` (للرجوع: Actions ← Rollback)
