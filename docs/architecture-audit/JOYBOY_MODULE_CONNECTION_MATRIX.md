# 🔗 JOYBOY MODULE CONNECTION MATRIX

> HEAD `d3169383`. التصنيف heuristic بالبادئة (القواعد كاملة تحت). الخلية (صف، عمود) = عدد علاقات النداء المباشرة المكتشفة من دوال الموديول الصف لدوال الموديول العمود. العلاقات الديناميكية (Intent/Handler/JS bridge) مش محسوبة هنا — ديناميكي-only متعلّم في GAP_ANALYSIS.

## قواعد التصنيف (بادئة اسم الملف)

- **المحرك والترجمة (Engine)**: `^(Engine|EngineBackend|TrLib|TrLibUi|TrProfile|TrWizard|BgTranslate|FastTr|FastId|ExtTranslate|ExtLlm|FreeLlm|PublicApis|Models|ModelsUi|ModelStore|ModelWatch|Offline|Offload|OffLang|SourceMode|Prompts|PromptsUi|TermNotes|LangPicker|BrainCore|BrainRpc|HybridVis|VisualMode|Speech|Stt|VoiceClean|SubFileTr)$`
- **التفريغ والصوت (Audio/STT)**: `^(AudioBridge|AudioCore|AudioEnc|AudioIO)$`
- **الترجمة النصية والملفات (Subtitle)**: `^(Sub[A-Z]\w*|SrtWriter|SubtitleView)$`
- **المشغّل (Player)**: `^(Player|PlayerLogic|PlayerPad|PlaybackBackend|OrbPlayer|PipSubBar|PlayQueue|PlayQueueUi|PlayTarget|QueueUi|NextPanel|SeekFilmstrip|ResumeAsk|MediaSwitch|MediaOps|StreamHunt|Trim|ControllerPad|DlQuality)$`
- **يوتيوب (YouTube)**: `^(Yt\w*|YoutubeUi)$`
- **MeeTube UI**: `^(Mt\w*|mtui|LocalMt)$`
- **المتصفح (Browser)**: `^(Browser|BrowserDl|BrowserSaver|BrSet|KeyBrowser|WebIdent|WebMute|WebVideos|VidHook|VidMark|VidPick|Sniffer|PageScan|PopupGuard|HideBox|MobileDataGuard)$`
- **الالتقاط الحي (Live)**: `^(Live\w*|ClipOverlay|ClipWatch|ClipHistory|CoSurf)$`
- **التلفزيون (TV/Cast)**: `^(Tv\w*|SendToTv|CastServer|CastCache|TypeServer|SegDl)$`
- **الريموت والأشعة (Remote/IR)**: `^(Remote\w*|Ir\w*|BtScan)$`
- **نقل الملفات (Transfer)**: `^(Ft\w*|Transfer|TransferUi|Xfer|FileManager|FmFileProvider|BatchExport|DataCapsule|DeskPush|Downloader)$`
- **الربط بين الأجهزة (Link)**: `^(Link\w*|Links)$`
- **الموسيقى والكلمات (Music)**: `^(Music\w*|Lyrics\w*|Karaoke|Song\w*|RecentSongs|StemCore|GhAudio)$`
- **المكتبة (Library)**: `^(Library\w*|VideoLib|VideoPicker|VideoScan|Recents|PubHistory|PubHistoryUi)$`
- **المهام (Tasks)**: `^(Tasks|TasksUi|TasksFab|TaskService|TaskRing)$`
- **الصيانة (Care)**: `^(Care\w*)$`
- **الشبكة (Network)**: `^(Net\w*|SiteBlock)$`
- **واجهة أساسية (UI Core)**: `^(Ui|Theme|Glass|Blur|Motion|Haptic|Skin|Icon\w*|IconPack\w*|Icons|Spinner|FlowRow|HtmlUi|EqView|SmartGroup|DragTrash|Notice|StatusStrip|SwipeCard|EndOverlay|RotateHint|AppWall|SplashArt|Poster|TrSlider)$`
- **التنقل والرئيسية (Nav/Main)**: `^(Main|BottomNav|TabNav|NavAnim|NavCustom|NavOrigin|NavSnap|LaunchShortcuts|Onboarding|ModePrompts|PageSettings|UniSearch)$`
- **النواة والإعدادات (Core/Config)**: `^(Core|JoyApp|I18n|Strict|Presets|ExtrasFlags|Changelog|CrashLog|LogStore|LogArchive|LogArchiveUi|Stats|StatsUi|Coverage|Settings|KeyVault|KeyStatus|KeyDialog|Backup|BackupCodec|Lock|LockCore|PrivacyFilter|PermAsk|Upd|UpdDlService|UpdateSheet|BootReceiver|KeepAliveService|SysVol|QrCode|Notes|Store|WallSources|FastId)$`
- **الودجات (Widgets)**: `^(Widgets|ActionsWidget|MusicWidget|PrayerWidget|PrayerTimes)$`
- **أخرى**: BotWallPrefsStore.java, DeviceUi.kt, EmbedIdentityPrefsStore.java, GhCi.kt, GhCiUi.kt, GlassDrawable.java, GlassSlider.java, GlassSource.java, GlassSwitch.java, GlassView.java, Haptics.java, JoyboyPlayerHost.java, MiniDock.kt, NavLens.java, PlaybackIdentityPrefsStore.java, PlayerTrace.java, SeekThumb.java, SheetGlass.java, ThemeMode.java, UiWatchdog.kt

