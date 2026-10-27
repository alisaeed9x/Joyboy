# صيغة باك الأيقونات (Icon Pack) — v598

الباك = ملف **zip** فيه:
- `pack.json` (في الجذر أو جوه فولدر واحد)
- ملفات `.svg` — **اسم الملف = اسم الأيقونة** (`home.svg` · `folder.svg` · `play.svg` · `bell.svg` · `settings.svg` · `theme.svg` · `search.svg` …). ممكن تحطها في أي فولدر جوه الـzip، أو تربطها صراحة بـ `icons` في pack.json.

## pack.json
```json
{
  "id": "my_pack",            // إجباري: حروف إنجليزي صغيرة/أرقام/_/- (2–32). المدمجة محجوزة: default outline filled duotone rounded
  "name": "باكتي",             // إجباري (≤ 48 حرف)
  "version": 1, "author": "", "description": "",
  "style": { "cornerRadius": 0, "strokeWidth": 2.0 },   // اختياري: تدوير زوايا وقت الرسم (0–6) · سمك الخط لو الـSVG مكتبش stroke-width
  "icons": { "theme": "svg/colors.svg" }                 // اختياري: ربط اسم ← ملف
}
```
## أسماء الأيقونات
الأساسية = مفاتيح `IconDefs.DEF` (131 اسم، شوف `IconNames.known()`). أسماء بديلة مقبولة: `settings`=gear · `theme`=palette · `notifications`=bell · `close`=cross · `add`=plus · `delete`=trash · `edit`=pencil … (الجدول في `IconNames.ALIASES`).
أيقونة ناقصة في الباك ← بترجع للأيقونة الافتراضية لوحدها.

## SVG المدعوم
`viewBox` (بيتحوّل لمربع 24×24) · `path` (كل الأوامر M L H V C S Q T A Z مطلق ونسبي) · `rect circle ellipse line polyline polygon` · `g` · `transform` · `fill stroke stroke-width stroke-linecap stroke-linejoin fill-rule opacity fill-opacity stroke-opacity` (كخصائص أو في `style="…"`).
- `currentColor` أو **الأسود** أو من غير fill = **لون النص** (بيتلوّن حسب السمة). أي لون تاني = لون ثابت.
- مش مدعوم (بيتجاهل مع تحذير): CSS/class · تدرّجات · clipPath/mask/filter · text · image.
- **مرفوض (الباك كله)**: `<script>` · `<foreignObject>` · `<use>` بمرجع خارجي · DOCTYPE/ENTITY.

## الحدود
zip ≤ 3 ميجا · 600 ملف · SVG ≤ 128 ك.ب · 48 طبقة و24000 أمر رسم للأيقونة.

## الاستيراد على التلفزيون
إعدادات ← المظهر ← 📦 باك الأيقونات ← ➕ استيراد. حط الـzip في `Download` أو `Movies/Joyboy` (أو ابعته من الموبايل بـ 📤) أو اختار من نظام الملفات. بيظهر تقرير فحص (❌ أخطاء بتمنع · ⚠ تحذيرات · ℹ ملخص). «📤 صدّر قالب» بيعمل zip فيه كل الأيقونات الافتراضية كـ SVG تعدّل عليها.

الباكات المدمجة بتتولّد بـ `python3 dev-tests/gen_icon_packs.py` من IconDefs.kt.
