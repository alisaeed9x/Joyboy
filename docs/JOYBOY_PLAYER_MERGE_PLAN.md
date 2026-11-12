# خطة دمج المشغّل — MeeTube هو المشغّل الوحيد + محرك الترجمة + التلفزيون

> مبنية على فحص `Joyboy_v929.zip` (فحص كود ثابت فقط — **لا compile ولا تجربة**).
> أرقام الأسطر = حالة v929 وبتتحرك مع أي تعديل، فدوّر بالاسم (`grep -n`) مش بالرقم.
> المستخدم: علي (Ali) — بيتكلم مصري، بيشتغل على مراحل، كل مرحلة = زيب جديد.

---

## 0. اقرأ ده الأول (ملخص 30 ثانية)

- **القرار (من المستخدم، نهائي):** مشغّل MeeTube (`MobilePlaybackActivity`) يبقى المشغّل **الرئيسي والوحيد**. بنستخدم كوده زي ما هو (إيماءات · PiP · مصغّر · SABR · HDR · SponsorBlock · كاست · فصول …) وبنضيف عليه حاجتين بس: **(أ) محرك الترجمة الكامل** و**(ب) تحكم التلفزيون/الريموت الكامل**. مشغّل Joyboy (`Player.kt`) يتشال في آخر مرحلة.
- **مفيش حاجة تتكتب من الصفر لو موجودة:** `MtTranslate` (الترجمة فوق MeeTube) و`MtPlay` (التوجيه) و`MtTvPlayer` (تحكم تلفزيون) **موجودين فعلاً** — بنكمّلهم.
- **سلوك الاتجاه المطلوب (المرحلة 1):** يوتيوب يفضل بورتريه ثم زرار شاشة كاملة (زي ما هو). أي ملف محلي/لينك مباشر/مصدر Joyboy يفتح **landscape وشاشة كاملة على طول** من غير ما المستخدم يدوس.
- **الخطر الأكبر:** واجهة MeeTube لمس؛ v928 جرّب يفتحها على التلفزيون وفشلت، فـv929 رجّع مشغّل Joyboy للتلفزيون. المرحلة 4 لازم تبقى طبقة D-pad، ومقفولة بـflag لحد ما تتجرّب على TCL.

---

## 1. قواعد التسليم (ثابتة من المشاريع اللي فاتت — التزم بيها)

1. كل مرحلة = رقم نسخة جديد: **v930 · v931 …** (الحالي v929). الرقم في `app/build.gradle.kts` سطر 7 `joyVerNum` (→ `joyVerName`)؛ `versionCode` بيتحسب من وقت البناء، ما تلمسوش.
2. ملف `CHANGES_vNNN.md` جديد (عربي مصري، بنفس شكل `CHANGES_v929.md`: عنوان + نقاط + «ملفات جديدة/معدّلة» + «لم يتم compile ولا اتجرّب»).
3. سطر جديد في **أول** `STATUS.txt` (`vNNN: ملخص … لم يتم compile ولا اتجرّب. التفاصيل: CHANGES_vNNN.md.`).
4. أي ملف `.kt` **جديد** لازم يتسجّل في `app/kt_manifest.txt` (سطر `com/tttt/subtitler/Xxx.kt`، 295 ملف حالياً) — وإلا الـCI بيمسحه. تحقق إن المانيفست مطابق لملفات `.kt` الفعلية.
5. التسليم: `Joyboy_vNNN.zip` بنفس هيكل الأرشيف.
6. **مفيش كومبايلر**: ما تقولش «اتبنى/اتجرّب»، قول «لم يتم compile». اتحقق من توازن الأقواس ومن كل `import` جديد. في Java (`:mobile`) ما تستخدمش أي class من مودول `app` (مودولاتهم منفصلة — الربط عبر interfaces فقط، انظر 3.2).
7. كل تغيير للتلفزيون شغّال **بس** لما `DeviceUi.isTv(ctx) == true`. الموبايل/التابلت بيتغيّروا بس بالمراحل 1-3 المقصودة.
8. ما تغيّرش قرار معماري بصمت، وما تعيدش شغل اتعمل، ولا تمسح مشغّل Joyboy القديم قبل المرحلة 5.
9. كل تعديل في ملفات `meetube/**` علّمه بتعليق `// JOYBOY(vNNN): …` (زي `JoyboyPlayerHost`) عشان لو MeeTube اتحدّث نعرف نعيد الدمج.
10. المستخدم بيبعت دايماً أرشيف المرحلة اللي قبلها؛ لو بعت خطأ كومبايل من Actions، اصلحه الأول قبل أي ميزة.

