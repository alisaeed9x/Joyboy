# v541 — م0-أ: فصل المشغّل عن المحرك (EngineBackend) — من غير أي تغيير في السلوك (مبني على v540)
> ماجرّبتش على جهاز، وماعملتش بناء كامل للـAPK (مفيش Gradle/Media3 هنا).
> **اللي اتجرّب فعلًا:** compile بـkotlinc 1.9.24 لـ`Engine.kt` + `EngineBackend.kt` + ملفات المنطق (قايمة run_tests.sh): **صفر أخطاء في EngineBackend.kt**. واتأكدت إن الفحص بيمسك الغلط (غيّرت نوع رجوع واحد عمدًا فطلع خطأ). أخطاء الملفات اللي برّه القايمة (Offline/TrWizard/LogStore…) متوقعة لأنها مش داخلة في الفحص ده.
> **اللي ماتجرّبش:** تجميع `Player.kt` (محتاج المشروع كله)، وتشغيل التطبيق.

## اللي اتعمل
- **ملف جديد `EngineBackend.kt`** (متسجّل في `app/kt_manifest.txt`):
  - `interface EngineBackend`: القايمة اتبنت من `grep` الفعلي لـ`engine.` في `Player.kt` (مش من تخمين المواصفة). زودت عليها اللي ظهر فعلًا ومكانش في المواصفة: `adoptDialect` · `awaitStopped` · `charactersNow` · `analyzeCharsNow` · `retryFailed` · `translateFromStart`.
  - `class LocalEngine(val e: Engine) : EngineBackend`: تفويض حرفي لـ`Engine` من غير منطق. `saveVersion` بالـdialect الفاضي بينادي النسخة الافتراضية القديمة (نفس السلوك).
- **`Player.kt` (تعديلات قليلة ومحددة):**
  - `lateinit var engine: Engine` ← `lateinit var engine: EngineBackend` + `private lateinit var realEngine: Engine`.
  - `initEngine`: `realEngine = eng; engine = LocalEngine(eng)`.
  - `startEngine`: بياخد `realEngine` (لأن `run()` مش في الواجهة).
  - `Live.engine = realEngine` (و`Live.engine === realEngine` في `onDestroy`)، وفحص `engine !== eng` بعد التحميل بقى `realEngine !== eng` (مقارنة الهوية على الـEngine الحقيقي).
  - باقي الـ ~250 استدعاء `engine.…` **ماتغيّرتش** ولا حرف.
- `app/build.gradle.kts`: `versionCode 541 / versionName 5.41`.

## انحراف مقصود عن المواصفة (وليه)
المواصفة قالت إعادة تسمية `engine` ← `eng` واستبدال `engine.` بـ`eng.` بـsed. **ماعملتهاش**: فيه متغيرات محلية اسمها `eng` في `Player.kt` (`val eng = engine` في 6 أماكن، و`eng` نص «gemini/google» في ترجمة ملف الترجمة)، فالاستبدال ممكن يتصادم ومقدرش أبني للتأكد. سبت الاسم `engine` ونوعه اتغيّر، والنتيجة نفس الفصل. لو عايز الاسم `eng` بعد ما تتأكد إن البناء ماشي، ده تعديل ميكانيكي لاحق.

## للتجربة (قبل م0-ب)
1. `Subtitler_v541.zip` ← بناء Actions. لو فشل ابعتلي اللوج (الأرجح: Unresolved/type mismatch في `Player.kt` في مكان ما شفتوش).
2. على الجهاز: فتح فيديو محلي، ترجمة، إيقاف/كمّل، seek، إعادة من الأول، قايمة النسخ (versions)، الشخصيات 🧑، دمج المكرر، تغيير اللهجة/الأسلوب. المفروض كل ده زي v540 بالظبط.
3. لما تقولّي «المحلي شغّال زي ما كان» أبدأ م0-ب (`PlaybackBackend`).
