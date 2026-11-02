# AUDIT_TECHNOLOGY_MAP — التقنيات الموجودة فعلياً (Subtitler v525)

الحالة: **مراجعة ثابتة للكود فقط** — لم يتم بناء المشروع ولا تشغيله (لا Gradle/SDK/محاكي في البيئة).

## 1. Jetpack Compose
**غير موجود.** نتيجة البحث في كل ملفات `.kt/.kts/.xml/.gradle/.toml`:
- `@Composable` / `ComposeView` / `androidx.compose` / `activity-compose` / `AndroidView`: **صفر نتائج**.
- `setContent {}` (امتداد Compose): **صفر نتائج**. (الاستدعاءات الموجودة هي `Dialog.setContentView(view)` و`Activity.setContentView(view)` العادية.)
- لا dependency ولا compiler plugin للـCompose في `app/build.gradle.kts`.
القرار: لا إضافة Compose، لا ادعاء أن شاشات Compose موجودة.

## 2. Android Views (الأساس)
- كل الـActivities ترث `android.app.Activity` (وليس AppCompat): Main.kt:53، Player.kt:44، Browser.kt:184، FileManager.kt:168، RemoteActivity.kt:35/767، TvHome.kt:45، LiveCaption.kt:43، SongCatch.kt:53.
- الواجهات تُبنى **برمجياً** (LinearLayout/FrameLayout… بالكود) بدون XML layouts.
- ملفات XML layout الوحيدة: `widget_live.xml`, `widget_song.xml`, `widget_tasks.xml` (Widgets).

## 3. WebView / HTML
- `WebView(` مستخدم في: Browser.kt, ClipWatch.kt, KeyBrowser.kt, LinkRefresh.kt, YtAccount.kt (تسجيل دخول يوتيوب فقط).
- `app/src/main/assets/index.html` (41,767 سطر): **لم يتم تتبع من يحمّله** في هذه الدفعة (HtmlUi.kt 510 سطر مرشّح — غير مؤكد).
- واجهة يوتيوب **ليست WebView**: YoutubeUi.kt/YtProUi.kt/YtWatch.kt/YtNp.kt بدون WebView؛ تعتمد NewPipeExtractor (YtNp 19 إشارة، YtFast 12).

## 4. مكتبات أساسية (app/build.gradle.kts)
media3 1.10.1 (exoplayer/hls/datasource/effect/transformer), androidx.webkit 1.11.0, Glide 4.16.0, OkHttp 4.12.0, ML Kit Translate 17.0.3, NewPipeExtractor v0.26.5, sherpa-onnx 1.12.39 (AAR يُنزَّل وقت البناء، ~50MB), JNI (cpp/CMake).
minSdk 26, targetSdk 34, compileSdk 36, versionCode 525.

## 5. آلية kt_manifest
`pruneStaleKt` (preBuild) تحذف أي `.kt` غير مذكور في `app/kt_manifest.txt`. تحققنا: القائمة (164) مطابقة للملفات (164) — `diff` أعطى IDENTICAL (بعد إزالة بادئة المسار).

## 6. حدود التشغيل البيني (Interop)
لا يوجد Compose، فلا حدود Compose/View. الحدود الفعلية: Views ↔ WebView (المتصفح) و Views ↔ NewPipe (يوتيوب).
