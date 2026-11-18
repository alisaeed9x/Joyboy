# Joyboy v1104 — يوتيوب: Y3 (الرئيسية/الاشتراكات من محرك MeeTube) + Y4 (الحساب بتوكن الكود)

> **لم يتم compile** ولا اتجرّب على جهاز — Actions هو الحكم. الأساس: `Joyboy_v1103.zip`.

## Y3 — الرئيسية والاشتراكات من MediaServiceCore (جوه يوتيوب Joyboy القديم)
- **`YtMtFeed.kt`** (جديد + `kt_manifest.txt`): مصدر الصفحتين = `contentService.getHome()` / `getSubscriptions()` + **تحميل مفتوح** بـ`continueGroup` (الـPage = مفتاح `ytm:N` لمجموعة محفوظة في الذاكرة، آخر 24 بس).
  - الاشتراكات **مرتّبة بالأحدث** (`MtYt.newestFirst`: لو ≥70% من العناصر ليها وقت نشر؛ غير كده ترتيب يوتيوب زي ما هو).
  - الرئيسية: الصفوف كلها مدموجة، والفيديوهات المشاهَدة (سجل Joyboy) بتتشال؛ لو أقل من 12 فيديو بتكمّل لحد 3 صفحات.
  - كاش رئيسية المحرك على القرص (`yt_mt_home.json`، لحد 7 أيام) → الفتح فوري، والتحديث في الخلفية يطلّع كبسولة «فيديوهات جديدة» (نفس آلية NewPipe القديمة).
- **`MtYt.kt`:** `subsFirst()` · `homeFirst()` · `more(group)` · `Feed` · `newestFirst`، وعمر الفيديو بيتحسب من وقت النشر لو يوتيوب مدّاش نص.
- **`YtProUi.kt` (تعليقات `(v1104 Y3)`):** `openTab(0)` و`openSubs()` بيجرّبوا المحرك الأول.
  - **شرط التشغيل:** المفتاح `yt_mt_feed` (افتراضي شغّال) + حساب الكود مسجّل دخول.
  - **احتياطي:** لو المحرك رجّع فاضي/فشل ← المسار القديم (NewPipe / RSS / الفيد الرسمي) بالظبط زي ما كان. غير مسجّل ← مفيش أي تغيير.
  - الفحص الدوري (كل 4 دقايق) بقى على رئيسية المحرك؛ اشتراكات المحرك بتتحدّث بفتح الصفحة أو السحب (مفيش RSS خلفي معاها). NewPipe مبيتهيّاش لرئيسية المحرك.
  - تبويب البداية: لو حساب الكود مسجّل بيفتح على الاشتراكات حتى من غير كوكيز/اشتراكات محلية.

## Y4 — الحساب كله بتوكن الكود (شيل الكوكيز + QR)
- **`YtAccount.kt` اتكتب من جديد** (نفس الأسماء العامة، فمفيش تغيير في الشاشات):
  | | كان (كوكيز WebView) | بقى (توكن الكود) |
  |---|---|---|
  | `loggedIn()` | وجود SAPISID | `MtYt.signedIn()` (كاش 20 ثانية) |
  | `login()` | WebView جوجل | `YtCodeLogin` (كود + youtube.com/activate) |
  | `logout()` | مسح كوكيز | `YtCodeLogin.logout()` + مسح كاش الرئيسية/الاشتراكات |
  | `listFirst/listMore` | InnerTube browse بالكوكيز | `contentService`: قنواتي · السجل · الاشتراكات · قوايمي (`getPlaylistsObserve`) · الإعجاب/شاهد لاحقًا/أي قايمة (`getGroup`) |
  | `syncSubs()` | `FEchannels` | `getSubscribedChannels()` + continueGroup |
  | `setSubscribed` | `subscription/subscribe` | `mediaItemService.subscribe/unsubscribe` |
  | `setLiked` | `like/like` | `setLike/removeLike` |
  | `postComment` | `comment/create_comment` | `commentsService.createCommentObserve` |
  | `authHeaders()` | كوكيز لـNewPipe | `null` (NewPipe بقى من غير حساب) |
  - اللي اتشال: WebView الدخول · `importCookie/exportCookie/sameSession` · توقيع SAPISIDHASH · `post/browse` الخام. الكوكيز المحفوظة قديمًا **بتتمسح أول تشغيل** (لازم تدخل بالكود مرة).
  - اللي فضل: `relatedLite` (مقترحات من غير حساب) + محلل الردود `parse` (الشورتس والمسارات الاحتياطية) + `YtAccount.shorts` (YtRss بيقراه).
  - مفاتيح القوايم الخاصة (`VLLL`/`VLWL`/`VL<id>`) بتفتح عن طريق `MediaItem` بسيط (java Proxy) فيه `playlistId` بس — لأن `getGroup(String)` بياخد توكن تكملة مش browseId.
- **`YtLink.kt` اتمسح** (QR التلفزيون + نقل الكوكيز) ومعاه سطره في `kt_manifest.txt`. على التلفزيون: «سجّل الدخول» بيفتح شاشة الكود مباشرة (الـdevice flow معمول للتلفزيون أصلًا).
- `YtProUi`: اختيار QR/يدوي اتشال · صف «🔐 سجّل الدخول بالكود». `Main.kt`: زرار الإعدادات بقى بيعدّي على `YtAccount.login` (عشان الكاش يتصفّر).
- `YtMtFeed.reset()` بيتنده عند الدخول/الخروج.

## أخطر نقط للبيلد (لو وقع ابعتلي اللوج)
1. `YtAccount.kt`: الـProxy لـ`MediaItem` (`playlistItem`/`videoItem`) · `getPlaylistsObserve().blockingFirst()` · `createCommentObserve(...).blockingFirst()` (rxjava3 على classpath التطبيق من Y1).
2. `YtMtFeed.kt`: `Page(String)` من NewPipe · الاستيراد `MediaGroup` (`:mediaserviceinterfaces`).
3. `YtProUi.kt`: `return@load Pair(...)` داخل لامبدا `load`.
4. R8: لا جديد (Proxy على interface من `:mediaserviceinterfaces` — لو اشتكى ضيف `-keep interface com.liskovsoft.mediaserviceinterfaces.data.MediaItem { *; }`).

## معيار النجاح على الجهاز
1. إعدادات ← يوتيوب ← «🔐 دخول بالكود» ← يوافق من `youtube.com/activate` ← الشاشة تقفل.
2. `yt_classic` شغّال ← تبويب يوتيوب ← الاشتراكات أحدث الفيديوهات فوق ويكمّل تحميل لتحت؛ الرئيسية كمان.
3. «أنت» ← قنواتي / السجل / الإعجاب / شاهد لاحقًا / قوايمي بتفتح؛ لايك واشتراك وتعليق بيتسجّلوا على الحساب.
4. إعادة تشغيل التطبيق: الدخول فاضل.

## لسه (مقصود)
- **Y5** مسح واجهة ميتيوب · **Y6** مسح `:mobile`/`:common` (ولازم قبلها نقل `MobileMainApplication.boot` لـ`app`) · **T5** الشورتس.
- `yt_classic` لسه افتراضي مقفول (ميتيوب هو الافتراضي لحد ما يتجرّب).
- نواقص Y2 القديمة (لينكات قناة/قايمة من برّه، PiP، SponsorBlock في Player…) لسه زي ما هي.
