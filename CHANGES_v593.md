# v593 — 3 أيقونات في اللانشر

- **Joyboy** (الرئيسية): أيقونة جديدة (صورة tv/mob media player) — `ic_launcher.xml` بقى adaptive من `ic_joy_main_bg/fg`.
- **Joyboy Browser**: `LaunchBrowserActivity` بتفتح `BrowserActivity` في كل مرة (CLEAR_TOP|SINGLE_TOP) حتى لو التطبيق شغّال في الخلفية على حاجة تانية.
- **Joyboy YouTube**: `LaunchYoutubeActivity` بتفتح `MainActivity` على بوابة يوتيوب (`tab=youtube`) بنفس الأسلوب.
- الاتنين شاشات شفافة بتقفل نفسها فورًا (`noHistory` + `excludeFromRecents`)، وكل واحدة بأيقونتها واسمها.
- ملفات: `LaunchShortcuts.kt` (جديد)، `AndroidManifest.xml`، `drawable-nodpi/ic_joy_*`، `mipmap-anydpi-v26/ic_joy_browser|yt|ic_launcher.xml`، `kt_manifest.txt`. versionCode 593 / 5.93. ماجرّبتش Gradle ولا جهاز.
