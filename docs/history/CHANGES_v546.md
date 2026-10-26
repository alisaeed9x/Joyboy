# v546 — م0-ب (ب1 + ب2) + م2-ج — مبني على v545
> **ماعملتش compile ولا بناء APK ولا شغّلت أي اختبار.** الجلسة مفيهاش kotlinc ولا إنترنت. اللي عملته: مراجعة يدوية + فحص توازن الأقواس (Player.kt قبل وبعد: نفس الميزان) + grep على كل استدعاء `player.` بعد التعديل.
> **غير مجمَّع:** `PlaybackBackend.kt` (جديد) · `Player.kt` · `PlayTarget.kt` · وكل ملفات v545 غير المجمَّعة (`LinkCore` · `LinkSession` · `RemoteEngine` · `TvTasksUi` · `BrainCore` · `BrainRpc` · `Tasks` · `KeepAliveService` · `RemoteActivity`).

## انحراف عن الخطة (بموافقة علي)
الخطة (بند 5.3) كانت بتقول م0-ب1 ← استنى علي يجرّب المحلي ← ب2. علي طلب صراحة م0-ب كاملة + م2-ج في تسليم واحد، فاتعملوا مع بعض. ده بيرفع خطر الأعطال: لو البناء فشل أو المحلي اتغيّر، الأول نصلّح بناء v546 وبعدين نجرّب المحلي قبل أي حاجة تانية.

## فحص الاعتماديات (قاعدة 10)
| المطلوب | الحالة |
|---|---|
| م0-أ `EngineBackend` (`engine` + `realEngine`) | ✅ في v541 |
| م1 `BrainRpc` (`/rpc` `/sync` `/frame`) | ✅ في v542 |
| م2-أ `LinkSession` · م2-ب `RemoteEngine` | ✅ في v545 (غير مجمَّعين) |
| `MediaSpec` في `PlayTarget.kt` | ✅ اتستخدم زي ما هو ومااتعرّفش تاني |

## م0-ب — `PlaybackBackend`
- **`PlaybackBackend.kt`** (جديد، متسجّل في `kt_manifest.txt`): الواجهة (`currentPosition` · `duration` · `isPlaying` · `playWhenReady` · `volume` · `playbackState` · `bufferedPosition` · `play/pause/seekTo/setPlaybackSpeed` · `load(MediaSpec)` · `release` · `audioTracks/currentAudio/selectAudio` · `videoSize()` · `exo()` · `isRemote`).
- **`LocalPlayback(ExoPlayer)`:** تفويض كامل من غير منطق.
- **`Player.kt`:** `lateinit var player: PlaybackBackend` (الاسم فضل `player` فالاستدعاءات البسيطة ماتغيّرتش). كل اللي محتاج ExoPlayer فعلًا بقى `player.exo()`: `currentTracks` (6) · `trackSelectionParameters` (كل التعيينات) · `videoFormat` · `audioSessionId` · `setSeekParameters` · `setAudioAttributes` · `setHandleAudioBecomingNoisy` · `setVideoSurfaceView` · `addListener` · `setMediaSource/setMediaItem/prepare` (switchYt · toggleYtMode · ytSilentRetry · الرابط المحدّث · buildPlayer). `DubPlay` و`VoiceClean.Playback` بياخدوا `exo()`.
- `player.videoSize` بقت `player.videoSize()` (زوج). `YtHandoff.take()` بيتلف في `LocalPlayback`.
- الـtick فاضل متزامن 200ms ويقرا من `player` زي ما كان.

