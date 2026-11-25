# v1313 — تجهيز نسخة «AW Player» المستنسخة (المرحلة أ)

## المطلوب (طلب علي 1:13 + توضيحه 1:18)
- 🎭 **نسخة من التطبيق بباكدج com.awystudio.awplayer واسم «AW Player»**: مفيش أي طريقة بدون روت تخلي تطبيق بباكدج مختلف يظهر لأندرويد بالباكدج ده، فالحل الوحيد كلون بنفس الكود بيتبني من نفس السورس:
  - `applicationId` واسم التطبيق بقوا خصائص جريدل: `-PjoyAppId=...` و`-PjoyAppLabel="..."` — الافتراضي من غيرهم Joyboy العادي من غير أي تغيير.
  - اسم التطبيق في المانيفست بقى placeholder `${appLabel}`.
- 📂 **فلتر VIEW إضافي**: التطبيقات اللي بتفرض مشغّل خارجي بالباكدج بتبعت intents بسكيمات content/file بأنواع video/* وaudio/* — ضفناها جنب فلتر http/https الموجود.
- المرحلة ب (خطوة بناء الكلون في build.yml) هتتدفع مباشرة بعد ما البيلد ده يعدّي.

## الملفات
- app/build.gradle.kts
- app/src/main/AndroidManifest.xml


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1313.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/build.gradle.kts
✏️ عدّل:   app/src/main/AndroidManifest.xml
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1312-run124` (للرجوع: Actions ← Rollback)
