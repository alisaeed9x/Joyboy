# v1057 — سويب داون على يوتيوب: الكارت المصغّر بيهبط من أول لحظة في مكانه النهائي (فوق الشريطين) من غير «نتش»
**ما اتعملش compile ولا تجربة — Actions هو الحكم.** الأساس: v1056.

## السبب
أنيميشن التصغير (`MobilePlaybackActivity.computeMorphTarget`) بيسأل المضيف عن بُعد الكارت من قاع الشاشة (`getMiniCardBottomOffsetPx`)، وفي `MobileBrowseActivity` كانت بترجّع **0** (من v911 على اعتبار إن الشريط تحت). بس الكارت الحقيقي متثبّت فوق `mobile_nav_glass` (شريط MeeTube) اللي هو نفسه فوق شريط Joyboy. فالفيديو كان بيهبط عند قاع الشاشة فوق الشريطين، وبعدها الكارت الحقيقي بيظهر في مكانه فوقهم = قفزة من غير أنيميشن.
وكمان: الكارت كان مربوط `constraintRight` (يمين) والـmorph بيفترض ركن END = الشمال في RTL (v910) — فالفيديو بيهبط شمال والكارت يظهر يمين.

## التعديل
- `MobileBrowseActivity.getMiniCardBottomOffsetPx()`: = ارتفاع الـroot − `mobile_nav_glass.getTop()` − 2dp (الكارت قاعه = أعلى الشريط − 10dp، والـmorph بيطرح 12dp). `getTop()` من غير translation عشان NavHider؛ لو الشريط مش متقاس/GONE بترجع 0 (السلوك القديم).
- `activity_mobile_browse.xml`: الكارت `constraintEnd_toEndOf` + `marginLeft/marginRight` 12dp بدل `constraintRight` ← مكانه الشمال في RTL (الدايرة اللي علّمت عليها)، وبيطابق هدف الـmorph. لو عايزه يمين: ارجع `constraintRight_toRightOf` وغيّر `rtl` في computeMorphTarget.

## مراجعة بدون compile
**فحصت:** الرموز (`R.id.mobile_nav_glass` · `R.id.mobile_browse_root` موجودين في نفس layout · `View` imported) · layout واحد بس لـactivity_mobile_browse (مفيش land/sw600dp) · check_manifest OK (مفيش .kt جديد) · (ج) مين تاني بيفترض يمين: MiniCardSwipe بيقيس من `getLeft()` الفعلي (مش بيفترض جهة) وmargin الـsnap = rightMargin (12dp مضبوطة صراحةً) · (هـ) الكارت elevation 8dp فوق الشريطين (6/7dp) ومتثبّت فوقهم فمفيش تغطية.
**مش متأكد منها (من الأخطر):**
1. إن حاوية playback وroot بتاع Browse بنفس الارتفاع/الإزاحة على الشاشة (التعليق في الكود بيقول كده) — لو فيه فرق inset هيبقى فيه فرق بسيط.
2. جهة الكارت: افترضت من الدايرة إنك عايزه الشمال؛ ما اتجرّبش.
3. Search/Channel لسه بـ`getMiniCardBottomOffsetPx` بتاعهم (ما اتلمسوش).
4. لو الشريط (NavHider) كان مخفي لحظة السويب الكارت بيهبط لمكانه البيت وبعدين يتحرك بـspring (أنيميشن مش قفزة).
