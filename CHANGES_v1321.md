# v1320 — مسار بديل لجيميناي + جلب الموديلات + تثبيت الجودة + إصلاح بيكر التيست الصوتي

## ميزات جديدة
1. [ميزة جديدة] محرك الترجمة بقى باختيارين (من الإعدادات ← تبويب الصوت، ومن شيت «⚙ إعدادات الترجمة» جوه المشغّل): «☁ جيميناي — بيعمل كل حاجة» زي الأول، و«🎙 وشبر + 🌐 جوجل ترانسليت» — وشبر بيفرّغ الصوت لنص على موبايلك والنص بيتبعت لجوجل ترانسليت يترجمه، من غير جيميناي خالص (مسار بديل شغال وقت ضغط جيميناي 503).
2. [ميزة جديدة] «🩺 حالة المسار البديل» في شيت «⚙ إعدادات الترجمة» جوه المشغّل: فحص سريع بيبيّن وشبر جاهز ولا لأ وجوجل ترانسليت بيرد ولا لأ، والمحرك الحالي إيه.
3. [ميزة جديدة] زرار «🔄 جلب الموديلات واختيار واحد» بقى في ٣ أماكن: قسم «🔑 كل المفاتيح» في الإعدادات، تبويب «الموديل والأداء»، وشيت «⚙ إعدادات الترجمة» جوه المشغّل — بيجيب كل الموديلات المتاحة على مفتاحك من جوجل، وتختار موديل واحد بيتطبّق على كل المفاتيح وكل الميزات (تغيير يدوي بس، مفيش أي تبديل تلقائي).
4. [ميزة جديدة] تشيك بوكس «اجعل الجودة المختارة الافتراضية لكل الفيديوهات» تحت قايمة الجودة جوه المشغّل: لما تفعّله، أي جودة تختارها بتتثبّت كجودة افتراضية لكل فيديوهات الإنترنت (يوتيوب، المتصفح، أي مصدر) — فيديوهات موبايلك (اللوكال) بتفضل بجودتها الأصلية زي ما هي.

## إصلاحات
1. [إصلاح] بيكر «اختيار فيديو من المكتبة» في التيست الصوتي: كان بيفتح شيت فاضي ويستنى سكان كامل لكل فيديوهات الموبايل قبل ما أي صف يظهر، فعلى مكتبة كبيرة كان بيفضل واقف على «بنحمّل…» وكأنه مش شغال. دلوقتي بيفتح فورًا لو المكتبة متكاشة، وبيبني كل الصفوف قبل ما الشيت يظهر، وفيه رسالة خطأ واضحة لو حصلت مشكلة بدل الصمت.

# v1321 — ريفراكتورينج شامل: مسح الكود الميت (من غير أي تغيير في السلوك)

## تحسينات داخلية
1. [تحسين] مسح ١٠١ ملف كود ميت (٢٫٣ ميجا سورس): ملفات Kotlin قديمة مش داخلة في البيلد أصلًا — الـbuild بيمسحها قبل الكومبايل في كل مرة (pruneStaleKt)، يعني حذفها من الريبو أمانه مضمون وصفر تأثير على أي ميزة أو تصميم. بتشمل: بقايا تطبيق MeeTube الأصلي (com/decio/skipii كلها)، نسخ قديمة من شاشات الاستوديو والمحاضرات والشات، وملف YtStore.kt المكرر (الحقيقي جوه YtNp.kt).
2. [تحسين] مراجعة اتساق الـmanifest: كل ملفات الكود الباقية (٣٣٧ ملف) هي بس اللي بتتكومبايل فعلًا — مفيش مصدرين للحقيقة.

## مفيش تغيير للمستخدم
مفيش أي سلوك أو شكل اتغير: الملفات دي مش بتتكومبايل أصلًا، والتصميم القديم (كلاسيك) والجديد (فانيلا/ماتيريال) زي ما هما بالظبط.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1321.md
✏️ عدّل:   STATUS.txt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Account.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Adaptive.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/AvSync.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Bell.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Captions.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/CaptionsUi.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Channel.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/CommentFeed.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/CommentPosting.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Comments.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Crash.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Feeds.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Fullscreen.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/FullscreenSliders.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Header.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Language.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/LiveActivity.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/MainActivity.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Motion.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/NavBar.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Net.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/NewVideos.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlaybackModes.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlaybackService.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlayerBar.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlayerFallback.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlayerMorph.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlayerState.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/PlayerUi.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Playful.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Rail.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Resume.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Screens.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SearchHistory.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SecureStore.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Settings.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Sheets.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Shorts.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/ShortsBank.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SignIn.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SkipiiApplication.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SkipiiBridge.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SkipiiHost.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SpeedPanel.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/SponsorBlock.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Tabletop.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Theme.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/UiKit.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/Updater.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/VideoSurface.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/VideoView.kt
🗑️ حذف:   app/src/main/java/com/decio/skipii/YouTube.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/CallChat.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/CallChatUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/CallKit.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/DocActions.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/GifWriter.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/LaunchSplashActivity.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/Lecture.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/LectureCss.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/LectureDoc.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/LectureUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MeeTubeBar.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MeeTubeLink.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtBar.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtFrames.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtMedia.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtOrigin.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtPlay.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtTranslate.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtTvPlayer.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtTvRoute.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtUpscale.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/MtWeb.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/PhoneHotspot.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/QuickTiles.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/SrtSplit.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/Studio.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioAutoTr.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioComplete.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioIntel.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioIntelUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioLink.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioPrivacy.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioPrivacyUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioSettingsUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioTx.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/StudioUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/ToolsUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/TxDetect.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/WallStore.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/WaridFiles.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/WaridTx.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YoutubeSlot.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtBubble.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtHistoryUi.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtLink.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtLoad.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtMiniCard.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtMtSignIn.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/YtStore.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1320-run132` (للرجوع: Actions ← Rollback)