## المصفوفة (صف = المُستدعي، عمود = المُستدعى) — أعلى الروابط

| الموديول | يستدعي (out) | يُستدعى (in) | داخلي (نفس الموديول) |
|---|---|---|---|
| واجهة أساسية (UI Core) | 46 | 670 | 177 |
| المشغّل (Player) | 509 | 67 | 1334 |
| المحرك والترجمة (Engine) | 244 | 159 | 895 |
| التلفزيون (TV/Cast) | 247 | 150 | 990 |
| النواة والإعدادات (Core/Config) | 107 | 193 | 219 |
| نقل الملفات (Transfer) | 139 | 41 | 490 |
| المتصفح (Browser) | 116 | 36 | 641 |
| التنقل والرئيسية (Nav/Main) | 115 | 22 | 152 |
| الريموت والأشعة (Remote/IR) | 38 | 97 | 251 |
| يوتيوب (YouTube) | 85 | 46 | 618 |
| المكتبة (Library) | 57 | 62 | 171 |
| الموسيقى والكلمات (Music) | 73 | 46 | 401 |
| MeeTube UI | 54 | 56 | 123 |
| الربط بين الأجهزة (Link) | 21 | 54 | 22 |
| الترجمة النصية والملفات (Subtitle) | 27 | 35 | 83 |
| الشبكة (Network) | 10 | 43 | 93 |
| التفريغ والصوت (Audio/STT) | 16 | 15 | 61 |
| الصيانة (Care) | 23 | 4 | 136 |
| الالتقاط الحي (Live) | 17 | 8 | 35 |
| المهام (Tasks) | 7 | 3 | 22 |
| الودجات (Widgets) | 2 | 0 | 43 |

## أقوى 60 ربط بين موديولين مختلفين

