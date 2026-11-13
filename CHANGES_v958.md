# v958 — نوافذ أزرار Joyboy فوق مشغّل MeeTube بقت نافذة MeeTube نفسها
**لم يتم compile ولا اتجرّب** (فحص توازن الأقواس بس).

## الفكرة
نوافذ MeeTube المنبثقة = `BottomSheetDialog` + `createSheetContent()` (مقبض + خلفية `bg_mobile_sheet`) + صفوف 48dp + `showPlayerSheet()` (كارت جانبي في اللاندسكيب، الـbars، إيقاف الجلاس). نوافذ Joyboy كانت PopupWindow مخصوص أو `Ui.sheet` (صفحة كاملة في اللاندسكيب).

## اتضاف (MobilePlaybackActivity.java)
- `joyboyRows(title, labels, actions)`: صفوف نصّية بنفس شكل صف MeeTube (action = null ← عنوان قسم).
- `joyboySheet(title, views)`: Views جاهزة (سلايدر/شيبس/نص) جوه نفس الشيت؛ نصوصها وأزرارها بتاخد ألوان الشيت (`restyleForSheet`).
- شغّالة للمشغّلين (MobilePlaybackActivity و MobileLocalPlaybackActivity). على التلفزيون/الريموت (مش MeeTube) الرجوع للقديم.

## اتوصّل (MtBar.kt / MtTranslate.kt)
`MtBar.meeRows/meeViews` ← بتُستخدم في: `popupRows` و`popupViews` (كل القوايم المنبثقة: 🧰 🔤 ⏱ 🗣 🎙 💬 الجسر…) · `rowsMenu` (بعنوانها) · `MtTranslate.openSheet/openSheetViews` (مسارات الصوت · سوفت سب · ترجمة أونلاين · تقدم الباتشات · أدوات الترجمة) · `translateMenu` (بعناوينها) · 📜 اللوج.

## لسه بنافذة Joyboy القديمة
قوايم `GAlert` (📝 الجمل · 🗂 النسخ · 🎨 الستايل/الخط/اللون) · 🧑 الشخصيات · 📄 دفعات الترجمة · 🔎 بحث الجمل · نافذة «الترجمة لسه ما خلصتش» عند الخروج · نوافذ ToolsUi (قص/GIF/ضغط). دول بيحتفظوا بمرجع للنافذة أو فيهم إدخال/تقدّم، ومحتاجين تحويل لكل واحدة لوحدها.
