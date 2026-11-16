# v1060 — تصغير meetube: شيل ترجمات اللغات غير العربي/الإنجليزي
**ما اتعملش compile — Actions هو الحكم.** الأساس: v1059.
شلت كل مجلدات `res/values-<لغة>` (ru/es/zh/de/fr/… ≈ 50 مجلد، ≈ 3.2MB قبل الضغط) من مودولات `meetube/` (common/mobile/…). فضل `values-ar` والـdefault (إنجليزي). `app/build.gradle.kts` أصلًا فيه `localeFilters ar,en` فالـAPK ما اتغيّرش.
**ما اتلمسش:** كل الكود، `baseline-prof.txt`، المودولات (كلها داخلة في settings.gradle.kts).
