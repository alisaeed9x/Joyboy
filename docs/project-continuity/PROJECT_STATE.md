# PROJECT_STATE
## P15 (2026-10-23) — v0.6.0 — المتطلبات الناقصة (D-018) + أول فحص حقيقي
- **اتنفّذ:** مظهر مخصّص (لون/خلفية/خط/اسم) · نسخ احتياطي/استعادة · إعدادات مشغّل أوسع · ترتيب كامل للصفوف والقايمة الجانبية · ترجمة خارجية (.srt/.vtt/.ass) + عرض ترجمة صورية (PGS/DVB). **ما اتنفّذش:** دعم الشبكة SMB/DLNA (D-019 ينتظر قرارك).
- **اتحقق منه فعلًا (2026-10-23، kotlinc 2.0.21 + JDK 21):** `sh dev-tests/run_tests.sh` = **12 ملف اختبار، 244 فحص PASS، 0 فشل** (166 للقديم اللي كان «مكتوب لم يُشغَّل» + 78 جديد) · `sh dev-tests/compile_check.sh android.jar` = **كل كود Android (ماعدا YtNp.kt) بيتكومبايل 0 أخطاء** على android-34 + stubs media3.
- **لسه غير متحقق:** Gradle/AAPT/Actions · `YtNp.kt` (NewPipe حقيقي) · تواقيع media3 الحقيقية (الـ stubs مكتوبة من الذاكرة، أهمها: `MediaItem.SubtitleConfiguration.Builder`, `setPreferredAudioLanguage`, حقول `Cue.bitmap/position/line/size/bitmapHeight`) · أي حاجة على TCL (رسم PGS، مُنتقي SAF على التلفزيون، كتابة الاسم بالكيبورد، شكل الخطوط/التدرّجات).
## الحالة الحالية
**P11–P14 اتعملوا كوثائق/أدوات (v0.5.2، غير مُجمَّع): خطة اختبار نهائية + smoke + CI jvm-tests + RELEASE + HANDOFF_REPORT**. P0–P5 (قبل كده مفحوصة kotlinc + 60 اختبار) + P5c + P6 + P7–P8 (يوتيوب NewPipe) + **P9 (تطبيقات/أصوات/ترتيب) + P10 (سكون)**. P5c/P6/P7-8 **مكتوبة ومتوصّلة لكن غير مُجمَّعة وغير مختبرة** (مفيش kotlinc ولا نت ولا Gradle في بيئة الجلسات الأخيرة) · 2026-10-23 · v0.5.0. **لسه ما اتبنيش بـ Gradle ولا اتجرّب على جهاز.**
## ما تم إنجازه (بدليل الكود/الوثائق)
- P4 مكتبة · P5 سجل + مشغّل · P5c ترقية المشغّل (D-011) · P6 بحث موحّد (D-012).
- **P7:** `YOUTUBE_P7_REPORT.md` (جرد Subtitler v475، تصنيف، اللي اتنقل/اتساب، مخاطر) + D-013 (اختيار المستخدم: NewPipe) + D-014.
- **P8:** `YtNp.kt` · `YtLogic.kt` · `NetImage.kt` · شاشة يوتيوب في `HomeActivity` (بحث/🎤/شبكة/المزيد/خطأ+إعادة/Back) · تشغيل في `PlayerLayer` (صوت منفصل بـ MergingMediaSource + HLS) · Gradle (jitpack + NewPipeExtractor v0.26.5 + desugaring + hls/datasource) · Manifest `INTERNET` · إعداد `yt_maxh` · `dev-tests/YtLogicTest.kt` (21 فحص مكتوب).
## ما لم يكتمل
- **كومبايل + `sh dev-tests/run_tests.sh`** على كل ما سبق (المتوقع: 60 قديم + SearchLogic + PlayerLogic + YtLogic) ← أول أولوية.
- **تنفيذ P12/P13 فعليًا** (التشغيل والبناء والتوقيع) محتاج بيئة/جهاز/قرارك. المتطلبات الناقصة في HANDOFF_REPORT §3. P11: اللي فاضل (R8/signing/اختبار جهاز/قيود يوتيوب) في `P11_SECURITY_AUDIT.md`. (خطوط/خلفيات/نسخ احتياطي/إعدادات مشغّل اتنفّذت في P15 — D-018.)
- يوتيوب: لا حساب/تسجيل دخول (متعمّد) · لا قنوات/قوايم/تعليقات/رئيسية · لا سجل/استكمال ليوتيوب (D-014) · لا اختيار لغة صوت يدوي · لا ترجمة ليوتيوب (غير مستخرجة).
- ترجمة خارجية وصورية للمحلي: اتنفّذت في P15 (D-018). VobSub مش مدعوم. دعم الشبكة SMB/DLNA: مؤجّل (D-019).
## غير متحقق منه
كومبايل P5c/P6/P8 · Gradle (حل jitpack/NewPipe/desugaring/R8 غير مفعّل) · تواقيع NewPipe (منسوخة من Subtitler المبني على نفس v0.26.5) · تواقيع media3 (`MergingMediaSource`, `ProgressiveMediaSource.Factory`, `DefaultHttpDataSource.Factory().setDefaultRequestProperties(Map)`, `onCues`, `setPreferredTextLanguage`) · بحث/تشغيل يوتيوب فعلي على TCL (نت، كودك H.264/VP9 على الشريحة، 1080p، تزامن الصوت المنفصل) · كيبورد/🎤 · Back · أداء بحث على آلاف الفيديوهات.
## المشكلات الحالية / مخاطر معروفة
- الكود الجديد اتكتب بدون مترجم (فُحصت الأقواس فقط). أول خطوة: كومبايل + اختبارات.
- **يوتيوب غير رسمي** ويتكسر بتغيّر يوتيوب (يتصلح بتحديث رقم المكتبة) وعلى الأغلب مخالف لشروط خدمة يوتيوب — توزيع جانبي فقط. مفيش وعد بإزالة إعلانات.
- روابط ستريم يوتيوب بتنتهي (ساعات): P11b بيجدّدها تلقائيًا مرتين (غير مجرَّب).
- (اتصلّح P11b، غير مجرَّب) كيبورد البحث/نتايج الخيوط المتأخرة/انتهاء رابط يوتيوب/فشل حفظ السجل.
## آخر تغيير (P9/P10)
جديد: AppsLogic.kt · Apps.kt · SaverLogic.kt · SaverLogicTest/AppsLogicTest. معدّل: HomeActivity (شاشة تطبيقات، ترتيب صفوف، سكون، إعدادات) · HomePrefs (order/move) · Ui (nav sound) · Manifest (queries) · run_tests.sh · docs.

