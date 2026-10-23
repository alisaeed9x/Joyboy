# TEST_RESULTS
| الاختبار | الأمر/الطريقة | البيئة | النتيجة | النوع |
|---|---|---|---|---|
| فحص كومبايل Kotlin | `kotlinc 2.0.21 -jvm-target 17 -cp android-34.jar app/src/main/java/com/vanilla/tvlauncher/*.kt` | Linux، JDK 21، android.jar من Sable/android-platforms | **نجح، 0 أخطاء** (type-check فقط) | آلي |
| HomePrefs (toggle/cycle/hiddenSet) | نفس الطريقة، `dev-tests/HomePrefsTest.kt` | JVM | **ALL PASS — 6/6** | آلي، منطقي |
| BackRouter | `kotlinc BackRouter.kt dev-tests/BackRouterTest.kt` ثم `java BackRouterTestKt` | JVM | **ALL PASS — 7/7** | آلي، منطقي |
| بناء Gradle / AAPT / Manifest / الموارد | — | — | **لم يُنفّذ** | — |
| تثبيت + تنقل ريموت + Back على TCL | — | — | **لم يُنفّذ — مطلوب منك** | يدوي |
| ظهور في اختيار Home على TCL | — | — | **لم يُنفّذ** | يدوي |
| فحص كومبايل بعد دمج P4+P5 | `kotlinc 2.0.21 -jvm-target 17 -cp android-34.jar app/src/main/java/com/vanilla/tvlauncher/*.kt` | Linux، JDK 21 | **نجح، 0 أخطاء** (type-check فقط) | آلي — 2026-10-22 |
| اختبارات JVM كلها | `sh dev-tests/run_tests.sh` | JVM | **BackRouter 12/12 · HomePrefs 6/6 · LibraryLogic 15/15 · HistoryLogic 27/27 = 60** | آلي، منطقي |
| اختبار «بيكشف فعلًا» | كسر ترتيب `continueList` عمدًا | JVM | الاختبار فشل كما يجب (يثبت إنه مش بيعدّي دايمًا) | آلي |
| Manifest | تحليل XML + فحص وجود drawable/style | Python | well-formed، الموارد موجودة | آلي (مش AAPT) |
| مسح فعلي لـ MediaStore / صور مصغّرة / USB | — | — | **لم يُنفّذ — محتاج جهاز** | يدوي |
| تشغيل فيديو + استكمال + كودكات (mkv/HEVC/AC3) | — | — | **لم يُنفّذ — محتاج TCL** | يدوي |
| Back/تركيز بالأسهم (RTL) على جهاز | — | — | **لم يُنفّذ — محتاج TCL** | يدوي |
| ملاحظة | اختبارات `BackRouterTest` الجديدة بتحاكي ترتيب الطبقات بدوال بديلة؛ بتثبت عقد BackRouter مش سلوك HomeActivity الحقيقي | | | |
| كومبايل بعد media3 | `kotlinc 2.4.0 -jvm-target 17 -cp android-34.jar app/src/main/java/.../*.kt dev-tests/media3-stubs/*.kt` | Linux، JDK 21 | **نجح، 0 أخطاء** — **بـ stubs** منسوخة من تواقيع Subtitler، فبيثبت سلامة الكتابة والأنواع بس، **مش** توافق media3 الحقيقي | آلي |
| اختبارات JVM على Kotlin 2.4.0 | `sh dev-tests/run_tests.sh` | JVM | 60/60 نجحت | آلي |
| بناء Gradle بـ media3 + تشغيل صيغ فعلية (mkv/HEVC/AC3) | — | — | **لم يُنفّذ** | محتاج Actions + TCL |
| — جلسة 2026-10-23 (P6 + P5c) — | | | | |
| كومبايل Kotlin | — | **مفيش kotlinc/kotlin في بيئة الجلسة دي** | **لم يُنفّذ** — الكود الجديد (SearchLogic/ExternalSearch/PlayerLogic/PlayerLayer/تعديلات HomeActivity) **غير مُجمَّع** | — |
| SearchLogicTest + PlayerLogicTest | `sh dev-tests/run_tests.sh` (اتضافوا للـ runner) | — | **لم يُنفّذوا** (مكتوبين فقط) | آلي — مطلوب تشغيله |
| تحقق خوارزمية التطبيع العربي | نسخة Python طبق الأصل من `normalize` على 6 حالات | Python 3 | **6/6 صح** — بيثبت منطق الخوارزمية **مش** كود Kotlin نفسه | آلي، بديل جزئي |
| توازن الأقواس/الأقواس المعقوصة | سكربت على الملفات الجديدة/المعدّلة | Python 3 | متوازنة في كل الملفات | آلي، فحص سطحي (مش بديل المترجم) |
| تحديث stubs لـ media3 | `dev-tests/media3-stubs/*` (+Text.kt, SeekParameters, AudioAttributes, VideoSize, onCues, repeatMode…) | — | اتحدّثت لتطابق الاستدعاءات الجديدة — **لم تُجمَّع**؛ `setTrackTypeDisabled`/`SeekParameters`/`setHandleAudioBecomingNoisy`/`setAudioAttributes` مأخوذة من استخدام Subtitler (مبني فعلًا)؛ `onCues(CueGroup)` و`setPreferredTextLanguage` و`pixelWidthHeightRatio` من معرفتي بـ media3 ومش متحقق منها | — |
| كل اختبارات الجهاز (كيبورد TCL، 🎤، ترجمة مدمجة، ملاءمة الصورة، قايمة المشغّل، Back) | — | — | **لم يُنفّذ — محتاج TCL** | يدوي |
| YtLogicTest (مكتوب: URI/صلاحية id/شورتس/تحويل VideoItem/عدّاد مشاهدات/عنوان فرعي/دمج بدون تكرار/اختيار الارتفاع) | `sh dev-tests/run_tests.sh` (سطر YtLogicTest جديد) | — | **لم يُشغَّل — مفيش kotlinc في الجلسة.** المتوقع 21/21 | آلي (غير منفّذ) |
| تتبّع يدوي لتوقعات YtLogicTest | حساب القيم المتوقعة بالورقة (chooseHeight/formatViews/subtitle/mergeById) | — | متسقة مع الكود، **مش بديل للتشغيل** | يدوي |
| توازن الأقواس لكل `*.kt` بعد P7/P8 | سكربت Python | Python 3 | متوازنة في كل الملفات | آلي، فحص سطحي (مش مترجم) |
| كومبايل P7/P8 + Gradle (jitpack/NewPipe/desugaring) | — | — | **لم يُنفّذ** — أول خطأ محتمل: تواقيع NewPipe في `YtNp.kt` (منسوخة من Subtitler المبني) وتواقيع media3 (`MergingMediaSource`/`DefaultHttpDataSource.Factory`) | — |
| بحث/تشغيل يوتيوب فعلي، جودة، صوت منفصل، Back، كيبورد/🎤 على TCL | — | — | **لم يُنفّذ — محتاج نت + TCL** | يدوي |
| SaverLogicTest (10) · AppsLogicTest (5) · HomePrefsTest (+6 order/move) | `sh dev-tests/run_tests.sh` | — | **لم يُشغَّلوا — مفيش kotlinc**. القيم المتوقعة اتحسبت يدويًا (offset بـ floorMod، ترتيب طبيعي، order/move) | آلي (غير منفّذ) |
| Manifest بعد `<queries>` | تحليل XML | Python | well-formed | آلي، مش AAPT |
| أداء/استهلاك السكون، حرق الشاشة، أصوات التنقل، فتح تطبيقات TCL، رجوع من تطبيق خارجي | — | — | **لم يُنفّذ — محتاج TCL** | يدوي |
| — جلسة 2026-10-23 (P11) — | | | | |
| كومبايل/اختبارات JVM | — | **مفيش kotlinc/kotlin في الجلسة** | **لم يُنفّذ** — تعديلات P11 + 4 اختبارات `friendlyError` في YtLogicTest مكتوبة بس | — |
| مراجعة أمان (قراءة كود + grep أسرار/http + Manifest XML) | قراءة يدوية + `grep` + Python | — | تمّت؛ النتايج في `P11_SECURITY_AUDIT.md` (7 إصلاحات، 6 مفتوحة) | يدوي/آلي سطحي |
| توازن الأقواس للملفات المعدّلة | مقارنة مع نسخة phase10 الأصلية (Python) | — | **مطابق للأصل** في PlayerLayer/HomeActivity/YtNp/YtLogic/Library/NetImage/YtLogicTest (بعض الملفات فيها اختلاف قديم بسبب ضعف السكربت مع نصوص فيها أقواس، مش من تعديلي) | آلي، سطحي |
| Home-press/تحرير المشغّل/تسريب الاتصال/Back/تركيز | — | — | **لم يُنفّذ — محتاج TCL** | يدوي |
| P11b (4 إصلاحات) | — | **مفيش kotlinc** | **لم يُنفّذ** كومبايل/اختبار؛ توازن الأقواس لـ HomeActivity/PlayerLayer/HistoryStore مقارنة بالأصل (آلي، سطحي) | — |
| إعادة جلب رابط يوتيوب منتهي · الكيبورد أثناء الفحص · تنبيه فشل الحفظ | — | — | **لم يُنفّذ — محتاج TCL + نت (والرابط بيحتاج ساعات ينتهي)** | يدوي |
| — P12 (2026-10-23) — | | | | |
| `bash -n dev-tests/adb_smoke.sh` + تشغيله بدون adb | bash | Linux | syntax سليم؛ بيخرج بـ exit 2 ورسالة «adb مش موجود» | آلي (المسار الاحتياطي بس) |
| smoke فعلي / jvm-tests على Actions / جدول B (26 حالة) | — | — | **لم يُنفّذ** | مطلوب |
| اختبارات JVM كلها (أول تشغيل فعلي للقديم + الجديد) | `sh dev-tests/run_tests.sh` | Linux، JDK 21، kotlinc 2.0.21 | **12 ملف، 244 PASS، 0 FAIL** (قديم 166 + Appearance/Backup/SubLogic/PlayerLogic الجديد) | آلي، منطقي — 2026-10-23 |
| فحص كومبايل كل كود Android (ماعدا YtNp) | `sh dev-tests/compile_check.sh android.jar` | android-34 (Sable) + stubs media3 | **نجح، 0 أخطاء** (type-check فقط؛ stubs مش التواقيع الحقيقية) | آلي — 2026-10-23 |
| Gradle / Actions / YtNp مع NewPipe | — | — | **لم يُنفّذ** | — |
| P15 على TCL (جدول ج في TEST_PLAN_FINAL) | — | — | **لم يُنفّذ — مطلوب منك** | يدوي |
