# v542 — م1: جانب التلفزيون «BrainRpc» (مبني على v541)
> ماجرّبتش على جهاز، وماعملتش بناء APK كامل. **اللي اتجرّب فعلًا:**
> - `BrainCoreTest` (جديد في dev-tests): 21 فحص نجحوا على JVM — SubCodec round-trip بالـ22 حقل · delta بالباتش (تعديل / حذف باتش / رجوعه / عميل جديد) · BrainBus (await/post/eventsSince).
> - `BrainRpc.kt` + `BrainCore.kt` اتعملهم compile بـkotlinc مقابل stubs للأنظمة اللي حواليهم (PlayerActivity/TvLink/Cfg/VideoScan/Thumbs): صفر أخطاء.
> **اللي ماتجرّبش:** `Player.kt` (محتاج المشروع كله) و`TvLink.kt` — تعديلاتهم صغيرة بس ماتجمّعوش. أي خطأ بناء هيبقى غالبًا فيهم.

## الملفات
- **`BrainCore.kt` (جديد، منطق نقي):** `SubCodec` (Sub↔JSON) · `BrainZip` (gzip+base64) · `SubDelta` (تتبّع التغيير بالباتش + المحذوف) · `SubSync.apply` (جانب الموبايل، جاهز لـم2) · `BrainBus` (ver + أحداث + آخر حالة + `await` للـlong-poll).
- **`BrainRpc.kt` (جديد):** `exec` (/rpc) · `sync` (/sync) · `frame` (/frame) · `libFolders/libVideos/libThumb` · `cfg`.
- **`TvLink.kt`:** 7 مسارات جديدة في `route` (بنفس التوكن): `/rpc /sync /frame /lib/folders /lib/videos /lib/thumb /cfg` + `openJson` (عشان op=load يعدّي على نفس `/open`).
- **`Player.kt`:**
  - `brainTick()` في tick الـ200ms: **مفيش أي شغل لو مفيش ريموت بيسأل** (آخر /sync أقدم من 30ث). لو فيه: كل 500ms بينشر `rState()` + `vid/ready/started`، والترجمة بتتقارن بالمرجع (`engine.subs !== ref`) وحسابها على خيط خلفي واحد.
  - `rpc` (UI، مهلة 3ث) و`rpcBg` (عمليات `engine.*` على خيط الطلب مش الـUI) و`frameJpeg`، + `PlayerActivity.brain()/brainFrame()` في الـcompanion. `onUi` بقت بمهلة اختيارية (الافتراضي 900ms زي ما كان).
  - `log/status/notice` بيبعتوا أحداث لـBrainBus (بس لو فيه ريموت بيسأل). `initEngine`: `BrainBus.reset()` عشان ترجمة فيديو قديم ما توصلش.
  - `grabSurface(maxDim = 960)`: نفس السلوك القديم لو من غير باراميتر.
- `app/kt_manifest.txt` (BrainCore/BrainRpc) · `dev-tests/BrainCoreTest.kt` (+ `run_tests.sh`) · `dev-tests/brain_curl.sh` (اختبار من Termux بدون موبايل) · النسخة 542 / 5.42.

## العقد (للموبايل في م2)
- `POST /rpc {cid, op, args}` ← `{ok, res}` أو `{ok:false, err:"noplayer"|"badurl"|…}`. نفس `cid` تاني = نفس الرد من غير تنفيذ (آخر 100، للناجح بس).
  - ops الـUI: `state · act{a,v} (كل أوامر rAct: toggle/seek/seekto/prev/next/speed/fit/cc/… ) · play · pause · seekto{ms} · volume{v 0..1} · speed.set{v} · track.audio{i} · track.quality{i} · conf.reload`.
  - ops المحرك (`engine.*`): `get · set{name,value} · pause · resume · translateFrom{sec} · translateFromStart · redoAll/redoFrom{i}/redoOnly{i} · retryProblems · retryFailed · removeDuplicates · saveVersion{name} · applyVersion{i} · versions · setConvDialect{d,existing} · adoptDialect{d} · langChanged{lang,pos} · saveNow · importSubs{subs}`. اللي بيرد بعدين: `recap · rewriteAll · refineWholeNow · correctPronounsNow · googleTranslateAll` ← أحداث `opreport` و`opdone` بصيغة `"<op>|<نص>"` في /sync.
  - `load {url,title,how,ref,ua,cookie}` ← يفتح رابط (مش محتاج مشغّل شغّال).
- `GET /sync?since=&wait=` ← `{ver, st, live, events[], subsGz | subs}`: `since=0` ← `subsGz` (gzip+base64 لـ delta كامل `full=true`)، بعده `subs` = `{full:false, chunks:{"<chunk>":[Sub…]}, removed:[…]}`. يستنى لحد 25ث لو مفيش جديد.
- `GET /frame?w=&q=` ← JPEG، أو `204` لو مفيش لقطة (الموبايل يعرض البوستر).
- `GET /lib/folders?refresh=1` · `/lib/videos?folder=<key>` · `/lib/thumb?id=` (204 لو مفيش صورة؛ 403 `perm` لو صلاحية الفيديوهات ناقصة على التلفزيون).
- `GET /cfg?keys=a,b` و `POST /cfg {"set":{k:v}}`: قايمة بيضاء (`sub_* tone_* tr_* stt_* off_* wtr* + conv_dialect loop base_lang keepgoing chunk trstart ask_bg_exit`)، أي مفتاح فيه «key/token» مرفوض، وبعد الكتابة بيتنفّذ `conf.reload` على المشغّل.

## اللي لسه (عن قصد، مش نسيان)
- «المتحكّم الأول يملك التحكم والتاني قراءة فقط» (قيد 7 في المواصفة): **ماتعملش** — حاليًا أي ريموت متقرن يقدر يبعت أوامر. هيتعمل مع م2 لما يبقى فيه عميل فعلي.
- `RpcDedupTest` و`LocalBackendParityTest`: محتاجين Android/المشروع كله؛ الـdedup متغطي بس بـ`brain_curl.sh` (نفس cid مرتين).
- `/lib` مبني على `folderKey` الخام من MediaStore (مش «التوزيع الذكي» بتاع الموبايل). ده الأبسط الصح لـم3؛ لو عايز نفس تقسيمة المكتبة الذكية نعدّله هناك.

## للتجربة
1. ابني v542 ← لو فشل ابعتلي اللوج.
2. على التلفزيون: افتح فيديو. من Termux على أي جهاز على نفس الشبكة: `sh dev-tests/brain_curl.sh <ip-التلفزيون> [كود-الإقران]`. المفروض: `/sync` يرد بـ`st` فيه عنوان ومكان الفيديو، `pause/seekto/play` يشتغلوا، `/frame` ينزّل JPEG حقيقي (مش أسود)، والتكرار بنفس cid يرد من غير ما ينفّذ تاني.
3. شغّل ترجمة على التلفزيون وكرر `/sync?since=<آخر ver>&wait=10`: المفروض تيجي `subs.chunks` للباتشات اللي اتغيّرت بس.
