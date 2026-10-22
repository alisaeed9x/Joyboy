# INTEGRATION_CONTRACTS (مسودة — تتكمّل في المرحلة 1)
- **BackRouter (منفّذ):** `register(name){Boolean}` / `dispatch(): String?` — طبقات بترتيب التسجيل، الأحدث أولًا؛ أول handler يرجّع true بيستهلك الضغطة. المستهلكون: TvActivity (سكون/بحث/PIN)، حوارات GDialog، WebView الدخول.
- **YouTube:** عبر Intent/واجهة منفصلة، تعطّله ما يعطّلش المكتبة.
- **سجل المشاهدة/الإعدادات:** نسخة واحدة (Cfg + recents.json في Subtitler الحالي).
- **المكتبة (منفّذ):** `LibraryStore.items` (قراءة فقط من الـ Main) ← يتحدّث بـ `scan(ctx){onUpdate}` (خيط خلفي، نتيجة على الـ Main، `gen` يلغي الفحص القديم). كاش `library.v1.txt` (ترويسة VTL-LIB-1؛ تالف/نسخة تانية → فاضي). الاستبعاد: `Cfg lib_excl` (CSV بـ folderKey) ويتطبّق وقت العرض فقط (المكتبة نفسها بتحتفظ بكل الفيديوهات). الفرز: `Cfg lib_sort`.
- **السجل (منفّذ):** `HistoryStore.entries` ← `record(ctx, item, pos, dur, ended)` فقط من PlayerLayer. ملف `history.v1.txt` (VTL-HIST-1). الربط بالمكتبة: `HistoryLogic.find(entry, items)` (key ثم uri). المستهلكون: صف متابعة المشاهدة، شاشة السجل، (P6) البحث.
- **المشغّل (منفّذ، D-008):** `PlayerLayer.open(PlayQueue)` / `close()` / `handleKey()` / `onPause()`. الرجوع حصرًا عبر BackRouter. خارجه: ما يعرفش HomeActivity غير `onClosed`.
- **ترتيب طبقات الرجوع:** screen < player < saver. معالج المشغّل بيرجّع false لو السكون ظاهر.

