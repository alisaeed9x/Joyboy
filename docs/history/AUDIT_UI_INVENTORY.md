# AUDIT_UI_INVENTORY — جرد الشاشات ومسارات التنقل

الحالة: ثابت (قراءة كود). كل «التقنية» هنا = Android View برمجي ما لم يُذكر غير ذلك. لا اختبار تشغيل.

## الأنشطة (AndroidManifest.xml)
| النشاط | الملف | الدخول | ملاحظات مثبتة |
|---|---|---|---|
| MainActivity | Main.kt:53 | LAUNCHER + SEND text/plain + VIEW `subtitler://ytlink` + VIEW https (youtube.com / m.youtube.com / youtu.be / music.youtube.com) | بدون launchMode (standard). يستضيف تبويبات (0 مكتبة فيديو، 1 موسيقى، 4 يوتيوب، 5 مهام، 7 إعدادات — من onBackPressed) |
| TvActivity (اللانشر) | TvHome.kt:45 | MAIN+**HOME**+DEFAULT+**LEANBACK_LAUNCHER** | singleTask, stateNotNeeded. ليس له LAUNCHER (يظهر في لانشر التلفزيون فقط) |
| PlayerActivity | Player.kt:44 | داخلي (exported=false) | شريط تحكم تلفزيون TvPlayerBar؛ ريموت في dispatchKeyEvent:371 |
| BrowserActivity | Browser.kt:184 | داخلي، **بدون intent-filter** | extras: `from_tv`, `start`, `noauto` |
| FileManagerActivity | FileManager.kt:168 | داخلي | |
| RemoteActivity / SendToTvActivity | RemoteActivity.kt:35 / :767 | LAUNCHER / SEND | ريموت الموبايل للتحكم في التلفزيون |
| LiveRequestActivity / SongRequestActivity | LiveCaption.kt:43 / SongCatch.kt:53 | داخلي (شفاف) | |
| Widgets | Widgets.kt + 3 layouts XML | AppWidget | |

## مسارات التنقل (من الكود)
- أيقونة التطبيق على التلفزيون → TvActivity.
- TvActivity بلاطة «يوتيوب» (TvHome.kt:980) / الاختصارات (:511) / الـdock (:1091) → `openMain("youtube")` (:2151) = `Intent(MainActivity)` + `CLEAR_TOP|SINGLE_TOP` + `force_phone=true` + `from_tv=true` + `tab=youtube`.
- TvActivity بلاطة «المتصفح» (:981) / اختصار (:512) → `openBrowser()` (:2147) = `Intent(BrowserActivity).putExtra("from_tv", true)`.
- فيديو يوتيوب من اللانشر (ytCard) → `openYt(id)` (:1757) = MainActivity + `yt_open=<id>` + `tab=youtube`.
- ملف محلي → PlayerActivity مباشرة (`playFile`, :2160 تقريباً).
- تطبيق خارجي → `launchApp(pkg)` (:1009): `getLeanbackLaunchIntentForPackage ?: getLaunchIntentForPackage` + NEW_TASK؛ لو null → Notice «التطبيق مش متاح»؛ الاستثناء → Notice + LogStore.
- Back من تبويب ≠ 0 في Main أثناء وضع التلفزيون → `TvFocus.goHome` (يفتح TvActivity CLEAR_TOP|SINGLE_TOP ويُنهي Main) Main.kt:1781.
- Back من جذر Main عندما fromTv → TvActivity CLEAR_TOP + finish (Main.kt:1782).
- Back في TvActivity → إغلاق فولدر أو `showHome()` (TvHome.kt:288) — **لا يخرج من اللانشر أبداً** (سلوك لانشر/Home مقصود على الأغلب).
- Back في Browser: customView → شريط تحميل → شريط بحث → `WebView.goBack()` → إغلاق تبويب → `finish()` (Browser.kt:1906).
- روابط من Browser/FileManager → `Links.open` (Links.kt:61) → **MainActivity** (CLEAR_TOP|SINGLE_TOP + EX_URL).

## ما لم يُجرد بعد (غير مؤكد)
تفاصيل كل تبويب (LibraryUi 1271 سطر، MusicUi 1828، Settings، TasksUi)، وتحميل `index.html`. لا حالات focus/loading/empty مفصلة لها في هذه الدفعة.
