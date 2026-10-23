# REQUIREMENTS_TRACEABILITY
التفاصيل في REQUIREMENTS.md. كلها «مخطط» ما لم يُذكر غير ذلك.
| ID | مكوّن | مرحلة | اختبار | حالة |
|---|---|---|---|---|
| FR-NAV-1 | BackRouter + HomeActivity | 2 | BackRouterTest (7/7) + يدوي TCL | منفّذ + اختبار منطقي؛ الجهاز غير مختبر |
| FR-NAV-2 | Ui.focusable + HomeActivity | 2–3 | يدوي TCL | منفّذ (تركيز/استعادة/حركة قابلة للتعطيل)، غير مختبر على جهاز |
| FR-HOME-LAUNCH | Manifest | 2 | يدوي TCL | منفّذ، غير مختبر |
| FR-HOME-1,2 | HomeActivity | 2–3 | يدوي | هيكل مؤقت |
| FR-HOME-3 | HomePrefs + Settings | 3/9 | HomePrefsTest (اتزاد order/move، لم يُشغَّل) | الإخفاء + **إعادة الترتيب (نقل لفوق)** منفّذين، غير مُجمَّعين |
| FR-LIB-* | LibraryLogic + LibraryStore + HomeActivity (مكتبة/مجلدات/إعدادات) | 4 | LibraryLogicTest (15/15) + جهاز | منفّذ ومتوصّل + اختبار منطقي؛ المسح/USB/الصور غير مختبرة على جهاز. ناقص: بحث (P6)، «نقل/حذف» بيتعامل معاه السجل فقط |
| FR-HIST-* / FR-PLAY-1 | HistoryLogic/Store + PlayerLayer + شاشة السجل | 5 | HistoryLogicTest (27/27) + جهاز | منفّذ + اختبار منطقي؛ المشغّل غير مجرَّب على جهاز. بحث داخل السجل: منفّذ في P6 (غير مختبر) |
| FR-SEARCH-* | SearchLogic + ExternalSearch + HomeActivity.buildSearch | 6 | SearchLogicTest (مكتوب، لم يُشغَّل) + يدوي TCL | **منفّذ، غير مُجمَّع وغير مختبر** (كيبورد/🎤/أخيرة/Back/مفيش فتح تلقائي لليوتيوب) |
| FR-YT-1 | فحص نوع يوتيوب قبل التكامل | 7 | YOUTUBE_P7_REPORT.md (مراجعة وثائق) | **منفّذ (توثيق)**: الحالة 3 (مكتبة NewPipe، سورس Subtitler مملوك) |
| FR-YT-2 | طبقة NewPipe معزولة، بدون تجاوز مصادقة | 8 | YtLogicTest (المنطق النقي، لم يُشغَّل) + يدوي | **منفّذ، غير مُجمَّع وغير مختبر** (YtNp/YtLogic/NetImage/PlayerLayer/HomeActivity) — بدون حساب/كوكيز |
| FR-YT-3 | عنصر يوتيوب واضح + معالجة الفشل + العودة للانشر + عدم فقدان المحلي | 8 | يدوي TCL | **منفّذ، غير مختبر**: شاشة يوتيوب، خطأ+إعادة، زرار التطبيق الرسمي، Back، يوتيوب خارج السجل (D-014) |
| FR-THEME-1 / FR-SET-1 | Themes/Cfg | 2 | يدوي | منفّذ جزئيًا (7 ثيمات + سكون + حركة + خط)، غير مختبر |
| FR-APPS-1 | AppsLogic + AppsStore + HomeActivity.buildApps + Manifest queries | 9 | AppsLogicTest (مكتوب، لم يُشغَّل) + يدوي TCL | **منفّذ، غير مُجمَّع وغير مختبر**: اختيارية ومخفية افتراضيًا، فتح بـ leanback intent، بدون شراء/صلاحيات واسعة |
| FR-SAVER-1 | SaverLogic + HomeActivity (tick/showSaver) | 2/10 | SaverLogicTest (مكتوب، لم يُشغَّل) + يدوي TCL | **منفّذ، غير مُجمَّع وغير مختبر**: 5د افتراضيًا (0/1/5/10/30)، ساعة/ساعة+تاريخ/سودا، ألوان خافتة، حركة كل دقيقة، لا يقاطع فيديو شغّال، تحديث الساعة عند تغيّر الدقيقة فقط |
| FR-PLAY-2 (D-011) | PlayerLayer + PlayerLogic | 5c | PlayerLogicTest (مكتوب، لم يُشغَّل) + يدوي TCL | منفّذ، **غير مُجمَّع وغير مختبر**: قايمة/ترجمة مدمجة/ملاءمة/A-B/نوم/تالي/أرقام |
| FR-SND-1 | Ui.focusable (AudioManager.FX_FOCUS_NAVIGATION_DOWN) + إعداد nav_sound | 9 | يدوي TCL | **منفّذ، غير مختبر**: اختياري، متوقف افتراضيًا؛ ممكن يصدر صوت عند إعادة بناء الشاشة |
| NFR-SEC / NFR-STAB (P11) | PlayerLayer.release · onNewIntent · YtNp/Library hardening · allowBackup=false · YtLogic.friendlyError | 11 | YtLogicTest (+4، غير مشغّل) + مراجعة P11_SECURITY_AUDIT + يدوي TCL | منفّذ (غير مُجمَّع)؛ 6 نقاط مفتوحة موثّقة |
| FR-FREE-1 | لا Pro/دفع | — | مراجعة كود | منفّذ (غير موجود أصلًا) |
| FR-THEME-1 (ألوان/خطوط/خلفيات/اسم) | Appearance + Ui(font) + HomeActivity.buildUi/buildSettings | 15 | AppearanceTest (24، PASS) + يدوي TCL | **منفّذ + اختبار منطقي؛ شكل الخطوط/التدرّجات على الجهاز غير مختبر** |
| FR-SET-1 (نسخ احتياطي/استعادة) | BackupLogic + Cfg.snapshot/restoreAll + HistoryStore.replaceFromText + HomeActivity | 15 | BackupLogicTest (PASS) + يدوي TCL | **منفّذ + اختبار منطقي؛ مُنتقي SAF/الكتابة على الجهاز غير مختبرة** |
| FR-PLAYSET-1 (إعدادات مشغّل أوسع) | PlayerLogic + PlayerLayer + buildSettings | 15 | PlayerLogicTest (PASS) + يدوي | منفّذ، غير مختبر على جهاز |
| FR-HOME-3 (ترتيب كامل) | HomePrefs + HomeActivity.orderRow/moveOrder | 15 | HomePrefsTest (PASS، move موجود) + يدوي | منفّذ (⬆⬇ للصفوف والقايمة الجانبية) |
| FR-SUB-EXT (ترجمة خارجية) | SubLogic + PlayerLayer(buildItem/onSubPicked) | 15 | SubLogicTest (PASS) + يدوي TCL | منفّذ، **غير مختبر على جهاز** (SAF/فيديو حقيقي) |
| FR-SUB-IMG (ترجمة صورية PGS/DVB) | BitmapSubView + SubLogic.bitmapRect | 15 | SubLogicTest (مستطيل الرسم) + يدوي TCL | منفّذ، **رسم غير مجرَّب** (محتاج ملف MKV بـ PGS) |
| FR-NET-1 (SMB/DLNA) | — | — | — | **غير منفّذ — D-019 ينتظر قرارك** |
| TEST/RELEASE (P12–P13) | TEST_PLAN_FINAL · adb_smoke.sh · build.yml jvm-tests · RELEASE.md | 12–13 | — | أوراق/أدوات جاهزة، **لم تُنفَّذ** |
