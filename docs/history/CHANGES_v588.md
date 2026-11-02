# v588 — تنضيف الباقي (قرارات AUDIT_03: A4 + D1 + D3 + D4)

## اتحذف
- `PublicApis.pull` و`lastOk` + اللي اتيتّم بعدهم (`MIN_ENTRIES`, `EVERY_MS`, `cacheFile`). باقي `PublicApis` (القراءة/`listed`) زي ما هو لأن `ExtTranslate.inRepo` بيستخدمه.
- `assets/prompts/east_asian.txt` — مفيش أي ذكر ليه في index.json ولا الكود، ومفيش `assets.list` بيمسح المجلد.

## اتأكد إنه لازم يفضل (مش بقايا)
- `StemCore.kt`: فيه `StemResampler` وبيستخدمه `VoiceClean` (فصل الصوت). الاسم مضلّل بس.
- `YoutubeUi.kt`: مش صفحة WebView — فيه `YtItem` و`YtHistory` و`YtThumbs` وكلهم مستخدمين في يوتيوب. الاسم قديم بس.

## الفحص
kotlinc على الشجرة كلها قبل/بعد: 650 خطأ = 650 بنفس القايمة (مكتبات ناقصة بس) → مفيش خطأ جديد. ماجرّبتش Gradle ولا جهاز.
