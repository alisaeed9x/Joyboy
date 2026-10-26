# v584 — تنضيف آمن (Phase C من AUDIT_09: K1 + K2 + K4-جزئي)

مفيش أي تغيير في الكود ولا في السلوك. حذف ملفات متأكد إنها مش مشاورة عليها:

- `app/src/main/assets/index.html` (2.5 ميجا) — مفيش أي `assets.open("index.html")` ولا أي ذكر ليه في الكود/الـ workflow.
- `res/drawable-nodpi/splash_art.jpg` + `res/drawable-land-nodpi/splash_art.jpg` + `splash_blur.jpg` + `logo.jpg` — مفيش R.drawable ولا @drawable ولا getIdentifier بيشاور عليهم (السبلاش بقى لون بس: splash_window → splash_bg).
- `add_jitpack.sh` — jitpack.io متضاف فعلًا في settings.gradle.kts. اتشال كمان التعليق اللي بيشاور عليه من app/build.gradle.kts.

لسه ما اتلمسش: الـ ~40 دالة (K3)، stubs بتاعة Sherpa/ML Kit (T1)، androidx.webkit (K5)، نقل CHANGES/STATUS لـ docs/history (باقي K4).
ماجرّبتش compile ولا Lint ولا جهاز.
