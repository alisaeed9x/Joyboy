# Joyboy v1080 — Skipii مكان اليوتيوب القديم + MeeTube (بقى GPL-3.0)

**قرار المستخدم (2026-11-16): دمج كود Skipii زي ما هو، وJoyboy يبقى GPL-3.0.** ملف `LICENSE` (GPL-3.0) اتحط في جذر الريبو، وهيدرز Skipii محفوظة في كل ملف منقول. ما اتعملش compile — Actions هو الحكم.

## 1) Skipii اتنقل جوه Joyboy
- 50 ملف `.kt` من Skipii (63 ملف أصلًا: 12 ملف تست ما اتنقلوش — مكانهم `src/test` مش `src/main`) تحت `com.decio.skipii` بنفس الباكدج والهيدرز.
- Compose اتضاف لـ:app لأول مرة (BOM 2024.09.03 + plugin 2.3.21 + `buildFeatures.compose/buildConfig = true` + coil 2.7.0 + Media3 dash/session/ui-compose) على نفس toolchain بتاع Joyboy (AGP 9.2.1 / Kotlin 2.3.21 / compileSdk 37 — مفيش downgrade).
- okhttp: Joyboy بيثبّت 5.4.0، وSkipii كان معلِن 4.12.0 — فضل 5.4.0، وكل استخدامات OkHttp في كود Skipii موجودة في 5.x (اتدقّقت بالاسم).
- `Tv.kt` (مشغّل تلفزيون كامل، 808 سطر) اتشال: Joyboy عنده طبقة تلفزيون كاملة أصلًا، فمشغّل تاني كان هيتعارض.
- ML Kit للترجمة اتشال تمامًا (الحظر الدائم): `CaptionTranslator` بقى stub فاضي بنفس الاسم، والترجمة بقت من محرك Joyboy (بند 5).
- السترينجز: 431 سترينج + 8 plurals من Skipii، والتصادم مع Joyboy طلع صفر (Joyboy عنده 5 سترينجز بس) فمفيش إعادة تسمية. الثيم `Theme.Skipii` + 3 ألوان اتنقلوا. `supportsRtl="false"` بتاع Skipii ما اتنقلش (Joyboy عربي RTL).
- `app_name` قيمته بقت "Joyboy" (كان "Skipii" — استخدام واحد بس كعنوان احتياطي للإشعارات).

## 2) اليوتيوب القديم + MeeTube اتشالوا
- اتمسح 16 ملف `.kt` (6,891 سطر): YtProUi / YtWatch / YtNp / YtChannel / YtRss / YtAccount / YtBackend / YtHistoryUi / YtFast / YtSkin / YtLink / YtThumbBtns / YtTopBar / YtLoad / YtSnap / YtLocale. واتمسح `joy_nav_glass.xml` (غلاف فاضي) + `mt_tab_enter/exit.xml` (0 استخدام).
- `YtStore` (كان جوه YtNp.kt ومستخدَم في `YoutubeUi.kt`) اتنقل حرفيًا لملف جديد `YtStore.kt`.
- **ما اتمسحش:** `MtGlass.kt` (شيم جلد عام، 21 مرجع — مش كود MeeTube)، `MtSmooth` / `LocalMt`، `YtModels.kt` (NpItem/YtPick شايلة أنواع في كل حتة)، `YtAudio` / `YtNext` / `YtFallback` / `YtFeedStore` / `MtFeed` / `MtHeader` / `MtSkin` (كلهم لسه مستخدمين).
- **MtYt.kt فضل كامل (213 سطر) ومش متقلّص** — لسه فيه منادين أحياء (Player.kt وMain.kt وSniffer وMtFeed وTvHome وYtNext وTvLink وYtFeedStore)؛ التقليص لـapp/boot/signedIn/signOut ممكن بس لما المنادين دول يتستبدلوا.
- من MeeTube فضل بس أنيميشن الـloading: الـ8 لودرات `avd_*` + `animator/neon_arc.xml` + `values/mt_res_v1074.xml` (وفيه `Theme.NewTube` اللي الـmanifest بيشاور عليه) + لودرات `MtPlayerLook`. مودولات `:youtubeapi` / `:sharedutils` / `:mediaserviceinterfaces` فضلت (تسجيل الدخول بالـactivation محتاجها، واستثناء nanojson زي ما هو).

