# v930 — المرحلة 0 من دمج المشغّل: نقط ربط + قرار توجيه بسبب (من غير تغيير سلوك)
الهدف النهائي: مشغّل MeeTube يبقى المشغّل الوحيد + محرك الترجمة + تحكم التلفزيون (الخطة: `docs/JOYBOY_PLAYER_MERGE_PLAN.md`، الحالة: `docs/PLAYER_MERGE_STATE.md`).
- `JoyboyPlayerHost` (meetube/mobile): `VideoInfo.sourceKind()` + `headers()`، و`Provider.wantsLandscape/onKey/onControlsVisibility/extraControls` — كلهم `default` فاضيين، ومعاهم dispatchers static بـtry/catch. لسه مفيش حد بينديهم (`MobilePlaybackActivity` ما اتلمستش).
- `MtPlay`: `decide(act, intent): Decision(ok, reason, src)` بنفس شروط `reroute` القديمة بالظبط (بدون آثار جانبية)؛ `reroute` بتستخدمها وبتكتب سبب البقاء على مشغّل Joyboy في اللوج. `mt_main_player` الافتراضي لسه false.
- الأرشيف: `docs/JOYBOY_PLAYER_MERGE_PLAN.md` (الخطة الكاملة للموديل الجاي) + `docs/PLAYER_MERGE_STATE.md` (حالة المراحل).
- معدّل: `JoyboyPlayerHost.java` · `MtPlay.kt`. مفيش ملف `.kt` جديد (`kt_manifest.txt` زي ما هو).
- السلوك على الجهاز: **مفيش أي تغيير** — كل الإضافات default/no-op والمنطق القديم بنفس الترتيب.
- لم يتم compile ولا اتجرّب. تحقق ثابت: توازن الأقواس في الملفين.
