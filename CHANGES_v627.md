# v627 — إصلاح فشل CI بتاع v626

- `TvHome.kt:112`: تحديد نوع `skinHost` صراحةً (`: TvSkinHost`) — الـobject كان بيشاور على نفسه (`TvPresets.show(skinHost)` في `open()`) فالـKotlin طلع "Type checking has run into a recursive problem" وفشل `compileDebugKotlin` في الـtests والـbuild.
- رفع الإصدار إلى 627 / 6.27.

## ملاحظة CI (job: verify)
- الفشل في `kt_manifest يطابق ملفات .kt`: المستودع على GitHub لسه فيه `IrTv.kt` و`RemoteActivity.kt` و`TvPanic.kt` (مش في الـmanifest عن قصد). لازم يتمسحوا من الريبو (`git rm`)، وإلا الـdiff هيفضل يفشل.
- خطأ `unknown target CPU 'armv8.2-a+dotprod+fp16'` في الـNDK ظاهر كتحذير بس، مش سبب الفشل.