## م2-ج — وضع المتحكم (`remote=true`)
- **`RemotePlayback`:** getters من `LinkSession.mirror` (ذاكرة بس) + extrapolate للموضع؛ الأوامر async (`play/pause/seekto/speed.set/track.audio/load`) مع قيمة تفاؤلية قصيرة؛ السحب على الشريط بيتجمّع (آخر قيمة كل 120ms). الصوت على الموبايل مكتوم (مابيشغّلش حاجة).
- **`PlayerActivity` لما `remote=true`:** مفيش `ExoPlayer` ولا `Engine` ولا `Store`. `buildRemotePlayer()` (جلسة + مستمع تغيّرات + خيط لقطات) · `initRemoteEngine()` (`RemoteEngine` + `VisualMode` فاضي) · `onRemoteChange()` (زرار التشغيل · العنوان · السرعة · حالة الترجمة · التحميل) · `remoteWatch()` من الـtick (انقطاع الاتصال · التلفزيون قفل الفيديو · التلفزيون ما فتحش الفيديو خلال 30ث ← بيقفل الشاشة).
- **اللقطة الحية:** خيط واحد على `/frame?w=480&q=60`؛ بيرسم على **نفس الـSurfaceView** بـlockCanvas، ومقاس السطح بيتحدّد من مقاس اللقطة. شغّال: لقطة كل ~600ms؛ واقف: بعد seek أو كل 6ث؛ مابيطلبش وهو في الخلفية/PiP/مفيش اتصال. `204` (DRM/أسود) ← بتفضل آخر لقطة (أو أسود) وسطر الحالة «📺 متحكم في التلفزيون». **مفيش بوستر بديل لسه.**
- **حراسة (عشان الموبايل ما يوقّفش حاجة على التلفزيون):** `retireEngine` · `saveRecent` · `onPause/onDestroy` `saveNow` · `killForNewVideo` · `startEngine` — كلها لا-عملية في الوضع ده. قفل شاشة المتحكم **مابيوقّفش** التلفزيون.
- **متعطّل في الوضع ده:** الوضع البصري (`visualStart()` ← «شغّال على التلفزيون») · الدبلجة (`DubPlay`) · تعزيز الصوت (`applyBoost`) · «الفيديو اللي بعده» (`hasNextEpisode=false` و`playNextYt`) · `v469Tick` (التلفزيون هو اللي بيعملها).
- **«▶ ترجمة»** = `engine.resume` على التلفزيون (`remoteBegin`).
- **`PlayTarget`:** بعد نجاح الإرسال بيفتح `PlayerActivity` بـ`remote=true` بدل `RemoteActivity`. «إعادة المحاولة» لسه موجودة، ومفيش تشغيل محلي لو التلفزيون مش متصل (قرار ب).
- `rState` على التلفزيون بيبعت كمان `vw` `vh`.

## اللي لسه / مش متأكد منه
- **م4:** `handleLinkIntent` · `Links.sendToTv` · `playYtAudio` · قرار الملف المحلي (الافتراضي: يشتغل محليًا). **م3:** `LibraryBackend` ما اتعملش. **م7:** تنظيف.
- أدوات الفيديو اللي بتقرا `toolSrc()` (قص/ضغط/GIF من داخل المشغّل) في وضع المتحكم **ماراجعتهاش**؛ الصح إنها تروح `task.add` على التلفزيون (موجودة في «🧰 مهام التلفزيون»).
- شاشات `TrWizard`/`sentDlg`/الإصدارات بتشتغل على `RemoteEngine` نظريًا؛ ماتفحصتش واحدة واحدة، وفي أماكن ممكن تفترض إن `uri`/`url` موجودين.
- `RemoteActivity` فاضلة زي ما هي (شاشة الريموت الدلالية + «🧰 مهام التلفزيون»).

## للتجربة (بالترتيب)
1. ابني v546. لو فشل ابعتلي اللوج — أغلب احتمال في `Player.kt` (تعديلات `exo()`) أو الملفات الأربعة الجديدة من v545.
2. **المحلي الأول:** فتح فيديو · seek · سرعة · مسار صوت · جودة يوتيوب (🎞) · PiP · ترجمة · دبلجة · «الصوت النضيف». لازم يبقى زي v545.
3. `bash dev-tests/run_tests.sh` (المنطق النقي ماتغيّرش، بس اتأكد).
4. وضع المتحكم: «وضع التلفزيون» شغّال ← افتح يوتيوب/ملف تلفزيون ← لازم تفتح شاشة المشغّل الكاملة، اللقطة تتحدّث، الترجمة تظهر، التشغيل/seek/سرعة بيتحكموا في التلفزيون، وقفل الشاشة مايوقّفش التلفزيون.
