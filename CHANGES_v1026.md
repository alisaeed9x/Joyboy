# v1026 — إلغاء فلاتر تحسين الصورة (✨ وضوح/ألوان/HDR/كامل) وأزرارها
**ما اتعملش compile ولا تجربة — Actions هو الحكم.** الأساس: v1025.
- اتشال زرار ✨ من شريط MeeTube (MtBar.kt) ومن الشريط العلوي في Player.kt.
- `MtEnhance.attach` ما بقاش بيتنادى في المشغّلين (MtTranslate.kt و Player.kt) ← مفيش مؤثرات ولا VideoSink ولا «دورة ريندرر» ولا `setVideoEffects(emptyList())` على أي فيديو.
- اتشالت كمان نداء `onSurfaceResized` من ⤢ (v1020) لأنه كان للفلتر بس.
- ملف MtEnhance.kt ودوال cycleEnhance/mtEnhanceCycle فضلوا كود ميت (مش بيتنادوا) عشان التعديل يفضل صغير؛ تتمسح لو عايز.
