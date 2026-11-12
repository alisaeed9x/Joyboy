# v878 — شيل PlayTube + دمج MeeTube كامل جوه متصفح يوتيوب (فوق v877)
## 🗑 PlayTube اتشال بالكامل
- مودول `:playtube` + Hilt/KSP/Compose/Room + `PlayTubeLink` + زرار PlayTube. `JoyApp` رجع `Application` عادي.
- تبويب/بلاطة «يوتيوب» رجعوا لبوابة Joyboy (كانوا محوّلين لـPlayTube من v876).
## 📺 MeeTube مدموج (GPL-3.0 — `meetube/LICENSE-MeeTube`)
- مودولات جديدة تحت `meetube/`: `:mobile` (مكتبة بدل تطبيق) · `:common` · `:youtubeapi` · `:mediaserviceinterfaces` · `:sharedutils` · `:appupdatechecker2` · `:sabr-media3` · `:doubletapplayerview-media3` · `:slidableactivity` · `:filepicker-lib`.
- زرار **MeeTube** فوق في بوابة يوتيوب (موبايل/تابلت): دوسة = قايمة (▶ افتح MeeTube · 🔁 «يوتيوب = MeeTube بدل البوابة» — `mt_replace` افتراضي مقفول).
- MeeTube بمشغّله (media3 + SABR) وحسابه وإعداداته؛ Back من أول شاشة يرجّعك لبوابة يوتيوب.
- **Lazy**: `MobileMainApplication.boot()` بيشتغل أول فتحة بس (ContextWrapper حوالين `JoyApp`، وكولباكات اللايف-سايكل متحوّلة للـApplication الحقيقي) ← مفيش بطء في فتح Joyboy. `JoyApp` بيبعت `onTrimMemory/onLowMemory` لـMeeTube.
- اتشال من MeeTube: أيقونات اللانشر (activity-alias) · روابط يوتيوب/المشاركة (`IntentRouterActivity`) · محدّثه الذاتي (`IN_APP_UPDATES=false`) · تبديل أيقونة التطبيق (inert). J2V8 من Maven بدل ~120MB `.so` في الريبو.
## 🛠 البيلد (تغيير كبير)
- AGP 8.10.1 → **9.2.1** · Gradle 8.11.1 → **9.6.1** · JDK 17 → 21 (CI) · كوتلن 2.4.0 → **2.3.21 المدمج** (من غير `kotlin-android`) · الإصدارات المشتركة من `meetube/SharedModules/constants.gradle`.
- الجذر `build.gradle` (Groovy) بدل `build.gradle.kts`؛ `android.nonTransitiveRClass=false`؛ اتشالت تثبيتات lifecycle/core (v874) وبلجن Hilt/KSP وبلوك `splits` (معطّل أصلًا) وبلوك `kotlin{}`؛ Glide 4.16 → 5.0.9؛ `largeHeap`؛ `jniLibs.useLegacyPackaging` (native مضغوط في الـAPK).
- قواعد R8 جديدة لـ MeeTube/J2V8/media3 في `proguard-rules.pro`.
## ⚠ مش متجرّب
مفيش Gradle ولا نت هنا — **مش متكومبايل**. أكتر نقط متوقع تحتاج جولة تصليح: DSL الجديد في `app/build.gradle.kts` · دمج الـmanifests · تعارض إصدارات المكتبات. ابعت اللوج لو فشل.
نقطة معروفة: «إعادة تشغيل التطبيق» جوه إعدادات MeeTube بتقفل العملية كلها (Joyboy كمان) وبتفتح MeeTube.
