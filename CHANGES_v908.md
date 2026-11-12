# v908 — البرنامج كله على سكن MeeTube الأصلي (مش ألوان منقولة)
## السكن في MeeTube جاي منين
- **الثيم الأساسي:** `Theme.NewTube` في `meetube/mobile/src/stmobile/res/values/styles_mobile.xml` (Material DayNight) — هو اللي بيحدد: السويتش (`Widget.MeeTube.Switch`) · اللودر · الدايلوجات والشيتس · السكرول · شريط النظام · نافذة الحركة · الخلفية.
- **اللوحة:** `colors_mobile.xml` (`values` = فاتح · `values-night` = داكن) — `mobile_color_*` و`meetube_*`.
- **الجلاس:** drawables `bg_glass_card/pill/sheet/circle` + كلاسات `GlassView/GlassSource/GlassDrawable/NavLens/GlassSwitch/GlassSlider/SheetGlass` + `Motion.java`.
- **الجانب فاتح/داكن:** `ThemeMode` (نظام/فاتح/داكن) بيتطبّق على كل شاشة قبل ما تتبني.
## اللي اتغيّر هنا
- `themes_splash.xml`: `AppSplash` بقى `parent="Theme.NewTube"` وكل شاشات Joyboy ورثته؛ وثيم الـApplication في `AndroidManifest.xml` = `@style/Theme.NewTube` (بدل DeviceDefault). يعني السويتش واللودر والدايلوج والسكرول وشريط النظام وخلفية النافذة بقت من MeeTube مباشرة.
- `JoyApp`: `onActivityPreCreated` بيطبّق `ThemeMode.syncResources(activity, desiredNight)` على شاشات Joyboy قبل بنائها (أندرويد 10+) — نفس آلية MeeTube.
- `MeeRes.drawable(name)` (في `Theme.kt`): بيجيب drawable MeeTube الأصلي. `MtGlass.pill` و`chip` (غير المختار) بقوا `bg_glass_pill` الأصلي بنصف قطر Joyboy.
- `MtGlass.EMPHASIZED_DECELERATE/ACCELERATE` = `Motion.java` بتاع MeeTube نفسه.
## لسه (نفس الفكرة، مرحلة جاية)
- باقي أشكال `MtGlass` (`glassCard/glassWell/sheetBg/floatPill/primary`) رسم Joyboy بألوان MeeTube — تتحوّل لـ`bg_glass_card/sheet` واحدة واحدة.
- الجلاس الحيّ: `MtGlassView/MtLens/GlassSwitch/GlassSlider/SheetFrost` نسخ Kotlin؛ الهدف استبدالها بكلاسات MeeTube الأصلية (`GlassView/NavLens/GlassSwitch/GlassSlider/SheetGlass`).
- أندرويد 8–9: جانب فاتح/داكن بيتبع النظام (مفيش preCreated).
## ⚠ مش متكومبايل ولا متجرّب
نقط تتجرّب: (1) `parent="Theme.NewTube"` من موارد app (ظهور الأسلوب من مكتبة :mobile) (2) `onActivityPreCreated` مع `ThemeMode.syncResources` (3) شاشات Joyboy بعد وراثة Material (ألوان الأزرار/الحقول الافتراضية) (4) `MtGlass.pill` (`this.fill`).
