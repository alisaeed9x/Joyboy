# 📒 JOYBOY SETTINGS OWNERSHIP INVENTORY

> Auto-generated from repo HEAD `d3169383` (v1391). Evidence: every key listed with its storage accessor call-sites (`Cfg.*` in `Core.kt:171` → SharedPreferences `"p"`). Read-only analysis.

## ملخص الأرقام

| مقياس | العدد |
|---|---|
| مفاتيح فريدة (قراءة أو كتابة عبر Cfg) | 292 |
| مفاتيح ليها صف UI (R rows في Settings.kt:505-528) | 24 |
| مفاتيح ليها label عبر sw() | 8 |
| مفاتيح بتتقري بس ومتتكتبش أبدًا | 64 |
| مفاتيح بتتكتب بس ومتتقريش أبدًا (مشتبه يتيمة) | 14 |
| مفاتيح بيستخدمها UI بس (مفيش مستهلك وظيفي خارج ملفات الواجهة) | 44 |
| مفاتيح مالهاش أي قراءة/كتابة (صفوف UI بس) | 3 |

ملاحظة منهجية: «مستهلك وظيفي» = ملف غير واجهي (مش Main/Settings/BrSet/GhCiUi/TvLookPad/KeyVault/NavCustom/PromptsUi/TvEdit/TrWizard/LangPicker) بيقري المفتاح. المفاتيح اللي اسمها «يتيمة» محتاجة تتأكد قبل أي حذف — ممكن تتكتب من مكان ديناميكي (reflection/backup/restore).

## الجدول الكامل (مجموع حسب بادئة المفتاح)

### `tv_*` — 49 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `tv_app_use` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_apps_sys` | — |  | 6 | 3 | TvHome.kt (6) |
| `tv_ask` | security |  | 0 | 1 | — |
| `tv_ask_home` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_autoscale` | tvset | 📺 تكبير الواجهة تلقائيًا على شاشة التلفزيون | 1 | 0 | TvScale.kt (1) |
| `tv_br_mode` | — |  | 1 | 1 | Browser.kt (1) |
| `tv_bri_soft` | — |  | 1 | 1 | TvBright.kt (1) |
| `tv_bri_soft_pct` | — |  | 2 | 1 | TvBright.kt (2) |
| `tv_clock24` | — |  | 4 | 0 | TvHome.kt (4) |
| `tv_fab_always` | — |  | 1 | 1 | TvModeFab.kt (1) |
| `tv_favs` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_fold_sort` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_fold_view` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_halo` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_icon_px` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_icon_shape` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_keymap` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_lift` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_loc` | — |  | 1 | 2 | TvHome.kt (1) |
| `tv_loc_asked` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_names` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_pin` | — |  | 3 | 2 | TvHome.kt (3) |
| `tv_pin_apps` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_pin_folders` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_play` | — |  | 1 | 2 | TvHome.kt (1) |
| `tv_pray` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_pray_method` | — |  | 2 | 1 | PrayerTimes.kt (1), TvHome.kt (1) |
| `tv_receiver` | — |  | 2 | 2 | TvHome.kt (2) |
| `tv_rows_hide2` | — |  | 6 | 3 | TvHome.kt (6) |
| `tv_rows_order` | — |  | 1 | 3 | TvHome.kt (1) |
| `tv_rows_v581` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_saver_min` | — |  | 2 | 0 | TvHome.kt (2) |
| `tv_scale_adj` | — |  | 4 | 1 | TvHome.kt (3), TvScale.kt (1) |
| `tv_scrim` | — |  | 2 | 0 | TvHome.kt (1), TvShell.kt (1) |
| `tv_setup_tried` | — |  | 1 | 2 | TvSetup.kt (1) |
| `tv_side_tabs` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_skin` | — |  | 1 | 2 | TvSkin.kt (1) |
| `tv_sync_off` | tvset |  | 4 | 2 | PlayTarget.kt (2) |
| `tv_temp_c` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_temp_max` | — |  | 2 | 1 | TvHome.kt (2) |
| `tv_temp_min` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_temp_ts` | — |  | 1 | 1 | TvHome.kt (1) |
| `tv_vid_sort` | — |  | 3 | 0 | Player.kt (1), TvHome.kt (1), TvSkinGlass.kt (1) |
| `tv_vid_view` | — |  | 1 | 0 | TvHome.kt (1) |
| `tv_wall` | — |  | 2 | 8 | TvHome.kt (1), TvShell.kt (1) |
| `tv_wall_sec` | — |  | 6 | 3 | TvHome.kt (6) |
| `tv_wall_sec_prev` | — |  | 1 | 2 | TvHome.kt (1) |
| `tv_wall_seen` | — |  | 2 | 1 | TvHome.kt (2) |
| `tv_warm` | — |  | 5 | 1 | TvHome.kt (4), Player.kt (1) |

