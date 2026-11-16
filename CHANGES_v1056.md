# v1056 — نفس سلوك v1055 بس في Player.kt (المشغّل اللي التلفزيون شغّال عليه فعليًا) + إصلاح «ابدأ من جديد»
**ما اتعملش compile ولا تجربة — Actions هو الحكم.** الأساس: v1055.

## ليه
v1055 كانت في MeeTube (MtMedia) بس، والتلفزيون بيشتغل غالبًا على Player.kt.

## Player.kt (الجديد)
- **الفيديو المخلّص يتفتح من الأول:** عند ENDED بيتمسح `lastpos` و«آخر المشغّلة» بتتسجّل بمكان 0 (`markFinished`). وشبكة أمان في `maybeEndCheck`: أول READY لو المكان >= المدة-٤ث (من أي مصدر: lastpos / ملف الترجمة / السجل) ← seekTo(0).
- **أزرار النهاية** (EndOverlay.kt من v1055): ⏮ السابق · 🔄 إعادة · ⏭ التالي (التالي لوحده بعد ٥ث) بدل كارت «التالي بعد…» للفيديو المحلي؛ يوتيوب لسه بالكارت القديم. اللف لأول/آخر فيديو بـ`wrapOn()` (تلفزيون افتراضيًا). ترتيب التلفزيون = tv_vid_sort.
- **ملاحظة:** ▶ بعد النهاية في Player.kt كان بيعيد من الأول أصلًا (`togglePlay`) — ما اتغيّرش.
- **«كمّل من حيث وقفت؟ / ابدأ من جديد»** (ResumeAsk.kt): بتظهر لما تفتح فيديو ليه تقدّم محفوظ (وللفيديو التالي اللي بيشتغل تلقائي) — على التلفزيون لو مفيش موبايل متوصل، وعلى الموبايل لو متوصل بالتلفزيون. ٥ث من غير اختيار ← من جديد. الفيديو بيتوقف لحد الاختيار.

## إصلاح: «ابدأ من جديد» من الموبايل والتلفزيون كان بيكمّل من تقدّمه
السبب: الموبايل كان بيبعت startms=0 ← التلفزيون ما يعرفش إنه اختيار صريح فيكمّل من عنده.
- الموبايل بيبعت `startms = -1` لما تختار «ابدأ من جديد» (PlayTarget.force + RemoteClient.openLink).
- TvLink.open بيحوّله لعلم `StartFresh` (ResumeAsk.kt) صالح ٢٠ث؛ Player.initEngine وMtMedia.tryResume بياخدوه مرة واحدة ويبدأوا من الصفر ويمسحوا المحفوظ.

## مش متأكد منها
١) pause() لحظة READY قبل السؤال ممكن يطلع نص ثانية صوت. ٢) فيديوهات مكتبة التلفزيون tv:// من الموبايل لسه ما بتتسألش على الموبايل. ٣) «ابدأ من جديد» لفيديو يوتيوب على التلفزيون ما اتغطّاش (الموبايل بيسأل للمحلي بس). ٤) Dialog على التلفزيون: التركيز بالريموت.

## مراجعة بدون compile (grep + قراءة فعلية)
**اللي فحصته فعلًا**
- (ح) kt_manifest: `check_manifest.sh` = OK، ومفيش ملف .kt جديد (كل الجديد جوه ResumeAsk.kt وPlayer.kt).
- (ح) الرموز والتواقيع: `TvLink.remoteList()`/`Remote.alive()` · `RemoteClient.state`/`St.CONNECTED` · `ResumeAsk.show(a,posMs,delaySec,onChoice)` · `EndOverlay.show(a,hasPrev,hasNext,autoNextSec,onPrev,onReplay,onNext)` · `PlaybackBackend` فيه `playbackState/duration/currentPosition/play/pause/seekTo` · `Player.STATE_*` من media3 (import سطر 26) · `h` و`remote` و`incognito` موجودين. مفيش تعريف مكرّر لأي دالة/متغير جديد في Player.kt. الأقواس متوازنة. مفيش `parent`/`context` مجهول جوه object.
- (أ) «ابدأ من جديد»: اتتبع من PlayTarget.force ← send ← RemoteClient.openLink ← TvLink.open ← StartFresh ← Player.initEngine / MtMedia.tryResume. أي مستدعي تاني لـopenLink (Browser/Links/Main) بيبعت startMs=0 فمش متأثر.
- (ب) الحالة: `endCheckDone/resumeSeekDone/askResumeMs/finishedMark` بتتصفّر في initEngine (وswapVideo بينادي عليه)، وfinishedMark بتتصفّر عند أي حالة غير ENDED؛ أزرار النهاية والسؤال بيتقفلوا في swapVideo/onDestroy.
- (ج) عدد الحالات: ما اتغيّرش أي عدد أوضاع.
- (د) الإعدادات: ما اتغيّرتش إعدادات بتتسرّب بين الجهازين؛ العلم StartFresh بيتسلّح على التلفزيون بس (من TvLink).
- (و) مفيش WebView جديد ولا تغيير في abiFilters.
**لقيته وصلّحته:** مفيش خطأ ظهر في المراجعة دي.
**مش متأكد منها (من الأخطر):**
1. الـDialog (أزرار النهاية/السؤال) على التلفزيون: تركيز الريموت على الأزرار ما اتجرّبش.
2. لو مكان محفوظ قريب من الآخر (من ملف الترجمة) الفيديو بيقفز لقرب النهاية لحظة قبل ما يرجع للصفر في أول READY — ممكن فلاش.
3. pause() لحظة READY قبل السؤال ممكن يطلع نص ثانية صوت.
4. StartFresh صالح ٢٠ث: لو فتح الفيديو اتأخر أكتر منها هيكمّل من المحفوظ.
5. (هـ) ما اتفحصتش بصريًا: الـDialog فوق مشغّل Player.kt (elevation/مكان) — محتاج تجربة على جهاز.
6. (ز) ما اتغيّرتش عمليات الخلفية.
