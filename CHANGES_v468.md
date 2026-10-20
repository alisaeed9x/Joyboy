# v468 — إصلاح بناء v467 + نسخة واحدة

- Engine.kt: بلوك skipIfBaseLang كان متحشر بين `transOnly` والـ setter بتاعها (Syntax error سطر 189) — اتنقل بعد الـ setter.
- الـ workflow بيبني assembleDebug بس، والـ ABI splits اتقفلت: ناتج واحد app-debug.apk (يونيفرسال: arm64 + armeabi-v7a).
- مفيش تغييرات تانية في كود التطبيق عن v467.