---

## 2. خريطة الكود (مسارات كاملة)

`A` = `app/src/main/java/com/tttt/subtitler/` · `M` = `meetube/mobile/src/stmobile/java/com/newtube/mobile/ui/playback/` · `C` = `meetube/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/`

| الملف | الحجم | الدور |
|---|---|---|
| `M/MobilePlaybackActivity.java` | ~12600 سطر | مشغّل MeeTube الرئيسي (الأساس الجديد) |
| `M/JoyboyPlayerHost.java` | 65 سطر | **الجسر**: interfaces `Provider` + `VideoInfo`؛ `:mobile` ما يعرفش `app` |
| `A/MtTranslate.kt` | ~300+ | `object MtTranslate : JoyboyPlayerHost.Provider` — الترجمة فوق MeeTube |
| `A/MtPlay.kt` | ~70 | `reroute(act, intent)` — بيحوّل فتح `PlayerActivity` لمشغّل MeeTube (الـflag `mt_main_player`) |
| `A/MeeTubeLink.kt` | | `bindBar()` بيسجّل `MtTranslate` كـProvider؛ `playYt()`؛ `open()`؛ `signIn` |
| `A/MtTvRoute.kt` | | `PlaybackPresenter.OpenInterceptor` — نافذة «تشغّله فين؟ تلفزيون/موبايل» |
| `A/MtTvPlayer.kt` | | تحكم ريموت الموبايل في MeeTube على التلفزيون (**خامل** من v929؛ بيدعم بس toggle/seek/seekto/speedset/close) |
| `A/PlayTarget.kt` | | `MediaSpec` + وضع التلفزيون + `force()` (إرسال للتلفزيون) |
| `A/Player.kt` | 7476 سطر | **مشغّل Joyboy القديم** (`PlayerActivity`) — مصدر الميزات اللي هتتنقل (وبيتشال في م5) |
| `A/Engine.kt` `EngineBackend.kt` `Store`/`PromptBuilder`/`AudioSources` | | محرك الترجمة نفسه (مشترك، ما يتلمسش) |
| `A/SubtitleView.kt` `SubStyle.kt` `SubDual.kt` `SubPick.kt` `SubFileTr.kt` `SubOnline.kt` `SubHunt.kt` `SubAlign.kt` `PlayerLogic.kt` | | عرض السبتايتل · الستايل · المزدوج · اختيار/استيراد/بحث · ضبط · منطق (`activeIndices`/`combine`) |
| `A/TvPlayerBar.kt` `TvScrub.kt` `TvInput.kt` `TvFocus.kt` | | شريط تحكم التلفزيون والتقديم المعلّق وإدخال الريموت |
| `A/RemoteClient.kt` `RemoteEngine.kt` `RemoteService.kt` `RemoteRouter.kt` `TvLink.kt` `BrainRpc.kt` `BrainCore.kt` | | ريموت الموبايل ↔ التلفزيون (بروتوكول HTTP/RPC) |
| `A/DeviceUi.kt` | | `isTv(ctx)` (سطر 35) · `phoneLike()` |
| `C/app/models/data/Video.java` | | `Video.localUri` (سطر 115) = ملف محلي/لينك مباشر |
| `C/app/models/playback/controllers/VideoLoaderController.java` | | بيفتح `localUri` عبر `openUrlList` (سطر ~512) |
| `A/Player.kt:4124` | | `if (MtPlay.reroute(this, intent)) { … finish() }` — نقطة دخول كل الميديا |

