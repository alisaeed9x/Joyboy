# v620

## إصلاح: الأيقونة الرئيسية بتخرج (كراش) — السبب الحقيقي
- أيقونة Joyboy الرئيسية بقت تشاور على `LaunchSplashActivity` من v619، لكن ملف `LaunchSplashActivity.kt` **ماكانش مكتوب في `app/kt_manifest.txt`**، فـ `pruneStaleKt` (قبل الكومبايل) كان بيمسحه ← الكلاس مش موجود في الـAPK خالص ← أندرويد بيرمي ClassNotFoundException أول ما تدوس الأيقونة. (اتأكدت بفحص dex جوه app-debug.apk: LaunchBrowser/LaunchYoutube/LaunchRemote/SplashArt موجودين، LaunchSplashActivity لأ.)
- أيقونة التلفزيون/اليوتيوب/المتصفح شغّالة لأنها كلاسات تانية في LaunchShortcuts.kt (وهو في الـmanifest).
- العلاج: اتضاف `com/tttt/subtitler/LaunchSplashActivity.kt` لـ kt_manifest.txt.
- جديد: `dev-tests/check_manifest.sh` — بيقارن كل ملفات .kt بالـmanifest ويقولك لو في ناقص (شغّله قبل أي push).

## تحسين صغير في نفس الملف (احتياطي)
- لو التطبيق شغّال أصلًا وإنت دست الأيقونة، البوابة بتقفل نفسها (isTaskRoot) وبتفتح Main بـ CLEAR_TOP|SINGLE_TOP — عشان ما تتعملش نسخة Main تانية فوق الأولى.

- رفع النسخة 620 / 6.20.

## مش مضمون
- ماجرّبتش Gradle ولا جهاز. التشخيص من فحص الـAPK والـmanifest.
