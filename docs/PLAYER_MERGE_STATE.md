# حالة دمج المشغّل (PLAYER_MERGE_STATE)

> الخطة الكاملة: `docs/JOYBOY_PLAYER_MERGE_PLAN.md` — اقراها الأول. الملف ده بس بيتتبّع **حالة** كل مرحلة. حدّثه في آخر كل مرحلة.
> كل المراحل: لم يتم compile ولا اتجرّب (مفيش كومبايلر هنا).

| المرحلة | الوصف | النسخة | الحالة |
|---|---|---|---|
| 0 | تجهيز: نقط ربط في `JoyboyPlayerHost` + `MtPlay.decide()` | v930 | ✓ اتكتبت (لم تُكومبايل) |
| 1 | اتجاه: محلي/مباشر يفتح landscape fullscreen | — | ✗ |
| 2 | محرك الترجمة الكامل + قلب `mt_main_player` + معالجة الاستثناءات | — | ✗ |
| 3 | أدوات الميديا (A-B · تكرار · boost · نوم · حلقات · استكمال) | — | ✗ |
| 4 | التلفزيون والريموت (D-pad + `MtTvPlayer` كامل + flag `mt_tv_player`) | — | ✗ |
| 5 | تنظيف: شيل مشغّل Joyboy القديم | — | ✗ |

## v932 (إصلاحات جانبية، مش مراحل)
- `SplashActivity`: التلفزيون الحقيقي ← `TvActivity` من أيقونة اللانشر (و`onNewIntent`).
- `LibraryUi`: مربعين مصدر المكتبة (تلفزيون/موبايل) تحت الهيدر.

## v931 (إصلاح جانبي، مش مرحلة)
- `MtTvRoute.intercept`: سؤال «تشغّله فين؟» بيظهر لو tv_mode شغّال أو الموبايل متصل (`RemoteClient.current != null`).

## اللي اتعمل في المرحلة 0 (v930) — سلوك التطبيق **ما اتغيّرش**
- `JoyboyPlayerHost.java`:
  - `VideoInfo`: `sourceKind()` ("youtube"/"local"/"direct" من `localUri()`) و`headers()` (فاضية لحد م2) — كلهم `default`.
  - `Provider`: `wantsLandscape(Activity)` · `onKey(Activity, KeyEvent)` · `onControlsVisibility(Activity, boolean)` · `extraControls(Activity, ViewGroup)` — كلهم `default` فاضيين.
  - Static dispatchers بنفس الأسماء، كلها بـ`try/catch (RuntimeException)`: `JoyboyPlayerHost.wantsLandscape/onKey/onControlsVisibility/extraControls`.
  - **لسه مفيش حد بينديهم** — `MobilePlaybackActivity` ما اتلمستش. المراحل 1/2/4 هي اللي هتنده.
- `MtPlay.kt`: `decide(act, intent): Decision(ok, reason, src)` بنفس شروط `reroute` القديمة بالظبط؛ `reroute` بقت تستخدمها وبتسجّل سبب الرفض في اللوج (`LogStore.add("MtPlay: skip — …")`) ما عدا حالتي «mt_main_player off» و«tv». الافتراضي `mt_main_player=false` زي ما هو.
- ما اتضافش أي ملف `.kt` جديد ← `kt_manifest.txt` ما اتغيّرش.

## قرارات
- `mt_main_player` يفضل false لحد ما م2 تعالج الاستثناءات (راجع جدول الخطة).
- التلفزيون يفضل على مشغّل Joyboy لحد flag `mt_tv_player` (م4) والتجربة على TCL.
- رقم النسخة بيتحسب من أحدث `CHANGES_vNNN.md` (`build.gradle.kts` سطر 7) — مفيش تعديل في gradle.

## المرحلة الجاية: 1
أول خطوة: `grep -n mVideoShapePending meetube/mobile/src/stmobile/java/com/newtube/mobile/ui/playback/MobilePlaybackActivity.java` (نقطة مش متأكد منها في الخطة قسم 6).