### 2.1 مسار التشغيل الحالي (v929)
```
أي فتح (مكتبة/ملفات/متصفح/نقل/مهام) → PlayerActivity.onCreate (Player.kt:4124)
  → MtPlay.reroute(act, intent)
       يرجّع false (يفضل مشغّل Joyboy) لو:  mt_main_player=false (الافتراضي!) · DeviceUi.isTv ·
       extras: remote · mirror_uri · ytid · ytpend · aurl · qlist · forceid · ref · cookie · incognito · hsite ·
       data غير content/file · ملف .part · url مش امتداد ميديا (mp4|m4v|mkv|webm|mov|m3u8|mpd|ts|3gp|avi|flv)
       غير كده: Video(videoId="joy_local_<hash>", title, localUri=src) → PlaybackPresenter.openVideo(v) → MobilePlaybackActivity
  → MobilePlaybackActivity يبني الـExoPlayer ثم (سطر ~1983) JoyboyPlayerHost.attach(this, mPlayer, mVideoArea, mOptionsRow, VideoInfo{videoId,title,localUri})
  → MtTranslate.attach: بيحط SubtitleView في الـoverlay (bottom margin 40dp) + يسجّل MtTvPlayer + يربط listener لتغيير الفيديو + tick
  → الترجمة: MtTranslate.start() ← Engine(Store, PromptBuilder, AudioSources) ← tick بيعرض السطور بـPlayerLogic.activeIndices/combine + offsetMs
```
- زرار الترجمة 🌐 **مخفي من v924** (`refreshBtn()` فيها `if (true) return`، و`attach` بيصفّر `btn/btn2`). `showMenu()` (إزاحة ±0.5ث · تصفير · ابعت للتلفزيون · «إعدادات محرك الترجمة» → `MeeTubeLink.openSettings(a,"tr_engine")`) موجود بس مش متربط بزرار ظاهر.
- `MtTranslate.autoFor` (= `"L:<src>"` للمحلي أو ytid) بيشغّل الترجمة أوتوماتيك أول ما الفيديو يشتغل (من `intent.autotr` و`!nosub`).
- `curId()` = `"L:<localUri>"` للمحلي أو الـytid (11 حرف).

### 2.2 مسار التلفزيون الحالي
```
على التلفزيون (DeviceUi.isTv) → MtPlay.reroute يرجّع false دايماً → مشغّل Joyboy (PlayerActivity) هو اللي شغّال.
ريموت الموبايل → RemoteClient → TvLink/BrainRpc على التلفزيون → Player.remoteAct(a: String, v: Long) (Player.kt:77, companion)
   • بيلاقي الـPlayerActivity الحي (live()) ويشغّل o.rAct(a,v) على الـUI thread
   • الحالة: Player.remoteState() → o.rState(): JSONObject (title,pos,dur,playing,speed,wtr,fit,cc,scale,vol,shift,audio,quality,tr,sf_*,kind="player",
     net_*, sblur/suni*, vname/vfolder, src/hdr, vw/vh, loop, lm …)
   • RPC: Player.brain(op, args) ← o.rpc(op,args) (وعمليات "engine.*" على خيط الطلب o.rpcBg)
   • لقطة حية: Player.brainFrame(maxDim,q) ← frameJpeg
   • تسليم/قائمة/ترجمة وارد من الموبايل: remoteHandoff · remoteQueueCur · remoteSubImport · remoteSubTr
MtTvPlayer (خامل): نسخة مصغّرة من ده فوق ExoPlayer بتاع MeeTube (toggle/seek/seekto/speedset/close فقط).
```

---

## 3. المقارنة (اللي هيتنقل وين)

### 3.1 موجود في MeeTube ومش محتاج شغل (ما تعيدوش)
SABR/DASH/HLS · HDR · إيماءات (pinch · قفل · hold-speed · سويب صوت/سطوع · double-tap · سحب للتصغير) · PiP + مشغّل مصغّر + `MobilePlaybackService` (خلفية/إشعار) · كاست · فصول · SponsorBlock · ambient · live chat · morph فتح/قفل · glass · جودة · مسارات صوت (`showAudioTrackSheet`) · autoplay/next-up · queue/shuffle · تحميل (`onDownloadTapped`) · ضبط حجم الفيديو (`setResizeMode`) · تدوير (`setRotationAngle`) · D-pad أساسي (`onRemoteKey` سطر ~4183).

