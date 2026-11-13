# v987 — حدّة الفيديو في مشغّل MT: عرض SurfaceView لكل الفيديوهات (sharp_surface)
مبني على v986. **ما اتعملش compile ولا اتجرّب على جهاز.**

## السبب (من مقارنة Player القديم بمشغّل MT)
- القديم: ExoPlayer على `SurfaceView` (الفيديو بيتركّب مباشرة بـ SurfaceFlinger/HWC وبمقياس الهاردوير والـvideo enhancer بتاع الشاشة).
- MT: نفس الـExoPlayer والـdecoder لكن على `TextureView` (GL bilinear) إلا لو الفيديو HDR — فالصورة أطرى حتى بنفس الملف والجودة.
- الديكودر/الـtrack selector/الـrenderers factory متطابقين تقريبًا، فمفيش فرق فيهم يفسّر الطراوة.

## التعديل
- `MobilePlaybackActivity.java` `updateHdrOutput()`: بيستخدم مسار الـSurfaceView الموجود (بتاع HDR) لكل فيديو لما `sharp_surface` شغّال (افتراضي شغّال)؛ الـmorph والـmini card والـShorts لسه على الـTexture زي ما هم.
- `Main.kt`: سويتش «🔍 حدّة الفيديو» تحت «الجودة الافتراضية» (مفتاح `sharp_surface`).

## أثر جانبي متوقّع
- زجاج الأزرار فوق الفيديو بيبقى لون بس من غير blur للفيديو، والـAmbient glow ممكن ما يشتغلش، لأن الـTexture بيبقى alpha 0 وقت الـSurfaceView.
- لو ظهر أي وميض/شاشة سودة أثناء التبديل مع الـmorph: اقفل السويتش وابعتلي وصفها.
