# مهارات التصميم والتعديل (Design Skills) — مرجع جاهز لأي ذكاء اصطناعي

الملف `Claude_Design_Skills.zip` جنب الملف ده فيه 12 مهارة (كل واحدة فولدر فيه `SKILL.md` + مراجع). فكّه الأول:
`unzip docs/design-skills/Claude_Design_Skills.zip -d /tmp/ds` وبعدين اقرا `SKILL.md` بتاع المهارة اللي محتاجها (مش كلهم مرة واحدة).

## إزاي تستفيد منها في Joyboy
Joyboy أندرويد Kotlin (Views/WebView)، والمهارات مكتوبة للويب. **استخدمها كمرجع تصميم ومراجعة، مش كود جاهز.** ماتضيفش مكتبات ولا Compose بسببها.

| محتاج إيه | افتح |
|---|---|
| حركة/Micro-interactions/حالات الضغط والخطأ | `community/dsk-interaction-design` |
| مراجعة شاشة قبل التسليم (قواعد الاستخدام) | `community/dsk-design-critique` |
| إتاحة: تباين، حجم اللمس، WCAG 2.2 | `community/dsk-accessibility-audit` |
| توحيد Tokens (ألوان/مسافات/استدارات) | `community/design-system` · `community/dsk-design-systems` |
| باليتات/أزواج خطوط/أساليب (للإلهام) | `community/ui-ux-pro-max` (فيه `scripts/search.py` بايثون، شغّله لو حبيت) |
| رفع جودة شاشة وظيفية لمصقولة | `community/dsk-design-elevation` |
| قواعد مراجعة واجهة (محزومة جوه، من غير نت) | `community/web-design-guidelines` |
| اتجاه بصري/ثيمات/بوسترات/صفحات HTML | `anthropic/frontend-design` · `theme-factory` · `canvas-design` · `web-artifacts-builder` |

## هوية Joyboy الحالية (Vanilla Studio — v1205 → v1216)
- الباليتة: Vanilla Cream `#F5F0E7` · Latte `#E6D8C5` · Mocha `#8B6B50` · Espresso `#29241F`. الداكن (من v1216): AMOLED أسود حقيقي `#000000` بسطوح رمادية محايدة (`#0E0E0E`/`#161616`/`#2A2A2A`) ولمسة Latte — مش بني.
- التقدّم/التشغيل: فيروزي هادي (`Theme.progress`: `#3E8E88` فاتح · `#6CC4BC` داكن؛ `#4DB6AC` · `#2A8C8C` في بعض الأماكن). التحديد: Mocha/Latte.
- المصادر المركزية: `Theme.kt` · `MtGlass.kt` · `Glass.kt` · `Motion.kt` — عدّل منها قبل ما تبدّل ألوان ثابتة في الشاشات.
- متساب عمدًا: شاشات IR والتلفزيون (فوكس/D-pad) · التصفح الخفي (بنفسجي = علامة الخصوصية) · ألوان يوتيوب · الأحمر/الأخضر الدلاليين · أنيميشن اللودينج الزجاجي الفيروزي.
- تحذير تعلّمناه: ماتكتبش تعليق `//` في نص سطر فيه كود بعده — بلع `}` وكسر البيلد (v1206 → اتصلح v1213).

## فحص الأمان (اتعمل قبل الإضافة)
- ما فيش ملفات MCP ولا hooks ولا إعدادات `settings.json` في أي مهارة (بحثنا بالاسم وبالمحتوى). المهارات دي مفيهاش MCP تتحط في الريبو.
- كل الأكواد (34 بايثون + 3 cjs + 2 sh): بدون طلبات شبكة ولا `eval/exec` ولا base64. الاستثناءات المفهومة: `slide-token-validator.py` بيشغّل المدقّق الموحّد بنفس المجلد · `fetch-background.py` بيبني رابط بحث Pexels بس (بدون تحميل) · `init-artifact.sh` بيعمل `npm/pnpm install` وقت الاستخدام (ويب فقط، ماتشغّلوش في Joyboy).
- ماتلقاش حروف مخفية/bidi ولا عبارات تلاعب بالتعليمات (prompt injection) في أي `.md/.py/.json`.
- `web-design-guidelines`: القواعد محزومة محليًا (`references/command.md`، SHA-256 `d246b026…e38e0234` اتطابق مع اللي مكتوب في المهارة) وبتمنع الجلب من النت.
- الروابط الخارجية الوحيدة: خطوط Google وصور Pexels وتوثيق ومراجع. التراخيص: Apache-2.0 (Anthropic) · MIT (المجتمع) — ملفات LICENSE جوه الفولدرات.

## المصادر
anthropics/skills · vercel-labs/agent-skills · nextlevelbuilder/ui-ux-pro-max-skill · cuellarfr/design-skills (آخر سحب: 2026-11-24).
