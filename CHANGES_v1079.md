# Joyboy v1079 — إصلاح فشل بناء v1078 (موارد)

`:app:processDebugResources` — 3 أسباب (كلها بقايا MeeTube :mobile المحذوف):
- `animator/neon_arc` ناقص (avd_neon_ring / avd_frosted_ring بيشاوروا عليه): اتعاد كتابته في `res/animator/neon_arc.xml` (trimPathEnd/Start بتمدّد وتلحيق).
- `layout/joy_nav_glass.xml` (attrs `glassTint`/`glassCornerRadius` مش موجودة): الملف مش في الزيب بس موجود في الريبو (بقايا) ومفيش كود بيندهه — حطيت نسخة فاضية بنفس الاسم تغطي عليه. الأفضل تمسحه من الريبو.

## الملفات
- جديد: `res/animator/neon_arc.xml` · `res/layout/joy_nav_glass.xml`
- ما اتعملش compile.
