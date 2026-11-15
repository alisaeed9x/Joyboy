# v1034 — مرحلة 5: يوتيوب مصغّر (داخلي، من غير PiP النظام)
**ما اتعملش compile ولا تجربة — Actions هو الحكم.** الأساس: v1033. القرار: §10-1 في الخريطة.

## اللي اتعمل
- **سحب لتحت / Back على فيديو يوتيوب = مصغّر داخلي** على الشاشة اللي تحت المشغّل (Browse/Search/Channel) بدل القفل. باقي المصادر (ملف/لينك/متصفح/واتساب/تلفزيون) **زي ما هي**: قفل + رجوع للمصدر (قرار v1001).
- `MobilePlaybackActivity.dockYoutubeIntoHost()` جديدة: نفس hand-off بتاع `minimizeForNavigation()` (`prepareMiniPlayerHandoff(false)` ← `MiniPlayerBridge.prepareMiniHostForHandoff` ← `startView(hostViewClass)` ← `removeTop(this)`) لكن بتـreorder الـhost المسجّل بدل مسار قناة. بتتنده في فرع `fallback-close` من `minimizeByDrag()` قبل القفل.
- `JoyboyPlayerHost.Provider.wantsYoutubeDock(activity)` (+ static wrapper) جديدة، و`MtTranslate` بتنفّذها: true لو الفيديو من غير `localUri` ومصدره `YOUTUBE` أو **غير مسجّل** (فيد MeeTube نفسه). `REMOTE_TV`/`BROWSER`/`LOCAL`/… = false.
- **بيرجع للقفل زي v1001 لما:** مفيش host تحت المشغّل (مثلًا فيديو اتفتح من تاب يوتيوب جوّا Joyboy ومفيش شاشة MeeTube تحته) · `MiniSize.DISABLED` (الحارس القديم لسه الأول) · داخل PiP · أي استثناء. يعني **مفيش PiP النظام لليوتيوب خالص**.
- لوج: `PlayerTrace: MINIMIZE branch=youtube-dock (v1034)` (أو `fallback-close` لو اتقفل). تعليق `MtOrigin` («يوتيوب ← مصغّر يوتيوب») بقى مطابق للسلوك.

## مخاطر معروفة (من §7 في الخريطة — محتاجة تجربة)
- `SurfaceTexture` عند التسليم للكارت (رعشة/سواد زي v978/981/985) — بنستعمل نفس مسار التنقل بين القنوات اللي شغّال، فالمفروض سليم.
- المترجم: `MtTranslate` و`MtBar` لسه مربوطين بالـActivity الحي أثناء التصغير (زي حالة التنقل بين القنوات). لو الترجمة لسه شغّالة وإنت مصغّر وبان طبقة/سبتايتل غريب قولّي.
- زر الرجوع مع دوك نشط: `MobileActivity.finish` بيعمل `moveTaskToBack` لو الشاشة جذر (موثّق في v1001).
- تعارض محتمل مع `suppressNextReturn` وتبديل Player.kt ⇄ MeeTube (v1030).

## للتجربة (من §7)
1) يوتيوب من فيد MeeTube ← سحب لتحت ← يفضل شغّال في كارت ← تنقّل بين الأقسام ← دوس الكارت يكبّر ← X يقفل. 2) نفس الحاجة بـBack. 3) فيديو ملف/متصفح ← سحب لتحت ← يقفل ويرجع للمصدر (زي قبل). 4) يوتيوب من تاب يوتيوب في Joyboy ← يقفل ويرجع (مفيش host).

## ملفات
`MobilePlaybackActivity.java`, `JoyboyPlayerHost.java`, `MtTranslate.kt`, `STATUS.txt`.
