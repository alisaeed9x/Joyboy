# v1058 — إصلاح فشل بيلد v1057
**السبب (من لوج Actions):** `PlayTarget.kt:253 Syntax error: Missing '}'` — في `force()` (من v1056) القوس `}` بتاع الـlambda بتاع `ResumeAsk.show` كان جوه تعليق `//` فاتبلع، فباقي الملف اتبرس غلط (وباقي الأخطاء — openController / send / isLocal / targetUrl / Modifier — ناتجة عنه).
**الإصلاح:** نقل `}` قبل التعليق في سطر `ResumeAsk.show(...)`. ما اتغيّرش أي سلوك.
**فحص:** عدد `{` = `}` في الملف بعد الإصلاح. **ما اتعملش compile — Actions هو الحكم.** الأساس: v1057.
