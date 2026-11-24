# Joyboy v1216 — الوضع الداكن AMOLED أسود بدل البني (فوق v1215)

> **ما اتعملش compile ولا اتجرّب على جهاز.**

## ✨ جديد
- **الخلفية في الداكن أسود حقيقي `#000000`** (AMOLED)، والبطاقات والأسطح رمادي محايد (`#0E0E0E` · `#161616`) والحدود `#2A2A2A`. النص كريمي فاتح ولمسة Latte بس للأزرار والتحديد.
- **كل الدرجات البنية في الداكن اتبدّلت برمادي/أسود محايد**: الميني دوك، شريط المشغّل، لوحات القوايم الزجاجية، قايمة التشغيل، كرة الموسيقى وخلفيتها، الإشعارات والتوست، فقاعة يوتيوب، شريط الحالة، المتصفح، ويدجت الصلاة، صفحة نقل الملفات.
- بما إن الخلفية بقت `#000000`، التطبيق بيعرّفها تلقائيًا كـAMOLED فبيخفّف لمعة الزجاج.
- **الفاتح ما اتغيّرش** (Vanilla/Mocha زي ما هو).

## ⚠️ خد بالك
- الطبقات اللي بتظهر فوق الفيديو (أزرار المشغّل، التوست) لونها واحد في الفاتح والداكن، فبقت رمادي فحمي بدل بني Espresso في الفاتح كمان. لو عايزها تفضل دافية في الفاتح بس قولّي.
- شيتس MeeTube الأصلية (`#1C1C21`) اتساب عمدًا لأنها أصلًا محايدة تقريبًا.
- جرّب على الجهاز: وضوح الحدود على الأسود، وإن الأزرار المتوسطة (رمادي `#363636`/`#454545`) مفرّقة عن الخلفية.

<details><summary>التفاصيل التقنية</summary>
Theme.kt (سطر الداكن): bg 000000 · surface 0E0E0E · card 161616 · border 2A2A2A · primary E6D8C5 · accent BDBDBD · text F5F0E7 · muted A8A8A8. ثوابت ألوان (80 استبدال hex بس) في Player · MtGlass · MtBar · MusicUi · PlayQueueUi (BG=000000) · MtPlayerLook · MiniDock · OrbPlayer · FtWeb · SubSelect · YtBubble · BottomNav · TaskRing · StatusStrip · Main · RotateHint · Notice · Browser · BrowserSaver · PipSubBar · PrayerWidget · widget_bg. تحديث قسم الهوية في AI_GUIDE.md وdocs/design-skills/README.md.
</details>


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
✏️ عدّل:   AI_GUIDE.md
➕ أضاف:  CHANGES_v1216.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BottomNav.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Browser.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/BrowserSaver.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/FtWeb.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MiniDock.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtBar.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtGlass.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MtPlayerLook.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/MusicUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Notice.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/OrbPlayer.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PipSubBar.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PlayQueueUi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Player.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/PrayerWidget.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/RotateHint.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/StatusStrip.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/SubSelect.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TaskRing.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Theme.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/YtBubble.kt
✏️ عدّل:   app/src/main/res/drawable/widget_bg.xml
✏️ عدّل:   docs/design-skills/README.md
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1215-run27` (للرجوع: Actions ← Rollback)
