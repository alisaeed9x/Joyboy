# v1257 — بلاجن: البنية التحتية (مرحلة 1)
## بلاجن
1. مودول جديد plugin-aniyomi: APK منفصل (com.tttt.joyboy.plugin.aniyomi) موقّع بنفس مفتاح Joyboy. فيه جسر (ping، وقايمة إضافات Aniyomi المتثبّتة). تشغيل الإضافات نفسها هييجي في مرحلة لاحقة.
2. البيلد بقى بيكتشف إيه اللي اتغيّر: تغيير في التطبيق بس = ريليز التطبيق بس، تغيير في البلاجن بس = ريليز البلاجن بس (tag ثابت plugin-aniyomi)، الاتنين = الاتنين.
3. صفحة بلاجن بتقرا plugin.json المنشور جنب الـAPK (النسخة وsha256)، ولو لسه مش منشور بتعرض «قريبًا».
⚠ ما اتعملش compile ولا اتجرّب على جهاز: الـCI هو اللي هيتأكد.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1257.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Plugins.kt
➕ أضاف:  plugin-aniyomi/build.gradle.kts
➕ أضاف:  plugin-aniyomi/src/main/AndroidManifest.xml
➕ أضاف:  plugin-aniyomi/src/main/java/com/tttt/joyboy/plugin/aniyomi/BridgeProvider.kt
✏️ عدّل:   plugins/index.json
✏️ عدّل:   settings.gradle.kts
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1256-run68` (للرجوع: Actions ← Rollback)
