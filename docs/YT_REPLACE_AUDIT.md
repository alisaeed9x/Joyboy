# Y0 — فحص «يتنقل / يتمسح» (v1103) — قراءة كود بس، لم يتم compile

## 1) اللي `app` بيستورده من ميتيوب (كله لازم يتعامل معاه قبل Y5/Y6)
| الكلاس | بيستخدمه | القرار |
|---|---|---|
| `MobileMainApplication.boot` | JoyApp · MeeTubeLink · MtPlay · MtYt (+ YtCodeLogin عن طريق MtYt) | **⚠ لسه محتاجينه** — MtYt/الدخول بيشغّلوا محرك MediaServiceCore بيه. قبل Y6 ننقل `boot()` لـ`app` (أو نعمل Application init بديل) |
| `YouTubeServiceManager` · `MediaFormat` · `MediaItemFormatInfo` | MtYt · YtCodeLogin | **يفضلوا** (`:youtubeapi` + `:mediaserviceinterfaces`) |
| `ThemeMode` (`ui.common`) | Theme.kt | **يتنقل لـ`app`** قبل مسح `:mobile` (الجلاس/الثيم ممنوع يتلمس) |
| `Video` · `VideoStateService` · `PlaybackPresenter` (`:common`) | MtMedia · MtPlay · MtTvRoute | **يتمسحوا** مع Y5 (مشغّل ميتيوب) |
| `JoyboyPlayerHost` · `MiniPlayerBridge` · `JoyboyBarHost` · `SeekFilmstrip` | MtBar · MtMedia · MtSmooth · MtTranslate · MtTvPlayer · YtMiniCard · MtFrames · MeeTubeLink | **يتمسحوا** مع Y5 (ربط مشغّل ميتيوب) |

ملفات `app` اللي بتستورد ميتيوب (13): JoyApp · MeeTubeLink · MtBar · MtFrames · MtMedia · MtPlay · MtSmooth · MtTranslate · MtTvPlayer · MtTvRoute · MtYt · Theme · YtMiniCard. اللي بيفضل بعد Y6: **MtYt + Theme + JoyApp(boot)** بس.

## 2) يوتيوب القديم (v887) — توافقه مع v1102
- فحص رموز: **كل** الكلاسات/الأوبجكتس اللي بيستعملها (`Ui` · `Cfg` · `Theme` · `Motion` · `Notice` · `PlayTarget` · `DeviceUi` · `TvFocus`…) موجودة في v1102 بنفس الدوال. الناقص: `ShortsPlayer` (اتمسح v889 — اتبدّل) و`YtExtract.loadPin/reprobe` (جوه YtFast القديم — اتبدّل بغلاف).
- استدعاءات v1102 لـ`YtHistory` (`add/clear/watchUrl`) متوافقة مع النسخة الحقيقية الراجعة.
- اللي اتنقل (15 ملف): `YtProUi` · `YtWatch` · `YtChannel` · `YtHistoryUi` · `YtAccount` · `YtNp` · `YtRss` · `YtLink` · `YtLoad` · `YtTopBar` · `YtSnap` · `YtThumbBtns` · `YtSkin` · `YtLocale` · `YoutubeUi`.
- اللي **ما اتنقلش** وليه: `YtFast` و`YtBackend` (طبقة السباق/InnerTube القديمة — اتبدّلوا بأغلفة صغيرة فوق `MtYt`)، `YtAudio/YtNext/YtFallback` (نسخ v1102 أحدث — فضلت)، `NpItem/YtPick` (موجودين في YtModels).
- اعتماد: **NewPipeExtractor v0.26.5** رجع (YtNp بيستخدمه للبحث/القنوات/القوايم/التعليقات) + استثناء nanojson + RxJava3/RxAndroid (لـ`signInObserve`).
- `ensureBoot` اتضافت في MtYt (شاشة الدخول محتاجة محرك MeeTube شغّال).

## 3) جدول الجلاس/الأنيميشن (ممنوع يتلمس) — لسه للتحقق في Y5
الجلاس الحالي في `app` (`MtGlass · MtGlassView · MtHeader · MtSkin · Theme · Glass`) — ما اتلمسش في Y0–Y2. الـJava اللي في `:mobile/ui/common` (`Glass*` · `SheetGlass`) محتاج grep تفصيلي قبل Y5 (مش اتعمل هنا).

## 4) تحديث v1105 (Y5) — جدول «يتنقل / يتمسح» بعد التنفيذ
| العنصر | القرار اللي اتنفّذ |
|---|---|
| `MeeTubeLink` · `MeeTubeBar` · `MtPlay` · `MtTvRoute` · `MtMedia` · `MtWeb` · `MtOrigin` · `MtBar` · `MtTranslate` · `MtTvPlayer` · `MtFrames` · `YtMiniCard` | **اتمسحوا** (v1105) |
| أكتيفيتيز `:mobile` (16) | **اتقفلوا من الـmanifest** (`tools:node="remove"`) — الكود لسه موجود |
| `MobileMainApplication.boot` | **لسه محتاجينه** (MtYt/الدخول) ← ينتقل لـ`app` في Y6 |
| `GlassView` · `GlassSource` · `NavLens` · `Motion` · `ThemeMode` · `SeekThumb` (`:mobile/ui/common|playback`) | **لازم يتنقلوا لـ`app` في Y6** (الجلاس/الشريط/الثيم بيستخدموهم) |
| `JoyboyPlayerHost.VideoInfo` | معتمد عليه `MtSmooth`/`MtEnhance` ← يتفكّ في Y6 |
| `MtGlass* · MtHeader · MtSkin · MtPlayerLook · Theme · MtSmooth · MtEnhance · MtFeed · MtYt` | **بيفضلوا** |

