# v933 — المرحلة 1 من دمج المشغّل: الملف المحلي / اللينك المباشر يفتح landscape + شاشة كاملة لوحده
- المراجعة: المرحلة 0 (v930) اتراجعت — `JoyboyPlayerHost` (نقط ربط default بـtry/catch) و`MtPlay.decide()` بنفس شروط `reroute` القديمة، ومفيش تغيير سلوك. مفيش حاجة اتعدّلت فيها. (ملحوظة: الخطة كانت مسمّية المراحل v930/v931، بس v931/v932 اتاخدوا لإصلاحات جانبية فالمرحلة 1 = v933.)
- `MobilePlaybackActivity`:
  - `requestOrientationFullscreen(toLandscape, target, windowOrientation)`: نفس الجزء اللي كان آخر `toggleFullscreen` (تابلت/متعدد النوافذ/تلفزيون ← layout override، غير كده force + hand-back + rotation fallback) اتفصل في دالة، والزرار بينادي عليها بنفس السلوك بالظبط.
  - `autoLandscapeIfLocal()`: بتتنده مرة واحدة في `onVideoShapeKnown` (أول فريم، الشكل بقى معروف). بتطلب landscape لو `Video.localUri` موجود (ملف محلي/لينك مباشر) أو تلفزيون أو `JoyboyPlayerHost.wantsLandscape` رجّعت true.
  - مبتعملش حاجة لو: فيديو رأسي (يفضل upright full زي #69) · PiP · Shorts · `mPortraitFull` · الـlayout أصلاً landscape · الشكل لسه مجهول (غير التلفزيون).
  - يوتيوب: مفيش تغيير (بورتريه + زرار ملء الشاشة). زرار ملء الشاشة وBack وhand-back وPiP ما اتلمسوش.
- معدّل: `MobilePlaybackActivity.java` بس. مفيش ملف `.kt` جديد (`kt_manifest.txt` زي ما هو).
- مهم: ده بيظهر على الموبايل بس لما الملف يتفتح في MeeTube، يعني بعد ما `mt_main_player` يتقلب لـtrue (م2)؛ لحد دلوقتي الافتراضي false فالملفات لسه بتفتح على مشغّل Joyboy.
- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس + الـimports (Video / NetPath / Configuration موجودين).
