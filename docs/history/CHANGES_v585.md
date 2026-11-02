# v585 — Phases B + C من AUDIT_09 (A مش ممكنة من الجلسة: محتاجة GitHub Actions)

## B — الربط (C2 + M1)
- **C2:** `TvLink.pair` — الكود بقى إجباري حتى لأول ريموت (قبل كده لو مفيش ريموتات مقترنة كان أي جهاز على الشبكة بيتقرن من غير كود). محاولة من غير كود ← التلفزيون يعرض كود صالح 12 ثانية على الشاشة (بيعيد استخدام الكود لو لسه صالح، وبحد أدنى 8 ثواني بين كل إشعار). `/hello` بقى بيرجّع `needCode=true` دايمًا. نص شاشة «ريموت الموبايل» على التلفزيون اتعدّل.
  - الريموتات المقترنة قبل كده **مش متأثرة**: بتتصل بالتوكن، ومفيش ترحيل مطلوب. اللي اتغيّر بس إن أي موبايل جديد لازم يكتب الكود.
- **M1:** زرار «✍ اكتب IP التلفزيون يدويًا» في شاشة الريموت (`RemoteActivity.askManualIp` + `RemoteClient.helloAt`). بيقبل `IP` أو `IP:بورت`، بيتأكد إن Subtitler بيرد على `/hello`، وبعدين يكمل نفس مسار الاقتران بالكود. آخر IP متخزّن في `tvlink_manual_ip`.

## C — تنضيف
- **K1/K2/K4(جزء):** `assets/index.html` + 4 صور + `add_jitpack.sh` (اتعملوا في v584، وتفاصيلهم في docs/history/CHANGES_v584.md).
- **K3:** اتمسح ~35 عضو معرّف ومحدش بينادي عليه (من قايمة A4): `TvHome` (tabBtn, glowFor, rowInto, recentCard, hero, quickTiles, appCards, appTile, storageTile, storageSub, phoneTypeDialog) · `RemoteActivity` (pBtn, sfDialog, ytPhoneBtn, spacer, pasteClip) · `TrWizard` (sttFallback, stepVisKind, stepMTone) · `RemoteLibrary` (cachedFolders, cachedVideos) · `ExtLlm.supportsStt` · `ExtTranslate.guessLang` · `LinkSession.logSnapshot` · `Lyrics.estimate` · `ModelWatch.baseline` · `Offline.deleteSherpa` · `TvUpdate.askOverlay` · `YtNp.subsFeed` · `YtWatch.hasQualities` · `OffLang.ModelDl` · `YtSkin.YtLogo`.
  - وكمان اللي اتيتّم بعدهم (cascade، اتكشف بعد الحذف): appCard, avgColor, glowRun/glowTarget/glowForNow/glowAnim/glowColor/glowMap, heroUri, overlayRoot, pickKind, stepMDeg, sfEngine, tabFocused, tkind, tsrc.
  - **مااتمسحش عن قصد:** `AudioIO.readAt` (override بيناديه Media3) · `PublicApis.pull` و`lastOk` (مش متوصلين بأي شاشة من v395 فالميزة واقفة، مش بقايا — محتاج قرارك).
- **K4:** كل `CHANGES_v*.md` القديمة + `AUDIT_*.md` + `CLEANUP_v356.txt` + `PLAN_UI.md` اتنقلوا لـ `docs/history/`. `STATUS.txt` و`README` فضلوا في الجذر (البرومبت بتاعك بيطلب سطر فوق STATUS في كل نسخة).

## اللي اتجرّب واللي لأ
- اتجرّب: عدّ الأقواس قبل/بعد في كل ملف اتعدّل (مفيش فرق)، وعدّ كل اسم اتمسح في الكود كله + dev-tests + XML (كان مرة واحدة بس = التعريف).
- **ماجرّبتش:** compile ولا Lint ولا جهاز (مفيش kotlinc ولا نت في الجلسة). ممكن imports بقت زيادة (تحذير بس). الاقتران والـ IP اليدوي محتاجين تجربة على التلفزيون الحقيقي: اقتران من غير كود لازم يفشل ويظهر كود، واقتران بالكود لازم ينجح، وريموت مقترن قبل كده لازم يفضل متصل.
