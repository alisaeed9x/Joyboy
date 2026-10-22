# DECISIONS
## D-001 — مشروع منفصل (2026-10-22) — **معتمد من المستخدم**
Vanilla TV Launcher تطبيق **جديد منفصل** (appId جديد). Subtitler v476 مرجع فقط: بنأخد منه الثيمات (Theme.kt)، منطق YouTube (NewPipe layer)، وميكانيزم المشغّل/الرجوع، بنسخ انتقائي مع مراجعة، مش تحويل التطبيق القديم. مرفوض: تحويل Subtitler (48 ألف سطر موبايل).
## D-002 — الرجوع — **معتمد (أولوية المستخدم)**
مكدّس رجوع ثابت (DESIGN_NAV §3) + `OnBackInvokedCallback` (API 33+) مع fallback `onBackPressed`؛ ممنوع الاعتماد على `setOnKeyListener`.
## D-003 — تقنية الواجهة — **مطبّق مؤقتًا في المرحلة 2 (Views)، اعتراضك يغيّره**
Views برمجية (إعادة استخدام Theme.kt/كروت TvHome، مخاطر كومبايل أقل لأن مفيش مترجم عندي) مقابل Compose for TV (تركيز أحسن لكن كود جديد كامل غير مُجرَّب). ميل مبدئي: Views.
## D-004 — الصلاحيات — مطبّق (المرحلة 2 بدون أي صلاحيات)
MediaStore + READ_MEDIA_VIDEO فقط؛ بدون MANAGE_EXTERNAL_STORAGE/SYSTEM_ALERT_WINDOW/RECORD_AUDIO إلا لو ثبت لازم.
## D-005 — appId وminSdk مؤقتين (2026-10-22)
`com.vanilla.tvlauncher` وminSdk 26 / targetSdk 34 / compileSdk 35 / Kotlin 2.0.21 / AGP 8.9.1 / Gradle 8.11.1 — افتراضات لأن المستخدم ما حدّدش. تتغيّر بسهولة (app/build.gradle.kts) قبل أول نشر؛ تغيير appId بعد التثبيت = تطبيق مختلف. Kotlin 2.0.21 بدل 2.4.0 بتاع Subtitler لأن مفيش اعتمادية هنا بتفرض 2.4.0.
## D-006 — Back على Launcher
الرئيسية لا تعمل `finish()` أبدًا (مفيش `super.onBackPressed()`)؛ `enableOnBackInvokedCallback=true` + تسجيل `OnBackInvokedCallback` على API 33+.
## D-007 — معرّف الفيديو المستقر (2026-10-22) — مطبّق
`VideoItem.key = الاسم|الحجم|المدة` (مش رقم MediaStore لأنه بيتغيّر بعد إعادة الفهرسة). السجل بيتربط بالـ key، وبعدين fallback بالـ uri. نفس الملف منسوخ في مجلدين = نفس key (مقبول). مرفوض: `_ID` وحده (بيتغيّر)، والمسار (بيتغيّر لو اتنقل). ملاحظة: الكود كان بيشير لـ D-007 قبل ما يتوثّق — اتوثّق دلوقتي.
## D-008 — المشغّل الداخلي (2026-10-22) — **محدَّث: media3 ExoPlayer 1.10.1**
كان VideoView مؤقتًا؛ استُبدل بـ media3 بعد طلب المستخدم «دعم الصيغ» (mkv/HEVC/AC3…). التنفيذ داخل `PlayerLayer.kt` فقط (طبقة في HomeActivity → Back عبر BackRouter). مطابق لنمط Subtitler v475 (`DefaultRenderersFactory.setEnableDecoderFallback(true)` + `ExoPlayer.Builder` + SurfaceView). الدعم الفعلي للكودكات = مفكّكات شريحة التلفزيون (DTS/TrueHD ممكن ما يشتغلوش)؛ AVI/WMV غير مدعومين من media3 → فشل التشغيل يفتح مشغّلًا خارجيًا لو موجود. لا ترجمة مرسومة لسه (محتاج media3-ui أو SubtitleView). مرفوض: FFmpeg extension (مش متاح للتحقق هنا، يزوّد الحجم).
## D-010 — نسخ أدوات البناء (2026-10-22) — **يعدّل D-005**
Kotlin 2.4.0 · compileSdk 36 · AGP 8.9.1 · Gradle 8.11.1 · Java 17 — مطابقة لـ Subtitler v475 (مبني فعلًا على Actions مع media3 1.10.1) بدل تخمين توافق Kotlin 2.0.21/compileSdk 35 مع media3. تغيّر DSL: `kotlin { compilerOptions { jvmTarget } }` بدل `kotlinOptions`. appId/minSdk/targetSdk كما هي (D-005).
## D-009 — سلوك السجل (2026-10-22) — مطبّق
أقل من 5 ثواني ما يتسجّلش ولا يمسح تقدّم موجود · الاستكمال من (آخر موضع − 3ث) · «اكتمل» = ≥95% أو آخر 15ث (لفيديو >دقيقتين) أو إشعار انتهاء حقيقي من المشغّل، **مش** بإغلاق التطبيق · حفظ دوري كل 10ث أثناء التشغيل + عند الإيقاف/pause/الخروج · سقف 500 سطر. السابق/التالي في المشغّل = PlayQueue فقط (مجلد أو قايمة)، السجل ما بيتحركش بيه.

