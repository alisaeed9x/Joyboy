# Joyboy v1149 — إصلاح فشل البناء بعد v1148 + «جديد» على اليمين
> **لم يتم compile ولا اتجرّب على جهاز.** الأساس: v1148.

## 1) فشل البناء (Duplicate resources)
- السبب: الريبو عندك فيه app/src/main/res/values/mt_res_v1074.xml قديم (مش في الزيب) وبيعرّف mobile_color_primary · neon_ring_path · Theme.NewTube، ونفس الـ3 اتعرّفوا تاني في joy_mt_res.xml الجديد.
- الحل: joy_mt_res.xml اتسمّى mt_res_v1074.xml (في values · values-night · values-sw600dp-port · values-v27) فرفع الزيب فوق الريبو بيستبدل القديم بدل ما يتكرر. مفيش تكرار تاني بين ملفات values في الزيب.

## 2) شارة «جديد» على كروت يوتيوب
- كانت Gravity.END (شمال في RTL) ← بقت Gravity.RIGHT (يمين).
- لو «🌐 مدبلج» ظاهرة (يمين برضه) الشارة بتنزل تحتها (translationY 26dp) بدل ما تتغطى.
- YtProUi.kt (bindDub · paintDub · إضافة الـnewB).
