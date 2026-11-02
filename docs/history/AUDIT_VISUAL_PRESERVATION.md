# AUDIT_VISUAL_PRESERVATION — الهوية البصرية الواجب الحفاظ عليها

الحالة: جرد من الكود والموارد فقط. **لا screenshots ولا مقارنة قبل/بعد** (لا محاكي). لم يتم تغيير أي شيء بصري.

- **سمات**: Theme.kt / Skin.kt / Glass.kt / YtSkin.kt (يوتيوب له سكين خاص). السمة تُخزَّن في Cfg مفتاح `theme` (افتراضي `mx`).
- **سمة التلفزيون**: TvHome.kt:102 — مرة واحدة فقط (`tv_black_v491`) تكتب `theme=amoled_w` عندما الوضع تلفزيون. (المفتاح عام يخص الواجهتين — انظر AUDIT_DEFECTS D-05.)
- **حلقة التركيز**: TvTiles.kt `TvTileView`: إطار أبيض 3dp، نصف قطر+5، تكبير 1.07، translationZ 10dp، مدة 130ms. `TvFocus.ring/mark` للعناصر العامة؛ `TvOwnFocus` للعناصر التي ترسم تركيزها بنفسها.
- **خلفيات اللانشر**: assets/wall/{sunset_mountains,forest_lake,desert_dusk}.jpg؛ تعتيم `tv_scrim` افتراضي 50% (TvShell.kt:28).
- **Splash/أيقونات**: res/drawable-nodpi (splash_art, splash_blur, logo, no_poster)، drawable-land-nodpi/splash_art، mipmap-* (ic_launcher + foreground)، values/values-v31/values-night (themes_splash, colors_splash, colors_launcher).
- **حركات**: res/anim (act_enter/act_exit/act_stay/nav_in_*/nav_out_*) + NavAnim.kt؛ `TvActivity`/`tvShell` يلغي الانتقال (`overridePendingTransition(0,0)`).
- **تحجيم التلفزيون**: TvScale.kt (42 سطر)، `compactUi = screenWidthDp < 720` في TvHome.kt:114 (ريل جانبي على الشاشات الضيقة).
- **متطلب الحفظ**: لا تُستبدل Views بغيرها؛ لا تغيير ألوان/تخطيطات؛ لا لمس لـ index.html.
- **غير مفحوص**: ألوان كل سمة، الخطوط (SubStyle: 22 خطاً حسب نص الواجهة)، أبعاد اللمس، CSS داخل index.html.
