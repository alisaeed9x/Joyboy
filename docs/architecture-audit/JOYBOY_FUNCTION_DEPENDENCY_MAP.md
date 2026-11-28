# 🕸 JOYBOY FUNCTION DEPENDENCY MAP

> HEAD `d3169383` (v1391). تحليل static heuristic: النداءات اتطلعت بمطابقة أسماء الدوال داخل أجسام الدوال (بعد تجريد الكومنتات والسترنجات). **العلاقات الديناميكية (startActivity/Intents/Handler/Reflection) مش ممثلة هنا بالكامل** — اتعلّمت في الفجوات. In-degree = عدد الدوال المميزة اللي بتنده الدالة. Out-degree = عدد الدوال المعروفة المميزة اللي الدالة بتندهها.

- دوال مفهرسة: **7026** (من 353 ملف)
- علاقات مباشرة مكتشفة: **8996**

## أعلى 60 دالة in-degree (الأكثر اتصالًا — hubs)

| # | الدالة | الملف:السطر | in-degree | out-degree |
|---|---|---|---|---|
| 1 | `setNegativeButton@232` | Glass.kt:232 | 137 | 0 |
| 2 | `setPositiveButton@231` | Glass.kt:231 | 83 | 0 |
| 3 | `setMessage@227` | Glass.kt:227 | 73 | 0 |
| 4 | `setItems@229` | Glass.kt:229 | 63 | 0 |
| 5 | `button@65` | Ui.kt:65 | 57 | 3 |
| 6 | `pressable@120` | Glass.kt:120 | 36 | 1 |
| 7 | `setView@228` | Glass.kt:228 | 36 | 0 |
| 8 | `lines@20` | FlowRow.kt:20 | 27 | 0 |
| 9 | `allMainKeys@249` | Core.kt:249 | 26 | 0 |
| 10 | `into@91` | TvDirect.kt:91 | 26 | 3 |
| 11 | `findAll@190` | CastServer.kt:190 | 24 | 4 |
| 12 | `shRow@3228` | Player.kt:3228 | 24 | 0 |
| 13 | `bodyText@25` | LinkHttp.kt:25 | 23 | 0 |
| 14 | `noHint@39` | Ui.kt:39 | 23 | 0 |
| 15 | `quiet@129` | MusicUi.kt:129 | 23 | 0 |
| 16 | `lang@89` | Offline.kt:89 | 22 | 0 |
| 17 | `ensureConnected@345` | RemoteClient.kt:345 | 22 | 1 |
| 18 | `shHead@3216` | Player.kt:3216 | 21 | 0 |
| 19 | `beginTranslate@2747` | Player.kt:2747 | 20 | 22 |
| 20 | `cStart@1019` | Engine.kt:1019 | 20 | 0 |
| 21 | `updateTr@2567` | Player.kt:2567 | 19 | 1 |
| 22 | `showHome@3021` | TvHome.kt:3021 | 18 | 35 |
| 23 | `pipTask@9118` | Player.kt:9118 | 18 | 0 |
| 24 | `titleOf@97` | Recents.kt:97 | 18 | 0 |
| 25 | `postJson@374` | RemoteClient.kt:374 | 18 | 0 |
| 26 | `addBack@381` | TrWizard.kt:381 | 17 | 1 |
| 27 | `override@22` | SongId.kt:22 | 17 | 0 |
| 28 | `setType@44` | mtui/SeekThumb.java:44 | 16 | 0 |
| 29 | `setNeutralButton@233` | Glass.kt:233 | 16 | 0 |
| 30 | `redo@906` | Engine.kt:906 | 16 | 5 |
| 31 | `currentDur@1200` | Engine.kt:1200 | 16 | 1 |
| 32 | `shChip@3250` | Player.kt:3250 | 16 | 0 |
| 33 | `query@19` | SongLinks.kt:19 | 15 | 0 |
| 34 | `openTab@812` | YtProUi.kt:812 | 15 | 14 |
| 35 | `lastTv@111` | RemoteClient.kt:111 | 15 | 1 |
| 36 | `ripple@63` | YtSkin.kt:63 | 15 | 0 |
| 37 | `loggedIn@48` | YtAccount.kt:48 | 15 | 0 |
| 38 | `getText@399` | RemoteClient.kt:399 | 14 | 1 |
| 39 | `pipNow@4393` | Player.kt:4393 | 14 | 0 |
| 40 | `redo@366` | TrLibUi.kt:366 | 14 | 6 |
| 41 | `hrow@2331` | TvHome.kt:2331 | 14 | 0 |
| 42 | `sectionTitle@2337` | TvHome.kt:2337 | 13 | 2 |
| 43 | `foreign@1066` | Core.kt:1066 | 13 | 3 |
| 44 | `input@58` | Ui.kt:58 | 13 | 0 |
| 45 | `onTap@537` | TvModeFab.kt:537 | 13 | 3 |
| 46 | `snapshot@277` | Core.kt:277 | 13 | 9 |
| 47 | `redraw@321` | CareKit.kt:321 | 13 | 0 |
| 48 | `onActivityPaused@96` | mtui/SheetGlass.java:96 | 12 | 0 |
| 49 | `chanId@43` | YtRss.kt:43 | 12 | 0 |
| 50 | `clickable@34` | IrRemoteView.kt:34 | 12 | 2 |
| 51 | `onActivityCreated@88` | mtui/SheetGlass.java:88 | 12 | 0 |
| 52 | `onActivityStopped@100` | mtui/SheetGlass.java:100 | 12 | 0 |
| 53 | `onActivityResumed@81` | mtui/SheetGlass.java:81 | 12 | 0 |
| 54 | `onActivityDestroyed@108` | mtui/SheetGlass.java:108 | 12 | 0 |
| 55 | `fmtMs@7894` | Player.kt:7894 | 12 | 0 |
| 56 | `toastMsg@293` | LibraryUi.kt:293 | 12 | 0 |
| 57 | `onActivityStarted@92` | mtui/SheetGlass.java:92 | 12 | 0 |
| 58 | `onActivitySaveInstanceState@104` | mtui/SheetGlass.java:104 | 12 | 0 |
| 59 | `shChipLp@3256` | Player.kt:3256 | 12 | 0 |
| 60 | `isDone@1197` | Engine.kt:1197 | 12 | 0 |

