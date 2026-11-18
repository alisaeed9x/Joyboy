# Joyboy v1105 — يوتيوب Y5: مسح واجهة ميتيوب وربطها (يوتيوب Joyboy القديم بقى الوحيد)

> **لم يتم compile** ولا اتجرّب على جهاز — مفيش كومبايلر هنا، Actions هو الحكم. الأساس: `Joyboy_v1104.zip`.
> اللي اتعمل هنا فحص ستاتيك بس: مفيش أي مرجع فعلي متبقي للملفات الممسوحة · توازن الأقواس مقارنة بـv1104 · `kt_manifest.txt` متزامن مع الملفات.

## اللي اتغيّر
**1) يوتيوب = يوتيوب Joyboy القديم (YtProUi) دايمًا**
- مفتاح `yt_classic` اتشال (والسويتش بتاعه في الإعدادات). كل بوابات يوتيوب (الشريط السفلي في Main/المتصفح/الملفات/FtUi/CareKit، فتح من إشعار أو لينك) بتفتح **تبويب 4 في Main**.
- من برّه Main (متصفح/ملفات/عناية): `TabNav.main(this, "youtube")` / `goMain("youtube")`.
- لينك قناة/قايمة/بحث (`openYtPage`) ← `YtProUi.openUrl` وللبحث `voiceResult`. سجل يوتيوب (`showYtHistory`) ← تبويب «أنت».
- استرجاع التبويب بعد قتل النظام للأكتيفيتي بقى يشتغل على الموبايل كمان (كان على التلفزيون بس).
- زرار «MeeTube» في شريط يوتيوب القديم اتشال.

**2) فيديو يوتيوب بيتفتح في `Player.kt`**
- `Main.playYt`: اتشالت فروع `MeeTubeLink.playYt` (العادي + الـ`if (false …)` الميت). كله بيعدّي على `ytStart` (مشغّل Joyboy) زي ما كان لما ميتيوب بيرفض.
- `PlayerActivity.onCreate`: شيلت `MtPlay.reroute/holdForPlayer`. `startPlayerNow` (Main) و`FileManager`: `startActivity(pi)` على طول.

**3) ملفات اتمسحت من `app` (12) + اتشالت من `kt_manifest.txt`**
`MeeTubeLink` · `MeeTubeBar` · `MtPlay` · `MtTvRoute` · `MtMedia` · `MtWeb` · `MtOrigin` · `MtBar` (2490 سطر) · `MtTranslate` (1485) · `MtTvPlayer` · `MtFrames` · `YtMiniCard`.
(الكل كان ربط مشغّل ميتيوب/شريطه/ترجمته؛ مشغّل ميتيوب مبقاش ممكن يتفتح.)

**4) اللي اتنقل/اتعدّل قبل المسح (القسم 3 من الخطة)**
- `JoyApp`: اتشال `bindBar`/`MtTvRoute.install`؛ `onTrimMemory/onLowMemory` بيكلّموا `MobileMainApplication.booted()` مباشرة؛ `MtFeed.install(this)`.
- `MtFeed`: خد من `MtTvRoute` متابعة «الشاشة الظاهرة» (`install/resumed`)؛ طلب تسجيل الدخول من التلفزيون بقى بيفتح **شاشة الكود** (`YtAccount.login`) بدل ميتيوب.
- `ResumeAsk`: `localVid` اتنقلت جواه (كانت `MtTranslate.joyVid`).
- `TvLink` / `BrainRpc`: اتشالت فروع `MtTvPlayer` و`MtTranslate.importSub/applyPhoneSubs` (مفيش مشغّل ميتيوب على التلفزيون).
- `MobileDataGuard`: شيلت إيقاف/استكمال `MtBar`.
- `AndroidManifest.xml`: 16 أكتيفيتي ميتيوب (`SplashActivity` · `MobileBrowse/Playback/LocalPlayback/Settings/Search/Channel/ChannelUploads/AppDialog/Update/SignIn/WebBrowser/AddDevice` · `SettingsTransferActivity` · `DownloadFolderActivity` · `SeekThumb`) عليهم `tools:node="remove"` ← **مفيش أي شاشة ميتيوب ممكن تتفتح**.
- نصوص: «سجّل الدخول من MeeTube» ← «بالكود»، ووصف قسمي المشغّل/يوتيوب في الإعدادات.

