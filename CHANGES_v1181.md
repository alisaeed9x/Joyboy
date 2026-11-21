# Joyboy v1181 — تصغير حجم الـAPK (من غير حذف أي ميزة)

الهدف: من ~42 ميجا لحوالي 31 ميجا. الأرقام تقديرية (ما اتعملش compile ولا قياس APK).

1. **Cronet-embedded اتشال** (~5–7 ميجا): كان بيتستخدم بس لتسخين اتصال QUIC (MediaHostPreconnect). CronetManager.getEngine بقى بيدوّر على أي مزوّد Cronet متاح (GMS) وإلا بيرجّع null، والمتصلين بيتجاهلوه ويكملوا OkHttp عادي. الأثر المتوقع: أول فيديو يوتيوب ممكن يبدأ أبطأ بجزء من الثانية.
2. **مكتبات ميتة اتشالت** (~1 ميجا): zxing-android-embedded · media3-transformer · media3-ui · viewpager2 (صفر استدعاء في الكود والـXML).
3. **R8**: اتشال `-keep` الكامل على androidx.media3 وGlide (المكتبات جاية بقواعدها) — كود التطبيق نفسه لسه محفوظ كامل (ما اتلمسش).
4. **البرومبتات** (~1.3 ميجا): 96 قالب `prompts/<id>.txt` شبه متطابقين بقوا حزمة أسطر مشتركة `prompts/_pack.txt` (المحتوى بالحرف نفسه، اتفحص round-trip). `PromptPack` في Core.kt بيعيد بناء الملف عند القراءة؛ `PromptBuilder` بيلفّ أي قارئ تلقائيًا؛ `readAssetOrPack` لشاشة البرومبتات.
5. **صور drawable-nodpi**: PNG ← WebP lossless (نفس البكسلات بالظبط، ~0.35 ميجا).

لو البيلد/التشغيل باظ بسبب R8 (HLS/DASH مثلًا): ارجع السطر `-keep class androidx.media3.exoplayer.** { *; }` في app/proguard-rules.pro.
لو Cronet وقّع حاجة: ارجع `implementation 'org.chromium.net:cronet-embedded:' + cronetApiVersion` في meetube/SharedModules/sharedutils/build.gradle.
