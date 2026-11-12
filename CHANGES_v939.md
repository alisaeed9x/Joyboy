# v939 — دمج المشغّل: أصل التشغيل + التصغير المصدر-واعي + كبسولة التنقل + توجيه اللانشر + المرحلة 4 (جزء 1)

**قرار:** المرحلة 5 (حذف مشغّل Joyboy) ما تتعملش — التلفزيون لسه معتمد عليه، والجسر الكامل
(`engine.*` / SfJob) لسه ما اتنقلش. المرحلة 4 هنا جزء 1 بس: `MtTvPlayer` موسّع ورا علم
`mt_tv_player=false`، والريموت/الـRPC بيتعاملوا معاه كاحتياطي لما مفيش مشغّل قديم حي.

**جديد — `MtOrigin.kt` (ملف جديد):**
- مخزن جلسة تشغيل واحد مصدر-واعي: نوع المصدر (YOUTUBE/BROWSER/LOCAL/EXTERNAL/DIRECT_URL/
  PLAYLIST/REMOTE_TV)، الرابط/الملف، `ret`، الصفحة الأصلية، معرّف الجلسة، الهيدرز.
  بيتسجّل في `MtPlay.openMt` وبيتقرأ عند التصغير والإغلاق — مفيش مسار بيخمّن الوجهة.
- وجهات الرجوع: BROWSER ← BrowserActivity · YOUTUBE(ret=yt) ← تاب يوتيوب · LOCAL/EXTERNAL/
  DIRECT_URL/PLAYLIST ← تاب الفيديوهات · REMOTE_TV ← TvActivity · يوتيوب بلا `ret` ← MeeTube نفسه.

**التصغير (P1):**
- `MobilePlaybackActivity.minimizeByDrag`: لو `JoyboyPlayerHost.onMinimize` وافق (مصدر مش يوتيوب
  ومش تلفزيون) بيدخل PiP بتاع النظام فوق التطبيق الأصلي (متصفح/واتساب) بدل ما يدور على
  مضيف MeeTube ويقع على يوتيوب. لو PiP اترفض بيكمل مسار التصغير العادي.
- `MtTranslate.onMinimize` بيرجع `true` للمصادر غير اليوتيوبية بس. يوتيوب بيكمل على دوك MeeTube.
- `MtMedia.detach`: `MtWeb.onDetach` بقى بيرجع Boolean — لو ما رجّعش، `MtOrigin.onDetach` بياخد دوره.
- `.part`: MeeTube بس لو مفيش مهمة تحميل نشطة (`TaskCenter`) بتملك الملف؛ النشط بيفضل على Joyboy.
- `mirror_uri` و`handoff` بيفضلوا على Joyboy عن قصد (مربوطين بـ`Player.kt` — نقلهم كان هيعيد
  التشغيل ويفقد الموضع). متوثّق، مش متخبّي.

**كبسولة التنقل (P2) — `BottomNav.kt`:**
- `place()` بقى السائق الوحيد: `mode()` و`set()` بينادوه مباشرة بعد `fillRow()` — قبل كده كان
  الاعتماد على listener حدود الصف اللي عمره ما بيتنادى عند تبديل الأطفال، فالكبسولة كانت
  متجمدة على Media.
- `cellOf`: `4 → YT_HOME` في ترتيب يوتيوب (قبل كده كان بيقع على ⋯).
- `place()` بيعيد المحاولة لحد 10 مرات لو الخلية لسه مش مقاسة (قبل كده كان بيسكت).
- مسار الرجوع بعد 600ms اللي كان بيرجّع الكبسولة للميديا اتشال — الكبسولة بتعكس الحالة الحقيقية.
- قاعدة المؤشر `START` ← `LEFT` (RTL: الحساب مطلق).
- كبسولة MeeTube العلوية: fallback بعد 1200ms لو الـlens settle اتفقد (`MobileBrowseActivity`).

