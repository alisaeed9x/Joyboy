# Joyboy v1110 — Y6c (صغيرة): تنضيف مراجع `MobileMainApplication` + إصلاح `PlayerTrace.install`

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: `Joyboy_v1109.zip`.

## اللي اتعمل
- **إصلاح ناتج عن v1108:** نسخة `PlayerTrace` في `app` (`mtui`) محدّش كان بينده `install(app)` بتاعها — كان بيتنده بس من جوه `MobileMainApplication.onCreate` للنسخة القديمة. اتضاف في `JoyApp.onCreate` (تتبّع اللمس/أنيميشن الشيتات اللي `Glass.kt` بيعتمد عليه).
- **`Main.kt`:** اتشال سطرين (`onCreate` و`onNewIntent`) بيسألوا `MobileMainApplication.booted()?.restorePictureInPictureFromLauncher` — استرجاع PiP بتاع مشغّل MeeTube اللي اتمسح في Y5، فكان بيرجّع `false` دايمًا.
- **`JoyApp`:** اتشال `onTrimMemory`/`onLowMemory` اللي كانوا بيبعتوا لـ`MobileMainApplication` (تنضيف كاش شاشات MeeTube المقفولة).
- النتيجة: `MobileMainApplication` فاضل مستخدم في **مكانين بس**: `JoyApp.onCreate` (`boot`) و`MtYt.boot()` — وده كل اللي فاضل قبل مسح `:mobile`.

## اللي فاضل (Y6d)
- استبدال `MobileMainApplication.boot` بتهيئة صغيرة في `app` لمحرك MediaServiceCore (`MtYt`/`YtCodeLogin`): `MobileMainApplication extends MainApplication` (:common) وبيجهّز `GlobalPreferences`/`AppInfo`/`OkHttpManager.setPreferHttp2`/مفاتيح الـbench… — لازم نحدد اللي محتاجه المحرك فعلًا (من `:youtubeapi`/`:sharedutils`) وننقله بس.
- بعدها: شيل المودولات من `settings.gradle.kts` و`app/build.gradle.kts` + 16 سطر manifest + `-keep class com.newtube.**`.
