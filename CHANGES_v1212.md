# Joyboy v1212 — توحيد ألوان الدايلوجات والقوايم والإشعارات (فوق v1211)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- **الإشعارات، قايمة اختيار الترجمة، السبينر، حلقة المهام، قايمة التشغيل، الميني دوك، شريط المشغّل، لوحات القوايم (Player/MtBar)**: الأزرق والبنفسجي والرمادي المزرق بقوا Espresso / Latte / Mocha الدافية.
- **أزرار التحميل والبحث والتبويبات المختارة** (فقاعة اللينك، علامة الفيديو، مراقب الكليبورد، المتصفح، البحث الموحّد، شريط الترجمة في PiP): بقت Mocha بدل الأزرق.
- **حالة «بتترجم» في زرار الترجمة**: بقت فيروزي هادي (بدل الأزرق) عشان تفضل واضحة جنب الأخضر (خلصت) والأحمر والكهرماني (إيقاف مؤقت).
- **كرة المشغّل (Orb)**: الخلفية البنفسجية بقت بنّي دافي.

## ⚠️ خد بالك
- ألوان بس؛ مفيش تغيير في أي وظيفة أو مكان زرار.
- اتساب عمدًا: شاشات الريموت (IR) والتلفزيون (فوكس/D-pad)، لون وضع التصفح الخفي (بنفسجي = علامة الخصوصية)، سلايدر الفيديو (HtmlUi)، ويدجتس الشاشة الرئيسية، والأحمر/الأخضر الدلاليين.
- جرّب على الجهاز: وضوح النص الأبيض فوق أزرار Mocha، وإن حالة الترجمة الفيروزي مفرّقة عن الأخضر.

<details><summary>التفاصيل التقنية</summary>
استبدال ألوان ثابتة (66 مكان، hex بس): Player (1F5FBF→8B6B50 وحالات الترجمة→2A8C8C · 455A64→5A4B3F · BFD0FF→E6D8C5 · 2E2E3C/121219/242432→3A322B/1A1612/2B241F) · Notice · SubSelect · Spinner · StatusStrip · TaskRing · PlayQueueUi (ACC→4DB6AC) · MiniDock · MtBar · OrbPlayer · PipSubBar · UniSearch · LinkBubble · VidMark · ClipWatch · Main · Browser (232838 · 1E6BFF · #4f8cff). الألفا محفوظة.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1212.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Browser.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/ClipWatch.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/LinkBubble.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MiniDock.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtBar.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Notice.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/OrbPlayer.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PipSubBar.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PlayQueueUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Player.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Spinner.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/StatusStrip.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SubSelect.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TaskRing.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/UniSearch.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/VidMark.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1211-run23` (للرجوع: Actions ← Rollback)