### 3.2 ناقص في MeeTube — يتنقل من Joyboy (مرجع الأسطر في `Player.kt`)
| الميزة | مرجع في Player.kt | الملفات المساعدة | مرحلة |
|---|---|---|---|
| ترجمة كاملة (جيميناي/ويشبر/ليبر) | `Engine.kt` | `MtTranslate.start/stop` موجودين | 2 |
| سبتايتل مزدوج | `cycleDual` ~4341 | `SubDual.kt` | 2 |
| نسخ محفوظة (versions) | `versionsPopup` ~7090 · `restoreVersion` ~7079 | `Store` | 2 |
| قايمة الجمل + بحث | `sentDlg` · `sentencesReport` ~3668 | | 2 |
| ضبط توقيت الترجمة | `syncStep` ~3995 | `SubAlign.kt` (في MtTranslate فيه offsetMs بس) | 2 |
| ستايل السبتايتل (ستوديو) | `tvSubDialog`/`subDialog` | `SubStyle.kt` `SubtitleView.kt` | 2 |
| سبتايتل جانبي `.srt/.vtt` + اختيار/بحث أونلاين | | `SubPick.kt` `SubFileTr.kt` `SubOnline.kt` `SubHunt.kt` | 2 |
| دبلجة / صوت | `dubToggle` ~6583 · `startVoice` ~6816 | | 2 |
| تحميل من المشغّل | `dlStart` ~2036 | | 2 |
| مسارات صوت للمصادر المستخرجة | `parseATracks` ~1871 · `switchAudio` ~1919 | | 2 |
| A-B repeat | `abSet` ~3827 · `abTick` ~3839 | | 3 |
| أنماط التكرار (عادي/نفس الفيديو/كل الفولدر) | `loopCycle` ~6066 | `loopMode()` | 3 |
| تضخيم صوت فوق 100% | `applyBoost` ~3509 · `setBoostPct` ~3506 (`Cfg vol_boost`) | | 3 |
| مؤقت النوم | `setSleepTimer` ~3570 · `sleepDialog` ~3577 | | 3 |
| ترتيب الحلقات | `stepEpisode` ~6093 | `PlayQueue.kt` | 3 |
| استكمال/آخر مشاهدة | `saveRecent` ~6372 · `saveRecentForce` ~6382 | | 3 |
| قفز بالأرقام | `tvJump` ~1548 · `tvDigit` ~1554 | | 3/4 |
| ريموت الموبايل ↔ التلفزيون كامل | `remoteAct` 77 · `rState` ~123 · `rpc`/`rpcBg` · `brainFrame` | `TvLink` `BrainRpc` `RemoteClient` | 4 |
| شريط تحكم التلفزيون D-pad | `tvInitBar` ~715 · `dispatchKeyEvent` ~1654 | `TvPlayerBar` `TvScrub` `TvInput` `TvFocus` | 4 |

**قاعدة الجسر (مهمة):** `:mobile` (Java) ما يشوفش `app`. أي ميزة جديدة محتاجة UI في MeeTube = زوّد **method جديدة على `JoyboyPlayerHost.Provider`** (بـ`default` فاضية عشان ما يتكسرش حاجة) وطبّقها في `MtTranslate`/`Mt*`. الـActivity تنده `JoyboyPlayerHost.xxx(...)` ملفوفة بـ`try/catch (RuntimeException)` زي `attach` (الإضافة ما تكسرش المشغّل أبداً). أمثلة methods محتاجة: `onKey(KeyEvent): Boolean` · `onControlsVisibility(boolean)` · `extraControls(ViewGroup bar)` · `wantsLandscape(): Boolean` · `onSeek/onPlayState` للحالة.

---

