# Joyboy v1085 — إصلاح الفورس كلوز عند فتح التطبيق (Skipii)

## السبب
- `JoyApp.onCreate` كان بيهيّئ Skipii كله وقت تشغيل التطبيق (شامل ShortsState وبنك الشورتس وWorkManager وغيرهم)، و`Main.showTabFn` كان بيلمس `SkipiiEmbed` في **كل** تبديل تبويب حتى أول تبويب. أي فشل في Skipii كان بيقفل التطبيق قبل ما يظهر أي حاجة ومن غير لوج.

## الإصلاح
- Skipii بقى بيتهيّأ كسول: أول ما تفتح تبويب يوتيوب بس (`SkipiiGate.ensure`)، ومفيش أي لمس لـ Skipii قبل كده.
- كل نداءات `SkipiiEmbed` في Main محاطة بـ try/catch: لو فشل Skipii بيتسجّل في اللوج (Skipii:init / Skipii:view …) والتبويب يرجع تلقائيًا ليوتيوب الداخلي بدل الإغلاق.
