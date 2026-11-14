# Player.kt ↔ MeeTube-integrated player (MtBar & co.) — Feature Parity Audit (v1013)
**المنهج:** مقارنة بالسورس (grep + قراءة المواضع). لا اختبار تشغيل. «موجود» = الكود موجود، مش «متجرّب».

| الميزة | Player.kt | MtBar/Mt* | ملاحظة |
|---|---|---|---|
| تشغيل/إيقاف، ⏮⏭ | ✓ (`togglePlay`, `stepOrSeek`) | ✓ | Player.kt: ⏮⏭ = حلقة مكتبة ← جملة ← ±10ث |
| قفز جملة | ✓ (`starts` في `stepOrSeek`) | ✓ (`jumpSentence`) | متكافئ |
| تقديم/ترجيع + ريموت | ✓ (MEDIA_FF/REWIND/STEP، 0-9) | ✓ (NUMPAD في MtTranslate.onKey) | |
| سرعة | ✓ (`speedPanel`) | ✓ | |
| A-B / تكرار | ✓ (`abSet/abClear`, `loopCycle`) | ✓ (MtMedia) | |
| مؤقت نوم / تضخيم صوت | ✓ | ✓ | |
| PiP / تدوير / قفل / 4 أوضاع fit | ✓ | ✓ | |
| صوت فقط | ✓ (`audioOnly`) | ✓ | |
| لقطة / رؤية بصرية | ✓ (`snapFrame`, visual) | ✓ | |
| ترجمة: لهجة/فصل صوت/دفعات/شخصيات/لوج/تصدير SRT/تزامن | ✓ | ✓ | |
| جودة / CC / filmstrip | ✓ | ✓ | filmstrip في Player.kt بيشتغل مع الجلاس بس (`SeekFilmstrip`) |
| إيماءات: دبل-تاب، سطوع، تكبير | ✓ (GestureDetector, `screenBrightness`) | (MeeTube الأصلي) | |
| Audio attributes | ✓ (`setAudioAttributes` USAGE_MEDIA) | — | |
| MediaSession/إشعار | عبر `KeepAliveService` | MeeTube | مش مدموج بين الاتنين |
| **✨ تحسين الصورة (MtEnhance)** | ✗ → **✓ v1013 (متوصّل، لم يُجرَّب)** | ✓ | زرار في قايمة ☰ |
| **مطابقة معدل الشاشة + تنبيه الديكودر (MtSmooth)** | ✗ → **✓ v1013 (متوصّل، لم يُجرَّب)** | ✓ | كان `install()` عام بس؛ الـ`attach` كان لـMeeTube فقط |
| كاست/ريموت/TvQueue/handoff/mirror/.part | ✓ فقط | ✗ | مقصود (Player.kt بس) |

## الخلاصة
معظم الأدوات كانت موجودة بالفعل في Player.kt. الفجوتين الحقيقيتين كانتا MtEnhance وMtSmooth وانقفلوا في v1013.

## قيود/حدود
- ما فحصتش «أزرار ظاهرة من غير وظيفة» بشكل شامل في 7619 سطر؛ فحصت قايمة الأدوات المقابلة لـMtBar فقط.
- `MtTvPlayer` (التلفزيون) مش ضمن النطاق: له علم `mt_tv_player=false` ومرحلة 5 (إزالة القديم) محظورة بقرار سابق.
- MtEnhance على Player.kt: بيشتغل عن طريق `setVideoEffects` على نفس ExoPlayer؛ ما اتأكدتش إن سطح العرض في Player.kt بيسجّل المؤثرات (في MeeTube احتاج «دورة ريندرر» في v992).
