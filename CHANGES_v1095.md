# Joyboy v1095 — كراش «يوتيوب» (MobileBrowseActivity · MaterialButton)

> ما اتعملش compile ولا اتجرّب على جهاز — Actions هو الحكم.

## الكراش (من last_crash.txt · Android 16)
`InflateException: activity_mobile_browse.xml سطر 110 (MaterialButton)` ← `IllegalArgumentException: The style on this component requires your app theme to be Theme.MaterialComponents`.

## اللي اتغيّر (دفاعي — السبب القاطع ما اتثبتش من الكود لوحده)
1. `values-v31/themes_splash.xml`: AppSplash (وAppSplashMain/AppMainPlain بالوراثة) كان parent = `Theme.DeviceDefault.NoActionBar` (مش Material) على أندرويد 12+، بينما `values/` parent = `Theme.NewTube` (v908). اتوحّد على `Theme.NewTube`.
2. `Theme.NewTube` (styles_mobile.xml): `colorPrimaryVariant` معرّفة صراحة (`?attr/colorPrimary`) — دي السمة اللي ThemeEnforcement بيفحصها.
3. `res/raw/keep.xml` جديد: `tools:keep="@style/*,@attr/*"` — MeeTube بيجيب ثيم الألوان بالاسم وقت التشغيل (`Helpers.getResourceId(name,"style")`) وتصغير الموارد (isShrinkResources) ممكن يشيله.

## لو لسه بيكرّش
ابعت last_crash.txt الجديد — لو نفس الرسالة يبقى السبب في ترتيب تطبيق الثيم (MotherActivity.initTheme / ThemeMode) وهنتتبّعه من هناك.
