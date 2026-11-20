# Joyboy v1148 — حذف واجهة ميتيوب الميتة + تنضيف كود ميت
> **لم يتم compile ولا اتجرّب على جهاز.** الأساس: v1147.

## 1) ميتيوب
- اتمسحت الموديولات: :mobile · :common · :filepicker-lib · :doubletapplayerview-media3 · :slidableactivity · :sabr-media3 · :appupdatechecker2 (كل أكتيفيتيزها كانت مقفولة من المانيفست والتطبيق مابيستورد منها كلاس). فاضل محرك يوتيوب: :youtubeapi · :mediaserviceinterfaces · :sharedutils (+ :sharedtests).
- 46 مورد اتنقلوا لـ app (res/**/joy_mt_res.xml + drawables/anims): Theme.NewTube واللودرز وحركات المشغّل والألوان.
- JoyApp: شيل الاحتياطي MobileMainApplication (MtEngine بس).
- المانيفست: شيل 16 سطر tools:node=remove + إضافة ACCESS_LOCAL_NETWORK · CHANGE_WIFI_MULTICAST_STATE · MODIFY_AUDIO_SETTINGS (كانوا جايين من مانيفست :mobile).
- build.gradle: media3-exoplayer-dash صريحة (كانت جاية مع :mobile) · شيل missingDimensionStrategy.
- proguard: شيل قواعد com.newtube.**.

## 2) كود ميت في app
- 4 ملفات اتمسحت: YtLoad · YtHistoryUi · PhoneHotspot · WallStore (+ kt_manifest).
- ~45 دالة من غير أي استدعاء اتمسحت (Coverage وPlayerLogic رجعوا زي ما هم لأن الاختبارات بتستخدمهم).

## ⚠ ما اتلمسش
Cronet المدمج · قواعد keep لـ media3 وcom.liskovsoft · المعماريتين (محتاجين تجربة على جهاز). لو الـCI workflow بيشاور على meetube/mobile يتعدل.
