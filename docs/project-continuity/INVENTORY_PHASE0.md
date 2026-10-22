# تقرير جرد المرحلة 0 — 2026-10-22
**المدخل الوحيد:** `Subtitler_v475.zip` (SHA-256: 14d7d70f031909a7e9128c1d5c63ee675db64d1970032d89956822ad7724065d). مفيش مشروع Launcher منفصل ولا ملف YouTube تاني اتسلّم.

## 1) إيه الموجود (اتفحص فعلًا)
- 420 ملف، ~148 ملف Kotlin (~48.7 ألف سطر)، Views برمجية (مفيش Compose/Hilt/Room). Kotlin 2.4.0 · AGP 8.9.1 · compileSdk 36 · **targetSdk 34 · minSdk 26** · appId `com.tttt.subtitler` · v4.75 (code 475).
- مفيش `.git` جوه الـ zip → حالة Git مجهولة. 
- المشغل: media3 ExoPlayer 1.10.1 (+HLS, transformer, effect). يوتيوب: **NewPipeExtractor v0.26.5** (جوه التطبيق، YtNp/YtProUi/YtWatch/YtChannel…) + WebView تسجيل دخول (YtAccount). ثيمات: `Theme.kt` (`Themes`، 14 سمة + مخصصة).
- **فيه أصلًا وضع تلفزيون:** `DeviceUi.kt` (phone/tablet/tv) + `TvActivity` (TvHome.kt: ريل جانبي، صفوف، بحث موحّد، PIN، شاشة سكون 180ث). Manifest فيه `LEANBACK_LAUNCHER`، **ومفيش `HOME`/`DEFAULT` category** → مش Launcher حقيقي لسه.
- نقاط دخول: MainActivity · TvActivity · PlayerActivity · BrowserActivity · FileManagerActivity (+ خدمات: Music/Task/Bg/KeepAlive/LiveCaption/SongCatch).

## 2) مخاطر / ملاحظات
1. **BUILD غير متحقق:** البيئة هنا فيها Java بس — مفيش kotlinc/Gradle/Android SDK. كل نسخ Subtitler الأخيرة نفسها «لم تُجمَّع» حسب سجل التغييرات.
2. `MANAGE_EXTERNAL_STORAGE` + `SYSTEM_ALERT_WINDOW` + `RECORD_AUDIO`… أوسع بكتير من مبدأ «أقل صلاحيات» في الـ Master Prompt → لازم قرار.
3. `app/subtitler.keystore` + كلمة سر `subtitler123` ظاهرة في `build.gradle.kts` (debug فقط). مقبول لـ debug لكن ما يتنقلش لمشروع الـ Launcher.
4. `STATUS.txt` = 391KB وفيه ~30 ملف CHANGES — ضوضاء توثيقية؛ مش بديل لملفات الاستمرارية.
5. الكود بيعتمد على `onBackPressed()` المهجور (انظر القسم 3).
6. الاسم/الشعار/`banner` = أيقونة التطبيق العادية (مش بانر 320×180 مخصص لـ TV).

## 3) تدقيق «الرجوع / stepback gesture» (قراءة كود فقط — **ما اتجرّبش على جهاز**)
| # | الموضع | الملاحظة | الثقة |
|---|---|---|---|
| B1 | `TvActivity` | **مفيش `onBackPressed` خالص.** `showSearch()` بتستبدل `content` (مش Dialog) → Back/سويب من البحث بيقفل الشاشة كلها بدل الرجوع للرئيسية. | عالية (من الكود) |
| B2 | `TvActivity.dispatchKeyEvent` | بينادي `wake()` قبل `super` → لو شاشة السكون ظاهرة، Back **بيصحّيها وبيقفل في نفس الضغطة**. | عالية |
| B3 | `YtAccount.kt:120` | رجوع WebView تسجيل الدخول متعلّق على `setOnKeyListener(KEYCODE_BACK)`. تعليق `Glass.kt` نفسه بيقول إن السويب ما بيوصلش للـ key listener → السويب بيقفل الحوار بدل `wv.goBack()`. | متوسطة (محتاج تجربة) |
| B4 | `Player.kt:915..4669` | PopupWindows `focusable=true` (بتبلع Back صح) إلا `Player.kt:2196` (`focusable=false`) → Back بيعدّي للـ Activity. | متوسطة |
| B5 | `Player.onBackPressed` | تعليق الدالة بيقول «أفقي يرجع للرأسي من غير قفل» بس الكود مفيهوش ده. توثيق ≠ كود. | عالية |
| B6 | كل الـ Activities | `targetSdk=34`، ومفيش `enableOnBackInvokedCallback` ولا `OnBackPressedCallback`. شغّال دلوقتي، لكن **لو targetSdk اتزوّد لـ 36 (Predictive Back افتراضي) `onBackPressed()` ممكن يبطل يتنادى** (على حد علمي) → كل منطق الرجوع يقع. | متوسطة (لازم تتأكد من توثيق أندرويد قبل الرفع) |
| B7 | `Main.onBackPressed` | الترتيب سليم (تحميل يوتيوب → يوتيوب → إعدادات → مهام → موسيقى → تاب → تلفزيون). نموذج كويس نعمّمه. | عالية |
| B8 | `Browser/FileManager/Glass(GDialog)` | معالجة صح (`GDialog.onBack` + `canGoBack`). | عالية |

## 4) القرار المقترح للمرحلة 1 (يحتاج موافقتك — D-001)
مشروع **Vanilla TV Launcher جديد منفصل** (appId جديد + `HOME` intent) ناخد منه: `Theme.kt`، طبقة NewPipe (`YtNp*`)، وميكانيزم المشغل — بدل تحويل Subtitler نفسه Launcher (48 ألف سطر مليانة موبايل: ترجمة/موسيقى/متصفح). السبب: نفس قيود «لا إعادة كتابة» لكن حدود واضحة.
**ملفات ناقصة:** تطبيق YouTube اللي هتديني إياه (APK/سورس؟) — ما اتسلّمش؛ المرحلة 7 متوقفة عليه. وموديل التلفزيون + إصدار Android بتاعه.
