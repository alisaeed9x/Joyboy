# حالة دمج المشغّل (PLAYER_MERGE_STATE)

> الخطة الكاملة: `docs/JOYBOY_PLAYER_MERGE_PLAN.md` — اقراها الأول. الملف ده بس بيتتبّع **حالة** كل مرحلة. حدّثه في آخر كل مرحلة.
> كل المراحل: لم يتم compile ولا اتجرّب (مفيش كومبايلر هنا).

| المرحلة | الوصف | النسخة | الحالة |
|---|---|---|---|
| 0 | تجهيز: نقط ربط في `JoyboyPlayerHost` + `MtPlay.decide()` | v930 | ✓ اتكتبت (لم تُكومبايل) |
| 1 | اتجاه: محلي/مباشر يفتح landscape fullscreen | v933 | ✓ اتكتبت (لم تُكومبايل) |
| 2 | محرك الترجمة الكامل + قلب `mt_main_player` + معالجة الاستثناءات | v934+v935+v936 | ✓ اتكتبت (لم تُكومبايل) — باقي بس: `mirror_uri` · `.part` · `handoff` · التلفزيون |
| 3 | أدوات الميديا (A-B · تكرار · boost · نوم · حلقات · استكمال) | v937 | ✓ اتكتبت (لم تُكومبايل) — النوم والتضخيم من MeeTube نفسه |
| 4 | التلفزيون والريموت (D-pad + `MtTvPlayer` كامل + flag `mt_tv_player`) | — | ✗ |
| 5 | تنظيف: شيل مشغّل Joyboy القديم | — | ✗ |

## المرحلة 3 (v937)
- `MtMedia.kt` (جديد): A-B (كبسولة + بند) · وضع التكرار (`loop`/`autoplay_wrap` مشتركين) · حلقات المكتبة (`Provider.onStep` ← ⏮ ⏭، والتالي عند النهاية، `PlayQueue` له الأولوية) · استكمال `lastpos` + «آخر المشغّلة» `recent.json`.
- **ما اتنقلش لأن MeeTube عنده:** مؤقت النوم (`SleepTimer`) · تضخيم الصوت (`AudioEffects`) · أوضاع التكرار بتاعته.
- **محتاج تجربة:** تعارض أوضاع MeeTube `CLOSE`/`PAUSE` مع «التالي» عند النهاية. قفز الأرقام ← م4.
- فجوة «آخر المشغّلة» اتقفلت هنا؛ لسه: تسجيل المصطادة (`WebVideos`) و`ret`.

## تكملة المرحلة 2 (v936) — الاستثناءات
- اتفتحوا في MeeTube: يوتيوب (`ytid/ytpend`) · المتخفي · الموقع المحجوب (`hsite`) · `forceid` · لينك من غير امتداد (فحص Content-Type في الخلفية).
- **تصحيح للخطة:** `incognito/hsite` = «ما تسجّلش» (مش استخراج).
- **لسه على Joyboy:** `mirror_uri` (م4) · `.part` · `handoff` · التلفزيون.
- **فجوات لازم تتقفل قبل م5:** تسجيل المصطادة (`WebVideos.register`/`WebIdent`) · `ret` · «آخر المشغّلة» (`recent.json`/`lastpos`) من مسار MeeTube.

## تكملة المرحلة 2 (v935)
- قايمة ⚙ فيها كمان: نسخ محفوظة · ستايل · سبتايتل جانبي · دبلجة (+صوت) · تحميل · qlist مسموحة.
- **لسه على مشغّل Joyboy (قرار، مش نسيان):** `ytid/ytpend/forceid` (يوتيوب) · `mirror_uri` · incognito · `hsite` · `.part` · url بدون امتداد ميديا · التلفزيون. تتحل مع م5 أو لما علي يطلبها.
- مسارات الصوت = قايمة MeeTube الأصلية؛ الحوار الزجاجي بدل bottom-sheet.