### `tr_*` — 26 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `tr_autoswap` | — |  | 6 | 3 | Player.kt (3), Engine.kt (2) |
| `tr_bal` | — |  | 3 | 7 | Engine.kt (1), Player.kt (1), TrSlider.kt (1) |
| `tr_dia_last` | — |  | 0 | 1 | — |
| `tr_engine` | audio |  | 2 | 12 | Player.kt (1), TrProfile.kt (1) |
| `tr_kind` | — |  | 1 | 5 | TrProfile.kt (1) |
| `tr_lang` | applang |  | 3 | 1 | GhAudio.kt (1), I18n.kt (1) |
| `tr_method` | audio |  | 1 | 8 | — |
| `tr_mode` | — |  | 3 | 1 | — |
| `tr_mode_id` | — |  | 5 | 13 | Engine.kt (1), Player.kt (1), Strict.kt (1) |
| `tr_one` | — |  | 0 | 7 | — |
| `tr_online` | — |  | 2 | 1 | — |
| `tr_online_dia` | — |  | 1 | 2 | — |
| `tr_online_ext` | — |  | 1 | 1 | — |
| `tr_quota` | — |  | 2 | 3 | — |
| `tr_s_conv` | — |  | 2 | 1 | — |
| `tr_s_dialect` | — |  | 1 | 2 | — |
| `tr_s_engine` | — |  | 1 | 3 | — |
| `tr_s_free` | — |  | 1 | 1 | — |
| `tr_s_lang` | — |  | 1 | 2 | — |
| `tr_s_live` | — |  | 1 | 1 | — |
| `tr_s_method` | — |  | 1 | 2 | — |
| `tr_s_model` | — |  | 1 | 3 | — |
| `tr_s_only` | — |  | 2 | 2 | — |
| `tr_saved` | — |  | 1 | 3 | — |
| `tr_src` | — |  | 1 | 2 | SourceMode.kt (1) |
| `tr_vkind` | — |  | 1 | 2 | SourceMode.kt (1) |

### `yt_*` — 19 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `yt_acc_sync` | ? |  | 1 | 1 | YtChannel.kt (1) |
| `yt_alang` | ? |  | 1 | 2 | YtNp.kt (1) |
| `yt_auto_pip` | ? |  | 3 | 1 | Player.kt (1), YtProUi.kt (1) |
| `yt_client_ver` | — |  | 1 | 0 | YtAccount.kt (1) |
| `yt_dock` | ? |  | 1 | 1 | YtWatch.kt (1) |
| `yt_dub_scan` | ? |  | 1 | 1 | YtWatch.kt (1) |
| `yt_fs_inplace` | player | ⛶ التكبير جوه نفس المشغّل (يوتيوب) | 1 | 0 | YtWatch.kt (1) |
| `yt_hl` | ? |  | 2 | 1 | YtLocale.kt (1) |
| `yt_maxh` | ? |  | 14 | 4 | Player.kt (7), Browser.kt (1), YtProUi.kt (1), YtWatch.kt (1) |
| `yt_mini_auto` | ? |  | 1 | 1 | YtWatch.kt (1) |
| `yt_next_delay` | player | ⏭ ثواني عد «الفيديو الجاي» في يوتيوب | 1 | 0 | Player.kt (1) |
| `yt_np_auth` | ? |  | 2 | 1 | YtNp.kt (1) |
| `yt_page_in_player` | player | ▶ صفحة الفيديو جوه المشغّل (يوتيوب) | 1 | 0 | YtWatch.kt (1) |
| `yt_portrait_clean` | player | 📱 شاشة نضيفة في الوضع الطولي (يوتيوب) | 0 | 0 | — |
| `yt_prewarm` | youtube | 🔥 تسخين يوتيوب في الخلفية | 1 | 0 | YtProUi.kt (1) |
| `yt_search_hist` | — |  | 1 | 1 | YtProUi.kt (1) |
| `yt_shorts_normal` | ? |  | 1 | 1 | YtBackend.kt (1) |
| `yt_sponsor` | ? |  | 1 | 2 | YtNp.kt (1) |
| `yt_subs_official` | ? |  | 2 | 1 | YtProUi.kt (1) |

