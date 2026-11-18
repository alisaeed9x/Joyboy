# Joyboy v1109 — Y6b: ThemeMode + موارد الجلاس لـ`app` وشيل `panicClose`

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: `Joyboy_v1108.zip`.

## اللي اتعمل
- **`mtui/ThemeMode.java`** (نسخة app من ThemeMode بتاع MeeTube): نفس ملف الإعدادات `newtube_theme` (الاختيار القديم محفوظ) ونفس الـAPI اللي `Theme.kt`/`Main`/`JoyApp` بيستخدموه (`peek · set · desiredNight · syncResources · LIGHT/DARK/SYSTEM`).
  اتشال منه اللي بيعتمد على اللي هيتمسح: سجل شاشات `MobileActivity` · `MotherActivity.setNightModeSource` (:common) · `NetPath.log` · `Loader`.
  - `JoyApp.onCreate` بينده `ThemeMode.init(this)` (كان بيتعمل جوه `MobileMainApplication.boot`).
  - المراجع في `Theme.kt · Main.kt · JoyApp.kt` اتحوّلت للنسخة الجديدة.
- **الموارد** (كانت جاية من `:mobile` بسبب `nonTransitiveRClass=false`) اتنسخت لـ`app/res`:
  `values/mtui_res.xml` (ألوان `meetube_{sheet_glass,glass_rim,glass_sheen,switch_off,switch_on,accent,toast_rim}` · ids `mobile_{frost,jiggle}_animator` و`mobile_sheet_rim` · `declare-styleable GlassView` · نص `mobile_settings_seek_thumb_failed`) + `values-night/mtui_res.xml` (النسخة الليلي للألوان). نسخة `app` بتغلب نسخة `:mobile` فمفيش تعارض.
- `Browser.closeLivePlayers`: اتشال سطر `MobilePlaybackActivity.panicClose()` (النشاط مقفول من الـmanifest من Y5).

## اللي فاضل لمسح `:mobile`/`:common` (Y6c)
1. **`MobileMainApplication.boot/booted()`** (1204 سطر، `Application` بيشغّل محرك MediaServiceCore + `onTrimMemory/onLowMemory` + `restorePictureInPictureFromLauncher`) — مستخدم في `JoyApp` و`MtYt` و`Main` (سطرين). ده أصعب جزء: لازم نعرف المحرك محتاج إيه بالظبط من التهيئة (GlobalPreferences/AppInfo/…) وننقل اللي لازم بس.
2. بعدها شيل `implementation(project(":mobile"/":common"/...))` من `app/build.gradle.kts` و`mod(...)` من `settings.gradle.kts` + 16 سطر `tools:node="remove"` من الـmanifest + `-keep class com.newtube.**`.
3. ضبط `app/build.gradle.kts` على ما كانت `:mobile` بتمرره (material · media3 · glide…) لو البيلد اشتكى من مكتبة ناقصة.

## ⚠ على الجهاز/البيلد
- تغيير المظهر (النظام/فاتح/داكن) من الإعدادات لازم يشتغل ويفضل بعد إعادة التشغيل (نفس ملف الإعدادات).
- ألوان السويتش/السلايدر/الشيتات في الفاتح والداكن زي ما كانت.
- لو البيلد وقع على `R.styleable.GlassView` أو attr مكرر: شيل `declare-styleable` من `mtui_res.xml` (الأصل لسه في `:mobile`) وابعتلي اللوج.
