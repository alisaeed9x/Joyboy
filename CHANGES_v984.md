# v984 — إصلاح خطأي بناء v982 (من اللوج) — بدون أي ميزة جديدة
مبني على v983. **ما اتجرّبش ولا اتعمله compile** (مفيش Gradle/جهاز) — الفحص: rule-7 grep فاضي + ktscan (303 ملف، مفيش تعليق مفتوح) + توازن الأقواس 0/0/0 في الملفين المعدّلين + manifest 303 سطر.

## اللوج اللي اتصلّح منه
اللوج اللي بعته المستخدم هو رن v982 (بدأ 16:17 EET — قبل ما v983 تتبعت أصلاً): **BUILD FAILED في 3m 34s** عند `:app:compileDebugKotlin` بخطأين بالظبط، والاتنين في كود v982 — وv983 زي ما اتبعتت شايلة نفس الخطأين (MtUpscale.kt ما اتغيّرش في v983، وsetStroke اتنقل زي ما هو). فـ v983 **ماتترفعش** — v984 هو اللي يترفع.

## الخطأ الأول — `MtUpscale.kt:188` (من v982)
`Return type of 'configure' is not a subtype of the return type of the overridden member 'fun configure(p0: Int, p1: Int): Size' defined in 'androidx/media3/effect/BaseGlShaderProgram'`.
- السبب: الملف كان مستورد `android.util.Size`، لكن `configure` بتاعة `BaseGlShaderProgram` بترجّع `Size` بتاعة media3 نفسها (`androidx.media3.common.util.Size`) — كلاس مختلف تماماً، فالـoverride اترفض.
- الإصلاح (:6): الاستيراد بقى `import androidx.media3.common.util.Size`. نفس الكلاس هو اللي رسالة `MSG_SET_VIDEO_OUTPUT_RESOLUTION` بتستناه في الـpayload (:155) — فالتلات استعمالات (`:155` و`:188` و`:200`) بقوا متسقين على نفس النوع.

## الخطأ الثاني — `MtBar.kt:1272` في v982 (بقى `:1399` في v983)
`None of the following candidates is applicable: fun setStroke(p0: Int, p1: ColorStateList!): Unit / fun setStroke(p0: Int, p1: Int): Unit`
- السبب: في `refreshAiButton()` (زرار AI) النداء كان `setStroke(maxOf(1, d.toInt()), 0xB3FFFFFF)` — والليترال `0xB3FFFFFF` أكبر من `Int.MAX_VALUE` فكوتلن بتعتبره `Long`، ولا overload من الاتنين بيقبل `Long`. نفس فئة غلطة v942 (`0xCC000000` في intArrayOf).
- الإصلاح (:1399): `0xB3FFFFFF.toInt()`.
- مسح إضافي: كل الليترالات الـhex الـ8 خانات اللي بتبدأ بـ8-F في `MtBar.kt` و`MtUpscale.kt` اتراجعت — ده الوحيد اللي كان ناقصه `.toInt()` (نفس الليترال في `:1153` كان متصحح أصلاً).

## الملفات المعدّلة
- `app/src/main/java/com/tttt/subtitler/MtUpscale.kt` — الاستيراد `:6` بس.
- `app/src/main/java/com/tttt/subtitler/MtBar.kt` — `:1399` بس.
- `CHANGES_v984.md` — جديد؛ `STATUS.txt` — مدخل v984.

## الفحص
- rule-7 grep: **فاضي**
- ktscan (303 ملف): **مفيش تعليق مفتوح**
- توازن الأقواس (بعد شيل الـstrings والتعليقات): **0/0/0** في الملفين المعدّلين
- manifest: **303 سطر، 0-diff**
- ما اتعملوش compile هنا — الـActions build هو البوابة الحقيقية، والاختبار على الجهاز لسه مطلوب (التلات إصلاحات بتاعة v983 + الـAI button).
