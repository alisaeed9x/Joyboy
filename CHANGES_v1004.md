# v1004 — إصلاح فشل البيلد بتاع v1003 (4 ملفات، 5 أخطاء compile)

**ما اتعملش compile هنا** (مفيش Gradle/SDK). الإصلاحات كلها صغيرة ومحصورة في الأسطر اللي اللوج شاور عليها.
الأساس: شجرة v1003 كما هي، من غير أي تغيير تاني.

| الملف | الخطأ في اللوج | الإصلاح |
|---|---|---|
| `StudioPrivacy.kt` (:227) | `val when = ...` — `when` كلمة محجوزة في Kotlin (Syntax error + «'when' must be exhaustive») | اتسمّت `whenTxt` |
| `StudioIntel.kt` (:430–431) | `Unresolved reference EVENT_BEGIN_TIME / EVENT_END_TIME` — دول مش في `Events` | بقوا `CalendarContract.EXTRA_EVENT_BEGIN_TIME / EXTRA_EVENT_END_TIME` (ثوابت الـIntent الصح) |
| `Player.kt` (:2961 + 5 مناديات: 3109 / 3482 / 3504 / 7168 / 7236) | `No value passed for parameter 'onDismiss'` — بعد ما اتضاف `footer` في v999 بقى آخر باراميتر، فالـtrailing lambda راح له بدل `onDismiss` | `showSheet(anchor, col, maxDp, tag, footer = null, onDismiss)` — `footer` قبل `onDismiss`. المناديات مش محتاجة أي تعديل (واحد بس بيمرّر `footer = applyRow` وبرضه شغال) |
| `CallChatUi.kt` (:187) | `Unresolved reference 'renderAll'` — `bubbleRow` بتنده `renderAll` قبل ما تتعرّف (local fun مفيهاش forward reference) | متغيّر `renderAllFn` متعرّف مع `lastSig`، `bubbleRow` بتندهه، واتربط بـ`renderAll` قبل أول نداء |

سلوك التطبيق ما اتغيّرش.
