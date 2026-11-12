# v928 — «تشغّله فين؟» جوه MeeTube + يوتيوب على التلفزيون بيفتح في مشغّل MeeTube
- وضع التلفزيون شغّال + تلفزيون مقترن (ريموت ← يوتيوب ← MeeTube): دوسة على أي فيديو بتطلّع نافذة «📺 على التلفزيون / 📱 على الموبايل» (MtTvRoute ← PlayTarget.askWhere). الموبايل = مشغّل MeeTube على الموبايل. التلفزيون = PlayTarget.force.
- الربط في MeeTube: `PlaybackPresenter.setOpenInterceptor` (جديد في :common، بيتنده في openVideo(Video) بس لو المشغّل مش قدّام). مفيش تلفزيون مقترن/وضع التلفزيون مقفول = MeeTube عادي من غير سؤال.
- على التلفزيون: `TvDirect.playYt` بيفتح الفيديو في مشغّل MeeTube (`MeeTubeLink.playYtOnTv`). يوتيوب بترجمة (video_tr) لسه على مشغّل Joyboy.
- ريموت الموبايل بيتحكم في مشغّل MeeTube على التلفزيون: `MtTvPlayer` (تشغيل/إيقاف · تقديم/تأخير · seekto · سرعة · إغلاق + حالة العنوان/المكان/المدة) عبر `TvLink.playerCmd` لما مفيش مشغّل Joyboy شغّال.
- زرار «📺 تشغيل على التلفزيون» اتشال من مشغّل MeeTube (MtTranslate). قايمة ⚙ فيها «ابعت للتلفزيون» لسه.
- ملفات جديدة: MtTvRoute.kt · MtTvPlayer.kt (متسجّلين في kt_manifest.txt). معدّل: PlaybackPresenter.java · MeeTubeLink · MtTranslate · TvDirect · TvLink · JoyApp.
- لم يتم compile ولا اتجرّب. نقط تتراجع على جهاز: (1) مشغّل MeeTube على التلفزيون (واجهته لمس، التحكم من الموبايل) (2) أزرار الريموت غير المدعومة بتتجاهل.
