# v938 — قفل فجوات المرحلتين 2 و3 (من غير ما نلمس المرحلة 4): هوية الفيديو + المصطادة + `ret` جوه مشغّل MeeTube
**قرار:** المرحلة 4 (التلفزيون/الريموت) ما اتلمستش. المرحلة 5 (حذف مشغّل Joyboy) ما تتعملش دلوقتي لأن التلفزيون لسه معتمد عليه.

**اللي اتقفل (ملف جديد `MtWeb.kt`):**
- **هوية فيديو النت:** كانت `f:<لينك>` في مسار MeeTube بينما مشغّل Joyboy بيستخدم `u:…` + `WebIdent` (نفس الفيديو بجودة/لينك تاني = نفس الهوية). دلوقتي نسخة من `Player.webId` + `WebIdent.resolve` بتتحط في `MtTranslate.idOverride` — فالترجمة/التقدم/«آخر المشغّلة» القديمة بتتلاقي. `forceid` بيتربط بـ`WebIdent.bindLink` زي الأول. (المتخفي: الهوية الخام من غير ربط.)
- **تسجيل المصطادة:** `WebVideos.register` (لينك · Referer/UA/Cookie · الاسم من صفحة الموقع · نوع · صفحة الفيديو · مخفي للموقع المحجوب) — نفس `Player.webRegister`. المتخفي ما بيتسجّلش.
- **اللقطة المصغّرة:** بعد 9ث من التشغيل (وبتعيد لحد 4 مرات كل 8ث) `PixelCopy` من `SurfaceView` (أو `TextureView.bitmap`) ← `WebVideos.saveThumb`؛ لو اللقطة موجودة قبل كده مابتتكررش.
- **extra `ret`:** لما المشغّل يتقفل فعلاً (`isFinishing` ومش تدوير/تغيير سطح) بيرجّعك للمتصفح (`browser`) أو تاب يوتيوب (`yt`) زي `Player.returnToOrigin`. انتقال لحلقة تانية جوه نفس المشغّل ما بيرجّعش.

**معدّل:** `MtWeb.kt` (جديد، متسجّل في `kt_manifest.txt` = 297) · `MtPlay.kt` (نداء `MtWeb.onOpen` في `openMt`) · `MtMedia.kt` (`attach` ← لقطة · `detach` ← `ret`). مفيش تعديل في `meetube/**`.

**لسه على مشغّل Joyboy عن قصد:** `mirror_uri` (م4) · `.part` (MeeTube مش بيعرض الجزء المحمّل) · `handoff` (تسليم من مصغّر يوتيوب شغّال) · التلفزيون.
**محتاج تجربة:** (1) الرجوع بـ`ret` بعد الخروج بالـBack ومن غير PiP · (2) فيديو مصطاد يظهر في «فيديوهات الإنترنت المحفوظة» بلقطة بعد ~9ث وبنفس ترجمته القديمة · (3) تعارض `CLOSE`/`PAUSE` عند النهاية (من v937).
- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس في الملفات التلاتة، والأسماء بالاسم (`WebVideos.register/saveThumb/thumbFile`، `WebIdent.resolve/bindLink/specific`، `Sniff.nameOf/kindOf`، `MainActivity`/`BrowserActivity`).