## 3) التبويب بقى Skipii (مدمج مش Activity منفصلة)
- `MainActivity` بقت `ComponentActivity` (لازم — SkipiiApp بيعمل cast لـComponentActivity عشان الـOrientation/PiP، والـComposeView محتاج LifecycleOwner + SavedStateRegistryOwner). تبويب اليوتيوب بقى `ComposeView` فيه `SkipiiApp(embedded = true)`، وشريط Joyboy السفلي والأدوات فضلوا.
- Back / ON_STOP / PiP اتنقلوا لـMainActivity: Back بيمشي على نفس أولويات Skipii (القايمة > الشريط > الشاشة الكاملة > التعليقات > القناة > تسجيل الدخول > القوائم > التبويب > المشغّل المفتوح)، وON_STOP بيحوّل للصوت بس، والـPiP شغال من الـmanifest.
- الشورتس (قاعدة v1073): الشورت (رابط `/shorts/` أو ≤60 ث) بيفتح Shorts pager، وأي فيديو تاني بيفتح المشغّل على طول — القاعدة بقت في `SkipiiHost.isShort/isShortUrl` + `SkipiiApp.openVideo/routeUrl`.
- `PlaybackService` اتسجّل في الـmanifest كـMediaSessionService (`mediaPlayback`) + `LiveControlsReceiver`. `MainActivity` خدت `supportsPictureInPicture` + `resizeableActivity`.

## 4) تسجيل الدخول — طريقتين
- **كود التفعيل (MeeTube):** زرار جديد في الإعدادات «🔐 تسجيل الدخول بحساب يوتيوب (كود التفعيل)» + صندوق activation في صفحة حساب Skipii — الاتنين بيندهوا `YtMtSignIn.signIn` (كود الجهاز + youtube.com/qr/activate/&lt;code&gt;). التوكن القديم في `global_prefs` زي ما هو.
- **العادي (Skipii):** تسجيل دخول جوجل بالـWebView زي ما هو في Skipii (كوكيز SAPISID + LOGIN_INFO مشفّرة).
- ملحوظة معمارية مش بج: التفعيل بيسجّل توكن MtYt (التشغيل/الموسيقى/التلفزيون/التحميل) ومش بيسجّل حساب Skipii نفسه — Home/Subs بتاعة Skipii لسه محتاجة دخول جوجل عشان تبقى signed-in.

## 5) الترجمة والتحميل على مشغّل Skipii
- زرارين جداد في صف التفاعلات تحت الفيديو (`PlayerActions`): «▶ ترجم» (بتتغيّر حالته: بجهّز / إيقاف مؤقت / كمّل) و«⬇ تحميل». الاختيار كان `ActionPill` مش `BarTool` (الترجمة أكشن تحت الفيديو زي مشاركة، مش كنترول على الفيديو نفسه).
- الجسر `SkipiiBridge.kt` (جديد): بياخد id الفيديو (11 حرف) + العنوان + الموضع بالثواني من `PlayerState`، وبيجيب رابط الصوت من `StreamCache`، ويبني `Engine` زي `Player.initEngine()` بالظبط (نفس مفتاح الحفظ `"yt:<id>"` فالترجمات المحفوظة مشتركة مع المشغّل القديم)، والترجمة بتظهر في نفس طبقة الكابشنز (`CaptionLayer`). TrSlider بيشتغل من غير توصيلة إضافية (المحرك بيقراه live)؛ وTrProfile بيتعمله `restore()` في بداية الترجمة. الموديل واحد `gemini-flash-lite-latest`.
- التحميل: `DlQuality`/`Downloader` زي ما هما (مفيش داونلودر تاني) — بيستهلكوا روابط `StreamCache.get(id)` + رابط الصوت + `Net.USER_AGENT`، وقايمة الجودات من `StreamSelection.heights` (سقف 1080p)، ونفس قايمة مهام التحميل بتاعة `Main.ytDownload`.
- **ما اتنقلش للمسار ده:** ديالوج ⚙ إعدادات الترجمة وقايمة الترجمة (ديالوجات View بتاعة PlayerActivity)، وTrLib stamping وعدادات NetLedger وBrainBus.

