# Joyboy v1072 — MeeTube اتشال واليوتيوب الداخلي رجع + 4 حاجات من MeeTube

القاعدة: شجرة v1071 الحالية (مش v863). ملفات يوتيوب القديمة الـ17 اتعادت من v863، وبرنامج MeeTube اتشال، والأربع حاجات المطلوبة اتطعّمت في اليوتيوب الداخلي.

## 1) اليوتيوب الداخلي رجع (من v863)
- الـ17 ملف المستعادة: YoutubeUi, YtAccount, YtBackend, YtChannel, YtFast, YtHistoryUi, YtLink, YtLoad, YtLocale, YtNp, YtProUi (1,719 سطر — الواجهة الرئيسية), YtRss, YtSkin, YtSnap, YtThumbBtns, YtTopBar, YtWatch.
- التضارب اتحل: `NpItem` و`YtPick` المتكررين اتشالوا من YtNp.kt (النسخة في YtModels.kt فضلت)؛ `YtHistory` الـstub في YtModels.kt اتشال والنسخة الحقيقية في YoutubeUi.kt فضلت (سجل المشاهدة بيتسجّل تاني).
- `NewPipeExtractor v0.26.5` اتضاف في app/build.gradle.kts (jitpack موجود أصلًا في settings).
- تبويب يوتيوب رجع في Main: `ytUi` + `ensureYt()` + showTabFn(4) + nav (YT_HOME..YT_ME) + رجوع (Back) + PiP + إعدادات «▶ افتح يوتيوب» + `t0 == "youtube"` + onNewIntent. أيقونة «Joyboy YouTube» في اللانشر بتفتح نفس التبويب (LaunchYoutubeActivity — من غير تغيير).
- `PlayerActivity.liveOrNull()` جديد (public) — `live()` كان private والـPiP-restore في Main كان بيناديه.

## 2) برنامج MeeTube اتشال
- مجلد meetube/ اتقص لـ3 مكتبات بيانات بس: `MediaServiceCore/youtubeapi` + `MediaServiceCore/mediaserviceinterfaces` + `SharedModules/sharedutils` (+ constants.gradle والتراخيص). اتشالت: mobile/ وcommon/ وfilepicker-lib/ وdoubletapplayerview-media3/ وslidableactivity/ وsabr-media3/ وSharedModules/appupdatechecker2/ وsharedtests/ + 3 سطور `:sharedtests` من الـgradle.
- settings.gradle.kts: 3 مودولات بس. app/build.gradle.kts: اتشال `:mobile` و`:common` و`:sharedutils` المباشر و`missingDimensionStrategy("default", "stmobile")`.
- اتمسحت 14 ملف: MeeTubeLink, MeeTubeBar, MtBar, MtTranslate, MtMedia, MtTvPlayer, MtTvRoute, MtPlay, MtWeb, MtEnhance, MtFrames, MtGlassView, MtOrigin, YtMiniCard.
- JoyApp.onCreate بقى: `GlobalPreferences.instance(this)` (بيبنّي طبقة بيانات يوتيوب وبيشغّل onInit — منها تسجيل AccountManager) + MtYt.app + NavOrigin/NavSnap + **MtFeed.install** (جديد — تتبّع الشاشة الظاهرة بدل MtTvRoute.install) + MtSkin/MtSmooth/MobileDataGuard/PermAsk. اتشال: MobileMainApplication.boot وPlayerTrace وbindBar وfollowMeeTubeTheme وonTrim/onLow.
- الثيم: MeeRes بقى ثوابت محلية (نفس ألوان v1071 بالظبط — الفاتح/الداكن من `Cfg theme_mode` «system/light/dark»)؛ أسماء العرض «MeeTube داكن/فاتح» بقت «داكن/فاتح» (الـids `mt_dark`/`mt_light` فضلت عشان الإعدادات المحفوظة). زرار سويتش المظهر في الإعدادات بقى Cfg + recreate.
- الشريط العايم الزجاجي اتشال من Main والـBrowser والـFileManager وFtUi وCareKit (شريط سفلي عادي)؛ GlassSlider بقى SeekBar عادي (Main + Ui)؛ GlassSwitch اتشال من Ui.switchRow (الشكل العادي دايمًا)؛ SheetGlass.frost اتشال من Glass/StatusStrip؛ GlassDrawable/MtFloat اتشالوا من MiniDock وFileManager وLibraryUi وPageSettings؛ منحنيات MtGlass بقت PathInterpolator محلية؛ MtSmooth.attach شال بارامتر JoyboyPlayerHost؛ Player اتشال منه MtPlay.reroute/holdForPlayer وmtEnhanceCycle وMtEnhance.detach.
- الروابط اتحوّلت لتبويب يوتيوب: Browser وFileManager وCareKit وFtUi (goMain/TabNav "youtube")؛ FileManager فتح الفيديو مباشر (من غير MtPlay.fastOpen)؛ Main.startPlayerNow مباشر؛ MobileDataGuard شال سطري MtBar؛ ResumeAsk بقى بمستخرج معرّف محلي (ytIdFrom)؛ TvLink/BrainRpc شالوا كل فروع MtTvPlayer/MtTranslate (مشغّل Joyboy بس)؛ BottomNav شال floatMode/floatGlass.
- MtFeed.install جديد (lifecycle callbacks) + resumed()؛ ديالوج طلب الدخول من التلفزيون بقى بنص الكود الجديد وبيفتح YtMtSignIn.
- MtYt.boot() بقى `GlobalPreferences.instance` بس؛ لابل «MeeTube» بقى «يوتيوب» (Got + Sniffer tag).
- **تسجيل دخولك الحالي بيفضل شغّال**: التوكن في نفس ملف الإعدادات (global_prefs) ونفس الباكدج — مفيش حاجة بتتمسح.

