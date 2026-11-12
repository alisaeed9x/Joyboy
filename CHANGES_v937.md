# v937 — المرحلة 3 من دمج المشغّل: أدوات الميديا جوه مشغّل MeeTube (ملفات / لينكات)
**اللي MeeTube عنده أصلاً (ما اتكرّرش):** مؤقت النوم بكل اختياراته + «آخر الفيديو» (`SleepTimer`) · تضخيم الصوت فوق 100% (`AudioEffects`، صف الصوت) · أوضاع التكرار بتاعته (`PLAYBACK_MODE_*`). مؤقت Joyboy (خفوت آخر 10 ثواني) مش منقول عمداً.

**اللي اتضاف (ملف جديد `MtMedia.kt` + قايمة ⚙ في `MtTranslate`):**
- **حلقة A-B:** بند في القايمة بيمشي أ ← ب ← إلغاء، وكبسولة حمرا في صف الأزرار (🅰 وقت / 🔁 A-B ✖) بتلغي بلمسة. بتتصفّر لما الفيديو يتغيّر. (فحص كل 250ms).
- **وضع التكرار:** عادي / 🔂 نفس الفيديو (`REPEAT_MODE_ONE`) / 🔁 كل الفولدر — بنفس مفاتيح Cfg بتاعة Joyboy (`loop` + `autoplay_wrap`) فالوضع مشترك بين المشغّلين. ما بنلمسش وضع MeeTube نفسه إلا لما إحنا اللي ضبطنا.
- **الحلقات:** زراري ⏮ ⏭ (الشريط · إشعار PiP · أسهم الريموت) بقوا بيعدّوا على الإضافة الأول (`JoyboyPlayerHost.onStep`) ← فولدر المكتبة (`VideoScan.cache` مرتّب بالاسم زي Joyboy على الموبايل). عند نهاية الفيديو: طابور التشغيل (`PlayQueue`) له الأولوية، وبعده الحلقة التالية ببطاقة العدّ التنازلي بتاعة MeeTube (`showNextCountdown`) لو `autoplay_next=1`، وإلا فوراً. آخر حلقة + «كل الفولدر» ← بيلفّ لأول واحدة. لو المكتبة لسه ما اتفحصتش بتتفحص في الخلفية.
- **الاستكمال + «آخر المشغّلة»:** أول ما الفيديو يجهز بيكمّل من `lastpos` (أو `recent.json`) لو المكان أكتر من 5 ثواني ومش قرب النهاية؛ بيحفظ `lastpos` كل 3ث و`recent.json` كل 20ث وعند الإيقاف/الخروج/الحلقة اللي بعدها — بنفس هوية مشغّل Joyboy (`f:الاسم:الحجم` + دعم `forceid`) فالمكتبة و«آخر المشغّلة» بيشتغلوا. (ده بيقفل فجوة «آخر المشغّلة» اللي اتسجّلت في v936.)
- **المتخفي/الموقع المحجوب:** مفيش حفظ استكمال ولا recents. فتح حلقة تانية من متخفي بيفضل متخفي.
- **بند «الليستة»** في القايمة بيفتح `PlayQueueUi`.
- **يوتيوب:** كل ده خامل (مفيش `localUri`) — MeeTube زي ما هو.

**جسر Java (`meetube/**`، معلّم `JOYBOY(v937)`):** `JoyboyPlayerHost.Provider.onStep(Activity,int)` + static `onStep` (try/catch) · `VideoInfo.endOfVideoSleep()` (من `SleepTimer.isEndOfVideo()` — عشان الإضافة ما تنقلش للحلقة التالية والمؤقت على «آخر الفيديو») · `MobilePlaybackActivity.stepVideo(int)` بتحل محل 6 نداءات `onNextClicked/onPreviousClicked` (الزراير، الـPiP، الريموت)؛ نداءات الشورتس ما اتلمستش.

**معدّل:** `MtMedia.kt` (جديد، متسجّل في `kt_manifest.txt` = 296) · `MtTranslate.kt` (hooks attach/detach، `onStep`، `joyVid`، `coverStats`، القايمة اتحوّلت لقايمة بنود) · `MtPlay.kt` (`startms` ← `MtMedia.noResume`) · `JoyboyPlayerHost.java` · `MobilePlaybackActivity.java`.

**لسه / ملاحظات:**
- أوضاع MeeTube `CLOSE`/`PAUSE` عند نهاية الفيديو ممكن تتعارض مع «التالي» بتاع الإضافة (لو المستخدم ضابط واحد منهم) — محتاجة تجربة.
- قفز بالأرقام (`tvDigit`) ← م4.
- فجوات v936 اللي لسه: تسجيل المصطادة (`WebVideos.register`/المصغّرة) و`ret`.
- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس في الملفات اللي اتعدّلت، والـAPIs بالاسم (`VideoScan.cache/scan/hasPermission`، `VideoLib.sortVideos`، `Recents.parse/upsert/toJson/ioLock`، `PlayQueue.hasNext/playNext`، `PlayQueueUi.show`، `showNextCountdown`).