### `gh_*` — 13 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `gh_audio_enhance` | — |  | 1 | 0 | GhAudio.kt (1) |
| `gh_every` | — |  | 1 | 0 | GhCi.kt (1) |
| `gh_fail_id` | — |  | 0 | 1 | — |
| `gh_fail_name` | — |  | 0 | 1 | — |
| `gh_fail_sum` | — |  | 0 | 2 | — |
| `gh_fail_url` | — |  | 0 | 1 | — |
| `gh_last_prompt` | — |  | 1 | 1 | GhAudio.kt (1) |
| `gh_notified` | — |  | 1 | 1 | GhCi.kt (1) |
| `gh_notify` | — | 🔔 إشعار لما بيلد يفشل | 1 | 0 | GhCi.kt (1) |
| `gh_prelog` | — | 📥 نزّل لوج الفشل مقدمًا | 1 | 0 | GhCi.kt (1) |
| `gh_token` | — |  | 1 | 0 | GhCi.kt (1) |
| `gh_tv_auto` | — | 📺 ابعت التحديث للتلفزيون أول ما يتصل | 1 | 0 | RemoteClient.kt (1) |
| `gh_watch` | — | 🔔 راقب فشل الورك فلوهات | 1 | 0 | GhCi.kt (1) |

### `sub_*` — 13 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `sub_anim` | — |  | 0 | 1 | — |
| `sub_bgopa` | — |  | 3 | 4 | Player.kt (3) |
| `sub_blur` | — |  | 2 | 1 | Player.kt (2) |
| `sub_dual` | — |  | 1 | 3 | Player.kt (1) |
| `sub_font` | — |  | 0 | 6 | — |
| `sub_fontstyle` | — |  | 0 | 1 | — |
| `sub_nobg` | — | إخفاء الخلفية | 3 | 4 | Player.kt (3) |
| `sub_plain` | — | نص أبيض عادي بحجم ثابت (بدون تلوين الأسماء وكلمة التأكيد والتكبير التل | 0 | 0 | — |
| `sub_posy` | — |  | 4 | 2 | Player.kt (4) |
| `sub_scale` | — |  | 6 | 3 | Player.kt (6) |
| `sub_two_lines` | — | الجملة الطويلة على سطرين فوق بعض (عند الفاصلة/النقطة، أو بالنص لو عدّت | 0 | 0 | — |
| `sub_uni_color` | — |  | 1 | 1 | Player.kt (1) |
| `sub_uni_on` | — | لون نص موحّد | 1 | 3 | Player.kt (1) |

### `tvlink_*` — 8 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `tvlink_id` | — |  | 1 | 1 | TvLink.kt (1) |
| `tvlink_last` | — |  | 1 | 3 | RemoteClient.kt (1) |
| `tvlink_name` | — |  | 1 | 0 | TvLink.kt (1) |
| `tvlink_need_code` | — |  | 1 | 0 | TvLink.kt (1) |
| `tvlink_phone_id` | — |  | 1 | 1 | RemoteClient.kt (1) |
| `tvlink_phone_name` | — |  | 1 | 0 | RemoteClient.kt (1) |
| `tvlink_remotes` | — |  | 1 | 1 | TvLink.kt (1) |
| `tvlink_tvs` | — |  | 1 | 1 | RemoteClient.kt (1) |

### `fl_*` — 6 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `fl_gem_key` | — |  | 2 | 1 | FreeLlm.kt (1), Player.kt (1) |
| `fl_groq_key` | — |  | 2 | 1 | FreeLlm.kt (1), Player.kt (1) |
| `fl_srv_key` | — |  | 2 | 1 | FreeLlm.kt (1), Player.kt (1) |
| `fl_srv_model` | — |  | 2 | 1 | FreeLlm.kt (1), Player.kt (1) |
| `fl_url` | — |  | 2 | 1 | FreeLlm.kt (1), Player.kt (1) |
| `fl_use_` | — |  | 1 | 1 | FreeLlm.kt (1) |

### `app_*` — 5 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `app_lang` | applang |  | 1 | 3 | I18n.kt (1) |
| `app_wall` | — |  | 1 | 2 | AppWall.kt (1) |
| `app_wall_scrim` | appearance |  | 2 | 1 | AppWall.kt (1) |
| `app_wall_sec` | — |  | 3 | 1 | AppWall.kt (3) |
| `app_wall_seen` | — |  | 2 | 1 | AppWall.kt (2) |

### `lock_*` — 5 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `lock_bio` | — |  | 1 | 3 | Lock.kt (1) |
| `lock_fails` | — |  | 1 | 4 | Lock.kt (1) |
| `lock_hash` | — |  | 2 | 2 | Lock.kt (2) |
| `lock_salt` | — |  | 1 | 2 | Lock.kt (1) |
| `lock_until` | — |  | 1 | 3 | Lock.kt (1) |

### `mt_*` — 5 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `mt_loader` | — |  | 1 | 1 | MtPlayerLook.kt (1) |
| `mt_loader_on` | — |  | 2 | 1 | MtPlayerLook.kt (2) |
| `mt_model` | — |  | 1 | 4 | LocalMt.kt (1) |
| `mt_player_anim` | — |  | 2 | 1 | MtPlayerLook.kt (2) |
| `mt_tr_mode` | audio |  | 13 | 11 | Player.kt (10), Engine.kt (1) |

### `music_*` — 5 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `music_auto_lyr` | music |  | 3 | 2 | MusicUi.kt (1) |
| `music_mode` | — |  | 1 | 1 | MusicService.kt (1) |
| `music_shuffle` | — |  | 1 | 1 | MusicService.kt (1) |
| `music_speed` | — |  | 1 | 1 | MusicService.kt (1) |
| `music_vol` | — |  | 1 | 1 | MusicService.kt (1) |

### `stt_*` — 5 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `stt_engine` | ? |  | 3 | 1 | Offline.kt (1), TrLib.kt (1) |
| `stt_lang` | ? |  | 1 | 5 | Offline.kt (1) |
| `stt_offline` | audio |  | 11 | 13 | Offline.kt (3), Player.kt (2) |
| `stt_par` | ? |  | 1 | 1 | Offline.kt (1) |
| `stt_vad` | audio | 🎧 تخطّي الصمت أثناء التفريغ على الجهاز (VAD) | 1 | 0 | Offline.kt (1) |

### `groq_*` — 4 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `groq_chunk2` | ? |  | 1 | 0 | — |
| `groq_key` | keys |  | 11 | 2 | Player.kt (3), Engine.kt (1), Onboarding.kt (1) |
| `groq_key_state` | keys |  | 2 | 3 | — |
| `groq_model` | keys |  | 8 | 1 | Engine.kt (5), Player.kt (1) |

### `lw_*` — 4 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `lw_clip_off` | — |  | 3 | 0 | ClipOverlay.kt (1), LinkBubble.kt (1), LinkWatch.kt (1) |
| `lw_hist_off` | capture | 🗑 عدم حفظ سجل الروابط المنسوخة | 3 | 0 | ClipOverlay.kt (1), LinkBubble.kt (1), LinkWatch.kt (1) |
| `lw_toast_off` | capture | 🔕 إخفاء إشعارات الترجمة الحية | 1 | 0 | LinkBubble.kt (1) |
| `lw_web_off` | capture |  | 2 | 1 | LinkWatch.kt (1) |

### `ytp_*` — 4 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ytp_hide_seen` | — |  | 1 | 1 | YtProUi.kt (1) |
| `ytp_hist_on` | ? |  | 1 | 2 | YtNp.kt (1) |
| `ytp_liked` | — |  | 1 | 2 | YtWatch.kt (1) |
| `ytp_notif_off` | ? |  | 0 | 1 | — |

### `auto_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `auto_hard` | general | 🤖 اقتراح الترجمة الصعبة تلقائيًا لو مفيش ترجمة | 1 | 0 | Player.kt (1) |
| `auto_requal` | perf | 🔁 إعادة الباتش تلقائيًا لو جودته ضعيفة | 1 | 0 | Engine.kt (1) |
| `auto_sub` | general | 💬 فتح ملف ترجمة جنب الفيديو تلقائيًا | 4 | 0 | Player.kt (4) |

### `ext_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ext_on_` | — |  | 1 | 2 | ExtTranslate.kt (1) |
| `ext_primary` | — |  | 1 | 2 | ExtTranslate.kt (1) |
| `ext_strict` | perf | 🛡 وضع صارم للمحرك الخارجي | 1 | 0 | ExtTranslate.kt (1) |

### `gest_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `gest_dbl_ms` | — |  | 2 | 1 | MtPlayerLook.kt (2) |
| `gest_levels` | — |  | 1 | 1 | MtPlayerLook.kt (1) |
| `gest_seek` | — |  | 1 | 1 | MtPlayerLook.kt (1) |

### `lyr_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `lyr_dance` | — |  | 1 | 1 | MusicUi.kt (1) |
| `lyr_font` | ? |  | 2 | 2 | MusicUi.kt (1) |
| `lyr_size` | ? |  | 2 | 2 | MusicUi.kt (1) |

### `pf_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `pf_ai` | — |  | 1 | 2 | PrivacyFilter.kt (1) |
| `pf_hosts` | — |  | 2 | 1 | PrivacyFilter.kt (2) |
| `pf_on` | — |  | 1 | 2 | PrivacyFilter.kt (1) |

### `song_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `song_auto_id` | audio | 🎵 التعرف التلقائي على اسم الأغنية | 1 | 0 | SongId.kt (1) |
| `song_lyrics` | — |  | 1 | 1 | SongHint.kt (1) |
| `song_mode` | — |  | 1 | 1 | SongHint.kt (1) |

### `ui_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ui_design` | — |  | 1 | 1 | Theme.kt (1) |
| `ui_mode` | appearance |  | 1 | 1 | DeviceUi.kt (1) |
| `ui_new` | — |  | 1 | 1 | Theme.kt (1) |

### `vis_*` — 3 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `vis_ahead` | perf |  | 2 | 1 | VisualMode.kt (1) |
| `vis_auto` | general | 👁 الوضع البصري التلقائي | 2 | 0 | Player.kt (1), RemoteClient.kt (1) |
| `vis_open_auto` | ? |  | 1 | 1 | VisualMode.kt (1) |

### `ab_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ab_lat_ms` | — |  | 1 | 1 | AudioBridge.kt (1) |
| `ab_saved_vol` | — |  | 2 | 2 | AudioBridge.kt (2) |

### `autoplay_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `autoplay_next` | — |  | 6 | 2 | Player.kt (6) |
| `autoplay_wrap` | — |  | 1 | 2 | Player.kt (1) |

### `conv_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `conv_dialect` | general |  | 22 | 5 | Player.kt (16), TrProfile.kt (3), BgTranslate.kt (1), SubFileTr.kt (1) |
| `conv_via` | — |  | 1 | 1 | Player.kt (1) |

### `ctx_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ctx_batches` | perf | 🧠 عدد الباتشات السابقة اللي بتتبعت كسياق | 1 | 0 | Engine.kt (1) |
| `ctx_min` | ? |  | 5 | 3 | Engine.kt (2), Offline.kt (1) |

### `glass_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `glass_jiggle` | appearance |  | 2 | 1 | MtGlassView.kt (1) |
| `glass_lens` | appearance |  | 2 | 1 | MtGlassView.kt (1) |

### `lang_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `lang` | ? |  | 12 | 2 | Player.kt (4), TrProfile.kt (2), GhAudio.kt (1), LiveCaption.kt (1) |
| `lang_chosen` | applang |  | 1 | 3 | I18n.kt (1) |

### `libre_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `libre_key` | — |  | 3 | 1 | ExtTranslate.kt (2) |
| `libre_url` | — |  | 5 | 1 | ExtTranslate.kt (3) |

### `mus_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `mus_art_rows` | — |  | 1 | 1 | LibraryUi.kt (1) |
| `mus_playlists` | — |  | 1 | 1 | MusicUi.kt (1) |

### `netuse_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `netuse_day` | — |  | 1 | 1 | NetUse.kt (1) |
| `netuse_day_b` | — |  | 1 | 1 | NetUse.kt (1) |

### `player_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `player_btn_look` | player |  | 1 | 1 | MtGlass.kt (1) |
| `player_rot_anim` | player | 🔄 أنيميشن تدوير الشاشة في مشغّل يوتيوب | 1 | 0 | Player.kt (1) |

### `pray_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `pray_cache` | — |  | 1 | 1 | PrayerTimes.kt (1) |
| `pray_loc` | — |  | 1 | 1 | PrayerTimes.kt (1) |

### `rstt_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `rstt_by_plan` | — |  | 1 | 1 | — |
| `rstt_on` | — |  | 0 | 1 | — |

### `subnet_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `subnet_autosync` | general | ⏱ ضبط توقيت ملف الترجمة من النت تلقائيًا | 1 | 0 | Player.kt (1) |
| `subnet_langs` | — |  | 1 | 0 | SubOnline.kt (1) |

### `tone_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `tone_strength` | ? |  | 11 | 5 | Player.kt (4), BgTranslate.kt (1), ExtTranslate.kt (1), SubFileTr.kt (1) |
| `tone_style` | general |  | 14 | 6 | Player.kt (7), BgTranslate.kt (1), ExtTranslate.kt (1), SubFileTr.kt (1) |

### `upd_*` — 2 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `upd_auto` | — |  | 1 | 1 | Upd.kt (1) |
| `upd_skip` | — |  | 2 | 2 | Upd.kt (1), UpdateSheet.kt (1) |

### `acc_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `acc_` | — |  | 1 | 2 | Theme.kt (1) |

### `ahead_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ahead` | ? |  | 1 | 0 | — |

### `alang_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `alang_smart_v623` | — |  | 1 | 1 | YtNp.kt (1) |

### `anim_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `anim_tabs` | appearance |  | 2 | 1 | — |

### `ask_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ask_bg_exit` | — |  | 5 | 2 | Player.kt (5) |

### `atrack_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `atrack` | ? |  | 1 | 0 | — |

### `audd_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `audd_token` | — |  | 1 | 1 | SongId.kt (1) |

### `badge_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `badge_alpha` | look |  | 1 | 1 | — |

### `base_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `base_lang` | ? |  | 3 | 1 | BgTranslate.kt (1), Player.kt (1) |

### `battery_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `battery_ask_ts` | — |  | 1 | 1 | PermAsk.kt (1) |

### `bg_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `bg_autostart` | more |  | 2 | 1 | BootReceiver.kt (1) |

### `big_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `big_text` | appearance |  | 1 | 1 | — |

### `chunk_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `chunk` | ? |  | 1 | 1 | — |

### `dl_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `dl_conns` | — |  | 2 | 1 | SegDl.kt (1), TasksUi.kt (1) |

### `dual_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `dual_listen` | — |  | 5 | 7 | Player.kt (3), Engine.kt (1), TrSlider.kt (1) |

### `eq_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `eq_asked` | — |  | 1 | 1 | MusicUi.kt (1) |

### `exit_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `exit_seen_ts` | — |  | 1 | 1 | CrashLog.kt (1) |

### `fastmode_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `fastmode` | — |  | 4 | 5 | Engine.kt (3), TrSlider.kt (1) |

### `fit_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `fit` | — |  | 2 | 0 | Player.kt (2) |

### `fs_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `fs_fit` | — |  | 2 | 0 | Player.kt (2) |

### `gloss_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `gloss` | ? |  | 2 | 0 | Engine.kt (1) |

### `glossary_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `glossary` | — |  | 1 | 0 | Engine.kt (1) |

### `haptics_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `haptics` | appearance |  | 2 | 1 | — |

### `hard_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `hard_mode` | — |  | 4 | 4 | Player.kt (3), VisualMode.kt (1) |

### `hide_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `hide_btn_dl` | — |  | 1 | 1 | MtPlayerLook.kt (1) |

### `hls_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `hls_ahead` | ? |  | 2 | 0 | AudioIO.kt (1) |

### `keepgoing_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `keepgoing` | perf |  | 2 | 1 | Player.kt (1) |

### `key_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `key_later` | — |  | 2 | 0 | KeyDialog.kt (2) |

### `kmode_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `kmode` | — |  | 3 | 1 | Player.kt (3) |

### `last_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `last_dl_bps` | — |  | 1 | 1 | NetLedger.kt (1) |

### `lib_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `lib_last` | — |  | 0 | 1 | — |

### `logarc_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `logarc_on` | trlog |  | 2 | 1 | Player.kt (1) |

### `loop_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `loop` | — |  | 8 | 4 | Player.kt (8) |

### `mode_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `mode_` | — |  | 1 | 1 | Theme.kt (1) |

### `model_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `model` | ? |  | 13 | 1 | Lyrics.kt (2), Player.kt (2), KeyStatus.kt (1), LiveCaption.kt (1) |

### `off_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `off_model` | ? |  | 1 | 6 | Offline.kt (1) |

### `omni_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `omni_mode` | — |  | 0 | 1 | — |

### `onekey_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `onekey_rest_ms` | perf | ⏱ راحة المفتاح الواحد بين الطلبات (ثانية) | 1 | 0 | FastTr.kt (1) |

### `perm_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `perm_asked_` | security |  | 1 | 1 | — |

### `pip_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `pip_hint_pending` | — |  | 1 | 1 | Player.kt (1) |

### `prefine_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `prefine` | — |  | 1 | 0 | RemoteClient.kt (1) |

### `preset_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `preset_last` | — |  | 1 | 1 | TrLib.kt (1) |

### `prewarm_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `prewarm_tabs` | youtube | 🔥 تسخين التبويبات عند فتح التطبيق | 1 | 0 | — |

### `progress_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `progress_glow` | — |  | 1 | 1 | MtPlayerLook.kt (1) |

### `pub_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `pub_hist_on` | capture | 📜 حفظ سجل الصفحات المعروضة | 1 | 0 | PubHistory.kt (1) |

### `qual_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `qual_pick_default` | — |  | 2 | 1 | Player.kt (2) |

### `ramp_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `ramp_all` | perf | ⚡ تسريع بداية الترجمة (باتشات صغيرة الأول) | 2 | 0 | Engine.kt (1), FastTr.kt (1) |

### `refine2pass_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `refine2pass` | — |  | 1 | 0 | Engine.kt (1) |

### `side_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `side_in_google` | perf | 🌐 استخدام محرك جوجل الجانبي كبديل | 1 | 0 | Engine.kt (1) |

### `speed_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `speed` | — |  | 1 | 0 | Player.kt (1) |

### `style_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `style` | ? |  | 5 | 0 | LiveCaption.kt (1), SubDual.kt (1), SubFileTr.kt (1) |

### `subhunt_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `subhunt_close` | capture | 🧲 قفل صفحة صيد الترجمة بعد ما تتحمّل | 1 | 0 | Browser.kt (1) |

### `term_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `term_notes` | — |  | 1 | 3 | TermNotes.kt (1) |

### `theme_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `theme` | — |  | 12 | 1 | TvUpdate.kt (4), TvInput.kt (2), TvWifi.kt (2), EndOverlay.kt (1) |

### `trstart_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `trstart` | — |  | 1 | 1 | Player.kt (1) |

### `tx_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `tx_order` | — |  | 1 | 1 | ExtTranslate.kt (1) |

### `vc_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `vc_model` | — |  | 1 | 1 | VoiceClean.kt (1) |

### `vid_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `vid_panel` | — |  | 1 | 0 | Browser.kt (1) |

### `viskeys_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `viskeys` | ? |  | 2 | 0 | — |

### `vol_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `vol_boost` | — |  | 9 | 1 | Player.kt (9) |

### `wadv_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `wadv_ok` | — |  | 1 | 1 | Player.kt (1) |

### `warm_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `warm` | ? |  | 2 | 1 | — |

### `whload_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `whload_` | — |  | 1 | 1 | Offline.kt (1) |

### `wifi_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `wifi_only` | — |  | 1 | 0 | NetGate.kt (1) |

### `wn_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `wn_seen` | — |  | 1 | 1 | UpdateSheet.kt (1) |

### `wtr_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `wtr` | — |  | 1 | 1 | Player.kt (1) |

### `xfer_*` — 1 مفتاح

| المفتاح | التبويب/المالك | الليبل | قراءات | كتابات | مستهلك وظيفي (غير UI) |
|---|---|---|---|---|---|
| `xfer_grid` | — |  | 1 | 1 | TransferUi.kt (1) |

## مفاتيح بتتكتب ومتتقريش (مرشحة يتيمة — تحتاج تأكيد)

- `gh_fail_id` — كتابات: GhCi.kt
- `gh_fail_name` — كتابات: GhCi.kt
- `gh_fail_sum` — كتابات: GhCi.kt
- `gh_fail_url` — كتابات: GhCi.kt
- `lib_last` — كتابات: Player.kt
- `omni_mode` — كتابات: TrWizard.kt
- `rstt_on` — كتابات: TrWizard.kt
- `sub_anim` — كتابات: Player.kt
- `sub_font` — كتابات: Player.kt
- `sub_fontstyle` — كتابات: Player.kt
- `tr_dia_last` — كتابات: TrProfile.kt
- `tr_one` — كتابات: Player.kt, TrSlider.kt
- `tv_ask` — كتابات: Main.kt
- `ytp_notif_off` — كتابات: Main.kt

## مفاتيح بتتقري ومتتكتبش من الكود (قيمها افتراضية أو من نسخة احتياطية)

- `ahead` — قراءات: Main.kt
- `atrack` — قراءات: Main.kt
- `auto_hard` — قراءات: Player.kt
- `auto_requal` — قراءات: Engine.kt
- `auto_sub` — قراءات: Player.kt
- `ctx_batches` — قراءات: Engine.kt
- `ext_strict` — قراءات: ExtTranslate.kt
- `fit` — قراءات: Player.kt
- `fs_fit` — قراءات: Player.kt
- `gh_audio_enhance` — قراءات: GhAudio.kt
- `gh_every` — قراءات: GhCi.kt
- `gh_notify` — قراءات: GhCi.kt
- `gh_prelog` — قراءات: GhCi.kt
- `gh_token` — قراءات: GhCi.kt
- `gh_tv_auto` — قراءات: RemoteClient.kt
- `gh_watch` — قراءات: GhCi.kt
- `gloss` — قراءات: Engine.kt, Main.kt
- `glossary` — قراءات: Engine.kt
- `groq_chunk2` — قراءات: Main.kt
- `hls_ahead` — قراءات: AudioIO.kt, Main.kt
- `key_later` — قراءات: KeyDialog.kt
- `lw_clip_off` — قراءات: ClipOverlay.kt, LinkBubble.kt, LinkWatch.kt
- `lw_hist_off` — قراءات: ClipOverlay.kt, LinkBubble.kt, LinkWatch.kt
- `lw_toast_off` — قراءات: LinkBubble.kt
- `onekey_rest_ms` — قراءات: FastTr.kt
- `player_rot_anim` — قراءات: Player.kt
- `prefine` — قراءات: RemoteClient.kt
- `prewarm_tabs` — قراءات: Main.kt
- `pub_hist_on` — قراءات: PubHistory.kt
- `ramp_all` — قراءات: Engine.kt, FastTr.kt
- `refine2pass` — قراءات: Engine.kt
- `side_in_google` — قراءات: Engine.kt
- `song_auto_id` — قراءات: SongId.kt
- `speed` — قراءات: Player.kt
- `stt_vad` — قراءات: Offline.kt
- `style` — قراءات: LiveCaption.kt, Main.kt, PromptsUi.kt, SubDual.kt
- `subhunt_close` — قراءات: Browser.kt
- `subnet_autosync` — قراءات: Player.kt
- `subnet_langs` — قراءات: SubOnline.kt
- `tv_autoscale` — قراءات: TvScale.kt
- `tv_clock24` — قراءات: TvHome.kt
- `tv_fold_sort` — قراءات: TvHome.kt
- `tv_fold_view` — قراءات: TvHome.kt
- `tv_halo` — قراءات: TvHome.kt
- `tv_icon_px` — قراءات: TvHome.kt
- `tv_icon_shape` — قراءات: TvHome.kt
- `tv_lift` — قراءات: TvHome.kt
- `tv_names` — قراءات: TvHome.kt
- `tv_saver_min` — قراءات: TvHome.kt
- `tv_scrim` — قراءات: TvHome.kt, TvShell.kt
- `tv_vid_sort` — قراءات: Player.kt, TvHome.kt, TvSkinGlass.kt
- `tv_vid_view` — قراءات: TvHome.kt
- `tvlink_name` — قراءات: TvLink.kt
- `tvlink_need_code` — قراءات: TvLink.kt
- `tvlink_phone_name` — قراءات: RemoteClient.kt
- `vid_panel` — قراءات: Browser.kt
- `vis_auto` — قراءات: Player.kt, RemoteClient.kt
- `viskeys` — قراءات: Main.kt
- `wifi_only` — قراءات: NetGate.kt
- `yt_client_ver` — قراءات: YtAccount.kt
- `yt_fs_inplace` — قراءات: YtWatch.kt
- `yt_next_delay` — قراءات: Player.kt
- `yt_page_in_player` — قراءات: YtWatch.kt
- `yt_prewarm` — قراءات: YtProUi.kt