## 6) الشريط السفلي والدايرة العايمة
- ثابت واحد `JoyboyChrome = 57.dp` (صف BottomNav 56 + فاصل 1 — الـMediaSwitch مش موجود على تبويب اليوتيوب أصلًا): الشريط السفلي بتاع Skipii والـmini-player والشريط الزجاجي بتاع الشورتس كلهم اترفعوا بنفس المقدار فوق شريط أدوات Joyboy، والتابلت والتلفزيون بياخدوا 0 (الشريط الجانبي مش السفلي).
- دايرة عايمة جديدة `YtBubble.kt` (207 سطر): لما تسيب اليوتيوب لأي تبويب تاني (مش Back — الـBack لسه بيقفل خالص زي v1001) الفيديو بيكمّل في دايرة 46dp بصورة الفيديو، قابلة للسحب، بتثبّت على الطرف، والسحب لعلامة الـX بيوقف الفيديو (إعادة استخدام `DragTrash` بتاع MiniDock). الضغطة عليها بترجّعك للمشغّل في نفس الموضع. النطاق in-app (مش overlay) لأن الصوت أصلًا في `PlaybackService`.

## 7) إصلاح إضافي في نفس النسخة — أيقونة التبويب المختار كانت مختفية
- السبب: الكبسولة بتاعة التبويب المختار كانت بتترسم فوق الأيقونة (اتضافت بعد الصف في الـFrameLayout)، ومن ساعة v1071 ما شالت الزجاج بقت تعبئتها مصمتة `#333333` فبتدفن الأيقونة والليبل تحتها. الإصلاح في `BottomNav.kt` بس: تعبئة شفافة جديدة `capsuleFill()` (داكن: أبيض 20%، فاتح: أسود 12%) — الأيقونة والليبل بقوا باينين. ترتيب التبويبات وشكل الكبسولة زي ما هما، والـchip بتاع توفير الداتا ما اتلمسش. الدليل: لقطتين `132533_8_kmma.jpg` (المتصفح مختار والكبسولة فاضية) و`132532_9_svxf.jpg` (الميديا مختارة والكبسولة فاضية).

## 8) حاجات اتقفلت
- الـupdater بتاع Skipii اتقفل (`checkIfDue`/`checkNow` بقوا no-op) — كان هيعرض تحديثات Skipii لمستخدمين Joyboy. تحديثات Joyboy زي ما هي.
- كسر محتمل اتلاقى واتصلّح في Phase 2: `Shorts.kt` و`ShortsBank` كانوا بيعملوا cast لـ`SkipiiApplication` (مش كلاس التطبيق — التطبيق `JoyApp`) فكان هيحصل `ClassCastException` أول ما الشورتس يتفتح؛ الـholder اتنقل لـ`SkipiiShorts` في JoyApp.
- كل الـsingletons اللي Skipii محتاجها اتوصّلت في `JoyApp.onCreate` (CrashLog وNewPipe وThemeSettings وAccount وBells وNewVideos وSponsorBlock وShortsBank وcallback الشبكة اللي بيلغي `StreamCache`).

## الملفات (ملخص)
- جديد: 50 ملف Skipii + `SkipiiHost.kt` + `SkipiiBridge.kt` + `YtBubble.kt` + `YtStore.kt` + `LICENSE` + سترينجز/ثيم Skipii + `ic_stat_skipii.xml`.
- ممسوح: 16 ملف يوتيوب قديم + `joy_nav_glass.xml` + أنيميشن `mt_tab_enter/exit`.
- معدّل (Joyboy): `Main.kt` (ComponentActivity + التضمين + الدايرة + تسجيل الدخول) و`JoyApp.kt` (ImageLoaderFactory + singletons) و`BottomNav.kt` (إصلاح الأيقونة بس) و`app/build.gradle.kts` (Compose) و`AndroidManifest.xml` (الخدمة + PiP) و`proguard-rules.pro` (keep لـ`com.decio.skipii`).
- معدّل (Skipii): `MainActivity.kt` (التضمين + الهندسة) و`Settings.kt` (صندوق activation) و`Updater.kt` (مقفول) و`Captions.kt` (شيل ML Kit + جسر الكابشنز) و`PlayerState.kt` (حالة الترجمة) و`PlayerUi.kt` (الزرارين) + R/BuildConfig imports في 27 ملف (لازم — الباكدج اتغيّر).
- الـmanifest: 316 ← 354 سطر، 0-diff. البوابات: rule-7 فاضي، ktscan 354 ملف NONE، توازن الأقواس 0/0/0 على كل الشجرة (باستخدام lexer بيفهم `""""` بتاعة كوتلن — الفحص الأول كان بيطلّع إنذار كاذب على `Captions.kt:141`).

