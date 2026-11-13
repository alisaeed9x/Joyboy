# v947 — مشغّل منفصل للملفات/المتصفح + شيل الأزرار المكرّرة من مشغّل MeeTube

## 1) نسخة مشغّل منفصلة للملفات واللينكات (مفيش لود لصفحة يوتيوب)
- `MobileLocalPlaybackActivity.java` (جديد، subclass من `MobilePlaybackActivity`) + إدخال في `meetube/mobile/src/stmobile/AndroidManifest.xml` بـ`taskAffinity="joyboy.localplayer"` — نفس المشغّل بالظبط بس في تاسك لوحده، فمفيش شاشة تصفّح MeeTube (الرئيسية) بتتحمّل ورا الفيديو.
- التوجيه: `ViewManager` بيقرا مكان `PlaybackView` من التسجيل، فـ`MtPlay.openMt` (ملف/لينك/متصفح) بينده `MobileLocalPlaybackActivity.route()` قبل `openVideo`، و`MeeTubeLink.playYt/playYtOnTv` بيندهوا `routeYoutube()` (يرجّع المشغّل العادي + أبوه Browse). ولما نسخة الملفات تتقفل (`onDestroy`) بترجّع تسجيل يوتيوب لوحدها، فدوسة فيديو جوه MeeTube نفسه تفضل على المشغّل العادي.
- `SystemPipBridge.restore` وإشعار الخدمة بقوا يستخدموا `getClass()` بدل `MobilePlaybackActivity.class` عشان استرجاع الـPiP والإشعار يرجّعوا نفس النسخة.
- **اللي لازم يتجرّب:** (أ) فتح فيديو من المكتبة/المتصفح ← بيفتح فوراً من غير ما صفحة يوتيوب تتحمّل (ب) الرجوع منه بيرجّع للمكان اللي فتحته منه (ج) PiP والتصغير من نسخة الملفات (د) تشغيل فيديو يوتيوب بعد ما تقفل ملف.

## 2) الأزرار المكرّرة (الدايرة الحمرا في الصورة)
- `MtBar.hideNativeDup()`: أزرار MeeTube الأصلية ⏮ ⏯ ⏭ (وسط الشاشة `mobile_player_transport`) + ⛶ `mobile_player_fullscreen` + سهم الأكشنز `mobile_player_actions_toggle` بتتخبّى (GONE) طول ما شريط Joyboy موجود — الصف الرئيسي بتاع Joyboy (🔓 | ⏮ ▶ ⏭ | ⧉ 🔄 ⛶) هو الوحيد. بتتفحص كل فريم (`OnPreDrawListener` الموجود) عشان لو MeeTube رجّعها تتخبّى تاني.
- شريط التقدم + صف الوقت: الـslot بقى 58dp، وشريط التقدم بيتحط على آخره (16dp فوق الصف الرئيسي) و`mobile_player_bottom_row` (الوقت 0:00 / 45:26 + الفصل) بيتحرّك معاه بنفس `translationY` فالوقت يفضل فوق الشريط زي المشغّل القديم (863).
- شريط التقدم نفسه وسحبه والفيلمستريب والفصول من MeeTube زي ما هي (ما اتلمسوش).

## الملفات
`MobileLocalPlaybackActivity.java` (جديد) · `AndroidManifest.xml` (meetube/mobile) · `SystemPipBridge.java` · `MobilePlaybackActivity.java` (سطر واحد: intent الإشعار) · `MtBar.kt` · `MtPlay.kt` · `MeeTubeLink.kt`. مفيش `.kt` جديد ← `kt_manifest.txt` ما اتغيّرش.

## لسه (مش اتعمل — محتاج صور/قرار)
- تقسيمة باقي الأزرار (الصف العلوي، الدواير، أحجام النوافذ المنبثقة) متقارنة بالقديم 863: الأحجام والترتيب في `MtBar.kt` (v944–946) أصلاً على نفس مقاسات `Player.kt` القديم (40dp دواير · ⏮▶⏭ 60/56/60 · شريحة ترجمة)، بس ما اتقارنتش بصرياً على جهاز. لو لسه في زرار مش في مكانه بعد البناء ابعتلي صورة جديدة وأحدد الزرار.
- لم يتم compile ولا اتجرّب (مفيش Gradle هنا) — البناء على GitHub Actions.