## أعلى 40 دالة out-degree (الأكثر نداءً — orchestrators)

| # | الدالة | الملف:السطر | out-degree | in-degree |
|---|---|---|---|---|
| 1 | `rAct@638` | Player.kt:638 | 61 | 1 |
| 2 | `tvInitBar@780` | Player.kt:780 | 58 | 0 |
| 3 | `makeWebView@817` | Browser.kt:817 | 50 | 2 |
| 4 | `modeSettings@4140` | Player.kt:4140 | 39 | 1 |
| 5 | `initEngine@6928` | Player.kt:6928 | 36 | 3 |
| 6 | `buildPlayer@6614` | Player.kt:6614 | 36 | 2 |
| 7 | `showHome@3021` | TvHome.kt:3021 | 35 | 18 |
| 8 | `trSettings@3613` | Player.kt:3613 | 35 | 8 |
| 9 | `build@3617` | Player.kt:3617 | 35 | 0 |
| 10 | `route@219` | TvLink.kt:219 | 32 | 0 |
| 11 | `makeTrRow@2896` | Player.kt:2896 | 28 | 0 |
| 12 | `applyChunk@2913` | Engine.kt:2913 | 27 | 1 |
| 13 | `rState@128` | Player.kt:128 | 26 | 2 |
| 14 | `translateMenu@3487` | Player.kt:3487 | 24 | 7 |
| 15 | `beginTranslate@2747` | Player.kt:2747 | 22 | 20 |
| 16 | `showPanel@957` | BrowserSaver.kt:957 | 22 | 3 |
| 17 | `stepHub@959` | TrWizard.kt:959 | 22 | 2 |
| 18 | `topBar@561` | TvHome.kt:561 | 22 | 0 |
| 19 | `translateContextual@2829` | Engine.kt:2829 | 22 | 2 |
| 20 | `showMenu@1466` | Browser.kt:1466 | 22 | 1 |
| 21 | `fetch@469` | Lyrics.kt:469 | 21 | 0 |
| 22 | `fillStorageView@2634` | TvHome.kt:2634 | 21 | 11 |
| 23 | `render@643` | LibraryUi.kt:643 | 20 | 0 |
| 24 | `buildSettings@1116` | FtUi.kt:1116 | 20 | 3 |
| 25 | `showSettings@1774` | Browser.kt:1774 | 20 | 2 |
| 26 | `showStats@779` | BrowserSaver.kt:779 | 18 | 3 |
| 27 | `langPopup@8589` | Player.kt:8589 | 18 | 3 |
| 28 | `trEngineRows@3461` | Player.kt:3461 | 18 | 1 |
| 29 | `trMainClick@3523` | Player.kt:3523 | 17 | 3 |
| 30 | `tvSubDialog@978` | Player.kt:978 | 17 | 4 |
| 31 | `openSubs@1157` | YtProUi.kt:1157 | 17 | 6 |
| 32 | `translateGroup@2384` | Engine.kt:2384 | 17 | 1 |
| 33 | `holeFill@3914` | Engine.kt:3914 | 17 | 1 |
| 34 | `buildPromptCore@1952` | Engine.kt:1952 | 17 | 0 |
| 35 | `render@2659` | TvHome.kt:2659 | 16 | 0 |
| 36 | `moreMenu@950` | TvHome.kt:950 | 16 | 3 |
| 37 | `mainMenu@889` | TvHome.kt:889 | 16 | 1 |
| 38 | `subSelectSheet@3420` | Player.kt:3420 | 15 | 3 |
| 39 | `trStartSheet@3561` | Player.kt:3561 | 15 | 4 |
| 40 | `refresh@165` | TvPlayerBar.kt:165 | 15 | 0 |

