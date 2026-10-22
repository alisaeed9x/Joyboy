# REQUIREMENTS_TRACEABILITY
التفاصيل في REQUIREMENTS.md. كلها «مخطط» ما لم يُذكر غير ذلك.
| ID | مكوّن | مرحلة | اختبار | حالة |
|---|---|---|---|---|
| FR-NAV-1 | BackRouter + HomeActivity | 2 | BackRouterTest (7/7) + يدوي TCL | منفّذ + اختبار منطقي؛ الجهاز غير مختبر |
| FR-NAV-2 | Ui.focusable + HomeActivity | 2–3 | يدوي TCL | منفّذ (تركيز/استعادة/حركة قابلة للتعطيل)، غير مختبر على جهاز |
| FR-HOME-LAUNCH | Manifest | 2 | يدوي TCL | منفّذ، غير مختبر |
| FR-HOME-1,2 | HomeActivity | 2–3 | يدوي | هيكل مؤقت |
| FR-HOME-3 | HomePrefs + Settings | 3 | HomePrefsTest + يدوي | الإخفاء منفّذ؛ الترتيب لسه |
| FR-LIB-* | LibraryLogic + LibraryStore + HomeActivity (مكتبة/مجلدات/إعدادات) | 4 | LibraryLogicTest (15/15) + جهاز | منفّذ ومتوصّل + اختبار منطقي؛ المسح/USB/الصور غير مختبرة على جهاز. ناقص: بحث (P6)، «نقل/حذف» بيتعامل معاه السجل فقط |
| FR-HIST-* / FR-PLAY-1 | HistoryLogic/Store + PlayerLayer + شاشة السجل | 5 | HistoryLogicTest (27/27) + جهاز | منفّذ + اختبار منطقي؛ المشغّل (VideoView) غير مجرَّب على جهاز. ناقص: بحث داخل السجل (P6) |
| FR-SEARCH-* | Search | 6 | يدوي | مخطط |
| FR-YT-* | YT integration | 7–8 | — | محظور (ملف ناقص) |
| FR-THEME-1 / FR-SET-1 | Themes/Cfg | 2 | يدوي | منفّذ جزئيًا (7 ثيمات + سكون + حركة + خط)، غير مختبر |
| FR-APPS-1 | Apps | 9 | يدوي | مخطط |
| FR-SAVER-1 | HomeActivity saver | 2/10 | يدوي | منفّذ (5د، قابل للتغيير، ما بيظهرش أثناء تشغيل فيديو)؛ غير مختبر على جهاز |