## 4. المراحل

### المرحلة 0 — تجهيز (v930، من غير ميزات)
1. وسّع `JoyboyPlayerHost.Provider` بالـmethods الجاية (كلها `default` فاضية) وباقي `VideoInfo` بـ`default`s لو محتاج: `String sourceKind()` (youtube/local/direct) · `Map<String,String> headers()`.
2. `MtPlay`: اعمل `MtPlay.decide(i): Decision` (مدعوم / مش مدعوم + السبب) بدل `reroute` الحالية اللي بترجّع boolean — عشان نعرف في المراحل الجاية أنهي extras لسه بتقفل وليه (سجّلها في `LogStore.add("MtPlay: skip <reason>")`).
3. **ما تقلبش `mt_main_player` لـtrue هنا.**
4. اكتب `docs/PLAYER_MERGE_STATE.md` قصير بحالة كل مرحلة (✓/✗) عشان الموديل اللي بعدك.

### المرحلة 1 — سلوك الاتجاه (طلب المستخدم الصريح)
**المطلوب:** ملف محلي / لينك مباشر / أي مصدر Joyboy يفتح **landscape + شاشة كاملة فوراً**. يوتيوب = زي ما هو (بورتريه + زرار ملء الشاشة).

**نقط الربط في `MobilePlaybackActivity.java`:**
- `toggleFullscreen()` (~4956) = الزرار نفسه: بيحسب `toLandscape` من `layoutOrientation()`، ويتعامل مع: فيديو رأسي (`isVerticalVideo()` → `setPortraitFull`) · `areOrientationRequestsIgnored()` (تابلت/Android16/multi-window → `setLayoutOverride(target)`) · وإلا `setRequestedOrientation(SENSOR_LANDSCAPE)` + `armOrientationHandBack(target)` + `mRotationFallback`.
- `onVideoShapeKnown()` (~4931) = أول فريم معروف شكله؛ فيها نفس منطق «اطلع landscape» لحالة autoplay جوه vertical-fullscreen (بيستخدم `setRequestedOrientation(SENSOR_LANDSCAPE)` + `armOrientationHandBack(Configuration.ORIENTATION_LANDSCAPE)`).
- `isVerticalVideo()` (~4909) · `mVideoShapePending` · `mVideoAspect` · `getVideo()`.
- `FREE_ORIENTATION = SCREEN_ORIENTATION_USER` (~538) + `armOrientationHandBack` / `releaseFullscreenRequest` = الـhand-back بعد الخروج.