## الدوال اليتيمة (in-degree = 0 و out-degree = 0، مرشحة dead code — باستثناء entry points الديناميكية)

عدد الدوال صفرية الاتصال: **1956** (منها **96** بجسم ≥ 15 سطر — أهم المرشحين للمراجعة)

| الدالة | الملف:السطور | حجم الجسم |
|---|---|---|
| `fetch@74` | YtStoryboard.kt:74-143 | 69 |
| `quickPanel@86` | TvSkinHub.kt:86-135 | 49 |
| `renderAll@30` | MusicWidget.kt:30-76 | 46 |
| `place@264` | Karaoke.kt:264-305 | 41 |
| `loop@334` | AudioBridge.kt:334-373 | 39 |
| `glyph@15` | FtViews.kt:15-53 | 38 |
| `render@53` | Notes.kt:53-90 | 37 |
| `sheet@233` | PlayerPad.kt:233-270 | 37 |
| `track@26` | SwipeCard.kt:26-63 | 37 |
| `transcode@308` | GhAudio.kt:308-344 | 36 |
| `parse@140` | YtRss.kt:140-175 | 35 |
| `addOverlay@262` | LiveCaption.kt:262-297 | 35 |
| `fit@20` | SubAlign.kt:20-54 | 34 |
| `kick@133` | TvScrub.kt:133-167 | 34 |
| `mergeSegments@214` | HybridVis.kt:214-247 | 33 |
| `search@419` | IrCore.kt:419-452 | 33 |
| `build@79` | TvFiles.kt:79-111 | 32 |
| `findRuns@24` | Coverage.kt:24-56 | 32 |
| `writeRange@129` | LinkHttp.kt:129-160 | 31 |
| `mux@303` | Downloader.kt:303-334 | 31 |
| `build@384` | MusicService.kt:384-414 | 30 |
| `dominant@212` | TvTiles.kt:212-242 | 30 |
| `applyPrivLook@1037` | Browser.kt:1037-1066 | 29 |
| `feed@1020` | Core.kt:1020-1048 | 28 |
| `build@104` | RemoteService.kt:104-132 | 28 |
| `clean@325` | AudioCore.kt:325-351 | 26 |
| `load@251` | NetLog.kt:251-277 | 26 |
| `clips@58` | FastId.kt:58-83 | 25 |
| `doConnect@226` | TvWifi.kt:226-251 | 25 |
| `http@44` | WallSources.kt:44-69 | 25 |
| `gtCall@187` | SubFileTr.kt:187-212 | 25 |
| `noiseFilter@248` | HybridVis.kt:248-272 | 24 |
| `toast@110` | LinkBubble.kt:110-134 | 24 |
| `compute@438` | OrbPlayer.kt:438-462 | 24 |
| `refresh@607` | FileManager.kt:607-631 | 24 |
| `save@226` | NetLog.kt:226-250 | 24 |
| `begin@309` | AudioBridge.kt:309-333 | 24 |
| `runDeep@569` | FileManager.kt:569-593 | 24 |
| `press@201` | MtGlass.kt:201-225 | 24 |
| `compute@27` | CareMetrics.kt:27-50 | 23 |
| `publish@173` | BrowserDl.kt:173-196 | 23 |
| `render@334` | Main.kt:334-357 | 23 |
| `parse@209` | Icons.kt:209-232 | 23 |
| `norm@19` | OffLang.kt:19-40 | 21 |
| `refresh@52` | StatusStrip.kt:52-73 | 21 |
| `header@65` | TvSkinBrand.kt:65-86 | 21 |
| `load@97` | CareApps.kt:97-118 | 21 |
| `switchBar@88` | MediaSwitch.kt:88-108 | 20 |
| `load@40` | TrLib.kt:40-60 | 20 |
| `peek@565` | IconPackCore.kt:565-585 | 20 |
| `linesFor@120` | SubAlign.kt:120-140 | 20 |
| `answer@134` | IrWizardCore.kt:134-153 | 19 |
| `listRow@1474` | TvHome.kt:1474-1493 | 19 |
| `backdrop@54` | TvShell.kt:54-73 | 19 |
| `group@101` | OffLang.kt:101-120 | 19 |
| `drawStrip@157` | SeekFilmstrip.kt:157-176 | 19 |
| `place@73` | VidMark.kt:73-91 | 18 |
| `download@63` | PublicApis.kt:63-81 | 18 |
| `dominant@403` | OrbPlayer.kt:403-421 | 18 |
| `addBar@48` | PipSubBar.kt:48-66 | 18 |
| `card@1381` | TvHome.kt:1381-1399 | 18 |
| `update@318` | Notice.kt:318-336 | 18 |
| `parse@696` | YtWatch.kt:696-714 | 18 |
| `feed@72` | EqView.kt:72-90 | 18 |
| `ensureBtn@88` | RotateHint.kt:88-105 | 17 |
| `sentSearch@5028` | Player.kt:5028-5045 | 17 |
| `openAutoStartSettings@1351` | Main.kt:1351-1368 | 17 |
| `groqModels@57` | GhAudio.kt:57-74 | 17 |
| `install@139` | VidHook.kt:139-156 | 17 |
| `hkdf@91` | FtCrypto.kt:91-108 | 17 |
| `bitmap@179` | QrCode.kt:179-196 | 17 |
| `pairsOf@141` | SubAlign.kt:141-158 | 17 |
| `column@805` | ControllerPad.kt:805-822 | 17 |
| `one@142` | CareApps.kt:142-159 | 17 |
| `parse@41` | PublicApis.kt:41-57 | 16 |
| `fetch@857` | YtNp.kt:857-873 | 16 |
| `looksHls@410` | AudioIO.kt:410-426 | 16 |
| `durationSec@255` | GhAudio.kt:255-271 | 16 |
| `fit@136` | Speech.kt:136-152 | 16 |
| `openSheet@596` | ControllerPad.kt:596-612 | 16 |

> ⚠️ in-degree = 0 **مش دليل حذف**: مكونات Manifest (activities/services/receivers/widgets)، callbacks النظام (onClick/onCreate)، وواجهات AIDL/JS بتتنده ديناميكيًا. القايمة دي مرشحين للفحص بس.