## آخر تغيير (P7/P8)
جديد: YtNp.kt · YtLogic.kt · NetImage.kt · YtLogicTest.kt · YOUTUBE_P7_REPORT.md. معدّل: PlayerLayer.kt (فرع يوتيوب + حراسة السجل) · HomeActivity.kt (شاشة يوتيوب + زرار البحث + إعداد الجودة) · build/settings.gradle.kts · AndroidManifest.xml · run_tests.sh · docs.
## الخطوة التالية
**كومبايل + `sh dev-tests/run_tests.sh`** (أو GitHub Actions `gradle assembleDebug`) ← أصلح أي فشل في الكود (مش الاختبار إلا لو غلط) ← بعدها نفّذ `TEST_PLAN_FINAL.md` (أ ثم ب) على TCL. شرط القبول: 0 أخطاء كومبايل + كل الاختبارات ALL PASS + بناء Gradle ناجح.
## ما يحتاج المستخدم
لوج GitHub Actions (أو أي مترجم) للكود الجديد · تجربة TCL: مكتبة/تشغيل/Back/كيبورد/🎤/**بحث وتشغيل يوتيوب (نت)**/جودة 1080 · تأكيد إنك فاهم قيود يوتيوب غير الرسمي (D-013) · هل عايز سجل منفصل ليوتيوب؟

## آخر تغيير (P11)
PlayerLayer (+release) · HomeActivity (onDestroy/onNewIntent/friendlyError) · YtNp (finally) · Library (cache/threads) · YtLogic (+friendlyError) · YtLogicTest (+4) · Manifest (allowBackup=false) · build.gradle (0.5.1) · docs (+P11_SECURITY_AUDIT.md, D-016).