**التنفيذ المقترح:**
1. دالة جديدة `autoLandscapeIfLocal()` في الـActivity: لو `getVideo().localUri != null` (أو `JoyboyPlayerHost.wantsLandscape()` رجّعت true) **و** مش `isVerticalVideo()` **و** مش `mIsInPip` **و** مش `isShortsMode()` → ادخل نفس مسار `toggleFullscreen()` لـlandscape (ما تكرّرش المنطق: استخرج جزء «اطلب landscape» من `toggleFullscreen` لدالة `requestLandscapeFullscreen()` والاتنين ينادوها).
2. نده `autoLandscapeIfLocal()` **مرة واحدة** لما الشكل يتعرف (نفس نقطة `onVideoShapeKnown`/أول فريم)، مش في `onCreate` (الشكل لسه مجهول). علشان يسرع: لو `localUri != null` ممكن تطلب landscape في `onCreate` قبل ما الشكل يتعرف ثم ترجع لو طلع رأسي (**تأكد من الكود الفعلي: لم أحدد بالظبط مكان ضبط/تصفير `mVideoShapePending` — `grep -n mVideoShapePending`**).
3. فيديو رأسي محلي → يفضل upright full (سلوك MeeTube الحالي، issue #69)، **مش** landscape.
4. الخروج بالـBack/الزرار لازم يرجع بورتريه عادي ويفضل الـhand-back شغّال (ما تكسرش `armOrientationHandBack`).
5. تلفزيون: `FocusRing.isTv(this)` → landscape دايماً، بدون hand-back.
6. أي حالة بتتجاهل طلب الاتجاه → `setLayoutOverride(LANDSCAPE)` (موجود في `toggleFullscreen`، استخدمه).

**معايير القبول (يجرّبها المستخدم):** ملف محلي أفقي = يفتح landscape fullscreen من غير لمس · ملف رأسي = upright full · يوتيوب = بورتريه زي الأول · زرار ملء الشاشة يشتغل في الكل · Back يرجّع بورتريه · PiP ما يتكسرش · تدوير الشاشة اليدوي يشتغل.

### المرحلة 2 — محرك الترجمة الكامل (الأكبر في الموبايل)
**أ) تفعيل التوجيه:** `MtPlay.enabled()` الافتراضي `false` ← **اقلبه لـ true** (`Cfg.bool(KEY, true)`) وخلّي في الإعدادات مفتاح رجوع. **بعد** ما تعالج الاستثناءات اللي في 2.1 (القايمة دي هي الشغل الحقيقي):
| الاستثناء (اتقفل ليه) | الحل المقترح |
|---|---|
| `ref` / `cookie` / `ua` (لينك محتاج هيدرز) | مصدر بيانات HTTP بهيدرز مخصصة في مشغّل MeeTube (`DefaultHttpDataSource.Factory().setDefaultRequestProperties`) — لازم تلاقي مكان بناء الـMediaSource (ابدأ من `VideoLoaderController.java` ~512 `openUrlList`) ومرّر الهيدرز عبر حقل جديد في `Video` (مثلاً `Video.headers`) |
| `aurl` (صوت منفصل) | `MergingMediaSource(video, audio)` |
| `qlist` (قايمة) | حوّلها لـqueue في MeeTube (`PlayQueue.kt` ↔ queue بتاعة MeeTube) |
| `forceid` / `ytid` / `ytpend` | يوتيوب بيروح لـ`MeeTubeLink.playYt` أصلاً؛ راجع إن `forceid` ما يتكسرش |
| `incognito` | ما يسجّلش تاريخ/استكمال (`Video` flag + تعطيل history) |
| `hsite` (صفحات/ستريمات محتاجة استخراج) | يفضل يمر بـ`Engine`/extractor الأول ثم يفتح المصدر المستخرج؛ لو معقّد: يفضل على مشغّل Joyboy لحد م5 (سجّل ده في `PLAYER_MERGE_STATE.md`) |
| `remote` / `mirror_uri` | م4 (تلفزيون) |
| ملف `.part` (تحميل شغّال) | يفضل على Joyboy أو MeeTube بـprogressive source — قرار لاحق |
| url مش امتداد ميديا (`MEDIA_EXT`) | وسّع بالـextraction أو اختبار `HEAD`/content-type |

**ب) UI الترجمة داخل MeeTube** (الزرار مخفي حالياً): ارجّع زرار 🌐 في `mOptionsRow` (الـ`options` اللي بيوصل لـ`attach`) أو في شريط التحكم عبر `Provider.extraControls`. حالات الزرار (انظر `refreshBtn` القديمة): «ترجم» · نسبة % (`PlayerLogic.percent(e.coveredSec(), durSec)`) · «…». ضغطة = `toggle()`، ضغطة مطوّلة/زرار تاني ⚙ = قايمة (`showMenu`). نفس ستايل glass بتاع MeeTube (`MtPlayerLook.kt` فيه الستايل الجاهز).

**ج) تحويل `showMenu()` (حوار `GAlert` بسيط) لـbottom-sheet كامل** بنفس شكل أوراق MeeTube، فيه: مزدوج · نسخ · جمل · ضبط توقيت (±) · ستايل · سبتايتل جانبي · مسار صوت · دبلجة · تحميل · إرسال للتلفزيون · إعدادات المحرك. كل بند يستخدم الدوال الموجودة (جدول 3.2) — **ما تعيدش كتابة منطق** `Engine`/`Store`.