## 3) الأربع حاجات من MeeTube (التركيب)
1. **الصفحة الرئيسية** (YtProUi.openTab(0)): `MtYt.homeFeed()` أولًا (فوق 6 فيديوهات بعد فلتر المشاهَد) — نفس محتوى صفحة MeeTube الرئيسية — وبعدين YtNp.home القديم كاحتياطي (مع العلاج الذاتي reset + إعادة محاولة). مزامنة الكوكيز اتشالت من الرئيسية.
2. **صفحة الاشتراكات** (YtProUi.openSubs): الفيد الرسمي بقى `MtYt.subscriptions()` (صفحة واحدة — مفيش صفحات بعدها) + فلتر الشورتس بالمدة بس؛ دمج RSS وترتيب بالأحدث زي ما هو؛ الاحتياطي RSS من المخزن المحلي لما تكون مش مسجّل. مزامنة قنوات الكوكيز اتشالت — القنوات من المخزن المحلي (استيراد ملف).
3. **تسجيل الدخول بالكود** (ملف جديد YtMtSignIn.kt + RxJava 3.1.12/RxAndroid 3.0.2 في app/build.gradle.kts — نفس نسخة المكتبات): ديالوج بيعرض الكود كبير + «🌐 متابعة مع جوجل» بيفتح `youtube.com/qr/activate/<الكود>` (تطبيق يوتيوب بيلقطه وتدوس «تم») + «📋 نسخ الكود» + إلغاء؛ فشل الدخول = «🔄 كود جديد» (مفيش استئناف لكود قديم). الدخول من YtProUi.startLogin (موبايل) والتلفزيون (QR من الموبايل أو الكود الجديد) ومن ديالوج MtFeed. الخروج بقى `MtYt.signOut()`. الـ`after` بتاع startLogin بيشتغل زي الأول (Notice + تحديث الفيد).
4. **زرار الصوت بس** (Player.toggleYtMode): بقى بإيقاف تراك الفيديو على نفس الـExoPlayer (`setTrackTypeDisabled(TRACK_TYPE_VIDEO)`) — نفس المكان، من غير إعادة تحميل ولا مصدر جديد ولا انتظار. زرار 🎧 العايم وكبسولة «🎧 وضع الصوت» بينادوه؛ الكبسولة بتظهر لأي فيديو يوتيوب (مش محتاجة مسار صوت منفصل)؛ تغيير الجودة بيرجّع الفيديو وبيفعّل التراك تاني.

## اللي اتعملش عمدًا
- ملفات الكوكيز القديمة (YtAccount.kt) فضلت في الشجرة بس من غير ما تتنده — login/logout/syncSubs/listFirst/listMore ميتة (باستثناء init + list في صفحة قوايم ميتة). قوايم الحساب (قنواتي/سجل يوتيوب/إعجاب/شاهد لاحقًا/قوايم التشغيل) اتشالت من صفحة «أنت» لأنها كانت بتتفتح بالكوكيز؛ اللي فضل: تحديث الفيد + الخروج + استيراد ملف اشتراكات (!import جديد).
- `playYtOnTv` مالوش أي منادي بعد مسح MeeTubeLink — تشغيل يوتيوب على التلفزيون بيمشي من مسارات Joyboy الموجودة (TvLink/PlayTarget) من غير تغيير.
- MeeTube player code نفسه اتمسح خالص (مش dormant زي v1069)؛ MtGlass/MtSkin/MtSmooth/MtHeader/MtFeed/MtYt فضلوا لأنهم كود Joyboy خالص.
- `switchToAudio()` (فتح مشغّل الموسيقى) فضل موجود بس زرار 🎧 مبقاش بيناديه.

## تحقق (ستاتيك بس — مش compile)
- rule-7 فاضي؛ hex: ضربة واحدة قديمة في NetGate.kt:40 (`0xcafed00dL` — Long قانوني مش Int)؛ Size: TransferUi/VideoScan بس (قديم)؛ ديالوج YtMtSignIn بيتعرض بـd.show().
- توازن 0/0/0 (أقواس/أقواس مربعة/أقواس معقوفة بعد شيل السترنجات والتعليقات) على كل الملفات المعدلة الـ30؛ فحص تعليقات بلوك على الشجرة كلها: مفيش تعليق مقفول غلط.
- `grep com.newtube|smartyoutubetv2` فاضي؛ liskovsoft المتبقي: youtubeapi + mediaserviceinterfaces + sharedutils بس.
- kt_manifest.txt: 314 = 314 ملف، 0-diff.
- ما اتعملش compile هنا — Actions هو الحكم.