**توجيه اللانشر (P3):**
- `SplashActivity`: `isTvMode()` = هاردوير أو `ui_mode=tv` من تفضيلات `p` (نفس تعريف
  `DeviceUi.isTv`)؛ ضغطة اللانشر مش محتاجة `isTaskRoot()` تاني؛ ضغطة لانشر بلا data عمرها
  ما بتقع على YouTube Browse — لو الهدف مش قابل للحل بيتقفل التطبيق.
- `Main.onNewIntent`: نفس شرط إعادة التوجيه للتلفزيون بتاع `onCreate` بيتطبق على الـintent الجديد.

**المرحلة 4 — جزء 1 (`MtTvPlayer.kt` أعيد كتابته، 75 ← ~300 سطر):**
- علم جديد `mt_tv_player` (افتراضي `false`) — بيقفل بس توجيه `MtPlay` للتلفزيون للملفات
  المحلية/المباشرة. مسار يوتيوب-على-TV من v928 (ريموت) مش متغطّي — تقفيله كان هيكسر الريموت.
- `act()` بيرجع `false` بصراحة للأوامر غير المدعومة (قبل كده كان بيرجع `true` كذب لأي أمر).
- جديد: prev/next (عبر `MtMedia.onStep`)، تدوير السرعة، loop/loopset (نفس مفاتيح `Cfg`)،
  ab_a/ab_b/ab_x، `volume.set` 0–100، `remoteToggle()`، `remoteHandoff()`/`remoteQueueCur()`
  من `MtOrigin`، `frameJpeg` عبر PixelCopy من SurfaceView بتاع MeeTube (fallback للـTextureView).
- `state()` موسّعة: loop/lm/vol/ab/cc/quality/audio/vw/vh/src/spd — بس المتنفذ فعلاً.
- `TvLink`: `/handoff` و`/queue addcur` و`playpause` بيقعوا على MtTvPlayer لما مفيش قديم حي.
- `BrainRpc`: `/sync` بيقول `live=true` لما MeeTube بس شغّال؛ `/frame` بيقع على `frameJpeg`.
- **لسه مش في الجزء 1:** ربط `TvPlayerBar` الكامل، جسر `engine.*`، SfJob/سبتايتل من الموبايل،
  fit/rotate، الوضع البصري، الدبلجة — كلهم متوثّقين كغير مدعومين بصراحة في `MtTvPlayer`.

**CLOSE/PAUSE (P4):**
- `MtMedia.onEnded`: حارس `playbackMode` — PAUSE/CLOSE ما بيفتحوش التالي أبداً، ONE بيعيد
  نفس الفيديو، ALL بيفتح التالي مرة واحدة.

**D-pad (P4):**
- `MtTranslate.onKey`: أرقام الريموت 0–9 (آخر رقمين = ثواني، تأكيد 2.2ث) على التلفزيون بس.
- `MobilePlaybackActivity.dispatchKeyEvent`: بينده `JoyboyPlayerHost.onKey` الأول.

**وثائق (P6):** `docs/PLAYER_UI_UX_BLUEPRINT.md` · `docs/PLAYER_SCREEN_FLOW.md` (Mermaid) ·
`docs/PLAYER_INTERACTION_SPEC.md` · `docs/PLAYER_TEST_MATRIX.md` (كلها «معلّقة» لحد البناء والتجربة).

**معدّل:** `MtOrigin.kt` (جديد، manifest 297 ← 298) · `MtPlay.kt` · `MtMedia.kt` · `MtWeb.kt` ·
`MtTranslate.kt` · `MtTvPlayer.kt` · `TvLink.kt` · `BrainRpc.kt` · `BottomNav.kt` · `Main.kt` ·
`JoyboyPlayerHost.java` · `MobilePlaybackActivity.java` · `MobileBrowseActivity.java` ·
`SplashActivity.java`.

- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس في الملفات المعدّلة، والأسماء بالاسم
  (`Cfg.bool/put`، `PlayerLogic.nextSpeed/clock`، `TaskCenter.items`، `MtMedia.onStep/abNext`،
  `Notice.show`، ملف تفضيلات `p`).
