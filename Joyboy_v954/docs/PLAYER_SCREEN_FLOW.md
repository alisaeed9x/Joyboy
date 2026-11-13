# تدفق الشاشات (PLAYER SCREEN FLOW) — v939

> لم يتم compile ولا اتجرّب على جهاز. المخططات دي بتوصف الكود الحالي (v939) مش سلوك متأكد منه.

## 1. فتح التطبيق من اللانشر

```mermaid
flowchart TD
    L[ضغطة أيقونة اللانشر] --> S[SplashActivity]
    S --> P{PiP restore؟}
    P -->|نعم| F1[استعادة PiP + finish]
    P -->|لا| LM{MAIN + LAUNCHER/LEANBACK + بلا data؟}
    LM -->|لا| BR[SplashPresenter = YouTube Browse]
    LM -->|نعم| TV{تلفزيون؟ هاردوير أو ui_mode=tv}
    TV -->|نعم| T[TvActivity]
    TV -->|لا| M[MainActivity]
    T --> F2[finish]
    M --> F2
    BR --> F2
```

- قبل v939: `isTaskRoot()` كان بيمنع إعادة التوجيه في الضغطة الدافية، و`isRealTv()` كان
  هاردوير بس، فأي جهاز مش بيبلّغ LEANBACK كان بيقع على YouTube Browse.
- بعد v939: `isTvMode()` = هاردوير أو `ui_mode=tv` من تفضيلات `p`؛ ضغطة اللانشر بلا data
  عمرها ما بتقع على الـpresenter — لو الهدف مش قابل للحل بيتقفل التطبيق بدل ما يفتح يوتيوب.
- `Main.onCreate` و`Main.onNewIntent` بيعيدوا نفس الفحص: وضع TV + intent بلا وجهة صريحة
  (لا `tab` ولا `yt_open` ولا `EX_URL` ولا VIEW/SEND ولا `force_phone`) → `TvActivity` + `finish`.

## 2. التشغيل المصدر-واعي (فتح → تصغير → رجوع)

```mermaid
flowchart TD
    O[فتح فيديو: يوتيوب/متصفح/واتساب/محلي/رابط/قائمة] --> R[MtPlay.openMt: MtOrigin.record]
    R --> PL[MobilePlaybackActivity]
    PL --> MIN{تصغير: سحب لأسفل / زر}
    MIN --> K{MtOrigin.kind؟}
    K -->|YOUTUBE| D[دوك MeeTube الأصلي]
    K -->|BROWSER/EXTERNAL/LOCAL/DIRECT_URL/PLAYLIST| P[PiP النظام فوق التطبيق الأصلي]
    K -->|REMOTE_TV| TVP[واجهة مشغّل التلفزيون]
    D --> X[توسيع: نفس الجلسة، نفس الموضع]
    P --> X
    X --> PL
    PL --> C{إغلاق}
    C --> RT[MtWeb.onDetach ثم MtOrigin.onDetach]
    RT --> DST{وجهة المصدر}
    DST -->|BROWSER| B[BrowserActivity]
    DST -->|YOUTUBE + ret=yt| Y[Main: تاب يوتيوب]
    DST -->|LOCAL/EXTERNAL/DIRECT_URL/PLAYLIST| V[Main: تاب الفيديوهات]
    DST -->|REMOTE_TV| T2[TvActivity]
```

- `MtOrigin` بيتسجّل في `MtPlay.openMt` وبيتقرأ في `MtTranslate.onMinimize` و`MtMedia.detach`.
- `ret` من `MtWeb` (http(s) بس) له الأولوية؛ `MtOrigin` هو الاحتياطي للمصادر اللي مالهاش `ret`
  (واتساب / `content://` / محلي).
- إغلاق من غير `ret` ولا أصل مسجّل = إغلاق عادي من غير تنقّل.

## 3. التلفزيون: ريموت الموبايل → TV

```mermaid
flowchart TD
    PH[موبايل: PlayerPad / أمر صوتي] --> TL[TvLink / BrainRpc]
    TL --> G{مين الشغّال؟}
    G -->|PlayerActivity حي| LEG[مشغّل Joyboy القديم: rAct/rState/rpc]
    G -->|لا قديم + MtTvPlayer حي| MT[MtTvPlayer: act/state]
    G -->|لا حد| NP[noplayer / none]
    MT --> ST[state: الحقول المتنفذة بس]
    ST --> PH2[الموبايل بيخفي اللي مش معلن]
    TL --> FR[/frame: PixelCopy من SurfaceView بتاع MeeTube]
    TL --> SY[/sync: live = hasLive || MtTvPlayer.live]
```

- `mt_tv_player=false` (افتراضي) بيقفل بس توجيه `MtPlay` للتلفزيون للملفات المحلية/المباشرة.
  مسار يوتيوب-على-TV من v928 (ريموت) مش متغطّي بالعلم — تقفيله كان هيكسر الريموت.
- القديم بيفوز دايماً لو حي — مفيش مشغّلين شغالين في نفس الوقت.

## 4. نهاية الفيديو — CLOSE / PAUSE / ONE

```mermaid
flowchart TD
    E[نهاية الفيديو] --> PM{playbackMode}
    PM -->|0 PAUSE| S1[إيقاف، بلا التالي]
    PM -->|1 CLOSE| S2[إغلاق، بلا التالي]
    PM -->|3 ONE| S3[إعادة نفس الفيديو]
    PM -->|2 ALL| N[التالي من الطابور/الفولدر]
    N --> G{فيه التالي؟}
    G -->|نعم| NX[تشغيل التالي مرة واحدة]
    G -->|لا| E2[نهاية القائمة حسب loop]
```

- `MtMedia.onEnded` بيطبّق الحارس ده بعد فحص `endOfVideoSleep`. قبل v939 كان فيه خطر
  إن CLOSE/PAUSE يفتحوا التالي بالغلط — الحارس بيمنعه صراحة.
