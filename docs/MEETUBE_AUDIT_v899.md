# MeeTube → Joyboy Player — Source Audit + Feature Matrix (v899)
المصدر المفحوص: `meetube-main.zip` — Java (`mobile/src/stmobile/java/com/newtube/mobile/ui/{settings,common,playback,browse,search}`)، و`mobile/src/stmobile/res` كله:
anim (9) · animator (2) · color (7) · raw (1) · drawable (201) · drawable-nodpi (18) · layout (88) · values (15 ملف) · values-night (4).
**البيئة:** مفيش Gradle ولا Android SDK ولا جهاز. أقصى ما اتعمل: توازن أقواس (مطابق للأصل) + `dev-tests/check_manifest.sh` (OK) + التأكد إن كل `R.drawable/anim` المستخدمة موجودة في res بتاع :mobile.
**الحالات:** DISCOVERED · IMPLEMENTED (كود مكتوب ومتوصّل — مش متكومبايل) · TESTED/VERIFIED = **ما فيش أي حاجة** (محتاجة بيلد وجهاز).

## 1) الثيمات (values / values-night / ThemeMode)
MeeTube عنده **3 اختيارات بس**: نظام · فاتح · داكن (`ThemeMode.SYSTEM/LIGHT/DARK`، `Tiles.THEME_*`)، مش سكنات متعددة. لون التمييز واحد: `meetube_accent` (#D7372D فاتح / #FF453A داكن). لا يوجد AMOLED ولا سيبيا في ميتيوب نفسه (الأموليد والرملي في Joyboy إضافة من v896).
| ملف المصدر | المحتوى | Joyboy | الحالة |
|---|---|---|---|
| values/colors_mobile.xml (+night) | ~60 لون: surface/on_surface/card/pill/divider/track/progress/placeholder/skeleton_highlight + glass tokens (`meetube_glass_*`، `sheet_glass`، `nav_inactive`، `bar_tint`) | `MtGlass.fill/stroke/rim/sheen/selected/sheet/navInactive` (v893-v897) — نفس القيم | IMPLEMENTED (v893+) |
| ThemeMode SYSTEM/LIGHT/DARK | اختيار الثيم | المظهر الرباعي (داكن/فاتح/أموليد/رملي) | IMPLEMENTED (v896) — «نظام» غير موجود |
| styles_mobile.xml (40KB) | Theme.NewTube، bottomSheetStyle، Widget.MeeTube.Loader*، ThemeOverlay.Loader.* | الجزء الخاص باللودر فقط (بند 5) | جزئي |
| values-sw600/720/800dp، values-v27 | قياسات تابلت/شريط التنقل | — | DISCOVERED |

## 2) إعدادات المظهر (AppPages.customize) — كل صف
| الخيار | مصدر | سلوك أصلي | Joyboy | حالة |
|---|---|---|---|---|
| Theme | AppPages:~109 / ThemeMode | system/light/dark | 4 اختيارات | IMPLEMENTED v896 |
| App icon ×8 | AppIcon.java + mipmap/nodpi | تبديل أيقونة اللانشر (activity-alias) | — خارج المشغّل | DISCOVERED |
| **Loader ×8** | common/Loader.java + drawable/avd_* + animator/neon_arc | سبينر موحّد | `MtLook.loaders` + `WinSpinnerDrawable` | **IMPLEMENTED v899** |
| Interface size | AppPages.interfaceSize | densityDpi | — | DISCOVERED |
| Watched bar · Refresh sound(raw/refresh_pop.wav) · Haptics | WatchedBar/RefreshFeedback/Haptics.java | — | — | DISCOVERED |
| Home layout · Shorts shelf · Video layout · Thumbnails ×4 · Playlists style · Watch style | HomeLayout/ClickbaitRemover/WatchLook | تخطيط يوتيوب | — (مش مشغّل) | DISCOVERED |
| **Player buttons (visibility per button)** | WatchLook.setShown + PlayerButton + SettingsPages.PLAYER_BUTTONS؛ ids: mobile_player_{audio,cast,chat,comments,download,dislike,like,lock,more,next,previous,share,subtitles,zoom,…} | إخفاء كل زرار على حدة | زرار ⬇ بس (`hide_btn_dl`) — أزرار Joyboy مختلفة (ترجمة/لغة/جودة) | **جزئي** IMPLEMENTED |
| **Player buttons look ×3** | common/Glass.playerButtons | see-through/frosted/minimal | `MtGlass.playerBtnLook` | IMPLEMENTED v898 (frosted = تقريب لوني) |
| Liquid glass | Glass.isLiquid | blur+lens+grain | `glass_all` + MtGlassView/MtLens | IMPLEMENTED v895-897 |
| Chip style (segment/pills) | Glass.chipStyle + GlassChipTrack.java | — | — | DISCOVERED |
| **Seek thumb dot/picture** | playback/SeekThumb.java + PlayerTimeBar | صورة (GIF/WebP) ≤5MB بدل النقطة | `MtLook.loadThumb` + `DualProgress.drawThumb` + `SeekThumb` activity نفسها | **IMPLEMENTED v899** |
| Mini player type ×4 | MiniControls/MiniSize/MobileMiniPlayerController | — | Joyboy عنده MiniDock/PiP خاص | DISCOVERED |
| Tab motion (NavLens) | common/NavLens.java | عدسة سائلة | `MtLens.kt` | IMPLEMENTED v897 |
| Tab glow ×4 · Search glow (SiriGlow) · Typing glow (GlowEditText) | NavBloom/SiriGlow/GlowEditText.java | توهّج | — | DISCOVERED |
| **Progress glow** | common/GlowProgressBar.Head | بيضاوي متلاشي عند رأس التقدّم | `MtLook.Glow` في `DualProgress` | **IMPLEMENTED v899** |
| Nav labels · brand name · top bar · search FAB | NavTabs | — | — | DISCOVERED |
| Tabs/Bars editor (drag) · Video menu | settings/BarEditor.java (479 سطر) | سحب لترتيب الشريط | — | DISCOVERED |

## 3) إعدادات المشغّل (PlayerPages) المتعلقة بالشكل/السلوك
| الخيار | مصدر | Joyboy | حالة |
|---|---|---|---|
| **Gestures: levels · seek · double-tap interval** | playback/PlayerGesturePrefs + SwipeLevels + GESTURES.md | `gest_levels`/`gest_seek`/`gest_dbl_ms` في `Player.kt` gd.onScroll/onDoubleTap | **IMPLEMENTED v899** |
| Captions style/size/position/per-channel | PlayerPages.captions + sheet_mobile_captions.xml | Joyboy عنده نظامه («🎬 شكل الترجمة»: خط/لون/خلفية/موضع/حجم + قرص) | معادل موجود (مش من ميتيوب) |
| Playback (when-ends ×7 · remember speed ×4 · speed list · sleep timer · audio focus · PiP · resume) | PlayerPages.playback | منطق ميتيوب/يوتيوب؛ Joyboy له مشغّله | DISCOVERED (خارج نطاق الشكل) |
| Quality caps · Audio (loudness/stable/dolby/EQ/volume) · SponsorBlock · DeArrow | PlayerPages | يوتيوب/ميتيوب فقط | DISCOVERED |
| Auto-hide controls | — لا يوجد إعداد في ميتيوب | — | غير موجود في المصدر |

## 4) مكوّنات المشغّل (layout/activity_mobile_playback.xml)
ids: container · view · glass · top_pill · top_scrim · bottom_scrim · transport · play_pause · previous · next · time_bar · position/duration/time · seek_filmstrip · scrub_chapter · level_bar/pill/icon · lock/unlock · fullscreen · zoom_hint · more(+hint) · options · actions(+toggle) · like/dislike/share/download/comments/chat/cast/subtitles/audio · skip_segment · live · notice · yt_overlay · debug.
| مكوّن | مصدر | Joyboy | حالة |
|---|---|---|---|
| PlayerTimeBar (1142 سطر: seek/preview/chapters/SponsorBlock marks) | playback/PlayerTimeBar.java | `DualProgress` (HtmlUi.kt) — بيرسم تغطية الترجمة وأشرطة خاصة؛ أخدت منه: التوهّج والمقبض | جزئي |
| **SeekFilmstrip** (205 سطر: معاينة إطارات أثناء السحب) | playback/SeekFilmstrip.java | `PlayerActivity.frameJpeg()` موجود بس ما اتوصلش | DISCOVERED (مش متعمل) |
| LevelBar/SwipeLevels (مؤشر صوت/سطوع) | playback/LevelBar, SwipeLevels | Joyboy `indShow(volInd/briInd)` | معادل موجود |
| WatchActionFeedback · NextCountdown · AmbientGlow · PinchZoomLayout · FloatingLock · MiniPip | playback/* | Joyboy: `giShow` + pulse (v469) · قرص للترجمة/الفيديو · lock موجود | معادل جزئي |
| Double-tap overlay | :doubletapplayerview-media3 | `gi` indicator (Joyboy) | معادل موجود |

## 5) الأنيميشن (anim/ animator/ Motion.java)
| المورد | الوصف | Joyboy | حالة |
|---|---|---|---|
| player_open_enter/exit (200ms، scale 0.94→1 + fade) | فتح المشغّل | `overridePendingTransition` في `PlayerActivity.onCreate` | IMPLEMENTED v899 |
| player_close_enter/exit (200-220ms، fade+slide 6%+scale) | قفل المشغّل | `PlayerActivity.finish()` | IMPLEMENTED v899 |
| browse_to_front_enter · mobile_fade_out · mobile_stay | انتقالات تصفّح | — | DISCOVERED |
| shorts_open_enter/close_exit | شورتس | — | DISCOVERED |
| neon_arc.xml (trimPath 1300ms) | لودر النيون | داخل AVD (بيتنسخ مع الـ drawable) | IMPLEMENTED v899 |
| press_3d.xml (scale 0.82، 90ms) | ضغطة زرار الرجوع | `MtGlass.press/pop` (v893) تقريب | جزئي |
| Motion.frostIn / pop / jiggle | common/Motion.java, NavLens | `MtGlass.pop/frostIn/press` + MtLens.jiggle | IMPLEMENTED v893-897 |

## 6) اللودرز الثمانية (drawable/avd_*، meetube_loader)
neon (avd_neon_ring) · frosted (avd_frosted_ring) · cookie (meetube_loader) · frosted_cookie · liquid_ring · glass_orb · morphing_blob · glass_orbit. تبعياتها بس `splash_mono/splash_glass_*/mobile_color_primary` + animator/neon_arc — كلها في :mobile ومتاحة لـ app (nonTransitiveRClass=false). **IMPLEMENTED v899**: اختيار بصري (بلاطات متحركة) + بيظهر في كل سبينر Joyboy.

## 7) Skeleton / Shimmer / Placeholders / Empty states
`ShimmerLinearLayout.java` · `SkeletonReveal.java` · `mobile_feed_skeleton(_rows).xml` · `mobile_watch_related_skeleton.xml` · `item_mobile_comment_skeleton.xml` · `bg_skeleton_block.xml` · `mobile_page_load_state.xml` · `item_mobile_load_more_failure.xml` — **DISCOVERED، مش متعمل** (Joyboy مفيهوش شاشات feed بنفس الشكل؛ لازم تحديد الشاشات الأول).

## 8) قوائم/شيتات/دايلوجات/كروت/تنقّل
GlassView.java · SheetGlass.java (489) · MobileSheets · GlassSwitch (356) · GlassSlider · GlassDrawable · MobileSnackbar · sheet_mobile_{speed,quality,captions,chapters,…}.xml · item_video_card/row · Navigation: NavTabs/NavBloom/ChipBar/FeedStrip — الجلاس/الأسطح/الشريط السفلي اتنقلوا في v893-v897 (MtGlass/MtGlassView/MtSkin/MtLens)؛ GlassSwitch/GlassSlider/SheetGlass الكاملة والـ snackbar **DISCOVERED، مش متعمل** كنسخ مطابقة.

## 9) Android TV
MeeTube phone-only (`FocusRing.java` بس). التلفزيون في Joyboy **ما اتلمسش** (كل التغييرات محاطة بـ `MtGlass.on()` = موبايل/تابلت).

## المتبقي (غير منفّذ) — قائمة دقيقة
إخفاء باقي أزرار المشغّل (مفيش مقابل لأزرار ميتيوب) · SeekFilmstrip · SponsorBlock marks · Tab/Search/Typing glow · Chip style segment/pills · Mini player types · Interface size · Watched bar/Haptics/Refresh sound · App icons · Skeleton/Shimmer · GlassSwitch/GlassSlider/SheetGlass/Snackbar المطابقة · انتقالات browse/shorts · «نظام» كثيم.
## غير متحقَّق منه (كله)
كل ما سبق «IMPLEMENTED» لم يُكوْمبَل ولم يُجرَّب. نقاط الشك: `Spinner.kt` (Drawable.Callback + AVD) · `HtmlUi.kt` DualProgress (glow/thumb) · `MtPlayerLook.kt` (ImageDecoder lambda، `R.drawable.avd_*` من :mobile) · `Main.kt` (spread داخل `ui.section`) · `Player.kt` (شرط الـscrub).
## بناء خارجي (أوامر)
```
cd Joyboy && bash dev-tests/check_manifest.sh
./gradlew :app:assembleDebug        # أو GitHub Actions الحالي
```
اختبار يدوي: إعدادات ← المظهر ← «🎬 مظهر المشغّل» ← اختار لودر → افتح فيديو واضغط زرار يبقى فيه بافرنج/ترجمة → السبينر يتغيّر · غيّر الصورة/النقطة → شريط التقدّم · قفّل التوهّج · غيّر مدة الضغطتين وجرّب · قفّل السحب · أعد تشغيل التطبيق وتأكد إن كل الإعدادات اتحفظت.
