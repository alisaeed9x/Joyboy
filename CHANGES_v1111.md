# Joyboy v1111 — Y6d: تهيئة محرك MediaServiceCore من `app` (بدل `MobileMainApplication.boot`)

> **لم يتم compile ولا اتجرّب على جهاز** — Actions هو الحكم. الأساس: `Joyboy_v1110.zip`.

## اللي اتعمل
- **`mtui/MtEngine.java`** (جديد): `boot(Application)` بيعمل **اللي المحرك محتاجه فعلًا بس**، منقول حرفيًا من `MobileMainApplication.onCreate`:
  - `OkHttpManager.setPreferHttp2(true)` في `static {}` (لازم قبل ما العميل المشترك يتبني) · `GlobalPreferences.instance(app)` (تخزين التوكن/الحساب).
  - أعلام `VideoInfoService` (PoT/HLS/kids/live/embed/fold/sig-runtime/DASH-live…) · `VodDelivery` · `BrowseServiceGates` · `SearchServiceGates` · `WatchNextGates` · `DefaultHeaders.setBrotliEnabled` · `YouTubeMediaItemService` (single-flight/preconnect) · `AppServiceIntCached.setPersistedAppInfoEnabled`.
  - مخازن هوية التشغيل: `BotWallPrefsStore` · `EmbedIdentityPrefsStore` · `PlaybackIdentityPrefsStore` (3 كلاسات Java صغيرة اتنسخت لـ`mtui` من `:mobile/player` — بتعتمد على `:youtubeapi` بس) + `WallMemory/VodVrLate/PlaybackReroll/PlaybackKeep` شغّالين.
  - تسخين بوابة PO token بعد 4 ثواني (من غير انتظار أول فريم/فتح — كانا من :common) · إخلاء تجمّع اتصالات OkHttp لما الشبكة تتبدّل · preconnect لـ`youtube.com`.
- **`JoyApp.onCreate`** بينده `MtEngine.boot`؛ لو رمى استثناء: **رجوع تلقائي** لـ`MobileMainApplication.boot` القديم (شبكة أمان — يتشال في Y6e). **`MtYt.boot()`** بينده `MtEngine.boot` (لو اتبوّت قبل كده مبيعملش حاجة).
- النتيجة: `app` مبقاش بيستخدم `MobileMainApplication` غير في الـfallback ده.

## اللي اتشال من التهيئة (واجهة :common/:mobile، ملهاش لازمة بعد Y5)
`Presenters/ViewManager/SidebarService/PhoneUi/AppUpdatePresenter/FeedCache/MediaServiceManager/YTSignInPresenter` · `SabrSourcePreference/PlayerInfrastructureWarmup/DownloadsBridge/LocalMediaBridge/SessionWarmup/PhoneOnlyPrefs/CardMenuMigration/LaunchMilestones/SystemPipBridge` · ترحيلات إعدادات الواجهة · كل أعلام `debug.arc.*` والـbenchmark.

## ⚠ لازم يتجرّب على الجهاز (أهم نقطة: المحرك مبقاش بياخد أي تهيئة من :common)
1. دخول بالكود يتحفظ بعد إعادة التشغيل (GlobalPreferences).
2. الرئيسية/الاشتراكات/تشغيل فيديو من MtYt بيشتغلوا زي قبل — وسرعة أول فتح (تسخين PoT).
3. **اللغة/المنطقة** في نتايج يوتيوب (عربي/مصر) — لو اتغيّرت عن قبل يبقى :common كان بيظبطها (`GeneralData`/`MediaServiceManager`) والمحرك دلوقتي على افتراضياته؛ قولي ونضيفها في `MtEngine`.
4. لو `LogStore` فيه `JoyApp:mtengine` يبقى الـfallback اشتغل (والسبب جوه السطر).

## اللي فاضل (Y6e — الأخيرة)
شيل `MobileMainApplication` fallback من `JoyApp` · شيل `implementation(project(":mobile"/":common"/":filepicker-lib"/":doubletapplayerview-media3"/":slidableactivity"/":sabr-media3"…))` من `app/build.gradle.kts` + `mod(...)` من `settings.gradle.kts` + 16 سطر manifest + `-keep class com.newtube.**` · ثم ضبط الـdependencies اللي `:mobile` كانت بتمررها (material/appcompat/media3/glide…) لو البيلد اشتكى.