## اللي اتعمل في المرحلة 2 (v934) — جزء أول
- `mt_main_player` الافتراضي **true** + مفتاح رجوع في إعدادات مظهر المشغّل (`MtPlayerLook`).
- هيدرز (`Video.headers`) + صوت منفصل (`Video.audioUrl`) ← `Media3SourceFactory.fromUrlWithHeaders`.
- زرار 🌐 + ⚙ في `mOptionsRow` (`MtTranslate.pill`)؛ ترجمة تلقائية شغّالة؛ قايمة: بدء/وقف · وضع مزدوج · جمل · إزاحة · تلفزيون · إعدادات المحرك.
- `onControlsVisibility` متوصّلة (هامش السبتايتل).
- **باقي في م2:** versions · ستايل السبتايتل · سبتايتل جانبي · دبلجة · تحميل · مسارات صوت · bottom-sheet · استثناءات: qlist · incognito · hsite · .part · url بدون امتداد · يوتيوب بترجمة (`ytid`).

## اللي اتعمل في المرحلة 1 (v933)
- `MobilePlaybackActivity`: `requestOrientationFullscreen()` (مفصولة من `toggleFullscreen`) + `autoLandscapeIfLocal()` بتتنده من `onVideoShapeKnown` عند أول فريم.
- شغّالة لما `Video.localUri != null` أو تلفزيون؛ الرأسي upright، يوتيوب زي ما هو.
- **ما بتظهرش على الجهاز قبل م2** (قلب `mt_main_player`).
- ما اتنفّذش: طلب landscape بدري في `onCreate` قبل معرفة الشكل (فيه وميض بورتريه لحظة قبل أول فريم) — ممكن يتضاف لو علي طلبه.

## v932 (إصلاحات جانبية، مش مراحل)
- `SplashActivity`: التلفزيون الحقيقي ← `TvActivity` من أيقونة اللانشر (و`onNewIntent`).
- `LibraryUi`: مربعين مصدر المكتبة (تلفزيون/موبايل) تحت الهيدر.

## v931 (إصلاح جانبي، مش مرحلة)
- `MtTvRoute.intercept`: سؤال «تشغّله فين؟» بيظهر لو tv_mode شغّال أو الموبايل متصل (`RemoteClient.current != null`).

## اللي اتعمل في المرحلة 0 (v930) — سلوك التطبيق **ما اتغيّرش**
- `JoyboyPlayerHost.java`:
  - `VideoInfo`: `sourceKind()` ("youtube"/"local"/"direct" من `localUri()`) و`headers()` (فاضية لحد م2) — كلهم `default`.
  - `Provider`: `wantsLandscape(Activity)` · `onKey(Activity, KeyEvent)` · `onControlsVisibility(Activity, boolean)` · `extraControls(Activity, ViewGroup)` — كلهم `default` فاضيين.
  - Static dispatchers بنفس الأسماء، كلها بـ`try/catch (RuntimeException)`: `JoyboyPlayerHost.wantsLandscape/onKey/onControlsVisibility/extraControls`.
  - **لسه مفيش حد بينديهم** — `MobilePlaybackActivity` ما اتلمستش. المراحل 1/2/4 هي اللي هتنده.
- `MtPlay.kt`: `decide(act, intent): Decision(ok, reason, src)` بنفس شروط `reroute` القديمة بالظبط؛ `reroute` بقت تستخدمها وبتسجّل سبب الرفض في اللوج (`LogStore.add("MtPlay: skip — …")`) ما عدا حالتي «mt_main_player off» و«tv». الافتراضي `mt_main_player=false` زي ما هو.
- ما اتضافش أي ملف `.kt` جديد ← `kt_manifest.txt` ما اتغيّرش.

## قرارات
- `mt_main_player` يفضل false لحد ما م2 تعالج الاستثناءات (راجع جدول الخطة).
- التلفزيون يفضل على مشغّل Joyboy لحد flag `mt_tv_player` (م4) والتجربة على TCL.
- رقم النسخة بيتحسب من أحدث `CHANGES_vNNN.md` (`build.gradle.kts` سطر 7) — مفيش تعديل في gradle.

## المرحلة الجاية: 4 (التلفزيون والريموت — flag `mt_tv_player`، D-pad، `MtTvPlayer` كامل). الفجوات القديمة (المصطادة · `ret`) تتقفل قبل م5. (أدوات الميديا: A-B · تكرار · boost · نوم · حلقات · استكمال)
أول خطوة: جدول الاستثناءات في الخطة (`ref/cookie` هيدرز ← `Video.headers`، `aurl`، `qlist`…) قبل قلب `mt_main_player`. (م1: `mVideoShapePending` بيتصفّر في `onVideoShapeKnown`، وبيتضبط true عند أي فيديو جديد في `bindWatchVideo`.)
