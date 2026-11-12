# v939 — توجيه التصغير حسب مصدر الفيديو + تثبيت كبسولة التنقل + تجهيز flag التلفزيون

- **تصغير فيديو المتصفح:** السبب من الكود إن MeeTube بيختار `MiniHost` آخر شاشة MeeTube، ولو مفيش Host بيرجع `BrowseView` افتراضيًا. متصفح Joyboy مش `MiniHost`، فكان التصغير يودّي لواجهة MeeTube بدل صفحة المتصفح.
- حفظت `ret=browser` كمان لمسار فيديوهات YouTube اللي اتفتحت من المتصفح، مش بس الستريمات المباشرة؛ وسياسة PiP بتتعرف على المصدر من `localUri` أو `videoId`.
- أضفت سياسة مصدر عبر `JoyboyPlayerHost.Provider.preferSystemPip()`؛ `MtWeb` بيرجع true فقط لما الفيديو اتفتح من Joyboy Browser (`ret=browser`). وقت السحب للتصغير، `MobilePlaybackActivity` يدخل PiP النظام بدل fallback لـ`BrowseView`، والمتصفح يفضل تحت الفيديو. لو الجهاز رفض PiP، المشغّل بيرجع لحالته بدل التنقل لشاشة غلط.
- **كبسولة الشريط السفلي:** كانت بتختفي تمامًا أثناء عدسة التنقل (`alpha=0`) ومستنية callback العدسة. خليتها تفضل ظاهرة بشفافية جزئية أثناء الانتقال وترجع كاملة عند انتهاء العدسة؛ كده callback متأخر/ملغي مش هيخفي التحديد.
- أضفت `mt_tv_player` كـflag تجريبي افتراضيه `false`؛ التوجيه للتلفزيون متساب legacy حتى لو اتغيّرت القيمة يدويًا، لحد اكتمال D-pad وRPC في م4.
- أضفت مستندات UI/UX وScreen Flow وInteraction Spec.

## ملفات جديدة
- `docs/PLAYER_UI_UX_BLUEPRINT.md`
- `docs/PLAYER_SCREEN_FLOW.md`
- `docs/PLAYER_INTERACTION_SPEC.md`

## ملفات معدّلة
- `meetube/mobile/src/stmobile/java/com/newtube/mobile/ui/playback/JoyboyPlayerHost.java`
- `meetube/mobile/src/stmobile/java/com/newtube/mobile/ui/playback/MobilePlaybackActivity.java`
- `app/src/main/java/com/tttt/subtitler/MtWeb.kt`
- `app/src/main/java/com/tttt/subtitler/MtMedia.kt`
- `app/src/main/java/com/tttt/subtitler/MtTranslate.kt`
- `app/src/main/java/com/tttt/subtitler/MtTvPlayer.kt`
- `app/src/main/java/com/tttt/subtitler/MtPlay.kt`
- `app/src/main/java/com/tttt/subtitler/BottomNav.kt`
- `docs/PLAYER_MERGE_STATE.md`
- `STATUS.txt`

## لم يتم تنفيذه في v939
- المرحلة 4 الكاملة: parity لكل `rAct/rState/rpc/brain/frameJpeg`، D-pad كامل، قفز بالأرقام، handoff وmirror_uri.
- `.part` وhandoff الكامل.
- حسم تعارض `CLOSE/PAUSE` مع التالي باختبار تشغيل.
- المرحلة 5 وحذف `Player.kt` و`PlayerPad.kt` و`PlayerLogic`.
- اختبار فعلي على TCL أو جهاز موبايل.

**التحقق:** لم يتم compile ولا اتجرّب على جهاز. لا يوجد `gradlew` داخل الأرشيف ولا أمر `gradle` متاح في بيئة التنفيذ الحالية. تم إجراء فحص ثابت محدود فقط؛ راجع تقرير التسليم.
