# Joyboy v1103 — يوتيوب: Y0 فحص + Y1 دخول بالكود + Y2 رجوع يوتيوب القديم (جنب ميتيوب)

> لم يتم compile ولا اتجرّب على جهاز — Actions هو الحكم.

## Y0 — الفحص
- `docs/YT_REPLACE_AUDIT.md`: جدول يتنقل/يتمسح + توافق v887 مع v1102. أهم نتيجة: `MobileMainApplication.boot` لسه محتاجينه (MtYt) → لازم ينتقل لـ`app` قبل Y6.

## Y1 — تسجيل الدخول بالكود
- **`YtCodeLogin.kt`** (جديد): شاشة Views في `app` بتنده `signInService.signInObserve()` (محرك MeeTube) → تعرض الكود + `youtube.com/activate` + 📋 نسخ + 🌐 فتح صفحة التفعيل (بالكود جاهز) + 🔄 كود جديد. لما الموافقة تتم الشاشة تقفل لوحدها والتوكن بيتحفظ في MediaServiceCore.
- `MtYt.ensureBoot()` متاحة من برّه. اتضافت `rxjava3` + `rxandroid` في `app/build.gradle.kts`.
- مكانها: الإعدادات ← قسم يوتيوب ← «🔐 دخول يوتيوب بالكود».
- **معيار النجاح:** يظهر كود → توافق من `youtube.com/activate` → الشاشة تقفل → `MtYt.signedIn()` = true بعد إعادة تشغيل التطبيق.

## Y2 — رجوع يوتيوب القديم (من v887) جنب ميتيوب
- اتنقل 15 ملف (القائمة في الـaudit) + `YtFast`/`YtBackend` أغلفة رفيعة فوق `MtYt` (جلب الروابط = المحرك الحالي، مش NewPipe). `YtNp.pick/pickAudio` بقوا يرجّعوا من `MtYt`.
- **مفتاح:** الإعدادات ← قسم يوتيوب ← «🧪 يوتيوب Joyboy القديم بدل MeeTube» (`yt_classic`، **افتراضي مقفول** = MeeTube زي ما هو). لما يتشغّل: تبويب يوتيوب (الشريط السفلي/لوحة البداية/إيقونة/إشعار) بيفتح `YtProUi` كتبويب 4 في Main (رجوع · بحث صوتي · ملف اشتراكات · onPause/onDestroy متوصّلين).
- الشورتس في YtProUi بقت بتتفتح كفيديو عادي (`ShortsPlayer` ممسوح) — تبويب الشورتس = T5.
- اتضاف: NewPipeExtractor v0.26.5 + استثناء nanojson (build) · قواعد R8/dontwarn (proguard) · `YtFeedJob` في المانيفست · الملفات الجديدة في `kt_manifest.txt`.
- **لسه (مقصود):** الدخول جوه يوتيوب القديم لسه بكوكيز WebView (Y4 بيحوّله للتوكن) · الرئيسية/الاشتراكات لسه من NewPipe/RSS القديم (Y3 بيبدّلهم بـ`MtYt.homeFeed/subscriptions` الموجودين أصلًا).

## أخطر نقط للبيلد (لو وقع ابعتلي اللوج)
1. Main.kt: `ensureYt()` (مكان `ensureMusic`) و`showTabFn` تبويب 4.
2. `YtNp`/`YtRss` + R8 (NewPipe + Rhino).
3. تعارض dex/resources بين NewPipeExtractor و`:youtubeapi`.
