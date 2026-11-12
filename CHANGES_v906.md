# v906 — الشريط السفلي بقى بنفس زجاج MeeTube الأصلي (مصدر واحد)
## المشكلة
شريط يوتيوب (MeeTube) كان بيستخدم `GlassView`/`GlassSource`/`NavLens` الأصليين، وشريط Joyboy (ميديا · يوتيوب · متصفح · العناية · المزيد) كان بيستخدم نسخة Kotlin منقولة (`MtGlassView`/`MtGlassSource`/`MtLens`): بلور 14dp بدل 8، من غير حبيبات ولا انكسار (أندرويد 13+) ولا الحافة اللامعة الأصلية، استدارة 28 بدل 34، وtint رقم ثابت مش `meetube_glass_tint`. جوه شاشة MeeTube كان بيترسم فوق طبقة شبه معتمة (من غير مصدر يبلّره).
## اللي اتغيّر
- `res/layout/joy_nav_glass.xml` (جديد): `GlassView` الأصلي بنفس مقاسات `mobile_nav_glass` (استدارة 34 · حشو 6 · ظل 6 · `meetube_glass_tint`).
- `MtGlassView.kt`: اتضاف `JoyGlassSource` (بيورّث `GlassSource` الأصلي ويرجّع الخلفية المتحركة MtAurora للزجاج) و`MeeGlass` (سياق فاتح/داكن حسب ثيم Joyboy، بنّاء الكبسولة، بنّاء العدسة). `MtFloat.build` بيستخدمهم بدل النسخة المنقولة. هوامش الشريط = 16 جنب / 12 تحت زي MeeTube.
- العدسة = `NavLens` الأصلي؛ بتشتغل لو سويتش Joyboy «عدسة الشريط» شغّال **و** إعداد MeeTube «Tab switch animation» شغّال (`NavLens.enabled`) ومش جهاز ضعيف. الاهتزاز (jiggle) بنفس الشرط.
- `MeeTubeBar.kt` (الشريط جوه شاشة MeeTube): نفس الكبسولة + بيبلّر `mobile_blur_target` (نفس صفحة MeeTube اللي شريطها العلوي بيبلّرها) بدل الطبقة المعتمة.
## اللي لسه مش موحّد (مقصود، مرحلة جاية)
- كروت الصفحات والشيتس (`MtGlass.glassCard/sheetTop/…`) لسه رسم Joyboy بأرقام منسوخة من MeeTube (الأرقام نفسها متطابقة، بس مش بتقرا من موارد MeeTube).
- اتجاه الثيم: لسه Joyboy → MeeTube (`MeeTubeLink.syncMtTheme`)، مش العكس.
- منحنيات الحركة (`MtGlass.EMPHASIZED…`) نسخة من `Motion.java` مش المصدر نفسه. الأماكن التانية اللي بتستخدم `MtGlassView` (غير الشريط) ما اتلمستش.
- ⚠ مش متكومبايل ولا متجرّب (مفيش Gradle ولا جهاز هنا). اتفحص: توازن الأقواس، `check_manifest.sh`. نقاط تتجرّب أول ما يتبني: (1) `@color/meetube_glass_tint` بيتحل من layout في موديول app (فلافور stmobile)؟ (2) لون الشريط في ثيم فاتح على جهاز نظامه داكن (MeeGlass.ctx) (3) العدسة بتتحرك صح فوق شريط Joyboy (بنص الخلايا الـ6) (4) الخلفية المتحركة بتبان مبلّرة تحت الشريط.