| من ← إلى | عدد العلاقات |
|---|---|
| المشغّل (Player) ← المحرك والترجمة (Engine) | 136 |
| التلفزيون (TV/Cast) ← واجهة أساسية (UI Core) | 124 |
| المشغّل (Player) ← واجهة أساسية (UI Core) | 119 |
| المحرك والترجمة (Engine) ← النواة والإعدادات (Core/Config) | 106 |
| نقل الملفات (Transfer) ← واجهة أساسية (UI Core) | 73 |
| المحرك والترجمة (Engine) ← واجهة أساسية (UI Core) | 56 |
| المشغّل (Player) ← التلفزيون (TV/Cast) | 49 |
| المتصفح (Browser) ← واجهة أساسية (UI Core) | 48 |
| المشغّل (Player) ← النواة والإعدادات (Core/Config) | 47 |
| النواة والإعدادات (Core/Config) ← واجهة أساسية (UI Core) | 46 |
| التنقل والرئيسية (Nav/Main) ← واجهة أساسية (UI Core) | 39 |
| MeeTube UI ← أخرى | 38 |
| المكتبة (Library) ← واجهة أساسية (UI Core) | 36 |
| الموسيقى والكلمات (Music) ← واجهة أساسية (UI Core) | 33 |
| المشغّل (Player) ← الريموت والأشعة (Remote/IR) | 32 |
| النواة والإعدادات (Core/Config) ← أخرى | 32 |
| المشغّل (Player) ← الترجمة النصية والملفات (Subtitle) | 29 |
| نقل الملفات (Transfer) ← الريموت والأشعة (Remote/IR) | 28 |
| يوتيوب (YouTube) ← التلفزيون (TV/Cast) | 26 |
| التلفزيون (TV/Cast) ← الربط بين الأجهزة (Link) | 22 |
| يوتيوب (YouTube) ← MeeTube UI | 22 |
| التلفزيون (TV/Cast) ← أخرى | 21 |
| المحرك والترجمة (Engine) ← الشبكة (Network) | 20 |
| التلفزيون (TV/Cast) ← المكتبة (Library) | 19 |
| الصيانة (Care) ← واجهة أساسية (UI Core) | 18 |
| الريموت والأشعة (Remote/IR) ← واجهة أساسية (UI Core) | 18 |
| المتصفح (Browser) ← التلفزيون (TV/Cast) | 17 |
| التنقل والرئيسية (Nav/Main) ← المشغّل (Player) | 17 |
| المشغّل (Player) ← المكتبة (Library) | 17 |
| التلفزيون (TV/Cast) ← المشغّل (Player) | 17 |
| المشغّل (Player) ← أخرى | 16 |
| المشغّل (Player) ← يوتيوب (YouTube) | 16 |
| واجهة أساسية (UI Core) ← MeeTube UI | 14 |
| يوتيوب (YouTube) ← الموسيقى والكلمات (Music) | 14 |
| يوتيوب (YouTube) ← واجهة أساسية (UI Core) | 14 |
| أخرى ← واجهة أساسية (UI Core) | 13 |
| واجهة أساسية (UI Core) ← أخرى | 13 |
| المتصفح (Browser) ← نقل الملفات (Transfer) | 12 |
| التنقل والرئيسية (Nav/Main) ← يوتيوب (YouTube) | 12 |
| المتصفح (Browser) ← أخرى | 10 |
| نقل الملفات (Transfer) ← أخرى | 10 |
| النواة والإعدادات (Core/Config) ← التلفزيون (TV/Cast) | 10 |
| المحرك والترجمة (Engine) ← التنقل والرئيسية (Nav/Main) | 9 |
| الموسيقى والكلمات (Music) ← النواة والإعدادات (Core/Config) | 9 |
| واجهة أساسية (UI Core) ← التلفزيون (TV/Cast) | 9 |
| التنقل والرئيسية (Nav/Main) ← النواة والإعدادات (Core/Config) | 9 |
| المشغّل (Player) ← الموسيقى والكلمات (Music) | 9 |
| المحرك والترجمة (Engine) ← الموسيقى والكلمات (Music) | 8 |
| المحرك والترجمة (Engine) ← التلفزيون (TV/Cast) | 8 |
| المحرك والترجمة (Engine) ← المشغّل (Player) | 8 |
| التلفزيون (TV/Cast) ← النواة والإعدادات (Core/Config) | 8 |
| الالتقاط الحي (Live) ← المتصفح (Browser) | 8 |
| نقل الملفات (Transfer) ← الربط بين الأجهزة (Link) | 8 |
| المكتبة (Library) ← MeeTube UI | 8 |
| MeeTube UI ← واجهة أساسية (UI Core) | 8 |
| المشغّل (Player) ← الربط بين الأجهزة (Link) | 8 |
| المشغّل (Player) ← المتصفح (Browser) | 8 |
| المشغّل (Player) ← التفريغ والصوت (Audio/STT) | 8 |
| التلفزيون (TV/Cast) ← الريموت والأشعة (Remote/IR) | 8 |
| التفريغ والصوت (Audio/STT) ← واجهة أساسية (UI Core) | 7 |
