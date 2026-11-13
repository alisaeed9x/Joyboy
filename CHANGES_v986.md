# v986 — شيل ميزات الشيدر (AI / AI Pro) وتثبيت الـbuffer — رجوع عرض الفيديو لسلوك v980
مبني على v985. ما اتعملوش compile (مفيش Gradle/جهاز) — الـActions build هو البوابة.

## اتشال
- `MtUpscale.kt` بالكامل (شيدرين AI / AI Pro) + سطره في `kt_manifest.txt`.
- `MtBar.kt`: زرار AI الدائري، `reapplyUpscaler` / `toggleUpscaler` / `refreshAiButton` / `refreshUpscaler`، حقول aiXxx، نداءاتهم في الـtick والـattach.
- `Main.kt`: قسم «✨ تحسين الفيديو (AI)» من الإعدادات.
- `MobilePlaybackActivity.java`: كل `setDefaultBufferSize` (تثبيت v981 على المقاس المكوَّد + هدف الـupscale + `joyboyRepinBuffer` + `joyboyRepinSessionBuffer` + الـlayout re-pin + نداءات onResume/PiP-exit) وحقلي `mJoyboyVideoW/H`.

## فضل زي ما هو
`.show()` للشيتات، شكل نافذة «الترجمة لسه ما خلصتش» القديم، سقف `yt_maxh` اللاصق، الخروج من يوتيوب، إزالة الـsplash، FIT في الفل سكرين، إخفاء ⤢ في الصفحة الرأسية، تسريع الـABR.
