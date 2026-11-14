# v997 — إصلاح فشل البناء (StudioUi.kt:602) على v996
**ما اتعملش compile هنا.** الإصلاح مبني على لوج GitHub Actions.

## المشكلة
`StudioUi.kt:602: No value passed for parameter 'report'` (+ نوع الـlambda ما اتحددش).
`Studio.translateToSrt(ctx, d, report, cancel = {false}, eng = null)` — النداء كان `translateToSrt(act, d) { m -> … }`، والـtrailing lambda بيروح لآخر باراميتر (`eng: String?`) مش `report`.

## الإصلاح
- `StudioUi.kt` سطر 602: `Studio.translateToSrt(act, d, report = { m -> … })` (بالاسم). مفيش تغيير في `Studio.kt` ولا أي ملف تاني.

## مش متأكد منه
- ده الخطأ الوحيد اللي ظهر في اللوج (3 رسايل لنفس السطر)؛ ممكن تظهر أخطاء تانية في مراحل بعده لو الـcompiler ما وصلهاش.
