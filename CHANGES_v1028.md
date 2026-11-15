# v1028 — إصلاح البيلد + سبب الـ60MB
**ما اتبنّاش عندي — Actions هو الحكم.** الأساس: v1027.

## 1) البيلد كان واقع في v1027 (غلطتي)
- تقرير الحجم اللي ضفته كان بيستخدم `java.util.zip.ZipFile` جوه Gradle فاتفسّرت `java` على إنها إضافة java ← `Unresolved reference 'util'`. اتصلح بـ`import java.util.zip.ZipFile` أول الملف.

## 2) سبب الـ60MB (من لوج البناء)
- الـCI بيشغّل `gradle assembleDebug` (خطوة «Build Universal Debug APK only»)، وbuildType الـdebug كان `isMinifyEnabled=false` ← مفيش R8 ولا تصغير موارد خالص. إعدادات التصغير اللي في release ما كانتش بتتطبق على الـAPK اللي بتنزّله.
- دلوقتي debug بيتصغّر بنفس قواعد release (`proguard-rules.pro`) + `shrinkResources`. مفتاح: `joyMinify` في gradle.properties (الافتراضي true).
- ⚠ R8 ما اتجرّبش على كود MeeTube من v878 (الـCI debug بس). لو البيلد وقع بأخطاء R8 ابعت اللوج؛ ولو التطبيق بيقفل/بيتصرف غلط: `joyMinify=false` يرجّع زي الأول.
- تقرير الحجم بقى بيشتغل مع assembleDebug كمان.

يفضل زي v1027: arm64 بس (`joyAbi`) + فلتر اللغات.
