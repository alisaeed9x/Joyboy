# Joyboy v1183 — المتصفح: مسح عدسة الجافا بالكامل + حماية المشغّلات المدمجة (فوق v1182)

> **ما اتعملش compile ولا اتجرّب على جهاز.** JS الحاجب (CORE_JS) اتفحص بـ`node --check` وتمام.

## ١) عدسة الجافا اتمسحت (Browser.kt / PageScan.kt)
اللي اتشال بالكامل (كود مش تعطيل):
- **الفحص الدوري للـDOM** (`scanOnce` + `scanGroups` + `scanLoop` + `JS` + `JSQ`): كان بيشتغل كل 2→10 ثواني ومع كل تحميل صفحة ويقرا `<video>`/`<source>`/og:video/لينكات mp4.
- **محرك الفحص العميق**: `autoScan` · `deepScan` · `watchDeep` · `autoHunt` · `autoServers` · `verifyAuto` · `scheduleAutoScan` + `autoBgAllowed/autoScanOn/autoDiscoverOn`.
- **دوسة الصفحة (kickScan)** كانت بتشغّل deepScan يدوي (PLAY_JS بيشغّل الفيديو مكتوم ويدوس على أزرار play) — دلوقتي الدايرة بتلف 5 ثواني بس وتستنى السنيفر.
- **اللقط ← deepScan** بعد أول فيديو اتلقط اتشال.
- **UI**: زرار 🔎 في الشريط · «افحص الصفحة» من القايمة · سويتش «فحص الصفحة أوتوماتيك» · شاشة نتيجة الفحص (`pageScan/runDeepSearch/showScanResults/showAutoResults/resolveHost/resolvePage`). الضغطة الطويلة على الدايرة = قايمة الفيديوهات الملقوطة.
- **PageScan.kt**: اتشالت `DOM_JS` `SCRIPT_JS` `AUTO_PLAY_JS` `AUTO_PAUSE_JS`. فاضلة `PLAY_JS` (بيستخدمها ClipWatch للخلفية مش المتصفح) ودوال الشبكة (resolve/probeReal…).

اللي فضل بيلقط: **السنيفر بتاع الشبكة** (`shouldInterceptRequest` ← `Sniff.accept`) + `VidHook` (صيّاد سلبي بيتحقن في الإطارات — مش عدسة، ومحتاجه لقوايم HLS/DASH من غير امتداد). لو عايزه يتشال هو كمان: سطر `VidHook.install(...)` في `createWebView` + دالة `onHook`.

## ٢) المشغّلات المدمجة كانت بتتشال/بتتخبّى (Sniffer.kt — AdBlock)
- `[class*=ad-container]` / `[class*=ad-wrapper]` / `[class*=ad-overlay]` / `[class*=ad-banner]` كانت بتطابق **load**-container · **head**-wrapper · **load**-overlay… فالـCSS بيخبّي والـJS بيعمل `.remove()` لحاوية المشغّل.
- الإصلاح: قاعدة الـCSS بقت `:is(...):not(:has(video,audio,iframe[src*=player],iframe[src*=embed]))`، والـJS فيه حارس `pl()` — مفيش `.remove()`/`kill()` لعنصر هو أو جواه `video/audio` أو iframe مشغّل (من غير شبكات الإعلانات) أو class فيه player/vjs/jwplayer/plyr/video-js/mejs.
- `ov()` (إخفاء أوفرلاي «Skip») بقت بتحترم الـiframe مش الـvideo بس.
- بتفضل بتشيل: إعلانات الشبكات المعروفة، بانرات IAB الخارجية، بانرات المراهنات، الكوكيز.

## ٣) اللقط من غير تشغيل (BrowserSaver.kt)
- `JS_GUARD` (حارس التشغيل التلقائي في توفير الداتا) كان بيضبط `preload='none'` على كل فيديو ← الصفحة ما بتطلبش الملف فالسنيفر ما يشوفش حاجة لحد ما تدوس play. بقى بيحوّل `none` ← `metadata` (أول بايتات بس، زي v1160).

## حاجة لازم تتعمل في الريبو (بيلد v1182 وقع)
اللوج: `Duplicate resources` — الـPNG القديمة لسه في الريبو جنب الـWebP. امسحها:
`git rm app/src/main/res/mipmap-*/ic_launcher.png app/src/main/res/mipmap-*/ic_launcher_foreground.png app/src/main/res/drawable-nodpi/{ic_joy_browser_bg,ic_joy_browser_fg,ic_joy_main_bg,ic_joy_main_fg,ic_joy_yt_bg,ic_joy_yt_fg,no_poster}.png`
