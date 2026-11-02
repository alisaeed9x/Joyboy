# v587 — AUDIT_09: Phase D (جزئي) + G، وفحص ثابت لـ A

## A — Baseline (من غير CI)
مش ممكن أشغّل GitHub Actions من الجلسة، فعملت أقرب حاجة: نزّلت kotlinc 2.0.21 + android.jar (API 34) وكومبلت الشجرة كلها قبل وبعد التعديل (وضع K1).
- الأخطاء 650 قبل و650 بعد، نفس القايمة بالظبط (كلها مكتبات ناقصة: media3 / Glide / NewPipe…). يعني التعديلات دي **ماضافتش أي خطأ جديد** ولا reference ناقص.
- `kt_manifest.txt` مطابق للملفات (184 قبل ← 182 بعد الحذف). كل الأسماء المحذوفة في v585 (K3) مفيش حد بيشاور عليها.
- ده مش بديل لـ CI: ما اتفحصش androidx/media3/NewPipe ولا الـ resources ولا الـ Gradle.

## D — Stubs & deps
- **T1 ML Kit:** اتشال `MlKitTr.kt` + كل نداءاته (Engine.googleItems، Player: `MlKitTr.ask`/`stepMl`/`askMlDownload`).
- **T1 Sherpa:** اتشال `SherpaStt.kt` + `Offline.downloadSherpa` + تنبيه التحويل لـ Whisper + سطرين الـ migrate. في `TrWizard`: اتشالت شاشتا `stepEngine` و`stepSherpa` وكارت «SenseVoice على الجهاز» وكل فروع `src == "sherpa"`.
  - سلوك قديم محفوظ: أي خطة/إعداد محفوظ فيه محرك `sherpa` بيتحوّل لـ `whisper` (كان بيحصل فعلًا في `Offline.engine()`).
  - `Main.kt` فيه سطر نص بس بيقارن `stt_engine == "sherpa"` — ما اتلمسش (مفيش reference لكلاس).
- **K5:** اتشالت `androidx.webkit:webkit:1.11.0` (0 imports، 0 ذكر في proguard/manifest). `androidx.core` متعلن مباشرة.

## G — Verification
- `.github/workflows/build.yml`: job جديد `verify` (continue-on-error، مش بيوقّف الـ APK): مطابقة `kt_manifest` للملفات + `gradle lintDebug` + رفع تقرير Lint. ده اللي هيأكّد K2 (UnusedResources).

## ما اتعملش (وليه)
- **E (T2/T3):** محتاجة اختبار فوكس/Back على التلفزيون TCL — تعديل أعمى هنا خطر على التنقل.
- **F — C3:** المفاتيح والتوكن بتتبعت HTTP والتوكن نفسه في الهيدر، فأي حماية لازم تبقى تبادل مفاتيح (أو TLS) ويغيّر كل الريموتات المقترنة. محتاج قرارك.
- **F — M3 / M6:** M6 بالقراءة: **الموضع** بيتبعت (`startms` في `/open`)؛ **الترجمة** لأ — مفيش حقل ترجمة، التلفزيون بيترجم لوحده بالإعدادات المتزامنة. M3 (شارة مين مشغّل) ما اتنفذتش.
- لسه: `PublicApis.pull/lastOk` (K3) و`east_asian.txt` و`StemCore.kt` و`YoutubeUi.kt` (D1/D3/D4) محتاجين قرارك/تأكيد.

ماجرّبتش Gradle ولا جهاز.