## «مش متأكد منه»
- **ما اتعملش compile هنا.** أخطر النقط على بناء Actions: (أ) plugin الـCompose 2.3.21 مع AGP 9.2.1 — لازم يتحل من `gradlePluginPortal()`؛ (ب) `MainActivity : ComponentActivity` وتفاعلات lifecycle/saved-state مع حالة Joyboy اليدوية؛ (ج) `LocalContext.current as ComponentActivity` في SkipiiApp — بينجح بس بسبب تغيير الـsuperclass؛ (د) R8 + Compose مع `joyMinify=true` على الـdebug (قواعد Compose/Coil الافتراضية + keep واحد بس)؛ (هـ) `activity-compose:1.10.1` مقابل activity 1.13.0 (الحل بيختار 1.13.0).
- سلوك OkHttp 5.4.0 وقت التشغيل (خصوصًا `media3-datasource-okhttp` + مشاركة `Net.client`) مش مثبَت على جهاز.
- `BuildConfig` هيحمل إصدار Joyboy، فـ`Updater`/`Crash` كانوا هيبلّغوا إصدارات Joyboy — الـupdater اتقفل، وCrash بيبلّغ إصدار Joyboy عادي.
- التفعيل ≠ دخول Skipii (بند 4) — اللي يفعّل ويستنى Subs/Home بتاعة Skipii تبقى signed-in هيتفاجئ؛ دي المعمارية مش بج.
- رابط `/shorts/` أو ضغطة ≤60 ث بيفتح قايمة Shorts من عنصر واحد (مفيش استمرارية بعدها)؛ وروابط المشاهدة العادية من غير مدة مبتاخدش نص قاعدة الـ60 ث.
- فوكس ريموت التلفزيون: `YtProUi.tvFocusList()` مالوش بديل متوصل؛ Skipii عنده نظام فوكس تلفزيون خاص بيه (مش متجرّب على TCL).
- سيب تبويب 4 مبقاش بيوقف حاجة (الـ`onHide()` القديم كان بيوقف الـmini-player)؛ Skipii بيكمّل كصوت مع الـmini-player زي تبديل تبويباته هو.
- `NewVideos.schedule` بيشغّل فحص WorkManager كل 15 دقيقة للقنوات المعلّمة بالجرس؛ وصول الإشعارات معتمد على إذن POST_NOTIFICATIONS وقت التشغيل.
- الهندسة اتحسبت ساكنًا (نفس الثابت في كل المكدسات) بس البرتريه هو المسار المتأكد؛ اللاندسكيب على الموبايل (الـrail والـmini في اليمين تحت) مش متأكد.
- مع الوضع الافتراضي (`PlayerControls.inBar = true`) مفيش NavPill ظاهر أصلًا وهي مدمجة، فرفع شريط المشغّل كامن لحد ما المستخدم يختار كنترول على الفيديو.
- الدايرة بتظهر أثناء الـbuffering كمان (عشان ما تطفيش مع إعادة التحميل) وبتختفي لو الفيديو متوقف مؤقتًا — بالتصميم. الشورتس مش متغطي بالدايرة (PlayerState بس).
- تركيبة الكبسولة في الثيم الفاتح محسوبة مش متشافة (اللقطتين كانوا ثيم داكن).
- أيقونات `Icons.Rounded.Translate` / `Icons.Rounded.Download` أسماء قياسية في `material-icons-extended` بس مش متأكدة من غير compiler؛ و`inner class BridgeHost` بيوصل لـprivates بتاعة الـobject (كوتلن قانوني بس مش مترجم).
- صف الكابشنز في الشاشة الكاملة ممكن يعلّم على "Off" والترجمة شغالة (الاختيار العربي الصناعي مش في `cap.tracks`) — تجميلي بس.
- `TrLib` stamping وعدادات `NetLedger`/`NetUse` و`BrainBus` وديالوج ⚙ إعدادات الترجمة ما اتنقلوش للمسار ده (متعمّد — مش لازمين للمسار الأساسي).
- WorkManager بيعتمد على الـinitializer بتاع androidx-startup اللي بييجي transitively — مش مثبَت في إعداد Joyboy.
- ShortsBank: مكان الكاش والإخلاء بعد Size=10 مش متأكد؛ وسلوك Home من غير تسجيل دخول (InnerTube.home بيستخدم `post` بتاع المسجّل) مش متأكد.