## اللي فضل (ممنوع يتلمس / لسه محتاجه — مقصود)
- **الجلاس والأنيميشن والثيم:** `MtGlass · MtGlassView · MtHeader · MtSkin · MtPlayerLook · Theme` + `MtSmooth · MtEnhance` — ما اتلمسوش.
- **ملاحظة مهمة لـY6:** الجلاس بتاعنا معتمد على كلاسات Java جوه `:mobile`: `GlassView` · `GlassSource` · `NavLens` · `Motion` · `ThemeMode` · `SeekThumb` · `JoyboyPlayerHost.VideoInfo` (داخلة في MtSmooth/MtEnhance) · وبوت المحرك `MobileMainApplication.boot` (MtYt/الدخول). **لازم تتنقل لـ`app` قبل ما `:mobile` يتمسح** — عشان كده كود `:mobile`/`:common` الـJava لسه موجود ومتكومبايل بس **مفيش أي أكتيفيتي منه بتتسجّل** (مسح الكود نفسه = Y6).
- `MtFeed` (دفع الرئيسية/الاشتراكات للتلفزيون) و`MtYt` فاضلين.

## أخطر نقط للبيلد (لو وقع ابعتلي اللوج)
1. `Main.kt`: `openYtPage` الجديدة (`ytUi?.openUrl` / `voiceResult`) · `showYtHistory` (`BottomNav.YT_ME - YT_HOME`) · سطر `4 -> "youtube"` في استرجاع التبويب.
2. `MtFeed.kt`: `Themes.current()` + `Ui(a, th0)` داخل لامبدا زرار الدايلوج · الـ`ActivityLifecycleCallbacks` الجديدة.
3. `TvLink.kt`: متغيّر `handled` بقى `var` من غير إعادة تعيين (warning بس) · `BrainRpc.kt` الفرع الجديد.
4. الـmanifest merger: لو اشتكى من `tools:node="remove"` على أكتيفيتي مش موجودة في المودول، شيل السطر بتاعها.
5. R8: مفيش جديد (القواعد `-keep com.newtube.** / com.liskovsoft.**` زي ما هي).

## معيار النجاح على الجهاز
1. البرنامج بيفتح، ودوسة «يوتيوب» (شريط سفلي من أي شاشة) بتفتح تبويب يوتيوب القديم من غير Splash ولا شاشة ميتيوب.
2. فيديو يوتيوب من أي مكان (كارت · إشعار · لينك · سجل) بيفتح في مشغّل Joyboy (Player.kt).
3. لينك قناة/قايمة بيفتح في التبويب؛ لينك بحث بيعمل بحث.
4. مشاركة/دخول بالكود من الإعدادات ← يوتيوب شغّال؛ التلفزيون لو طلب دخول بيفتح شاشة الكود على الموبايل.
5. الجلاس والأنيميشن زي ما كانوا؛ الإعدادات والتحميل والترجمة (Player.kt) من غير تغيير.

## اللي اتشال معاه (مميزات كانت في مشغّل ميتيوب لفيديوهات يوتيوب بس)
إيماءات · PiP بتاعه · SABR · SponsorBlock · فصول — بترجع في `Player.kt` لو طلبتها بالاسم.

## لسه
**Y6** (نقل كلاسات الجلاس/الثيم/boot لـ`app` ثم مسح `:mobile`/`:common`/`:sabr-media3`…) · **T5** تبويب الشورتس · نواقص Y2 القديمة (PiP بين يوتيوب والمتصفح، SponsorBlock في Player…).
