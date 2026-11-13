# v885 — إصلاح فشل البناء في v884
## ✅ اللي اتعمل
- `app/build.gradle.kts`: إضافة `:common` و`:sharedutils` و`:mediaserviceinterfaces` كاعتماديات مباشرة على `:app`. السبب: `MeeTubeLink.syncMtTheme` (v884) بيستخدم `MainUIData` من `:common`، و`:mobile` بيعرّفه بـ `implementation` فمكانش بيتمرر → `Unresolved reference 'common'` / `MainUIData` / `Cannot infer type`.
- مفيش أي تغيير تاني.
## ⚠ مش متجرّب
مش متكومبايل هنا — ابعت اللوج لو ظهر خطأ تاني.
