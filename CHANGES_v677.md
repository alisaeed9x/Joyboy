# مرحلة 1 — تنضيف الميت (من غير تغيير سلوك)

متحقق منه: kotlinc على الشجرة كلها = 616 خطأ (نفس خط الأساس، كلهم مكتبات ناقصة) و**صفر أخطاء جديدة**. ما اتبناش Gradle ولا اتجرّب على جهاز.

## اتمسح (38)
- Browser.kt : BrowserActivity.sendPageToTv (سطور 2039-2045 في الأصل)
- ControllerPad.kt : class VolumeSlider (سطور 430-477 في الأصل)
- DeviceUi.kt : DeviceUi.cycleNext (سطور 57-59 في الأصل)
- Engine.kt : Engine.retranslateOne (سطور 2557-2580 في الأصل)
- FtPeers.kt : FtPeers.ping (سطور 229-235 في الأصل)
- FtPicker.kt : FtPicker.selectAllShown (سطور 425-430 في الأصل)
- FtUi.kt : FileTransferActivity.typeIcon (سطور 245-245 في الأصل)
- HtmlUi.kt : class DualVolBar (سطور 495-510 في الأصل)
- Karaoke.kt : Karaoke.segments (سطور 85-89 في الأصل)
- Main.kt : MainActivity.tvShellGo (سطور 1787-1797 في الأصل)
- Player.kt : PlayerActivity.geminiPick (سطور 2378-2390 في الأصل)
- Player.kt : PlayerActivity.googleTranslateNow (سطور 5814-5818 في الأصل)
- Player.kt : PlayerActivity.sfNoneDialog (سطور 2015-2020 في الأصل)
- Player.kt : PlayerActivity.softAsk (سطور 2022-2051 في الأصل)
- Player.kt : PlayerActivity.softAvailable (سطور 2000-2013 في الأصل)
- PrayerTimes.kt : PrayerTimes.bgFor (سطور 100-109 في الأصل)
- PrayerTimes.kt : PrayerTimes.fmtLeft (سطور 60-66 في الأصل)
- PrayerTimes.kt : PrayerTimes.fmtLeftMin (سطور 69-73 في الأصل)
- RemoteClient.kt : None.offerApk (سطور 632-651 في الأصل)
- RemoteClient.kt : RemoteClient.helloAt (سطور 242-245 في الأصل)
- RemoteClient.kt : RemoteClient.musicLyrics (سطور 550-554 في الأصل)
- RemoteClient.kt : RemoteClient.ptrClick (سطور 437-439 في الأصل)
- RemoteClient.kt : RemoteClient.ptrMove (سطور 431-431 في الأصل)
- RemoteClient.kt : RemoteClient.ptrScroll (سطور 434-434 في الأصل)
- RemoteClient.kt : RemoteClient.sendText (سطور 496-503 في الأصل)
- SongId.kt : SongId.options (سطور 400-427 في الأصل)
- TvFocusMem.kt : TvFocusMem.recall (سطور 19-19 في الأصل)
- TvHome.kt : TvActivity.themeDialog (سطور 2129-2129 في الأصل)
- TvHome.kt : TvActivity.tvSkinDialog (سطور 206-212 في الأصل)
- TvInput.kt : TvAccessService.wifiToggle (سطور 297-314 في الأصل)
- TvModeFab.kt : TvModeFab.openFan (سطور 265-305 في الأصل)
- TvSkinBrand.kt : ManufacturerTvSkin.legacyHeader (سطور 43-48 في الأصل)
- TvSkinGlass.kt : TvSkinGlass.legacyHomeHeader (سطور 243-246 في الأصل)
- TvSkinHub.kt : TvSkinHub.legacyHomeHeader (سطور 81-84 في الأصل)
- TvTasksUi.kt : class TvTasksUi (سطور 15-93 في الأصل)
- TvUpdate.kt : TvUpdate.askReady (سطور 86-95 في الأصل)
- YoutubeUi.kt : class YtThumbs (سطور 73-99 في الأصل)
- YtLink.kt : YtLink.handleScanned (سطور 79-88 في الأصل)
- ملف TvTasksUi.kt كله + سطره من app/kt_manifest.txt

## اتساب عمدًا
- `TvSkinRail` (سكِن v625 غير متسجّل) و`PrivateListen` (سماعة خاصة v596) — قرار منك: توصيل ولا مسح.
- `TvSkin.themes` (فيه overrides) و`TvBlur.frosted/frostedButton` و`Notice.tvCapsuleBg` — الكومبايلر كشف إن مسحهم بيكسر، فرجّعتهم.
- دوال الاختبارات (Coverage/PlayerLogic).
- الـcatch الفاضية: ما لمستهاش (تسجيل في مسارات سريعة ممكن يغرق اللوج).
