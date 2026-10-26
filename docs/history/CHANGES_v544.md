# v544 — م5 + م6 (جانب التلفزيون بس) — مبني على v543
> **ماعملتش compile ولا بناء APK ولا شغّلت اختبارات.** الجلسة مفيهاش kotlinc ولا إنترنت. اللي عملته: مراجعة يدوية للأقواس والتواقيع وإن مفيش `//` في وسط سطر بعد كود.
> **غير مجمَّع:** `BrainCore.kt` · `BrainRpc.kt` · `Tasks.kt` · `Player.kt` · `dev-tests/BrainCoreTest.kt` (5 فحوص جديدة + 10 تحقّقات مكتوبين ومااتشغّلوش).

## فحص الاعتماديات (قاعدة 10)
| المطلوب | موجود في v543؟ |
|---|---|
| م0-أ `EngineBackend` + `LocalEngine` | ✅ |
| م1 `BrainRpc` / `BrainBus` / `/cfg` بقايمة بيضاء | ✅ (القايمة البيضاء كانت موجودة من v542) |
| م0-ب `PlaybackBackend` | ❌ |
| م2 `LinkSession` / `RemotePlayback` / `RemoteEngine` | ❌ |
| م3 `LibraryBackend` | ❌ |

لأن م2 ناقصة، **اتبنى بس اللي على التلفزيون**. أي شاشة على الموبايل (TrWizard/sentDlg/الإشعار) **ماتلمستش**.

## م5 — المحرك والإعدادات (جانب التلفزيون)
- `/cfg` بالقايمة البيضاء + `engine.*` RPC (versions/saveVersion/applyVersion/…) + اللوج بأحداث `/sync`: **موجودين من v542**، ماغيّرتهمش.
- **جديد:** لقطة حالة المحرك بتتنشر في `/sync` تحت `eng`: `coverage` · `pending` · `refining` · `batches` · `problems` · `rows` (= `batchRows`) · `failed` · `done` · `offBrief` · `offDetail`. دي اللي `RemoteEngine` (م2-ب) هيقراها بدل ما يسأل التلفزيون في كل tick. بتتبني على خيط خلفي كل 1.5ث بس لو فيه ريموت بيسأل، وبتتتجاهل لو المحرك اتغيّر في النص.
- `BrainEngineJson` (encode/decode) في `BrainCore.kt` عشان يتختبر على JVM.

## م6 — المهام والإشعارات (جانب التلفزيون)
- `TaskCenter.changed()` بيبعت لقطة المهام لـ`BrainBus.setTasks` (لو فيه ريموت بيسأل). `/sync` بيرد بـ`tasks` (id/kind/title/state/pct/msg/paused/outSize). أول `/sync` بيحدّثها بنفسه.
- `BrainTasksJson` + `TaskRow` في `BrainCore.kt` (JVM-testable). `BrainBus.reset()` مابتمسحش المهام (مستقلة عن الفيديو).

## اللي ماتعملش (عن قصد، محتاج م2)
- شاشات الموبايل: `TrWizard` / `sentDlg` / الإصدارات / اللوج على `RemoteEngine`، وكتابة الإعدادات من الموبايل بـ`/cfg`.
- عرض تقدّم المهام على الموبايل، وإشعار `KeepAliveService` اللي يعكس حالة التلفزيون.
- **إنشاء مهمة** (قص/ضغط/GIF) على فيديو التلفزيون من الموبايل، وأوامر التحكم في المهمة (إيقاف/إلغاء/إعادة): المواصفة (ج-7) بتقول «يعرض التقدّم» بس، فماضفتهمش من غير موافقتك.

## ملاحظة أداء
`/sync` دلوقتي بيرد بـ`tasks` و`eng` في كل رد حتى لو ماتغيّروش (كام KB لفيديو طويل). لو بان تقيل نخليهم delta بالـver.

## للتجربة (على التلفزيون)
1. ابني v544 ← لو فشل ابعتلي اللوج.
2. `dev-tests/brain_curl.sh` أو `curl` على `/sync?since=0&wait=0` بتوكن مقترن ← لازم يظهر `tasks` و`eng`.
3. افتح فيديو واترجمه ← `eng.batches` لازم تتغيّر علامات (⏳ ← ✅) وقت الترجمة، و`eng.cov` تكبر.
4. ابدأ مهمة قص/ضغط ← `tasks[].pct` لازم يزيد في `/sync`.
5. شغّل `bash dev-tests/run_tests.sh` ← `BrainCoreTest` المفروض يعدّي (لو فشل وماتجمّعش، غالبًا غلط في الاختبار نفسه).
