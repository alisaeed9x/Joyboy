# v545 — م5 + م6 كاملين (ماعدا اللي محتاج وضع المتحكم في PlayerActivity) — مبني على v544
> **ماعملتش compile ولا بناء APK ولا شغّلت أي اختبار.** الجلسة مفيهاش kotlinc ولا إنترنت. اللي عملته مراجعة يدوية للأقواس والتواقيع (كل أعضاء `EngineBackend` اتطابقوا) وإن مفيش `//` في وسط سطر بعد كود.
> **غير مجمَّع:** `BrainCore.kt` · `BrainRpc.kt` · `Tasks.kt` · `Player.kt` · `KeepAliveService.kt` · `PlayTarget.kt` · `RemoteActivity.kt` · الجدد: `LinkCore.kt` · `LinkSession.kt` · `RemoteEngine.kt` · `TvTasksUi.kt` · `dev-tests/BrainCoreTest.kt` (فحوص جديدة مكتوبة ومااتشغّلتش).

## فحص الاعتماديات (قاعدة 10)
| المطلوب | الحالة |
|---|---|
| م0-أ `EngineBackend` · م1 `BrainRpc` | ✅ موجودين |
| م0-ب `PlaybackBackend` | ❌ لسه (تعديل جذري في `Player.kt`، ومستني تأكيدك إن المحلي شغّال — بند 5.3 في الخطة) |
| م2-أ `LinkSession` · م2-ب `RemoteEngine` | ✅ **اتبنوا في v545** |
| م2-ج وضع المتحكم في `PlayerActivity` | ❌ لسه (محتاج م0-ب) |

## جانب التلفزيون
- **أوامر المهام** (`/rpc`): `task.add` (trim/audio/gif/compress على الفيديو الشغّال على التلفزيون) · `task.stop/cancel/pause/resume/retry/remove/clear` بالـid. (ده خارج حرفية المواصفة ج-7 «يعرض التقدّم»، واتعمل بطلبك.)
- **قيد الريموتات (د-7):** أول متحكّم (بالـtoken) يملك التحكم؛ التاني بيرد عليه `409 readonly` لحد ما يبعت `control.take`. الملكية بتنتهي بعد 45ث من غير أوامر. أوامر القراءة (`state`/`engine.get`/`engine.versions`) مسموحة للكل. `/sync` فيه `ctl` (true = إنت المالك).
- `engine.characters` · `engine.analyzeChars` · `engine.set` بأعلام زيادة (`paused`/`skipIfBaseLang`/`baseSkipped`/`baseLangCode`).
- لقطة المحرك (`eng`) اتوسّعت: الأعلام (`userPaused`/`paused`/`transOnly`/`keepGoing`/اللهجة/النبرة/…) + `covered` + `pendingCount` + الإصدارات (اسم/لهجة/عدد) + الشخصيات.

## جانب الموبايل (جديد)
- **`LinkCore.kt`** (منطق نقي): `LinkMirror` (يطبّق ردّ `/sync`: subs بقائمة **جديدة** عند كل delta، state، eng، tasks، events)، `PosExtrap` (extrapolate الموضع محليًا)، reset تلقائي لو الفيديو اتغيّر (`vid`) أو التلفزيون اتعمله restart.
- **`LinkSession.kt`:** long-poll على `/sync` فوق `RemoteClient` · إعادة اتصال بـbackoff · `connected` · `send(op,args)` async بنفس الـ`cid` في 3 محاولات · `setCfg` (يبعت `/cfg`) · معالجات الأوامر الطويلة (`opreport`/`opdone`) · لوج المحرك · `takeControl()`.
- **`RemoteEngine.kt`:** كل أعضاء `EngineBackend` من النسخة المحلية؛ الأوامر async؛ الأعلام ليها قيمة تفاؤلية 3ث.
- **`TvTasksUi.kt`:** «🧰 مهام التلفزيون» (زرار جديد في شاشة الريموت): تقدّم + إيقاف/استكمال/إلغاء/إعادة/مسح، و«استلم التحكم» لو متحكّم تاني ماسك.
- **إشعار الموبايل بيعكس التلفزيون:** لو «وضع التلفزيون» شغّال ومفيش مشغّل محلي، `KeepAliveService` بيعرض عنوان/موضع/تقدّم ترجمة التلفزيون، وأزرار الإشعار (تشغيل/السابق/التالي/سيك/خروج) بتروح للتلفزيون. زرار «وقّف الترجمة» في الإشعار مش موصّل (مفيش `userPaused` toggle دقيق من الإشعار).
- الجلسة بتبدأ لوحدها من شاشة الريموت ومن `PlayTarget` بعد الإرسال.

## اللي لسه (عن قصد)
- **م0-ب `PlaybackBackend`** و**م2-ج** (`PlayerActivity` بـ`remote=true`، `/frame` بدل SurfaceView، تعطيل `DubPlay/loud/visual`) و`PlayTarget` يفتح `PlayerActivity` بدل `RemoteActivity`. دول هما اللي بيخلّوا `TrWizard`/`sentDlg`/الإصدارات/اللوج تشتغل على `RemoteEngine` بنفس واجهة المشغّل. `RemoteEngine` جاهز ومستني بس إن `PlayerActivity` يستخدمه.
- م3 (`LibraryBackend` تحت `LibraryUi`) وإكمال م4.

## للتجربة
1. ابني v545 ← لو فشل ابعتلي اللوج (أغلب احتمال في الملفات الجديدة الأربعة).
2. `bash dev-tests/run_tests.sh` ← `BrainCoreTest` فيه ~30 فحص جديد.
3. على الموبايل: شاشة الريموت ← «🧰 مهام التلفزيون». على التلفزيون ابدأ قص/ضغط ← التقدّم لازم يظهر ويتحدّث.
4. وصّل ريموتين: التاني لازم يشوف «👁 قراءة فقط» + زرار «استلم التحكم».
5. شغّل «وضع التلفزيون» وافتح فيديو ← إشعار الموبايل يعكس التلفزيون، وزرار ⏯ في الإشعار يتحكم فيه.
