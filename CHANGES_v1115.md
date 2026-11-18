# Joyboy v1115 — إصلاح خطأي بيلد v1114 (SetBackBuffer + keyDots)

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: شجرة v1114 موثّقة (manifest 321).
> الدليل: بيلد v1114 فشل في Actions في 1م 45ث (`:app:compileDebugKotlin`، exit code 1) — خطأين بالظبط. البيلد كمان مسح 97 ملف Skipii قديم من الريبو (بقايا copy-over عند المستخدم) — مش سبب الفشل، وما اتلمسش هنا.

## 1) `CastCache.kt:70` — Unresolved reference 'setBackBufferDurationMs'

- السبب: `DefaultLoadControl.Builder` في media3 **1.10.1** (نسخة المشروع، app/build.gradle.kts:58) مفيهوش `setBackBufferDurationMs` — الدالة دي اتبدّلت بدالة جديدة بتاخد وسيطين. الغلطة من v1113 (NetCache اتكتب بالدالة القديمة).
- الحل: `.setBackBufferDurationMs(60_000)` اتبدّلت بـ `.setBackBuffer(60_000, false)` — المكافئ بالظبط (الدالة القديمة كانت بتشتغل كأن `retainBackBufferFromKeyframe = false`). اتأكّد: مفيش أي `setBackBufferDurationMs` تاني في `app/src` كله.
- البفرات التانية (30_000/120_000/2_500/5_000) والـ 64MB target وكل حاجة تانية في NetCache زي ما هي.
- `CastCache.kt` (NetCache.loadControl ~:70).

## 2) `Player.kt:4112` — Unresolved reference 'keyDots'

- السبب: `keyDots()` اتضافت في `class Engine` (Engine.kt:180) في v1114، بس مكان الاستدعاء `Player.kt:4112` بينده عليها من `engine` — والنوع المعلن بتاعه `EngineBackend` (interface، Player.kt:1912 `lateinit var engine: EngineBackend`). الواجهة مفيهاش `keyDots`، ولا `LocalEngine` (المنفّذ اللي بيغلّف `val e: Engine`). التحقق في v1114 اتأكّد إن الدالة «متعرّفة» بس، من غير ما يشيك على نوع الـ receiver في مكان الاستدعاء — وده الغلط، وما اتكررش هنا.
- الحل:
  - `fun keyDots(): String = ""` اتضافت في واجهة `EngineBackend` (EngineBackend.kt:36) بجسم افتراضي بيرجع `""` — فأي منفّذ تاني يشتغل من غير تعديل.
  - `override fun keyDots(): String = e.keyDots()` اتضافت في `LocalEngine` (EngineBackend.kt:127) — بتفوّض لـ Engine الحقيقي.
  - `RemoteEngine` (المنفّذ التاني، RemoteEngine.kt:12) بياخد الافتراضي `""` — فجلسات البعيد بتظهر من غير نقط. ده مقصود ومتسجّل هنا.
- النقط نفسها (الألوان، الترقيم، مسار التحديث) زي ما هي في v1114 — ما اتغيّرتش.
- `EngineBackend.kt` (:36، :127).

## مش متأكد منه

- `setBackBuffer(60_000, false)` هو المكافئ المكتوب في مرجع الـ API الرسمي للدالة القديمة — بس ما اتجرّبش على جهاز، وActions هو الحكم.
- الإعادة الفورية على نفس المفتاح بعد 503 (من v1114) ممكن تخلّص MAX_TRIES بسرعة لو السيرفر فضل واقع — بأمر المستخدم، وأول حاجة تتشيك لو الباتشات بدأت تفشل جملة بعد التحديث ده.
