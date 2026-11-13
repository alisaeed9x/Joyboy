# v953 — إصلاح بناء v952 + PlayerTrace: نوافذ الشيت/Dialog + transform جوه الـTextureView

**لم يتم compile ولا اتجرّب على جهاز** (اتعمل فحص syntax للـJava بس). الأداة لسه **مقفولة افتراضياً** ومفيش أي تغيير في سلوك المشغّل.

## 1) إصلاح فشل البناء (v952)
لوج الـGitHub Actions: `compileDebugKotlin FAILED` — `MtBar.kt:780: Argument type mismatch … Function1 but Function0 expected`.
السبب: `circ()` في شريط الأدوات كان بيمرّر `{ v -> f(v) }` لـ`fsCircle` وهي بتاخد `() -> Unit` (من غير View). دلوقتي `circ()` بيمسك الـView بنفسه ويبعته للـlambda. باقي الاستدعاءات (`{ v -> … }` و`{ … }`) ماشية زي ما هي.

## 2) القوايم اللي بتتفتح كشيت / Dialog (كانت ناقصة)
الـDialog والشيت (`Ui.sheet` وغيره) نافذة منفصلة برّه شجرة الـActivity، فكان التتبّع مش شايفها. دلوقتي بيتعدّ **كل نوافذ العملية** (WindowInspector على API 29+، وWindowManagerGlobal.mViews احتياطي):
- `WINDOW OPEN <نوع>@رقم` لحظة ما تظهر: النوع (Dialog / Popup / Panel / Toast / Activity:X) + **اللي فتحها** (آخر لمسة وزرارها).
- بعد 150ms `WINDOW … details`: كلاس النافذة، attrs الـWindowManager (type · gravity · size MATCH/WRAP · offset · dim · alpha · softInput · flags · اسم الـanimation style)، مكان النافذة بالـpx والـdp والمسافات من الأربع حواف، **هل خرجت برّه الشاشة**، **اللوح (panel) اللي جوه النافذة** ومكانه، وكل الأزرار جواها.
- `WINDOW CLOSE` بمدة ما فضلت مفتوحة.
- **حركة النافذة واللوح فريم بفريم**: مفاتيح `win-Dialog@3/window` و`win-Dialog@3/panel` بنفس Δ وJANK/JUMP/REVERSE/ALPHA-JUMP؛ وأي TextureView/SurfaceView جوه نافذة بيتتبّع كمان.
- **خريطة الأزرار**: `BUTTONS (screen opened)` و`NEW/GONE` دلوقتي فيها أزرار الشيت بعلامة `[Dialog@3]`، وسطر `windows open:` بيلخّص النوافذ المفتوحة.
- **اللمس جوه الشيت/الـDialog**: بيتسجّل `TOUCH down … win=Dialog@3 hit=…` (عن طريق لفّ `Window.Callback` بـProxy بيحوّل كل حاجة للأصلي)، وكمان لمس قوايم MtBar المنبثقة (`win=Popup:…`). مواضع اللمس بقت محسوبة نسبةً للنافذة نفسها مش للشاشة.

## 3) transform جوه الـTextureView (كان ناقص)
`setTransform(Matrix)` بيحرّك/يكبّر/يلف الفيديو من جوه الـView من غير ما حدود الـView تتغيّر. دلوقتي لكل TextureView فيه مفتاح إضافي `…/xf` = مكان **الصورة الفعلي** على الشاشة (حدود الـView بعد الـmatrix) + سطر `[matrix identity|SET scale sx×sy trans tx,ty rot ° · picture visible N%]` (النسبة الظاهرة من الصورة = قياس القصّ/الزووم). الدوران (من الـmatrix ومن `rotation` للـView وأبوه) بيتسجّل `rot=` و`Δrot`، وتغيّره لوحده بيعدّ حركة.

## حدود لسه موجودة
- أنيميشن دخول/خروج **النافذة نفسها** (windowAnimations) بيتنفّذ في system_server ومش بيبان في الفريمات؛ بنسجّل اسم الـstyle بس. أنيميشن عناصر جوه الشيت (translationY/alpha على View) بيبان لأنه جوه الـpanel.
- لو الجهاز قفل الـreflection (WindowManagerGlobal/`mWindow`) هيطلع سطر `WINDOWS: … blocks …` ويرجع التتبّع للـActivity + منبثقات MtBar بس؛ ولو لفّ الـcallback فشل بس فمفيش `TOUCH` جوه الـDialog (وبيفضل `WINDOW OPEN/CLOSE` شغّال).
- SurfaceView مفيش له matrix (بيتقاس بالـlayout/scale بس).
- نوافذ `Toast` بتتسجّل open/close بس ومش بتتحسب حركة.
