# 🗺 JOYBOY COMPLETE FLOW MAP

> HEAD `d3169383` (v13.91). خرائط Mermaid منفصلة لكل مسار (الخريطة الواحدة مش هتسع). المعرفات ثابتة: `F:<ملف>:<سطر>`. العلاقات المتقطعة = ديناميكية (Intent/Handler/Callback).

## A. بدء التطبيق

```mermaid
flowchart TD
    A[JoyApp.onCreate<br/>F:JoyApp.kt] --> B[MainActivity<br/>F:Main.kt]
    B --> C[TabNav / BottomNav<br/>F:TabNav.kt / BottomNav.kt]
    C --> D[التبويبات: videos · music · youtube · tasks · settings<br/>F:Main.kt:602-851]
    D --> E[بناء الإعدادات من R rows + tab builders<br/>F:Settings.kt:23,503 · Main.kt]
    B -.Intent.-> LA[LaunchBrowserActivity /<br/>LaunchYoutubeActivity /<br/>TvHomeAlias - LEANBACK]
    BR[BootReceiver] -.system boot.-> BG[BgService / KeepAliveService]
```

- مكونات Manifest: 17 activity + 16 service + 7 receiver + 1 provider (AndroidManifest.xml).
- UiWatchdog (Main.kt:144): مراقب تهنيج الواجهة — مصدر لوجات ⛔ اللي اتبنى عليها إصلاح v1389.

## B. مسار ترجمة فيديو (يوتيوب/متصفح/ملفات)

```mermaid
flowchart TD
    U[ضغطة «ترجم»] --> BT[Player.beginTranslate<br/>F:Player.kt:2747]
    BT -->|remote| RB[remoteBegin ← تلفزيون]
    BT --> EN[Engine.load<br/>F:Engine.kt:993]
    EN --> PP[prepareInner<br/>F:Engine.kt:1481]
    PP --> AU[exportBatchAudio<br/>F:Engine.kt:852]
    AU --> STT{مصدر التفريغ}
    STT -->|stt_engine=whisper| WH[WhisperStt أوفلاين<br/>F:Stt.kt:36 · whisper.cpp]
    STT -->|mt_tr_mode=groq| GQ[Groq سحابي]
    STT -->|احتياطي| GM[Gemini]
    WH & GQ & GM --> NORM[normAsr<br/>F:Engine.kt:351]
    NORM --> TT[translateTick ← translateGroup<br/>F:Engine.kt:2355,2384]
    TT --> PR[buildPrompt + promptExtras<br/>F:Engine.kt:1913,343]
    PR --> LLM[askJson<br/>F:Engine.kt:2796]
    LLM --> AP[applyChunk / applyGroupTranslated<br/>F:Engine.kt:2913,2467]
    AP --> GL[enforceGlossary<br/>F:Engine.kt:286]
    GL --> CV[maybeConvert تحويل لهجة<br/>F:Engine.kt:768]
    CV --> REN[SubtitleView على المشغّل<br/>F:SubtitleView.kt]
    TT -.كاش سطور.-> LC[lineCache<br/>F:Engine.kt:404-444]
    TT -.ثغرات.-> HF[holeFill<br/>F:Engine.kt:3914]
    TT -.جودة.-> Q[refinePass / crossCheck<br/>F:Engine.kt:3368,2194]
```

التفاصيل الكاملة في JOYBOY_TRANSLATION_ENGINE_FLOW.md.

## C. مسار التحديث (OTA عبر GitHub)

```mermaid
flowchart LR
    GH[GitHub Releases<br/>build-NNN] --> UP[Upd.kt — فحص في الخلفية]
    UP --> US[UpdateSheet — بطاقة التحديث]
    US --> UD[UpdDlService — تنزيل APK]
    UD --> IN[تثبيت Android PackageInstaller]
    CI[مراقب CI: GhCi/GhCiJob/GhCiUi<br/>gh_watch + أبناء شرطيون] -.إشعار فشل البيلد.-> US
```

