# v909 — الكروت والشيتس والسلايدر والحركة بقوا من MeeTube نفسه (مش نسخ)
## اللي اتغيّر
- **الكروت:** `MtGlass.glassCard` = `bg_glass_card` الأصلي (fill + stroke شعري من `colors_mobile.xml`) بنصف قطر Joyboy. `glassWell` (الحقول والأسطح الداخلية) = `bg_glass_pill` الأصلي. لو المورد مش لاقيه بيرجع للرسم القديم.
- **الشيتس:** `sheetBg` و`sheetTop` بقت قاعدتهم `bg_glass_sheet` الأصلي (مع نفس لمعة/حافة الألوان من MeeTube).
- **الشيت المثلّج:** `GDialog` بقى بينده `SheetGlass.frost(...)` الأصلي بدل `SheetFrost.kt` (اتمسح). نفس البلور والحافة بتاعة MeeTube.
- **السلايدر:** `Ui.slider` وسلايدر `Main.kt` بقوا `com.newtube.mobile.ui.common.GlassSlider` الأصلي (ألوانه من `meetube_switch_on/off` لوحده). `GlassSlider.kt` اتمسح.
- **السويتش:** `Ui.switchRow` بقى بيبني `com.newtube.mobile.ui.common.GlassSwitch` الأصلي جوه صف زي صف MeeTube (النص + السويتش، والصف clickable هو اللي بيقلب). بيرجّع `SwitchRow` (بيكشف `isChecked`) بدل `Switch`؛ التلفزيون وأي فشل بناء بيرجعوا للـIconSwitch القديم. `GlassSwitch.kt` بتاع Joyboy اتمسح. اتغيّر نداء واحد في `Main.kt` (`as SwitchRow`).
- **الأزرار الأساسية:** `MtGlass.primary` بقى كبسولة مصمتة زي زرار MeeTube الرئيسي (من غير تدرّج).
- **الكبسولات العايمة:** `floatPill` (دوك الميديا وشريط الحالة) بقى بزجاج وحافة رسائل MeeTube (`meetube_toast_glass/rim`)، وشريط الحالة بيستدعي `SheetGlass.frost` زي `MobileSnackbar` (بلور حقيقي للصفحة تحته).
- **الحركة:** `MtGlass.pop` و`frostIn` و`press` بقوا بيستخدموا `Motion.pop/nudge/frostIn` الأصليين و`press_3d.xml` (stateListAnimator) — ولو الحالة فيها قيم مخصوصة بيفضل الكود القديم.
- **نضافة:** اتمسح `MtLens.kt` و`SheetFrost.kt` و`GlassSlider.kt` وكلاسات `MtGlassSource/MtGlassView` (كلها كود ميت — مفيش حد بيستخدمها) + اتشالوا من `kt_manifest.txt`.
- **هيدر الصفحات:** `MtHeader.kt` جديد — نفس تركيبة هيدر MeeTube (زرار رجوع 40dp `bg_glass_circle` + `ic_mobile_back_arrow` + `press_3d`، عنوان 20sp medium). متوصّل بهيدر `FtUi` (صفحات النقل) على الموبايل/التابلت؛ التلفزيون زي ما هو.
- **دوك الميديا:** الكبسولة المقفولة بقت `GlassDrawable.apply` الأصلي فوق `GlassSource` الصفحة (بلور حي وبيتابع السحب). `MtFloat` بيسجّل المصدر لكل شاشة (`MtFloat.sourceFor`). الموسّعة (مستطيل) لسه `floatPill`، ولو الشاشة مفيهاش شريط عايم (مفيش مصدر) برضه `floatPill`.
## لسه
- `MtHeader` متوصّل بـ`FtUi` بس؛ هيدرات الشاشات التانية (المتصفح · المخزن · العناية…) تتوصّل واحدة واحدة. وهو من غير بلور حي فوق المحتوى (محتاج `GlassSource` حوالين القايمة).
- شاشات المشغّل/الريموت/المكتبة اللي بتبني ألوانها بإيديها (PAD_FILLS) و`NavAnim` لسه.
## ⚠ مش متكومبايل ولا متجرّب
نقط تتجرّب: (00) `GlassDrawable.apply` على الدوك (ظهوره لما يتسحب · تبديل مقفول/موسّع) و`MtHeader` على صفحات النقل (اتجاه السهم في RTL). (0) `GlassSwitch` الأصلي داخل `switchRow` (محتاج ثيم Material — لو فشل بيرجع للقديم) و`SheetGlass.frost` على StatusStrip. (1) `SheetGlass.frost(t, window, corner)` على الدايلوج (حافة مزدوجة محتملة لأن `sheetBg` فيها لمعة وSheetGlass بيضيف لمعة تانية) (2) `GlassSlider` الأصلي جوه شاشات Joyboy (3) `press_3d` مع الـanimate() القديمة على نفس الـview (4) `bg_glass_card` بدل الجرادينت الشفاف فوق خلفية MtAurora.
