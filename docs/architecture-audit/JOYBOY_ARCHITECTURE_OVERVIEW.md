# 🏛 JOYBOY ARCHITECTURE OVERVIEW

> HEAD `d3169383` (v13.91، build-735، 2026-11-28). تحليل قراءة فقط — مفيش أي تعديل في الكود.

## ما هو Joyboy؟

تطبيق أندرويد أصلي (Kotlin + Java، Views تقليدية — بدون Compose) اسمه البرمجي `com.tttt.joyboy` (الحزمة `com.tttt.subtitler`). الوظيفة الأساسية: ترجمة فيديوهات (يوتيوب/متصفح/ملفات) بالذكاء الاصطناعي — تفريغ صوتي (whisper.cpp أوفلاين أو Groq/Gemini سحابي)، ترجمة على دفعات (batches) مع مسرد مصطلحات مقفول وتحويل لهجة، وعرض الترجمة على مشغّل مدمج. حواليه منظومة كاملة: متصفح مدمج، مشغّل، مكتبة، موسيقى وكلمات، التقاط حي (live caption)، تحكم تلفزيون/ريموت IR، نقل ملفات بين الأجهزة، صيانة الجهاز، مهام، ودجات.

## أرقام عامة

| مقياس | قيمة |
|---|---|
| ملفات الكود الرئيسية (app/.../subtitler) | 353 (334 Kotlin + 19 Java) |
| إجمالي سطور الكود | 110,252 |
| دوال مفهرسة | 7,026 |
| علاقات نداء مباشرة مكتشفة (heuristic) | 8,996 |
| مفاتيح إعدادات (Cfg) | 289 |
| مكونات Manifest | 17 activity + 16 service + 7 receiver + 1 provider (AndroidManifest.xml) |
| minSdk / targetSdk | 26 / 34 (app/build.gradle.kts:23) |
| التوقيع/البيلد | CI: apply-patch.yml ← patches/*.zip ثم build.yml ← release build-NNN |
| Native | whisper.cpp + substem + onnxruntime عبر CMake (app/build.gradle.kts:26)، arm64 أساسي |
| وحدات جانبية | meetube/ (مكتبة جاهزة)، desktop/، tools/، dev-tests/، plugin-aniyomi/، skipii/ |

## نقاط الدخول (LAUNCHER)

- `.MainActivity` — الرئيسية العادية (موبايل)
- `.LaunchBrowserActivity` — فتح مباشر للمتصفح
- `.LaunchYoutubeActivity` — فتح مباشر ليوتيوب
- `.TvHomeAlias` — LEANBACK_LAUNCHER (تلفزيون)
- `android.speech.RecognitionService` — خدمة تعرف صوتي نظامية

## الخريطة الوظيفية (22 عائلة — تصنيف heuristic بالبادئة)

| العائلة | ملفات | سطور | دوال | أمثلة |
|---|---|---|---|---|
| المشغّل (Player) | 19 | 14,458 | 893 | ControllerPad.kt, DlQuality.kt, MediaOps.kt, MediaSwitch.kt |
| المحرك والترجمة (Engine) | 33 | 13,334 | 915 | BgTranslate.kt, BrainCore.kt, BrainRpc.kt, Engine.kt |
| التلفزيون (TV/Cast) | 40 | 11,997 | 805 | CastCache.kt, CastServer.kt, SegDl.kt, SendToTv.kt |
| يوتيوب (YouTube) | 22 | 8,746 | 598 | YoutubeUi.kt, YtAccount.kt, YtAudio.kt, YtBackend.kt |
| نقل الملفات (Transfer) | 20 | 8,124 | 438 | BatchExport.kt, DataCapsule.kt, DeskPush.kt, Downloader.kt |
| المتصفح (Browser) | 16 | 6,712 | 473 | BrSet.kt, Browser.kt, BrowserDl.kt, BrowserSaver.kt |
| النواة والإعدادات (Core/Config) | 34 | 6,119 | 393 | Backup.kt, BackupCodec.kt, BootReceiver.kt, Changelog.kt |
| واجهة أساسية (UI Core) | 29 | 5,694 | 354 | AppWall.kt, Blur.kt, DragTrash.kt, EndOverlay.kt |
| الموسيقى والكلمات (Music) | 14 | 5,190 | 358 | GhAudio.kt, Karaoke.kt, Lyrics.kt, LyricsApi.kt |
| أخرى | 20 | 4,922 | 256 | BotWallPrefsStore.java, DeviceUi.kt, EmbedIdentityPrefsStore.java, GhCi.kt |
| التنقل والرئيسية (Nav/Main) | 12 | 3,984 | 205 | BottomNav.kt, LaunchShortcuts.kt, Main.kt, ModePrompts.kt |
| الريموت والأشعة (Remote/IR) | 15 | 3,735 | 260 | BtScan.kt, IrCatalogUi.kt, IrCore.kt, IrProfiles.kt |
| الصيانة (Care) | 15 | 2,721 | 156 | CareApps.kt, CareAppsUi.kt, CareBatteryUi.kt, CareClean.kt |
| MeeTube UI | 11 | 2,470 | 195 | LocalMt.kt, MtEngine.java, MtEnhance.kt, MtFeed.kt |
| المكتبة (Library) | 9 | 2,376 | 169 | LibraryBackend.kt, LibraryCore.kt, LibraryUi.kt, PubHistory.kt |
| التفريغ والصوت (Audio/STT) | 4 | 1,863 | 92 | AudioBridge.kt, AudioCore.kt, AudioEnc.kt, AudioIO.kt |
| الترجمة النصية والملفات (Subtitle) | 10 | 1,856 | 122 | SrtWriter.kt, SubAlign.kt, SubDual.kt, SubHunt.kt |
| الشبكة (Network) | 7 | 1,413 | 116 | NetGate.kt, NetLedger.kt, NetLog.kt, NetStatsUi.kt |
| الالتقاط الحي (Live) | 6 | 1,402 | 68 | ClipHistory.kt, ClipOverlay.kt, ClipWatch.kt, CoSurf.kt |
| الربط بين الأجهزة (Link) | 8 | 1,320 | 63 | LinkBubble.kt, LinkCore.kt, LinkHttp.kt, LinkPhone.kt |
| الودجات (Widgets) | 4 | 1,099 | 58 | ActionsWidget.kt, PrayerTimes.kt, PrayerWidget.kt, Widgets.kt |
| المهام (Tasks) | 5 | 717 | 39 | TaskRing.kt, TaskService.kt, Tasks.kt, TasksFab.kt |

## أكبر 15 ملف (مراكز الثقل — god-file suspects)

| الملف | سطور | دوال |
|---|---|---|
| Player.kt | 9,152 | 605 |
| Engine.kt | 4,067 | 242 |
| TvHome.kt | 3,610 | 274 |
| Browser.kt | 3,491 | 249 |
| YtProUi.kt | 2,401 | 142 |
| YtWatch.kt | 2,290 | 139 |
| Main.kt | 2,201 | 108 |
| Core.kt | 1,575 | 111 |
| MusicUi.kt | 1,531 | 126 |
| LibraryUi.kt | 1,466 | 110 |
| PlayerPad.kt | 1,339 | 87 |
| FileManager.kt | 1,239 | 65 |
| FtUi.kt | 1,172 | 77 |
| TrWizard.kt | 1,112 | 90 |
| BrowserSaver.kt | 1,072 | 96 |

## مبادئ معمارية موثقة من الكود

1. **EngineBackend كواجهة عزل** (EngineBackend.kt:14): «الواجهة اللي المشغّل بيكلّم بيها المحرك» — المنفّذ الحالي LocalEngine بيفوّض لـ Engine الحقيقي، والتصميم مهيأ لمنفّذ بعيد (RemoteEngine للتلفزيون).
2. **Cfg مركزي** (Core.kt:171): كل الإعدادات في SharedPreferences `"p"` عبر CfgCodec + TrNorm — 128 ملف بيستخدمه.
3. **tvOv**: نسخة تجاوز للتلفزيون من الإعدادات المشتركة (Cfg.tvOv، TvEdit.kt).
4. **طبقة UI موحدة** (Ui.kt): switchRow/chips/section/card/slider — وMtGlass variant زجاجي (mtui/).
5. **التصميم flat**: 336 ملف Kotlin في حزمة واحدة بدون sub-packages — التقسيم بالبادئات مش بالحزم.
