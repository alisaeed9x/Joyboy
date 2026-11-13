# v943 — إصلاح فشل بناء v942 (11 خطأ كلهم في `MtBar.kt` الجديد)

- **السبب:** تشغيل v942 فشل في `:app:compileDebugKotlin` بعد 3م 40ث بـ11 خطأ — كلهم في ملف `MtBar.kt` الجديد بتاع v942، وباقي النسخة اتكومبايلت. مفيش أي تعديل تاني في النسخة دي غير إصلاح الأخطاء دي.
- **الإصلاحات (10 تعديلات في `MtBar.kt` بس):**
  - `Player` مقابل `ExoPlayer` (سطر 976): الملف كان عامل `import androidx.media3.common.Player as ExoPlayer` — يعني `player` كان واجهة `Player` مش `ExoPlayer` الحقيقي، و`VoiceClean.Playback` عايز `ExoPlayer` الحقيقي. اتغيّر الاستيراد لـ`androidx.media3.exoplayer.ExoPlayer` (المتصلين بيبعتوا `ExoPlayer` الحقيقي أصلاً من `MtTranslate`).
  - `Long` مقابل `Int` (سطر 163): `0xCC000000` قيمة أكبر من `Int` فكانت بتتفسر `Long` جوه `intArrayOf` — بقت `0xCC000000.toInt()`.
  - `rpc` من غير `args` (سطوة 378/1009/1036): `MtTranslate.rpc(op, args: JSONObject)` بيتنده من غير تاني باراميتر — بقت `JSONObject()` فاضية في التلاتة.
  - `FlowRow` مقابل `LinearLayout` (سطوة 401/427/449): دوال `topRow`/`trRow`/`toolRow` معلنة إنها بترجّع `LinearLayout` بس بتبني `FlowRow` (وهو `ViewGroup` مش `LinearLayout`) — بقت معلنة `: ViewGroup` (المتصل بيستخدم `addView` بس).
  - `exportSrt` بترجّع `Boolean` مش ملف (سطر 675): الكود كان بيعامل الناتج كملف وبيقرأ `.name` — بقت `val ok = exportSrt()`، و`exportSrt` نفسها هي اللي بتطلع التنبيه باسم الملف لما تنجح.
  - `Int` مقابل `Long` (سطر 1107): `shiftOffset(d: Long)` كان بيتنادى بـ`Int` — بقى `d.toLong()`.
- **ما اتغيّرش:** مفيش أي ميزة اتضافت أو اتشالت — v943 = v942 + إصلاحات البناء دي بس.
- **ما اتجرّبش:** مفيش Gradle هنا — البناء الفعلي على GitHub Actions؛ والشريط والجسر والريموت لازم يتجرّبوا على الجهاز وعلى TCL.
