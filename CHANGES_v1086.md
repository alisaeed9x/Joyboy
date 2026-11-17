# Joyboy v1086 — شيل كود اليوتيوب/MeeTube/Skipii بالكامل (للعزل + تجهيز مكان التركيب)

## اللي اتشال
- مودول `:skipii` كله + مجلد `meetube/` (youtubeapi · mediaserviceinterfaces · sharedutils) + اعتماديات NewPipeExtractor / rxjava / rxandroid / jitpack.
- ملفات اليوتيوب من `app`: YtProUi · YtWatch · YtChannel · YtNp · YtBackend · YtAccount · YtRss · YoutubeUi · MtYt · YtMtSignIn · YtFeedStore · YtNext · YtAudio · YtFallback · YtFast · YtHistoryUi · YtLink · YtLoad · YtLocale · YtSkin · YtSnap · YtThumbBtns · YtTopBar.
- كل كود Skipii في Main/JoyApp/BottomNav (الـ init والـ ComposeView والشورتس والشريط فوق).
- رجّعنا `MainActivity : Activity` وتوقيعات `onNewIntent`/`onRequestPermissionsResult` والـ manifest زي v1079 (كانت اتغيّرت عشان Skipii).

## بدل اللي اتشال: `YoutubeSlot.kt`
- ملف واحد فيه بدائل فاضية بنفس التوقيعات (بترجّع null / فاضي / false) عشان باقي الكود يفضل يتبني.
- كل نقطة اتعلّمت بـ `YOUTUBE_SLOT` — ابحث عنها لما تركّب Skipii.
- نقطة التركيب الأساسية: `Main.ensureYt()` + كلاس `YtProUi` (دلوقتي شاشة فاضية مكتوب فيها «يوتيوب — مش متركّب دلوقتي»). الباقي: `MtYt` (جلب الروابط للمشغّل والموسيقى) · `YtFeedStore` (فيد التلفزيون) · `YtHistory`/`YtNext`/`YtFallback`/`YtAudio`.
- تبويب «الشورتس» مخفي لحد ما Skipii يتركّب (`BottomNav.normalOrder`).

## تغييرات بنائية
- إصدارات المكتبات اتنقلت من `meetube/SharedModules/constants.gradle` لـ `versions.gradle` في الجذر.
- `kt_manifest.txt` اتحدّث (الملفات المحذوفة اتشالت و`YoutubeSlot.kt` اتضاف).

## ملاحظة
- أي حاجة بتحتاج روابط يوتيوب (تشغيل لينك يوتيوب في المشغّل/الموسيقى/التحميل/بحث يوتيوب) مش هتشتغل لحد ما نركّب البديل.
