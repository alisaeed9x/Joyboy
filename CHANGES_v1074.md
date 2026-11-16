# Joyboy v1074 — إصلاح فشل بناء v1072 (25 خطأ)

سجل Actions (رن 2026-11-16 13:12 EET — الرفعة بتاعة v1072): `BUILD FAILED` — خطأين في المهام: `:app:checkDebugDuplicateClasses` + `:app:compileDebugKotlin` (25 خطأ `e:`). كلهم من شغل v1072 (مسح MeeTube + رجوع يوتيوب الداخلي) — 5 أسباب جذرية، اتصلّحوا كلهم هنا:

## 1) `:sharedutils` اتشالت من :app بالغلط (6 أخطاء)
- في v1072 شلت `implementation(project(":sharedutils"))` من app/build.gradle.kts — بس youtubeapi وmediaserviceinterfaces معرّفينها `implementation` (مش `api`) فمش بتتسرّب لـ:app، وكود :app بيستوردها مباشرة: `JoyApp.kt:4/20` و`MtYt.kt:31` و`YtMtSignIn.kt:16/35` — `Unresolved reference 'sharedutils' / 'GlobalPreferences'` (+ `MtYt.kt:195 signOut` كانت كاسكيد من نفس السبب).
- الإصلاح: رجّعت `implementation(project(":sharedutils"))` في app/build.gradle.kts.

## 2) موارد MeeTube اتمسحت والكود لسه بيندهها (12 خطأ)
- `MtPlayerLook.kt:35-42` — 8 لودرات (AVD): `avd_neon_ring` و`avd_frosted_ring` و`meetube_loader` و`avd_frosted_cookie` و`avd_liquid_ring` و`avd_glass_orb` و`avd_morphing_blob` و`avd_glass_orbit` — كانت في `meetube/mobile` res.
- `Player.kt:1879` و`4269` — حركات فتح/قفل المشغّل: `player_open_enter/exit` و`player_close_enter/exit` — كانت في `meetube/mobile` res/anim.
- الإصلاح: الـ12 ملف اتنسخوا زي ما هم من زيب v1071 إلى `app/src/main/res/drawable` و`app/src/main/res/anim` + ملف قيم جديد `app/src/main/res/values/mt_res_v1074.xml` فيه الـ4 موارد اللي الـAVDs بتشاور لها (`mobile_color_primary #FF453A` + `neon_ring_path` + `path_player_play` + `path_player_pause` — منسوخة زي ما هي من mobile values). الـ2 AVD بتوع التشغيل/الإيقاف (`avd_player_play_to_pause`/`pause_to_play`) مفيش كود بيندههم — ما اتنسخوش.
- ملحوظة: `loaderOn()` بيتطلب `MtGlass.on()` — اللودرات شغّالة زي ما كانت.

## 3) `Main.kt:1819` — `ui` و`th` من onCreate (نفس فئة غلطة v1071)
- `showYtHistory()` (دالة عضو) كانت بتنده `YtHistoryUi.show(this, ui, th, ...)` — و`ui`/`th` local في onCreate بس. دي تعديلي في v1072 (مش غلطة قديمة).
- الإصلاح: `val th2 = Themes.current()` + `Ui(this, th2)` جوه الدالة نفسها.

## 4) `YtMtSignIn.kt` — ناقص import (غلطتي في v1072)
- سطور 70/79 بيستخدموا `View.LAYOUT_DIRECTION_RTL` من غير `import android.view.View`. اتضاف.

## 5) `YtFast.kt:36-37` — `YtExtract.loadPin/reprobe` مش موجودين
- `YtFast.kt` المستعاد من v863 بيندههم، بس v889 مسح فاحص عملاء InnerTube من `YtExtract` (كل الجلب بقى من MtYt).
- الإصلاح: stub في `Sniffer.kt` — `loadPin()` فاضية + `reprobe(force) = false` — فالتسخين بيروح للبديل الموجود (`YtNp.init()` + `pickAudio`).

## 6) `Duplicate class com.grack.nanojson` (مهمة منفصلة)
- youtubeapi بتجيب `com.grack:nanojson:1.10` (من Maven) وNewPipeExtractor بيجيب fork بنفس الكلاسات (`com.github.TeamNewPipe:nanojson`) — الاتنين مع بعض بيوقّعوا `checkDebugDuplicateClasses`.
- الإصلاح: استثناء في app/build.gradle.kts على `:youtubeapi` — `exclude(group = "com.grack", module = "nanojson")` — الـfork بيفضل وبيوفّر نفس الكلاسات لـyoutubeapi والـextractor (نفس الـpackage والـAPI).

## تحقق (ستاتيك بس — مش compile)
- rule-7 فاضي؛ hex: ضربة واحدة قديمة في NetGate.kt:40 (`0xcafed00dL` — Long قانوني).
- توازن 0/0/0 على كل الملفات المعدلة الـ8؛ فحص تعليقات بلوك على الشجرة كلها: مفيش تعليق مقفول غلط.
- تدقيق موارد كامل (سكريبت): كل `R.*` في الكود وكل `@*` في الـXML بيشاوروا لموارد موجودة — صفر مفقود (بعد الاستعادة؛ الباقي كان `android.R.*` نظامي).
- الـ12 ملف XML المستعادة + mt_res_v1074.xml بيتحللوا (parse) صح.
- kt_manifest.txt: 314 = 314 ملف، 0-diff (مفيش ملفات kt اتضافت أو اتمسحت في v1074).
- ما اتعملش compile هنا — Actions هو الحكم.
