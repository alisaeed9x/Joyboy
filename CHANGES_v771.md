# v771 (على v770) — إصلاح خطأ الكومبايل في PlayerPad.kt

## اللي اتغيّر
- بيلد v770 وقع بالخطأ: `PlayerPad.kt:269 Unresolved reference 'TEXT_DIRECTION_ANYRTL'`.
- الاسم الصح في أندرويد: `View.TEXT_DIRECTION_ANY_RTL` (فيه underscore). اتصلّح في سطر لوج الموبايل (📜).
- مفيش أي تغيير تاني. `versionName` بقى 7.71.

## الملفات
`PlayerPad.kt` · `app/build.gradle.kts` (7.71)

⚠ ما اتكومبايلش محليًا (الإصلاح مبني على رسالة الخطأ في لوج GitHub Actions).
