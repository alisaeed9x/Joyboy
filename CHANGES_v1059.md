# v1059 — شيل صور السبلاش (تصغير حجم المشروع)
**ما اتعملش compile — Actions هو الحكم.** الأساس: v1058 (فيها إصلاح قوس PlayTarget.kt).

## اتشال
`splash_1/2/3.jpg` · `splash_wide.jpg` (drawable-nodpi) · `splash_win.jpg` (xhdpi/xxhdpi/xxxhdpi) — ≈ 2.3MB.

## التعديل
- `SplashArt.kt`: `show()` بقت فاضية (نفس التوقيع) ← نداءات Main.kt وTvHome.kt ما اتلمسوش.
- `splash_window_joy.xml` (عادي / television / land-television): اتشال عنصر الصورة، فضل اللون البني `joy_splash_bg` سادة.
- فحصت بـgrep: مفيش أي مرجع تاني لأسماء الصور (kt/java/xml).
