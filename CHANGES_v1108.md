# Joyboy v1108 — Y6a: نقل كلاسات الجلاس/الحركة من `:mobile` لـ`app` (من غير مسح لسه)

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: `Joyboy_v1107.zip`. ده **جزء** من Y6 (الشرط اللي اتكتب في خطة Y5: ننقل اللي `app` معتمد عليه قبل مسح المودولات).

## اللي اتعمل
- **12 كلاس Java اتنسخوا** من `meetube/mobile/.../ui/{common,playback}` لـ`app/src/main/java/com/tttt/subtitler/mtui/` بـpackage جديد `com.tttt.subtitler.mtui`:
  `Glass · GlassDrawable · GlassSource · GlassView · GlassSlider · GlassSwitch · NavLens · SheetGlass · Motion · PlayerTrace · SeekThumb · JoyboyPlayerHost`
  - فحصت الاعتماديات برمجيًا: المجموعة دي **مقفولة على نفسها** (مفيش أي كلاس منهم بيستخدم حاجة تانية من ميتيوب غير `R`)، فالنسخ مباشر.
  - الكود **نفسه حرفيًا**؛ اتغيّر بس سطر الـ`package` وسطور `R` (`com.liskovsoft.smartyoutubetv2.tv.R` ← `com.tttt.subtitler.R`).
- **11 ملف في `app` + `joy_nav_glass.xml`** اتحوّلت مراجعهم من `com.newtube.mobile.ui.*` للكلاسات الجديدة (`Glass.kt · MiniDock · JoyApp · StatusStrip · MtEnhance · Main · MtGlass · MtSmooth · Ui · MtPlayerLook · MtGlassView`).
- `proguard-rules.pro`: `-keep class com.tttt.subtitler.mtui.**` (الجلاس بيتعمل inflate من الـlayout).
- **النسخ الأصلية في `:mobile` لسه موجودة** (عشان مفيش duplicate class ولا `:mobile` يقع). الـ`app` بقى مش بيستخدمها.

## لسه ناقص قبل مسح `:mobile`/`:common` (الجزء الجاي من Y6)
1. **`ThemeMode`** (بيعتمد على `MotherActivity.setNightModeSource` و`NetPath` من `:common`) — مستخدم في `Theme.kt` و`Main` و`JoyApp`.
2. **`MobileMainApplication.boot/booted()`** (محرك MediaServiceCore + `onTrimMemory/onLowMemory` + `restorePictureInPictureFromLauncher`) — مستخدم في `JoyApp`, `MtYt`, `Main`.
3. `Browser.kt:1209` بينده `MobilePlaybackActivity.panicClose()`.
4. **الموارد**: الجلاس لسه بياخد من `:mobile` ألوانه `meetube_*` و`ids_motion` و`styleable GlassView` و`mobile_settings_seek_thumb_failed` (شغّالة دلوقتي لأن `android.nonTransitiveRClass=false`)؛ لازم تتنسخ لـ`app/res` (+ `values-night`) قبل المسح.
5. بعدها: شيل `implementation(project(":mobile"/":common"...))` من `app/build.gradle.kts` و`mod(...)` من `settings.gradle.kts`، ومسح 16 `tools:node="remove"` في الـmanifest.

## ⚠ على الجهاز/البيلد
- لو البيلد وقع: غالبًا `R.id.design_bottom_sheet`/`R.styleable.*` (بيتحلّوا من الـR المدموج) أو توقيع Java↔Kotlin في `MtGlassView`/`Ui` — ابعت اللوج.
- شكل الجلاس (الشريط السفلي، الشيتات، السويتشات، السلايدر) لازم يطلع زي ما كان بالظبط.
