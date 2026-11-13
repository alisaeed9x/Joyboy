# v907 — مصدر واحد للواجهة = MeeTube (مسح السكنات والثيمات + عكس اتجاه الثيم)
## اللي اتمسح
- **كل ثيمات Joyboy**: يوتيوب زجاجي/فاتح/أموليد · MX · iOS · ويندوز 11/7/XP · إنفينكس · ساكورا · سماوي · سيبيا (رملي) · حماية العين · **استوديو السمات المخصّصة** · `ThemeTile` · هجرات الثيم القديمة في `Core.kt`.
- **كل سكنات Joyboy**: الأصلي · ويندوز 11 · فقاعي · حاد · هادئ · iOS · XP · أموليد + أصواتها (`SkinSound`) + `IosSkin.kt` (اتشال ومن `kt_manifest.txt`).
- فروع الأشكال القديمة جوه `Glass.kt` (كروت/أزرار/دايلوجات/حركة iOS·XP·W11·أموليد) اتشالت وفضلت حركة MeeTube بس.
- منتقيات التلفزيون: «السمات» · «اختيار السمة بعد السكن» · «نمط الحركة والتفاعل» · مربعات الثيم في ريموت الموبايل (`TvLookPad`) · مزامنة الثيم/الحركة (`TvHome`).
- `MeeTubeLink.syncMtTheme` (كان بيدفع ثيم Joyboy لـMeeTube) — اتشال بالكامل.
## اللي بقى المصدر
- `Theme.kt`: ثيم واحد بيتبني من **موارد MeeTube** (`mobile_color_*` + `meetube_accent`) بسياق فاتح/داكن حسب إعداد MeeTube. `Themes.byId(أي حاجة)` = ثيم MeeTube الحالي (الـ32 نداء في باقي الكود اشتغلوا من غير تعديل). الجانب (نظام/فاتح/داكن) = `ThemeMode` بتاع MeeTube.
- `MeeRes` (في `Theme.kt`): قراءة موارد MeeTube بالاسم (`getIdentifier`) — من غير MeeTube ما يشتغل ومن غير اعتماد على `R`.
- `ThemeMode.peek(Context)` (مودول MeeTube): قراءة الإعداد المحفوظ قبل ما MeeTube يتهيّأ.
- `MtGlass` ألوان الزجاج (fill/stroke/rim/sheen/selected/sheet/navInactive) بتتقرا من `meetube_glass_*` مباشرة (كانت أرقام منسوخة؛ الرقم القديم بقى fallback).
- `Skin.kt`: 5 دوال pass-through (`dur/rad/radF/interp/icon`) عشان نقط الاستدعاء القديمة (~60) تفضل متكومبلة — مفيش اختيار سكن.
- إعدادات ← المظهر (قسمين في `Main.kt`): بقى «نظام · فاتح · داكن» وبيكتب في `ThemeMode.set` بتاع MeeTube نفسه → يوتيوب والبرنامج يتغيّروا مع بعض.
- `JoyApp`: لو الثيم اتغيّر من جوه MeeTube، أي شاشة Joyboy ترجعلها بتتبني من جديد بالألوان الجديدة.
## ما اتلمسش (مقصود)
- **تخطيطات التلفزيون `TvSkin*` (Hub · Classic · Glass · Samsung · LG · TCL · Xiaomi)**: دي تخطيط الرئيسية مش سكن مظهر. لسه موجودة واختيارها شغّال (بالألوان المأخوذة من MeeTube).
- `MtSkin` (الخلفية المتحركة) و`MtLens`/`MtGlassView`/`GlassSwitch`/`GlassSlider`/`SheetFrost` — لسه اللي بتبني الجلاس، بس بألوان MeeTube.
## ⏭ لسه (مرحلة جاية)
- فروع ميتة متبقية: `if (false) …` و`"" == "xp"` في `Ui.kt`/`Motion.kt`/`Main.kt` — بتتكومبل (تحذيرات بس)؛ تتقطع في تنضيف لاحق بعد ما البيلد يعدّي.
- كروت الصفحات `glassCard/glassWell/floatPill` وأرقام استدارة 1.3× لسه رسم Joyboy (MeeTube ما عندوش مقابل resource ليها).
- منحنيات الحركة `MtGlass.EMPHASIZED…` نسخة من `Motion.java`.
## ⚠ مش متكومبايل ولا متجرّب
مفيش Gradle هنا. اتأكد: توازن الأقواس (نفس فرق الأصل) · `check_manifest.sh` OK · مفيش مرجع باقي لـ `Skins.cur/all/set` أو `IosSkin` أو `SkinSound` أو `ThemeTile` أو `Themes.custom/phone` أو `syncMtTheme`.
نقط متوقع تحتاج نظرة لو البيلد وقع: (1) `Theme.kt` — `ThemeMode.peek` من Kotlin و`Configuration.UI_MODE_NIGHT_MASK.inv()` (2) `TvHome.kt`/`TvLookPad.kt` حوالين الأجزاء المحذوفة (3) `Glass.kt` (`pressable`/`enter`/`exit`/`restyle` اتكتبوا من جديد) (4) `Main.kt` قسم «المظهر» ×2.
نقط تتجرّب على الجهاز: لون الشاشات على فاتح/داكن/نظام · رجوع من MeeTube بعد تغيير الثيم · الجلاس (`meetube_glass_*`) على الفاتح.
