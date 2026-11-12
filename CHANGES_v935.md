# v935 — تكملة المرحلة 2: باقي أدوات الترجمة جوه مشغّل MeeTube
قايمة ⚙ (حوار زجاجي GAlert) بقت فيها كل ده، كله بيستخدم منطق Joyboy الموجود (من غير إعادة كتابة Engine/Store):
- **🗂 النسخ المحفوظة:** احفظ الحالية / بدّل لنسخة (`Engine.versions/applyVersion/adoptDialect` + `conv_dialect`).
- **🎨 ستايل السبتايتل:** حجم ±10 · شفافية الخلفية (25/45/70/100/من غير) · الخط (كل خطوط Joyboy) · حركة الدخول · لون موحّد — بنفس مفاتيح `Cfg` بتاعة مشغّل Joyboy (`sub_scale/sub_bgopa/sub_nobg/sub_font/sub_anim/sub_uni_*`) فالستايل مشترك بين المشغّلين.
- **📄 سبتايتل جانبي:** اختار `.srt/.vtt` (`SubPick`)؛ عربي يتعرض علطول، غير كده يتترجم (جيميناي لو في مفتاح وإلا جوجل — `SubFileTr.translate`) ويتعرض من غير محرك. «✖ شيل» بيرجّع الوضع العادي. بيتصفّر عند تغيير الفيديو.
- **🎙 دبلجة:** `Dub.build` من الجمل المترجمة (أو السبتايتل الجانبي) ثم `DubPlay` على ExoPlayer بتاع MeeTube؛ وقف/إلغاء؛ تبديل صوت سلمى/شاكر.
- **⬇ تحميل:** للينك المباشر الشغّال بنفس الهيدرز والصوت المنفصل (`Downloader.Job`) كمهمة في تبويب «المهام». (`VideoInfo.audioUrl()` جديدة في `JoyboyPlayerHost`.)
- **qlist:** بقت مسموحة في `MtPlay.decide` — بيتشغّل اللينك المختار (`url` + `aurl`)؛ ما فيش تبديل جودات من جوه المشغّل (أي طلب فيه `ytid` لسه على Joyboy).
- معدّل: `JoyboyPlayerHost.java` · `MobilePlaybackActivity.java` (سطر `audioUrl`) · `MtPlay.kt` · `MtTranslate.kt`. مفيش ملف `.kt` جديد (`kt_manifest.txt` زي ما هو).
- **قرارات م2:** مسارات الصوت = قايمة MeeTube الأصلية (`showAudioTrackSheet`) من غير نسخ · «bottom-sheet» = حوار GAlert الزجاجي (نفس شكل MeeTube الزجاجي) · لسه على مشغّل Joyboy عن عمد: يوتيوب (`ytid/ytpend/forceid`) · `mirror_uri` · المتخفي (محتاج تعطيل تاريخ MeeTube) · `hsite` (استخراج) · `.part` · لينك بدون امتداد ميديا · التلفزيون (م4).
- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس، والـAPIs المستخدمة اتأكدت بالاسم (`Engine.versions`، `SubPick.pick/read`، `SubFileTr.translate`، `Dub.build/segsOf`، `DubPlay`، `Downloader.Job`، `TaskCenter.add`، `Store.keyFor`).
