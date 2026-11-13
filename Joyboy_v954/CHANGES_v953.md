# v953 — إصلاح بناء v952 + PlayerTrace: نوافذ الشيت/Dialog + transform جوه الـTextureView

**لم يتم compile ولا اتجرّب على جهاز** (اتعمل فحص syntax للـJava بس). الأداة لسه **مقفولة افتراضياً** ومفيش أي تغيير في سلوك المشغّل.

## 1) إصلاح فشل البناء (v952)
لوج الـGitHub Actions: `compileDebugKotlin FAILED` — `MtBar.kt:780: Argument type mismatch … Function1 but Function0 expected`.
السبب: `circ()` في شريط الأدوات كان بيمرّر `{ v -> f(v) }` لـ`fsCircle` وهي بتاخد `() -> Unit` (من غير View). دلوقتي `circ()` بيمسك الـView بنفسه ويبعته للـlambda. باقي الاستدعاءات (`{ v -> … }` و`{ … }`) ماشية زي ما هي.

## 2) القوايم اللي بتتفتح كشيت / Dialog (كانت ناقصة)
الـDialog والشيت نافذة منفصلة برّه شجرة الـActivity، فكان التتبّع مش شايفها. دلوقتي بيتعدّ **كل نوافذ العملية** (WindowInspector على API 29+، وWindowManagerGlobal.mViews احتياطي):
- `WINDOW OPEN <نوع>@رقم` لحظة ما تظهر: النوع (Dialog / Popup / Panel / Toast / Activity:X) + **اللي فتحها** (آخر لمسة وزرارها).
- بعد 450ms (بعد أنيميشن الدخول) `WINDOW … details`: كلاس النافذة، attrs الـWindowManager (type · gravity · size MATCH/WRAP · offset · dim · alpha · softInput · flags)، مكان النافذة بالـpx والـdp والمسافات من الأربع حواف، **هل خرجت برّه الشاشة**، **الـcontent اللي جواها** ومكانه النهائي، وكل الأزرار جواها.
- `WINDOW CLOSE` بمدة ما فضلت مفتوحة، ولو الشيت GDialog: `· Nms after dismiss()` = مدة أنيميشن الخروج الحقيقية (من أول `dismiss()` لحد ما النافذة اتشالت).
- **حركة الشيت فريم بفريم**: `win-Dialog@3/window` (النافذة نفسها) و`win-Dialog@3/content` (الـ`android.R.id.content` اللي `Glass.enter/exit` بيحرّكه: alpha + scale + translationY، ~250ms دخول و200ms خروج) بنفس Δ وJANK/JUMP/REVERSE/ALPHA-JUMP. الـcontent بيتختار مرة واحدة ويفضل ثابت (قبل كده اللوح كان بيتختار بمقاس وكان ممكن يتبدّل وسط الأنيميشن). أي TextureView/SurfaceView جوه نافذة بيتتبّع كمان.
- **أنيميشن النظام للنافذة** (windowAnimations): بيتقرا من الـresources الحقيقية — الـstyle (أو الافتراضي من الثيم) و ENTER/EXIT بنوع كل حركة ومدتها وinterpolator. بيتنفّذ على سطح النافذة في system_server فمش بيتقاس فريم بفريم، بس تعريفه مسجّل بالظبط.
- **خريطة الأزرار**: `BUTTONS (screen opened)` و`NEW/GONE` فيها أزرار الشيت بعلامة `[Dialog@3]`، وسطر `windows open:` بيلخّص النوافذ المفتوحة.
- **اللمس جوه الشيت/الـDialog** من طريقين: (1) `GDialog.dispatchTouchEvent` بيبلّغ PlayerTrace مباشرة (من غير reflection — بيغطّي كل شيتات `Ui.sheet`)، (2) لفّ `Window.Callback` بـProxy للـDialogs التانية (AlertDialog وغيره). التكرار بيتفلتر. بيتسجّل `TOUCH down … win=Dialog@3 hit=…` وكمان لمس قوايم MtBar المنبثقة (`win=Popup:…`). مواضع اللمس محسوبة نسبةً للنافذة مش للشاشة.
- `GDialog.show()` بيسجّل `MARK sheet show (<الكلاس>)` و`dismiss()` بيسجّل `MARK sheet dismiss() called`.

## 3) transform جوه الـTextureView (كان ناقص)
`setTransform(Matrix)` بيحرّك/يكبّر/يلف الفيديو من جوه الـView من غير ما حدود الـView تتغيّر. دلوقتي لكل TextureView فيه مفتاح إضافي `…/xf` = مكان **الصورة الفعلي** على الشاشة (حدود الـView بعد الـmatrix) + سطر `[matrix identity|SET scale sx×sy trans tx,ty rot ° · picture visible N%]` (النسبة الظاهرة من الصورة = قياس القصّ/الزووم). الدوران (من الـmatrix ومن `rotation` للـView وأبوه) بيتسجّل `rot=` و`Δrot`، وتغيّره لوحده بيعدّ حركة.

## حدود لسه موجودة
- أنيميشن النافذة اللي بيشغّله **النظام** على سطحها بيتسجّل تعريفه ومدته بس (مش فريم بفريم). على شاشات زي MIUI/HyperOS ممكن النظام يستبدل الأنيميشن ده بتاعه، فالتعريف المسجّل هو اللي في الـresources مش اللي اتنفّذ فعلاً.
- لو الجهاز قفل الـreflection هيطلع سطر `WINDOWS: … blocks …` ويرجع التتبّع للـActivity + منبثقات MtBar بس. لمس شيتات `GDialog` بيفضل شغّال (مش بيعتمد على reflection)؛ لمس الـDialogs التانية بس هو اللي بيتأثر.
- SurfaceView مفيش له matrix (بيتقاس بالـlayout/scale بس).
- نوافذ `Toast` بتتسجّل open/close بس ومش بتتحسب حركة.
