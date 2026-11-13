# v949 — إصلاح فشل البيلد (MtEnhance.kt)
- **السبب:** `GlUtil.GlProgram` مش موجودة في media3 1.10.1 — الكلاس بقى `androidx.media3.common.util.GlProgram` (top-level). خطأ سطر 289 كان نتيجة نفس المشكلة (`prog.use()` اتفسّرت على إنها Kotlin `use`).
- **اللي اتغيّر:** import جديد + `GlProgram(VERT, FRAG)` بدل `GlUtil.GlProgram(...)`. مفيش أي تغيير تاني.
- **لسه:** لم يتم compile ولا اتجرّب (مفيش نت/SDK هنا). ممكن يظهر خطأ تاني بعد ده لو في حاجة تانية في الملف.
