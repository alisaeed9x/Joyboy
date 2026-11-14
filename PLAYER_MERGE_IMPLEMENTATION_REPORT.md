# Player merge — Implementation Report (v1013)

## الملفات المعدّلة
- `app/src/main/java/com/tttt/subtitler/Player.kt` فقط (+34 سطر، صفر حذف).
- وثائق: `JOYBOY_SETTINGS_AUDIT.md` · `PLAYER_FEATURE_PARITY_AUDIT.md` · `CHANGES_v1013.md` · `STATUS.txt`.

## اللي اتنقل
1. `mtParityAttach()` — بيوصّل `MtSmooth` و`MtEnhance` بنفس `ExoPlayer` الشغّال (`player.exo()`)، من `onResume` (idempotent: بيتجاهل نفس الـplayer). بيتخطى لو ريموت/كاست (`exo()==null`).
2. `mtParityDetach()` في `onDestroy`.
3. زرار «✨ تحسين الصورة: <الوضع>» في قايمة ☰ → `mtEnhanceCycle()` (نفس منطق `MtBar.cycleEnhance` + إشعار التأكيد بعد ثانيتين).

## قرارات معمارية
- مفيش محرك تاني ولا state manager جديد؛ الكائنين `MtSmooth`/`MtEnhance` singleton موجودين ومستخدمين.
- `VideoInfo` بيتمرّر `null` (الـ`info` في الاتنين بيتخزّن بس ومابيتقراش — اتأكدت بالـgrep).
- الافتراضي «إيقاف» ← من غير تأثير على أي مستخدم ما فعّلش التحسين.
- مافيش مفاتيح جديدة؛ بيستخدم `mt_enh_*` و`mt_hz*` الموجودين (فمفيش ترحيل).

## اللي ماتنقلش (وليه)
- قفز الجملة، A-B، نوم، تضخيم، PiP… موجودة أصلًا في Player.kt (اتأكدت).
- أي حاجة خاصة بالتلفزيون/`MtTvPlayer` — خارج النطاق (م5 محظورة).

## الاختبارات والبناء
- **لم يتم compile.** البيئة هنا مفيهاش Gradle ولا Android SDK، والمشروع بيعتمد على Actions (راجع STATUS.txt).
- **لم يتم تشغيل اختبارات** (JVM ولا جهاز: موبايل/تابلت/TV).
- فحص يدوي للدِف: الرموز المستخدمة موجودة (`PlaybackBackend.exo()`, `MtSmooth.attach/detach`, `MtEnhance.attach/detach/cycleMode/modeName/lastWhy/reportLater`, `remote`, `say`, `pd`).

## مشاكل متبقية / مخاطر
- أول بناء Actions هو الحكم؛ لو فيه خطأ هيبقى في الـlambda `reportLater` أو توقيع `pd` (متوقع مطابق).
- MtEnhance على Player.kt ممكن يحتاج seek/دورة ريندرر عشان يبان (زي MeeTube v992) — غير مجرَّب.
- تنظيم الإعدادات للتابلت/TV والفوكس غير مجرَّب.