**د) `SubtitleView`:** موجود في الـoverlay. تأكد إنه يتحرك لفوق مع ظهور أزرار التحكم (bottom margin ديناميكي عبر `Provider.onControlsVisibility`) ويشتغل مع PiP (`PipSubBar.kt` موجود) والـRTL.

**هـ) التكرار بين الفيديوهات:** `trans` listener (`onMediaItemTransition`) + `maybeAuto()` موجودين؛ اتأكد إنهم يشتغلوا مع queue الجديدة.

**معايير القبول:** ملف محلي يفتح في MeeTube + زرار الترجمة ظاهر + الترجمة بتشتغل وتتعرض + كل بنود القايمة شغّالة + `autotr` من المتصفح/المكتبة بيشغّل الترجمة لوحده + الرجوع لمشغّل Joyboy بمفتاح الإعدادات لسه شغّال.

### المرحلة 3 — أدوات الميديا
نفس الأسلوب: كل ميزة = منطق في `app` (Kotlin، copy-adapt من `Player.kt`) + نقطة واجهة في MeeTube (زرار في قايمة ⚙ أو الشريط عبر `Provider`).
- **A-B repeat:** `abSet(which)`/`abTick()` → listener على `player.currentPosition` (استخدم نفس `tick` بتاع `MtTranslate` أو `Handler`)؛ UI: زراري A/B في ورقة الأدوات + علامات على `mTimeBar` (لو صعب: بس مؤشر نصي).
- **أنماط التكرار:** MeeTube عنده `RepeatMode`/shuffle؛ ضيف «كل فولدر» عبر queue (`PlayQueue`/`TvQueue`).
- **تضخيم الصوت:** `LoudnessEnhancer(player.audioSessionId)` (`applyBoost` فيه المنطق + `Cfg vol_boost`)؛ ربطه بسويب الصوت في MeeTube (فوق 100% = boost).
- **مؤقت النوم:** MeeTube عنده `showSleepTimerSheet` — قارن بـ`setSleepTimer` وادمج الناقص (آخر الفيديو/الحلقة) بدل ازدواج.
- **ترتيب الحلقات + الاستكمال:** `stepEpisode`/`saveRecent` → استخدم تاريخ MeeTube (`getHistoryPositionMs`/`setResumePositionMs`) للمحلي كمان، و`Video.videoId="joy_local_<hash>"` هو المفتاح.
- **قفز بالأرقام:** `tvDigit/tvJump` — للتلفزيون أساساً (م4)، على الموبايل اختياري.

### المرحلة 4 — التلفزيون والريموت (الأخطر)
**مبدأ:** ما نغيّرش بروتوكول الريموت (`RemoteClient`/`TvLink`/`BrainRpc` على الموبايل شغّالين ومتجرّبين). بنغيّر **الطرف اللي بينفّذ** على التلفزيون: بدل `Player.remoteAct/remoteState/brain` بتشتغل على `PlayerActivity`، تشتغل على MeeTube.

1. **`MtTvPlayer` يتوسّع** ليطابق الـAPI: `act(a,v)` يغطي **كل** أوامر `Player.rAct` (استخرجها: `grep -n "fun rAct" Player.kt` وانسخ قايمة الأوامر: toggle/seek/seekto/speed/fit/cc/scale/shift/audio/quality/loop/vol/… ) · `state(): JSONObject` بنفس مفاتيح `rState()` (القايمة فوق في 2.2) · `rpc/brain(op,args)` · `frameJpeg(maxDim,q)` (لقطة من الـSurface/`mSessionSurface`). وصّل `TvLink.playerCmd` يستخدمه لما `Player.live()==null && MtTvPlayer.live()`.
2. **واجهة D-pad:** `onRemoteKey` (~4183) أساسي — ضيف طبقة تلفزيون: لما `FocusRing.isTv(this)` أخفِ واجهة اللمس وأظهر شريط تحكم بتركيز D-pad (نمط `TvPlayerBar.kt` + `TvScrub.kt` تقديم معلّق + `TvFocus.kt` تركيز). عبر `Provider.extraControls`/`onKey` (انظر قاعدة الجسر)، ما تنقلش `TvPlayerBar` للـJava — خلّي الـKotlin يبني الـView ويحطه في الـoverlay.
3. أزرار الميديا (play/pause/ff/rew/next/prev) + الأرقام (`tvDigit`) + Menu (يفتح ورقة الترجمة/الأدوات بتركيز) + Back (يقفل الطبقة العلوية الأول).
4. **التوجيه:** `MtPlay.reroute` سطر `|| DeviceUi.isTv(act)` يتحوّل لـflag منفصل `mt_tv_player` (**الافتراضي false**) — ما يتشالش إلا لما المستخدم يجرّب على TCL ويقول تمام. نفس الشي لـ`TvDirect.playYt`/`TvHome.openYt` (v929 رجّعهم لمشغّل Joyboy عن عمد).
5. `MtTvRoute` (نافذة تشغّله فين) يفضل زي ما هو للموبايل.
6. قاعدة: كل ده `DeviceUi.isTv == true` فقط.

