# v687 — إصلاح SubPick.kt اللي كان بيتمسح وقت البناء
- السبب الحقيقي لـ Unresolved reference 'SubPick': مهمة pruneStaleKt بتمسح أي .kt مش مكتوب في app/kt_manifest.txt، وSubPick.kt (من v684) ماكانش فيه.
- ضفت com/tttt/subtitler/SubPick.kt في app/kt_manifest.txt.
- (تصحيح لملاحظة v686: الملف كان موجود في الريبو فعلًا، المشكلة كانت المانيفست.)
- ما اتكومبايلش هنا ولا اتجرّب على جهاز.