- الصفوف الشرطية (v1392): gh_notify/gh_prelog/gh_tv_auto تحت gh_watch (GhCiUi.kt:140-162).
- القاعدة الذهبية: versionCode = وقت البناء بالدقايق (app/build.gradle.kts:23-24) — أي بيلد أحدث يتثبت فوق القديم بدون فقد بيانات.

## D. مسار الالتقاط الحي (Live Caption)

```mermaid
flowchart TD
    LW[LiveRequestActivity / ClipOverlayService] --> LL[LiveLogic]
    LL --> LC2[LiveCaptionService — التقاط صوت النظام]
    LC2 --> STT2[تفريغ + ترجمة حية - نفس Stt/Engine]
    STT2 --> OV[Overlay فوق التطبيقات - LiveCaptionService]
    LW2[LinkWatchService] -.مراقبة روابط.-> LL
```

- إعدادات `lw_*` (المالك: تبويب capture) — الأب `lw_web_off` وأبناؤه lw_toast_off/lw_hist_off (شرطيون من v1392، Settings.kt R rows + Main.kt).

## E. مسار التلفزيون

```mermaid
flowchart TD
    TV[TvActivity / TvHomeAlias] --> TH[TvHome.showHome<br/>F:TvHome.kt:3021]
    TH --> TS[TvSkin* — 7 ملفات سكنات]
    TH --> TP[TvPlayerBar / TvDirect — تشغيل]
    TP --> CS[CastServer — بث للتلفزيون<br/>SubSidecar ~186-253]
    M[الموبايل] -.RemoteClient.cmd.-> RS[RemoteService على التلفزيون]
    M -.tvOv تجاوز الإعدادات.-> TE[TvEdit — نسخة التلفزيون من الإعدادات]
    IR[IrWizard/IrProfiles — قواعد بيانات IR<br/>assets/irdb_*.txt] --> RM[RemoteActivity — ريموت]
```

## F. مسار نقل الملفات والربط

```mermaid
flowchart LR
    FT[FtUi/FtXfer — نقل بين الأجهزة<br/>FtCrypto + FtIdentity] <--> LN[Link* — جلسات ربط<br/>LinkCore/LinkSession/LinkHttp]
    TR[Transfer/TransferUi] <--> FT
    TVF[TvFilesActivity] <--> FT
```

## G. مسار الموسيقى

```mermaid
flowchart TD
    MS[MusicService] --> MU[MusicUi]
    SC[SongCatchService / SongNotifListener] --> SI[SongId — song_auto_id]
    MU --> LY[Lyrics/LyricsApi/LyricsNorm — الكلمات]
    LY --> KA[Karaoke — عرض متزامن]
    LY -.شرطي.-> SW{music_auto_lyr شغال؟}
```

## H. مسار الصيانة (Care)

CareKit كمنسق + CareApps/CareClean/CareSecurity/CareBattery/CareDevice/CareSpeed/CarePrivacy (واجهات *Ui منفصلة) + CareMetrics/CareRings/CareScanExtra — 14 ملف، أقل عائلة ارتباطًا بالباقي (ميزة معمارية جيدة).

## علاقات ديناميكية حرجة (غير ظاهرة في جراف النداء الساكن)

| العلاقة | النوع | الدليل |
|---|---|---|
| Manifest components ← نظام أندرويد | Intent/Launcher | AndroidManifest.xml (41 مكون) |
| Widgets (7) ← AppWidgetManager | Broadcast | LiveWidget/SongWidget/PrayerWidget/MusicWidget/TasksWidget/ActionsWidget |
| RemoteClient.cmd → RemoteService | شبكة/أوامر نصية | TvEdit.kt: (open_settings) |
| WebView JS bridge في المتصفح | @JavascriptInterface | Browser.kt + VidHook/Sniffer |
| UiWatchdog → LogStore | مراقب خيط الواجهة | Main.kt:144 |
| BootReceiver → خدمات الخلفية | BOOT_COMPLETED | AndroidManifest.xml |
| host.log/notice في Engine | callback عبر واجهة host | Engine.kt (متفرقات) |
