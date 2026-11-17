# v1081
## سبب فشل البيلد
`:skipii:exportDebugConsumerProguardFiles` → AGP رفض `-dontobfuscate` لأنه global option جوه `skipii/consumer-rules.pro`.
## الإصلاح
- شلت `-dontobfuscate` من `skipii/consumer-rules.pro` (باقي القواعد زي ما هي، `-keepattributes` مسموح).
- حطيته في `app/proguard-rules.pro` — نفس التأثير (أسماء حقيقية في الـ crash reports) من غير ما يكسر الـ library.
## ملاحظات
- سطور "pruning stale source" في اللوج (96 ملف) طبيعية: ملفات قديمة مش في kt_manifest، مش السبب.
- لا تعديل في أي كود Kotlin. لسه ما اتبنيش عندي (مفيش SDK).
