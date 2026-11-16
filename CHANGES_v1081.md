# Joyboy v1081 — إصلاح فشل بناء v1080 (12 خطأ compile بالظبط)

بناء v1080 فشل في Actions (run 2026-11-16 16:14–16:17 UTC، `:app:compileDebugKotlin`، 3m 5s) على 12 خطأ — لا أكتر ولا أقل (12 سطر `e: file://` في اللوج، وكلهم اتأكدوا على نفس السطر في شجرة v1080). النسخة دي بتصلّح الـ12 بس، ومفيش أي تغيير تاني.

## الإصلاحات (12 خطأ، 4 ملفات)

1–4. `SkipiiBridge.kt:83, :153, :166, :212` — `Unresolved reference 'R'` (رسايل `player_translate_no_video` / `player_translate_no_audio` / `player_translate_failed`): الملف في باكدج `com.decio.skipii` فـ`R` العارية مبتتحلّش لحاجة. الحل: سطر واحد `import com.tttt.subtitler.R` (نفس الاستيراد اللي في 28 ملف Skipii تانيين). السلاسل التلاتة موجودين فعلًا في `res/values/skipii_strings.xml:321–323` — مفيش موارد جديدة اتضافت.
5. `SkipiiBridge.kt:277` — `Modifier 'inner' is not applicable inside 'standalone object'`: `private inner class BridgeHost` جوه `object SkipiiTranslate`. الحل: اتشال `inner`، والكلاس فضل مكانه (متنقلش لملف تاني)؛ الحاجات اللي كان بيقراها من الأوبجكت اتمرّرت باراميترات صريحة في الكونستركتور: `currentEngine: () -> Engine?` (مكان `this@SkipiiTranslate.engine`)، `post: (() -> Unit) -> Unit` (مكان `postMain`)، `cuesOf: (List<Sub>) -> List<CaptionCue>` (مكان `toCues`)، وتاج اللوج بقى النص الحرفي `"SkipiiTranslate"` (نفس قيمة `TAG`). موقع الاستدعا (`start()` :174) بيمرّر التلاتة كلوسجر.
6–7. `Settings.kt:1752` — `PillButton(...) { ... }`: `No value passed for parameter 'onClick'` + اللامدا اتربطت لـ`icon: ImageVector?` بدل `onClick`. تعريف `PillButton` (`UiKit.kt:446`): `fun PillButton(text: String, onClick: () -> Unit, icon: ImageVector? = null)`. الحل: اللامدا اتمرّرت مسمّاة `onClick = { ... }`؛ جسمها زي ما هو بالظبط (`YtMtSignIn.signIn(act) { ... }`).
8. `PlaybackService.kt:142` — `it.taskInfo.taskId` على `RecentTaskInfo?` nullable. الحل: `it.taskInfo?.taskId == open` (لو `taskInfo` بـnull المقارنة بتطلع false — نفس معنى الفحص الأصلي).
9–10. `Main.kt:1463` — `'onRequestPermissionsResult' overrides nothing` + نوع الباراميتر: الأوفررايد كان `Array<out String>` لكن `ComponentActivity` (androidx.activity 1.10.1) بيعلن `Array<String>`. الحل: الباراميتر بقى `Array<String>`؛ جسم الدالة مبيستخدمش `perms` أصلًا (بيستخدم `rc`/`res` بس) فمفيش أي تعديل تاني. سلوك rc==13/rc==12 زي ما هو.
11–12. `Main.kt:1937` — `'onNewIntent' overrides nothing` + `Intent?` مقابل `Intent`: الأوفررايد كان `Intent?` لكن `ComponentActivity` بيعلن `onNewIntent(intent: Intent)` غير-null. الحل: الباراميتر بقى `Intent`؛ الـ`?.` الزيادة في الجسم سليمة على non-null واتسابت زي ما هي. سلوك استعادة PiP من أيقونة اللانشر زي ما هو.

## ملحوظة عن اللوج: الـ58 ملف اللي pruneStaleKt مسحها — مش جزء من الفشل

اللوج بيورّي pruneStaleKt بيمسح 58 ملف (Studio.kt وLecture.kt وMtBar.kt وWaridTx.kt وYtWatch.kt وغيرهم). الملفات دي موجودة في ريبو GitHub بس مش موجودة لا في شجرة v1079 ولا في شجرة v1080 (اتأكدنا: صفر ملف Studio في pristine-v1079 وفي زب v1079 المرفوع). دي بواقي قديمة في الريبو من رفع copy-over، وpruneStaleKt مسحها صح وقت البناء. **مفيش حاجة اترجّعت منها.** ده معناه كمان إن ميزة «الاستوديو» كانت غايبة أصلًا من سلسلة v1079 بتاعة المستخدم قبل v1080 — دي ملحوظة منفصلة للمستخدم، مش في نطاق النسخة دي.

## ملفات اتعدّلت

- `app/src/main/java/com/decio/skipii/SkipiiBridge.kt` (استيراد R :24، استدعا BridgeHost :174–180، تعريف الكلاس :277–284، log/status/changed/notice)
- `app/src/main/java/com/decio/skipii/Settings.kt` (:1752)
- `app/src/main/java/com/decio/skipii/PlaybackService.kt` (:142)
- `app/src/main/java/com/tttt/subtitler/Main.kt` (:1463، :1937)
- `CHANGES_v1081.md` + `STATUS.txt` (جديد)
- المانيفست متغيّرش (354 سطر، مفيش ملفات ‎.kt جديدة ولا محذوفة)

## البوابات

- فحص التعليقات (قاعدة 7): فاضي (0).
- ktscan: 354 ملف — UNCLOSED: NONE.
- توازن الأقواس: 0/0/0 على الملفات الأربعة المعدّلة (ليكسر صحيح للـraw strings).
- المانيفست: 354 سطر، مطابق لملفات القرص (0-diff).

## مش متأكد منه

- ما اتعملش compile هنا — الـActions هو الحكم. الـ12 خطأ دول كانوا كل اللي في اللوج، فلو البناء عدّى `compileDebugKotlin` يبقى الإصلاح كمّل هدفه؛ أي فشل جديد هيبقى في خطوة تانية (موارد / R8 / ربط).
- `return@PillButton` جوه `onClick = { ... }` بيرجع من اللامدا نفسها (ليبل الدالة) — نفس سلوك الصيغة الأصلية.
- الـ`?.` الزيادة في جسم `onNewIntent` بعد ما الباراميتر بقى non-null سليمة ومش هتطلع غير warning على الأكثر.