**معايير القبول:** فيديو محلي/يوتيوب على TCL بالريموت الحقيقي: تشغيل/إيقاف/تقديم/جودة/ترجمة · ريموت الموبايل يتحكم في كل حاجة زي ما كان مع مشغّل Joyboy (مقارنة بـ`rState` القديم) · لا تركيز بيتسرق ولا شاشة سودا.

### المرحلة 5 — تنظيف
احذف `Player.kt`/`PlayerPad.kt`/`PlayerLogic` غير المستخدمة **بعد** ما كل ميزة في 3.2 تتأكد شغّالة (فحص `grep` لأي مرجع لـ`PlayerActivity`: Manifest سطر 80 · `Player.remoteAct` · `Player.brain` · `PlayerActivity.live`). راجع PiP/المصغّر/الإشعار مع الميزات الجديدة. حدّث `docs/`، وكل `MEETUBE_AUDIT`. شيل flags الرجوع القديمة بس بعد موافقة المستخدم.

---

## 5. مخاطر وملاحظات (اتعلمناها)
- **v928 فشلت على التلفزيون** (واجهة لمس) → v929 رجّع Joyboy. ما تكرّرش نفس الخطأ: التلفزيون = طبقة D-pad + flag.
- `MobilePlaybackActivity` فيه سلوك اتجاه معقّد (issues #44 #60 #69، تابلت، Android 16، multi-window، pinned task) — **ما تبسّطوش**؛ أي تعديل اتجاه يمرّ على `toggleFullscreen`/`onVideoShapeKnown`/`armOrientationHandBack`.
- `JoyboyPlayerHost.attach` بيتنده كل ما المشغّل يتبني تاني (فيديو جديد/تغيير سطح): `MtTranslate.attach` لازم يفضل **idempotent** (بيشيل النسخ القديمة بالـtag قبل ما يضيف).
- الإشعارات: على الريموت `Notice.beginCapture/endCapture` (v799) بتجمع رسائل الأوامر وترجعها للموبايل — حافظ عليها لما تنقل `rAct`.
- الأرشيف فيه ملفات كتير: لو عايز تبحث ابدأ بـ`CHANGES_v923.md` (MtPlay) · `CHANGES_v928.md` (فشل التلفزيون) · `CHANGES_v929.md` · `docs/MEETUBE_AUDIT_v905.md`.

## 6. نقاط ما اتأكدتش منها (اتأكد قبل ما تبني عليها)
1. مكان ضبط/تصفير `mVideoShapePending` بالظبط (م1).
2. قايمة أوامر `Player.rAct` الكاملة وأسماء مفاتيح `rState` كلها (م4) — اتقرأ جزء منها بس.
3. هل `MobilePlaybackActivity` بيدعم سبتايتل جانبي بطريقة ما (مش لاقيه في الـActivity نفسها؛ ممكن في `common`) — `grep -rn "SubtitleConfiguration" meetube/`.
4. هل `Video` عنده حقل هيدرز؛ لو لأ هتضيفه (`C/app/models/data/Video.java` ~115).
5. أرقام الأسطر كلها تقريبية حسب v929.
