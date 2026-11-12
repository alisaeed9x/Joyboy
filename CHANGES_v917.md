# v917 (مشغّل يوتيوب)
- السبب الجذري: التطبيق RTL (عربي) فـ end/start اتقلبوا (end = شمال)، والكود الأصلي لـ MeeTube متكتب على إن end = يمين.
- زرار تكبير الشاشة: العايم (mobile_watch_fullscreen_fab) layout_gravity bottom|right بدل bottom|end؛ وصف الأزرار تحت الفيديو (mobile_player_bottom_row) layoutDirection=ltr فالوقت شمال وتكبير الشاشة يمين.
- الكارت المصغّر (سحب الفيديو لتحت): كان بيتحرك يمين من المشغّل (اتجاه LTR) وبعدين يلزق شمال لأن مكانه الأصلي end = شمال. اتثبّت يمين: activity_mobile_browse.xml (constraintRight + marginRight) و mobile_mini_player_overlay.xml (bottom|right + marginRight)، و MiniCardSwipe.snapToCorner بقى بيقرا rightMargin.
لم يتم compile ولا اتجرّب.
