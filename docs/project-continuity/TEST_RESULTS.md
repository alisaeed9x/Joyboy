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

