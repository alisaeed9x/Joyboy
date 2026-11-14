# v996 — إصلاح فشل البناء (LectureCss) على v995 «الاستوديو»
**ما اتعملش compile هنا** (مفيش Gradle/SDK). الإصلاح مبني على لوج GitHub Actions لبناء v994.

## المشكلة
`LectureDoc.kt:386: Unresolved reference 'LectureCss'` — الكائن `LectureCss` (CSS صفحة HTML لمذكرة المذاكرة) مستدعى من v993 ومش متعرّف في أي ملف (موجود في v995 كمان: `LectureDoc.kt` فيه استدعاء واحد).

## الإصلاح
- `LectureCss.kt` (جديد، في kt_manifest): `object LectureCss { const val HTML }` — CSS كل الكلاسات اللي `buildHtml` بيستخدمها، RTL، فاتح/غامق تلقائي، وبيطبع كويس.
- مفيش تغيير في أي ملف تاني: الاستوديو (Studio.kt / StudioUi.kt) وكل تعديلات v995 زي ما هي.

## مش متأكد منه
- اللوج الأصلي بيخص v994؛ ممكن تظهر أخطاء تانية في كود الاستوديو الجديد (v995) لأنه ما اتعملوش compile قبل كده.
- شكل صفحة HTML ما اتشافش على جهاز.
