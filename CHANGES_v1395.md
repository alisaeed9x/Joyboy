# v1395 — تنظيف المفاتيح الميتة (قرارات علي 28/11: «اللي محتاج يتحذف يتحذف»)

1. [حذف] مفتاح omni_mode الميت — كان بيتكتب في TrWizard وصفر قراءة في الكود كله
2. [حذف] مفتاح rstt_on الميت — نفس الحال
3. [حذف] ModelWatch بالكامل (ملف + استدعاءاته في Main وPlayer): فحص «موديل flash-lite أحدث» وإشعاره وعلامة 🆕 — ميتة من v224 (بترجع قوايم فاضية دايمًا)
4. [حذف] صف «📱 شاشة نضيفة في الوضع الطولي» من إعدادات المشغّل — التوجل كان ظاهر ON ومش بيعمل أي حاجة (مفتاحه yt_portrait_clean مش مقروء)
5. [حذف] احتياطي gh_token من GhCi.token(): التوكن المدمج المشفّر بس، وأي قيمة قديمة متخزنة بتتمسح تلقائيًا أول استخدام (سطر الحماية في النسخ الاحتياطية فضل عشان النسخ القديمة ما ترجعوش عند الاسترجاع)

مفيش أي تغيير في السلوك الشغّال — كل المحذوف كان بلا أثر فعلي.


## 🗂️ الملفات اللي اتغيّرت (أوتوماتيك)
```
➕ أضاف:  CHANGES_v1395.md
✏️ عدّل:   STATUS.txt
✏️ عدّل:   app/kt_manifest.txt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/GhCi.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Main.kt
🗑️ حذف:   app/src/main/java/com/tttt/subtitler/ModelWatch.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Player.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/Settings.kt
✏️ عدّل:   app/src/main/java/com/tttt/subtitler/TrWizard.kt
```
🔙 نسخة احتياطية قبل التعديل: `backup-v1394-run203` (للرجوع: Actions ← Rollback)
