# Joyboy v1078 — إصلاح فشل بناء v1077 (خطأين)

سجل Actions (رن 2026-11-16 12:15 UTC): `BUILD FAILED` — مهمتين فشلوا.

## 1) `:app:processDebugResources` — `style/Theme.NewTube not found`
- `Theme.NewTube` كان في `meetube/mobile` (styles_mobile.xml) واتمسح في v1072، بس `AndroidManifest.xml` (android:theme) و`AppSplash` في `values/themes_splash.xml` لسه بيشاوروا عليه.
- الإصلاح: ستايل `Theme.NewTube` جديد في `values/mt_res_v1074.xml` بأب `Theme.DeviceDefault.NoActionBar` (نفس أب نسخة values-v31).

## 2) `:app:compileDebugKotlin` — `MtYt.kt:195 Unresolved reference 'signOut'`
- `ServiceManager.getSignInService()` بيرجّع `mediaserviceinterfaces.SignInService` (الجديدة) ومفيهاش `signOut()` — دي كانت في `oauth.SignInService` القديمة.
- الإصلاح: `MtYt.signOut()` بقت تعدّي على `accounts` وتنده `removeAccount` لكل حساب.

## الملفات
- معدّل: `MtYt.kt` · `res/values/mt_res_v1074.xml`

## تحقق
- ما اتعملش compile — Actions هو الحكم.
