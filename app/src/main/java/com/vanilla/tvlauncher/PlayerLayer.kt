@file:OptIn(UnstableApi::class)

package com.vanilla.tvlauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مشغّل داخلي (media3 ExoPlayer — D-008 محدَّث) كطبقة فوق الشاشة، مش Activity منفصلة → Back بيعدّي على BackRouter (D-002).
 * P5b (مرجع: مشغّل Subtitler v475، D-011): تركيز الصوت + فصل الصوت لو السماعة اتفصلت، ترجمة مدمجة (نصية) بحجم قابل للتغيير،
 * قايمة إعدادات بالريموت (صوت/ترجمة/سرعة/صورة/حجم الترجمة/نوم/A-B/تكرار/تلقائي)، نط بالأرقام، ملاءمة الصورة،
 * بطاقة «التالي بعد 5 ث»، تنبيه «صوت من غير صورة»، وإبقاء الشاشة صاحية أثناء التشغيل.
 * الصيغ: mkv/mp4/webm/ts/mov بمستخرجات media3؛ الفيديو والصوت بمفكّكات الجهاز (decoder fallback مفعّل) — الدعم الفعلي بيعتمد على شريحة التلفزيون.
 * AVI/WMV مش مدعومين. لو فشل التشغيل الداخلي بيتفتح بمشغّل خارجي لو موجود.
 * P15 (D-018): ترجمة خارجية (.srt/.vtt/.ass) بمنتقي ملفات SAF أو من مجلد التطبيق `files/subs` (من غير صلاحيات جديدة) + رسم ترجمة صورة (PGS/DVB) + إعدادات أوسع. VobSub مش مدعوم.
 * التحكم: ◀ ▶ ±10ث (تسارع) · OK إيقاف/تشغيل · ▲ ▼ أو MENU قايمة الإعدادات · أرقام = نط لوقت · ⏮ ⏭ قايمة التشغيل فقط.
 */
class PlayerLayer(private val act: Activity, private val ui: Ui, private val th: Theme, private val onClosed: () -> Unit) {
    val root = FrameLayout(act).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val surface = SurfaceView(act)
    private val subTv: TextView = ui.text("", 26f, 0xFFFFFFFF.toInt(), true).apply {
        gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt()); setBackgroundColor(0x77000000)
        setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)); visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    private val bmpSub = BitmapSubView(act)
    private val titleTv: TextView = ui.text("", 20f, 0xFFFFFFFF.toInt(), true)
    private val timeTv: TextView = ui.text("", 18f, 0xFFFFFFFF.toInt(), true)
    private val stateTv: TextView = ui.text("", 40f, 0xFFFFFFFF.toInt(), true)
    private val hintTv: TextView = ui.text("◀ ▶ تقديم/تأخير · OK إيقاف · ▲ ▼ قايمة (صوت/ترجمة/سرعة/صورة) · أرقام: نط لوقت · ⏮ ⏭ السابق/التالي", 13f, 0xFFCCCCCC.toInt())
    private val nextTv: TextView = ui.text("", 18f, 0xFFFFFFFF.toInt(), true).apply {
        visibility = View.GONE; setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12)); background = ui.box(0xEE111111.toInt(), th.primary, 12); layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    private val barFill = View(act).apply { setBackgroundColor(th.primary) }
    private val barRest = View(act).apply { setBackgroundColor(0x55FFFFFF) }
    private val controls = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(32), ui.dp(16), ui.dp(32), ui.dp(20)); setBackgroundColor(0xAA000000.toInt()) }
    private val panel = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); background = ui.box(0xEE111111.toInt(), 0, 16)
    }
    private val h = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var queue: PlayQueue? = null
    private var cur: VideoItem? = null
    private var prepared = false
    private var endedHandled = false
    private var lastSaveAt = 0L
    private var hideAt = 0L
    private var lastSeekKeyAt = 0L
    private var seekStreak = 0

    // حالة المشغّل
    private var speedIdx = Cfg.int("player_speed_i", PlayerLogic.speeds.indexOf(1f)).coerceIn(0, PlayerLogic.speeds.size - 1)   // P15: السرعة الافتراضية من الإعدادات (التغيير من اللوحة للفيديو ده بس)
    private var fit = Cfg.int("player_fit", 0).coerceIn(0, 2)
    private var subScale = PlayerLogic.clampSub(Cfg.int("sub_scale", 100))
    private var autoNext = Cfg.bool("player_autonext", true)
    private var loopOne = false
    private var ab = PlayerLogic.AB()
    private var sleepMin = 0
    private var sleepDeadline = 0L
    private var vidW = 0
    private var vidH = 0
    private var firstFrame = false
    private var startId = 0
    @Volatile private var released = false   // P11: بعد release() أي نتيجة خيط متأخرة تتجاهل
    private var ytRetry = 0                  // P11: عدد إعادات جلب رابط يوتيوب المنتهي في نفس التشغيل (أقصى 2)
    private var extLabel: String? = null        // اسم ملف الترجمة الخارجية المتحمّل مع الفيديو الحالي (للاختيار التلقائي ولعرضه)
    private var extPending = false              // لسه ما اخترناش مسار الترجمة الخارجية بعد التحضير
    private var tvDigits = ""
    private var digitRun: Runnable? = null

    // اللوحة وبطاقة «التالي»
    private var panelOpen = false
    private var panelIdx = 0
    private var panelHideAt = 0L
    private var nextUpOn = false
    private var nextUpDeadline = 0L
    private var nextUpItem: VideoItem? = null

    val active: Boolean get() = root.visibility == View.VISIBLE
    val playing: Boolean get() = active && prepared && (player?.isPlaying == true)

    private class Row(val label: () -> String, val change: (Int) -> Unit)
    private val rows: List<Row> = listOf(
        Row({ "🔊 الصوت: " + audioLabel() }, { d -> changeAudio(d) }),
        Row({ "💬 الترجمة: " + subLabel() }, { d -> changeSub(d) }),
        Row({ "📄 ترجمة خارجية: " + extRowLabel() }, { d -> changeExt(d) }),
        Row({ "⏩ السرعة: " + PlayerLogic.speedLabel(PlayerLogic.speeds[speedIdx]) }, { d -> changeSpeed(d) }),
        Row({ "▣ الصورة: " + PlayerLogic.fitNames[fit] }, { d -> changeFit(d) }),
        Row({ "🔤 حجم الترجمة: $subScale%" }, { d -> changeSubScale(d) }),
        Row({ "🎨 لون الترجمة: " + PlayerLogic.SUB_COLORS[PlayerLogic.idxOf(PlayerLogic.SUB_COLORS, Cfg.int("sub_color", 0xFFFFFFFF.toInt()))].second }, { d -> changeSubColor(d) }),
        Row({ "▭ خلفية الترجمة: " + PlayerLogic.SUB_BGS[PlayerLogic.idxOf(PlayerLogic.SUB_BGS, Cfg.int("sub_bg", 0x77000000))].second }, { d -> changeSubBg(d) }),
        Row({ "⏱ خطوة التقديم: " + Cfg.int("seek_base", 10) + " ثانية" }, { d -> changeSeekBase(d) }),
        Row({ "😴 مؤقت النوم: " + PlayerLogic.sleepLabel(sleepMin) }, { d -> changeSleep(d) }),
        Row({ "🔂 حلقة A-B: " + PlayerLogic.abLabel(ab) }, { _ -> pressAB() }),
        Row({ "🔁 تكرار الفيديو: " + (if (loopOne) "شغّال" else "معطّل") }, { _ -> toggleLoop() }),
        Row({ "⏭ تشغيل التالي تلقائيًا: " + (if (autoNext) "شغّال" else "متوقف") }, { _ -> toggleAutoNext() })
    )
    private val rowViews = ArrayList<TextView>()
    private val panelFoot: TextView = ui.text("", 12f, 0xFFAAAAAA.toInt())
    private val PANEL_ROWS = 9   // اللوحة أطول من الشاشة → نافذة صفوف حوالين المختار

    init {
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFit() }
        root.addView(bmpSub, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))   // P15: ترجمة مصوّرة (بنفس مقاس السطح — applyFit)
        root.addView(subTv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(ui.dp(80), 0, ui.dp(80), ui.dp(130)) })
        stateTv.gravity = Gravity.CENTER
        root.addView(stateTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        controls.addView(titleTv)
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(barFill, LinearLayout.LayoutParams(0, ui.dp(6), 0f)); bar.addView(barRest, LinearLayout.LayoutParams(0, ui.dp(6), 1f))
        controls.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(10), 0, ui.dp(8)) })
        controls.addView(timeTv); controls.addView(hintTv)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(nextTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(ui.dp(24), ui.dp(24), ui.dp(24), 0) })
        for (r in rows) {
            val tv = ui.text("", 19f, 0xFFFFFFFF.toInt(), true).apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) }
            rowViews.add(tv); panel.addView(tv, LinearLayout.LayoutParams(ui.dp(440), -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) })
        }
        panel.addView(panelFoot.apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), 0) })
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(24), 0) })
        applySubSize(); applySubStyle()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> stateTv.text = "…"
                Player.STATE_READY -> { if (!prepared) { prepared = true; showControls() }; endedHandled = false; if (stateTv.text == "…") stateTv.text = "" }
                Player.STATE_ENDED -> onEnded()
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!prepared) return
            if (isPlaying) { if (stateTv.text == "⏸") stateTv.text = "" }
            else if (player?.playbackState == Player.STATE_READY) { saveNow(); stateTv.text = "⏸"; showControls() }   // وقف (يدوي أو تركيز الصوت راح لتطبيق تاني)
        }
        override fun onRenderedFirstFrame() { firstFrame = true }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                vidW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1); vidH = videoSize.height
                applyFit()
            }
        }
        override fun onCues(cueGroup: CueGroup) {
            val t = cueGroup.cues.filter { it.bitmap == null }.mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }.joinToString("\n")
            subTv.text = t; subTv.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
            bmpSub.setCues(cueGroup.cues.filter { it.bitmap != null })   // P15: ترجمة مصوّرة (PGS/DVB)
        }
        override fun onPlayerError(error: PlaybackException) {
            // P11: رابط يوتيوب بينتهي بعد ساعات → لو كان شغّال (prepared) نجيب رابط جديد ونكمّل من نفس الموضع (أقصى مرتين)
            val v = cur
            if (v != null && YtLogic.isYt(v.uri) && prepared && ytRetry < 2) { ytRetry++; retryYt(v); return }
            onFailed(error.errorCodeName)
        }
        override fun onTracksChanged(tracks: Tracks) {
            // رسالة صريحة لما كودك مش مدعوم على الجهاز (بدل شاشة سودة/صمت من غير تفسير)
            for (g in tracks.groups) {
                if (g.length == 0 || g.isSupported) continue
                val mime = g.getTrackFormat(0).sampleMimeType ?: "?"
                if (g.type == C.TRACK_TYPE_VIDEO) Toast.makeText(act, "كودك الفيديو ($mime) مش مدعوم على التلفزيون ده", Toast.LENGTH_LONG).show()
                else if (g.type == C.TRACK_TYPE_AUDIO && tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.isSupported })
                    Toast.makeText(act, "كودك الصوت ($mime) مش مدعوم — الفيديو هيشتغل بدون صوت", Toast.LENGTH_LONG).show()
            }
            if (extPending) selectExt()
            if (panelOpen) refreshPanel()
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val rf = DefaultRenderersFactory(act).setEnableDecoderFallback(true)   // نفس إعداد Subtitler: لو مفكّك فشل جرّب اللي بعده
        val p = ExoPlayer.Builder(act, rf).build()
        // نفس Subtitler: المشغّل بيمسك تركيز الصوت (تطبيق تاني يشغّل صوت → بيقف)، وبيقف لو الصوت بقى «noisy» (سماعة/HDMI اتفصلت)
        try { p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); p.setHandleAudioBecomingNoisy(true) } catch (_: Throwable) { }
        // ترجمة عربي تتختار لوحدها لو موجودة في الملف
        try { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredTextLanguage("ar").build() } catch (_: Throwable) { }
        p.setVideoSurfaceView(surface); p.addListener(listener); p.setPlaybackSpeed(PlayerLogic.speeds[speedIdx])
        player = p; return p
    }

    fun open(q: PlayQueue) {
        val first = q.current ?: return
        queue = q; root.visibility = View.VISIBLE; root.keepScreenOn = true   // الشاشة تفضل صاحية أثناء المشاهدة
        start(first)
        h.removeCallbacks(ticker); h.post(ticker)
    }

    private fun start(v: VideoItem, resumeOverride: Long = -1L) {
        saveNow()
        cur = v; prepared = false; endedHandled = false; lastSaveAt = 0L; firstFrame = false; vidW = 0; vidH = 0
        ab = PlayerLogic.AB(); loopOne = false; ytRetry = 0; extLabel = null; extPending = false; hideNextUp(); closePanel()
        subTv.text = ""; subTv.visibility = View.GONE; bmpSub.setCues(emptyList())
        titleTv.text = v.name; timeTv.text = ""; stateTv.text = "…"
        val id = ++startId
        h.postDelayed({
            if (active && id == startId && prepared && !firstFrame && player?.isPlaying == true)
                Toast.makeText(act, "الصوت شغّال بس مفيش صورة — غالبًا كودك الفيديو (${videoMime()}) مش مدعوم على الجهاز ده", Toast.LENGTH_LONG).show()
        }, 6000L)
        if (YtLogic.isYt(v.uri)) { startYt(v, id); showControls(); return }   // P8: يوتيوب (NewPipe) — D-013
        val resume = if (resumeOverride >= 0L) resumeOverride else if (Cfg.bool("player_resume", true)) HistoryLogic.resumePos(HistoryStore.entryFor(v)) else 0L
        try {
            val p = ensurePlayer()
            p.repeatMode = Player.REPEAT_MODE_OFF
            applyPrefs(p); applySubStyle()
            p.setMediaItem(buildItem(v), resume)
            p.prepare(); p.playWhenReady = true
        } catch (e: Throwable) { onFailed(e.javaClass.simpleName) }
        showControls()
    }

    /**
     * تشغيل يوتيوب: جلب الرابط بـ NewPipe في خيط خلفي (شبكة)، وبعدين المصدر على الـ Main.
     * id == startId بيلغي النتيجة لو المستخدم عدّى لفيديو تاني/قفل المشغّل أثناء الجلب. صوت منفصل = MergingMediaSource.
     */
    private fun retryYt(v: VideoItem) {
        val pos = posMs()
        Toast.makeText(act, "رابط يوتيوب انتهى — بنجدّده…", Toast.LENGTH_SHORT).show()
        stateTv.text = "…"
        startYt(v, ++startId, pos)
    }

    private fun startYt(v: VideoItem, id: Int, resumeMs: Long = 0L) {
        val vid = YtLogic.idOf(v.uri)
        Thread {
            var st: YtStream? = null
            try { st = YtNp.pick(vid, Cfg.int("yt_maxh", 1080)) } catch (_: Throwable) { }
            val got = st
            h.post {
                if (released || !active || id != startId) return@post
                if (got == null) {
                    Toast.makeText(act, "تعذّر جلب الفيديو من يوتيوب — اتأكد من النت (أو يوتيوب غيّر حاجة وده محتاج تحديث للمكتبة)", Toast.LENGTH_LONG).show()
                    close(save = false); return@post
                }
                try {
                    val p = ensurePlayer()
                    p.repeatMode = Player.REPEAT_MODE_OFF
                    val f = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("User-Agent" to got.ua)).setAllowCrossProtocolRedirects(true)
                    val src = if (got.audio != null) {
                        val pf = ProgressiveMediaSource.Factory(f)
                        MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(got.url))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(got.audio))))
                    } else DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(Uri.parse(got.url)))
                    if (resumeMs > 0L) p.setMediaSource(src, resumeMs) else p.setMediaSource(src)
                    p.prepare(); p.playWhenReady = true
                } catch (e: Throwable) { onFailed(e.javaClass.simpleName) }
            }
        }.apply { isDaemon = true }.start()
    }

    // ===== P15: ترجمة خارجية (D-018) =====
    private fun subMap(): List<Pair<String, String>> = SubLogic.decodeMap(Cfg.str("sub_map", ""))
    private fun subsDir(): File? = try { File(act.getExternalFilesDir(null) ?: act.filesDir, "subs") } catch (_: Throwable) { null }

    /** ترجمة الفيديو: (1) ملف اختاره المستخدم (لو لسه مقروء) (2) ملف بنفس اسم الفيديو في files/subs. null = مفيش */
    private fun extSubFor(v: VideoItem): Pair<Uri, String>? {
        val m = subMap(); val u = SubLogic.mapGet(m, v.key)
        if (u != null) {
            val uri = Uri.parse(u)
            val ok = try { act.contentResolver.openInputStream(uri)?.use { true } ?: false } catch (_: Throwable) { false }
            if (ok) return uri to (SubLogic.mapGet(m, "n|" + v.key) ?: SubLogic.shortName(u))
            Cfg.put("sub_map", SubLogic.encodeMap(SubLogic.mapRemove(SubLogic.mapRemove(m, v.key), "n|" + v.key)))   // الإذن/الملف راح → فكّ الربط
            Toast.makeText(act, "ملف الترجمة المربوط مبقاش متاح — اتشال الربط", Toast.LENGTH_LONG).show()
        }
        val d = subsDir() ?: return null
        val name = try { SubLogic.matchSidecars(v.name, d.list()?.toList() ?: emptyList()).firstOrNull() } catch (_: Throwable) { null } ?: return null
        return Uri.fromFile(File(d, name)) to name
    }

    private fun buildItem(v: VideoItem): MediaItem {
        val ext = try { extSubFor(v) } catch (_: Throwable) { null }
        val mime = ext?.let { SubLogic.mimeFor(it.second) }
        if (ext == null || mime == null) return MediaItem.fromUri(Uri.parse(v.uri))
        extLabel = ext.second; extPending = true
        val sc = MediaItem.SubtitleConfiguration.Builder(ext.first).setMimeType(mime).setLanguage(SubLogic.langOf(ext.second)).setLabel(ext.second).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        return MediaItem.Builder().setUri(Uri.parse(v.uri)).setSubtitleConfigurations(listOf(sc)).build()
    }

    /** أول ما المسارات تتحدد: اختار ترجمة الملف الخارجي (بدل ما تتغلب بترجمة مدمجة بلغة مفضّلة) */
    private fun selectExt() {
        val p = player ?: return; val lab = extLabel ?: run { extPending = false; return }
        val g = textGroups().firstOrNull { it.getTrackFormat(0).label == lab } ?: return
        extPending = false
        if (!g.isSelected) try { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build() } catch (_: Throwable) { }
    }

    private fun extRowLabel(): String = extLabel ?: "مفيش (OK لاختيار ملف · ◀ إزالة)"

    private fun changeExt(dir: Int) {
        val v = cur ?: return
        if (YtLogic.isYt(v.uri)) { flash("مش متاحة ليوتيوب", 1200L); return }
        if (dir < 0) {   // إزالة الربط (ملف subs بنفس الاسم لسه بيتحمّل لو موجود)
            val m = subMap()
            if (SubLogic.mapGet(m, v.key) == null) { flash("مفيش ملف مربوط", 1200L); return }
            Cfg.put("sub_map", SubLogic.encodeMap(SubLogic.mapRemove(SubLogic.mapRemove(m, v.key), "n|" + v.key)))
            reloadKeepingPos(v); flash("اتشال ربط الترجمة", 1200L); return
        }
        pickSub()
    }

    @Suppress("DEPRECATION")
    private fun pickSub() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            act.startActivityForResult(i, 45)
        } catch (_: Throwable) {
            Toast.makeText(act, "مفيش مُنتقي ملفات — حطّ الملف (باسم الفيديو) في: " + (subsDir()?.absolutePath?.substringAfter("/Android/", "Android/data/…/files/subs") ?: "files/subs"), Toast.LENGTH_LONG).show()
        }
    }

    /** نتيجة المُنتقي (من HomeActivity.onActivityResult) — data = null لو اتلغى */
    fun onSubPicked(data: Intent?) {
        val v = cur ?: return; val uri = data?.data ?: return
        var name: String? = null
        try { act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) } } catch (_: Throwable) { }
        val n = name ?: SubLogic.shortName(uri.toString())
        if (!SubLogic.isSubName(n)) { Toast.makeText(act, "الملف ده مش ترجمة مدعومة (srt / vtt / ass / ssa / ttml)", Toast.LENGTH_LONG).show(); return }
        try { act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }   // لو المزوّد مش بيدعم → بيشتغل الجلسة دي بس
        Cfg.put("sub_map", SubLogic.encodeMap(SubLogic.mapPut(SubLogic.mapPut(subMap(), v.key, uri.toString()), "n|" + v.key, n)))
        reloadKeepingPos(v)
        Toast.makeText(act, "اتحمّلت: $n", Toast.LENGTH_SHORT).show()
    }

    private fun reloadKeepingPos(v: VideoItem) { val pos = posMs(); start(v, pos) }

    /** تفضيلات اللغة (من الإعدادات): الصوت "" = زي ما هو · الترجمة "" = عربي لو موجودة (السلوك القديم) */
    private fun applyPrefs(p: ExoPlayer) {
        try {
            val a = Cfg.str("pref_audio", ""); val t = Cfg.str("pref_sub", "").ifEmpty { "ar" }
            val b = p.trackSelectionParameters.buildUpon().setPreferredTextLanguage(t)
            if (a.isNotEmpty()) b.setPreferredAudioLanguage(a) else b.setPreferredAudioLanguage(null)
            p.trackSelectionParameters = b.build()
        } catch (_: Throwable) { }
    }

    private fun changeSubColor(dir: Int) {
        val l = PlayerLogic.SUB_COLORS.map { it.first }
        Cfg.put("sub_color", PlayerLogic.cycle(l, Cfg.int("sub_color", l[0]), dir)); applySubStyle()
    }
    private fun changeSubBg(dir: Int) {
        val l = PlayerLogic.SUB_BGS.map { it.first }
        Cfg.put("sub_bg", PlayerLogic.cycle(l, Cfg.int("sub_bg", 0x77000000), dir)); applySubStyle()
    }
    private fun changeSeekBase(dir: Int) { Cfg.put("seek_base", PlayerLogic.cycle(PlayerLogic.SEEK_BASES, Cfg.int("seek_base", 10), dir)) }

    private fun videoMime(): String = player?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }?.getTrackFormat(0)?.sampleMimeType ?: "?"

    private fun posMs(): Long = try { player?.currentPosition ?: 0L } catch (_: Throwable) { 0L }
    private fun durMs(): Long = try { (player?.duration ?: 0L).let { if (it < 0) 0L else it } } catch (_: Throwable) { 0L }

    /** حفظ دوري (10ث) + عند الإيقاف/pause/الخروج. «مكتمل» بقاعدة HistoryLogic أو انتهاء حقيقي فقط. */
    private fun saveNow() {
        val v = cur ?: return; if (!prepared) return
        if (YtLogic.isYt(v.uri)) return   // D-014: يوتيوب ما بيتسجّلش في سجل المكتبة المحلية
        HistoryStore.record(act, v, posMs(), durMs()); lastSaveAt = System.currentTimeMillis()
    }

    private fun onEnded() {
        if (endedHandled) return; endedHandled = true
        val v = cur
        if (v != null && !YtLogic.isYt(v.uri)) HistoryStore.record(act, v, 0, durMs(), ended = true)
        if (sleepMin == -1) { sleepMin = 0; stateTv.text = "😴"; showControls(); return }   // مؤقت النوم «آخر الفيديو»
        val q = queue
        if (q != null && q.hasNext()) {
            if (autoNext) showNextUp(q.items[q.index + 1]) else { stateTv.text = "✓"; showControls() }
        } else close(save = false)
    }

    /** فشل التشغيل الداخلي: رسالة بالكود + محاولة أخيرة بمشغّل خارجي (لو موجود) ثم نقفل */
    private fun onFailed(code: String) {
        val v = cur
        Toast.makeText(act, "تعذّر التشغيل الداخلي ($code)", Toast.LENGTH_LONG).show()
        if (v != null && YtLogic.isYt(v.uri)) { close(save = false); return }   // مفيش مشغّل خارجي لرابط يوتيوب الداخلي
        close(save = true)
        if (v != null && openExternal(v)) Toast.makeText(act, "اتفتح بمشغّل تاني", Toast.LENGTH_SHORT).show()
    }

    private fun openExternal(v: VideoItem): Boolean = try {
        act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.uri), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); true
    } catch (_: Throwable) { false }

    fun close(save: Boolean = true) {
        if (!active) return
        if (save) saveNow()
        h.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Throwable) { }
        player = null; prepared = false; cur = null; queue = null
        panelOpen = false; panel.visibility = View.GONE; hideNextUp()
        ab = PlayerLogic.AB(); sleepMin = 0; sleepDeadline = 0L; tvDigits = ""; digitRun = null
        subTv.text = ""; subTv.visibility = View.GONE; stateTv.text = ""
        root.keepScreenOn = false; root.visibility = View.GONE
        onClosed()
    }

    /** Back: لوحة الإعدادات أو بطاقة «التالي» تتقفل الأول (خطوة واحدة)، وبعدها يقفل المشغّل. */
    fun onBack() { when { panelOpen -> closePanel(); nextUpOn -> { hideNextUp(); stateTv.text = "✓"; showControls() }; else -> close() } }

    /** P11: تحرير نهائي (من onDestroy) — بدون onClosed ولا لمس الواجهة */
    fun release() {
        try { if (active) saveNow() } catch (_: Throwable) { }
        h.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Throwable) { }
        released = true
        player = null; prepared = false
    }

    fun onPause() { if (active) { saveNow(); try { player?.pause() } catch (_: Throwable) { }; showControls() } }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (prepared) {
                val pos = posMs(); val dur = durMs()
                val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                (barFill.layoutParams as LinearLayout.LayoutParams).weight = frac; (barRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - frac
                barFill.requestLayout()
                val q = queue; val sp = PlayerLogic.speeds[speedIdx]
                val ends = if (playing && dur > 0) "     ينتهي " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + ((dur - pos) / sp).toLong())) else ""
                timeTv.text = LibraryLogic.formatDur(pos) + " / " + LibraryLogic.formatDur(dur) +
                    (if (sp != 1f) "     ${PlayerLogic.speedLabel(sp)}" else "") +
                    (if (q != null && q.items.size > 1) "     ${q.index + 1}/${q.items.size}" else "") + ends +
                    (if (ab.active) "     🔂 A-B" else "") + (if (loopOne) "     🔁" else "") + (if (sleepMin != 0) "     😴" else "")
                if (System.currentTimeMillis() - lastSaveAt >= 10_000L && playing) saveNow()
                val jt = PlayerLogic.abJumpTarget(ab, pos); if (jt >= 0) player?.seekTo(jt)
            }
            if (sleepDeadline > 0L && now >= sleepDeadline) fireSleep()
            if (nextUpOn) {
                val s = PlayerLogic.nextUpSecs(nextUpDeadline, now)
                if (s <= 0) playNextNow() else nextTv.text = "التالي: ${nextUpItem?.name ?: ""}\nبعد $s ث · OK تشغيل الآن · Back إلغاء"
            }
            if (panelOpen && now > panelHideAt) closePanel()
            if (controls.visibility == View.VISIBLE && playing && System.currentTimeMillis() > hideAt) controls.visibility = View.GONE
            h.postDelayed(this, if (ab.active) 200L else 500L)
        }
    }

    private fun showControls() { controls.visibility = View.VISIBLE; hideAt = System.currentTimeMillis() + Cfg.int("hide_secs", 4).coerceIn(2, 30) * 1000L }
    private fun flash(s: String, ms: Long = 700L) { stateTv.text = s; h.postDelayed({ if (stateTv.text == s) stateTv.text = "" }, ms) }

    // ===== ملاءمة الصورة =====
    private fun applyFit() {
        val w = root.width; val hh = root.height
        if (w <= 0 || hh <= 0) return
        val (sw, sh) = if (vidW > 0 && vidH > 0) PlayerLogic.fitSize(w, hh, vidW, vidH, fit) else (w to hh)
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != sw || lp.height != sh) { lp.width = sw; lp.height = sh; lp.gravity = Gravity.CENTER; surface.layoutParams = lp }
        (bmpSub.layoutParams as FrameLayout.LayoutParams).let { bl -> if (bl.width != sw || bl.height != sh) { bl.width = sw; bl.height = sh; bl.gravity = Gravity.CENTER; bmpSub.layoutParams = bl } }
    }
    private fun applySubSize() { subTv.textSize = 26f * subScale / 100f }
    private fun applySubStyle() {
        val bg = Cfg.int("sub_bg", 0x77000000)
        subTv.setTextColor(Cfg.int("sub_color", 0xFFFFFFFF.toInt())); subTv.setBackgroundColor(bg)
        subTv.setShadowLayer(if ((bg ushr 24) < 0x40) 10f else 6f, 0f, 0f, 0xFF000000.toInt())   // بدون خلفية = ظل أقوى عشان تتقري
    }

    // ===== التحكم =====
    private fun seekBy(dir: Int) {
        val p = player ?: return; if (!prepared) return
        if (nextUpOn) { hideNextUp(); stateTv.text = "" }
        val now = System.currentTimeMillis()
        seekStreak = if (now - lastSeekKeyAt < 700L) seekStreak + 1 else 0
        lastSeekKeyAt = now
        try { p.setSeekParameters(if (seekStreak >= 1) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT) } catch (_: Throwable) { }   // ضغط متتابع = أسرع (أقرب كيفريم)
        val dur = durMs()
        p.seekTo((posMs() + dir * PlayerLogic.seekStep(seekStreak, Cfg.int("seek_base", 10))).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE))
        flash(if (dir > 0) "▶▶" else "◀◀", 500L); showControls()
    }

    private fun toggle() {
        val p = player ?: return; if (!prepared) return
        if (p.isPlaying) p.pause()
        else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L); p.play() }
        showControls()
    }

    private fun jump(n: VideoItem?) { if (n != null) start(n) else flash("—", 600L) }

    // نط بالأرقام: رقمين أو أقل = ثواني · أكتر = دقايق+ثواني (OK أو استنى ثانيتين)
    private fun tvDigit(d: Int) {
        if (!prepared) return
        if (tvDigits.length >= 4) tvDigits = ""
        tvDigits += d.toString()
        flash("⏱ روح لـ " + LibraryLogic.formatDur(PlayerLogic.digitsToSec(tvDigits) * 1000L), 2200L); showControls()
        digitRun?.let { h.removeCallbacks(it) }
        val r = Runnable { tvJump() }; digitRun = r; h.postDelayed(r, 2200L)
    }
    private fun tvJump() {
        val buf = tvDigits; tvDigits = ""; digitRun?.let { h.removeCallbacks(it) }; digitRun = null
        if (buf.isEmpty()) return
        val dur = durMs(); val t = (PlayerLogic.digitsToSec(buf) * 1000L).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        try { player?.seekTo(t) } catch (_: Throwable) { }
        flash("⏱ " + LibraryLogic.formatDur(t), 1100L); showControls()
    }

    // ===== الصوت والترجمة =====
    private fun langName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "؟"
        return try { Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("ar")).ifBlank { code } } catch (_: Throwable) { code }
    }
    private fun trackName(g: Tracks.Group): String {
        val f = g.getTrackFormat(0)
        return (f.label?.takeIf { it.isNotBlank() } ?: langName(f.language)) + (if (g.type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) " · ${f.channelCount}ch" else "") + (if (g.type == C.TRACK_TYPE_TEXT && PlayerLogic.isBitmapSub(f.sampleMimeType)) " 🖼" else "")
    }
    private fun audioGroups(): List<Tracks.Group> = player?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported } ?: emptyList()
    private fun textGroups(): List<Tracks.Group> = player?.currentTracks?.groups?.filter {
        it.type == C.TRACK_TYPE_TEXT && it.isSupported && it.length > 0   // P15: الصورية (PGS/DVB) بقت متعرضة كمان — بتترسم في BitmapSubView
    } ?: emptyList()

    private fun audioLabel(): String { val g = audioGroups(); return if (g.isEmpty()) "—" else (g.firstOrNull { it.isSelected } ?: g[0]).let { trackName(it) } + (if (g.size > 1) "  (${g.size} مسارات)" else "") }
    private fun subLabel(): String {
        val g = textGroups(); if (g.isEmpty()) return "مفيش ترجمة في الملف"
        return g.firstOrNull { it.isSelected }?.let { trackName(it) } ?: "مقفولة"
    }

    private fun changeAudio(dir: Int) {
        val p = player ?: return; val g = audioGroups()
        if (g.size < 2) { flash("مفيش مسار صوت تاني", 1200L); return }
        val i = g.indexOfFirst { it.isSelected }.let { if (it < 0) 0 else it }
        val n = g[((i + dir) % g.size + g.size) % g.size]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(n.mediaTrackGroup, 0)).build()
        flash("🔊 " + trackName(n), 1500L)
    }

    /** الترتيب: مقفولة ← ترجمة 1 ← ترجمة 2 … ← مقفولة */
    private fun changeSub(dir: Int) {
        val p = player ?: return; val g = textGroups()
        if (g.isEmpty()) { flash("مفيش ترجمة في الملف", 1400L); return }
        val cur = g.indexOfFirst { it.isSelected } + 1   // 0 = مقفولة
        val n = ((cur + dir) % (g.size + 1) + (g.size + 1)) % (g.size + 1)
        val b = p.trackSelectionParameters.buildUpon()
        if (n == 0) { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); subTv.text = ""; subTv.visibility = View.GONE; bmpSub.setCues(emptyList()); flash("💬 الترجمة: مقفولة", 1200L) }
        else { b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g[n - 1].mediaTrackGroup, 0)); flash("💬 " + trackName(g[n - 1]), 1500L) }
        p.trackSelectionParameters = b.build()
    }

    private fun changeSpeed(dir: Int) {
        val l = PlayerLogic.speeds.indices.toList()
        speedIdx = PlayerLogic.cycle(l, speedIdx, dir)
        try { player?.setPlaybackSpeed(PlayerLogic.speeds[speedIdx]) } catch (_: Throwable) { }
    }
    private fun changeFit(dir: Int) { fit = PlayerLogic.cycle(listOf(0, 1, 2), fit, dir); Cfg.put("player_fit", fit); applyFit() }
    private fun changeSubScale(dir: Int) { subScale = PlayerLogic.clampSub(subScale + dir * PlayerLogic.SUB_STEP); Cfg.put("sub_scale", subScale); applySubSize() }
    private fun changeSleep(dir: Int) {
        sleepMin = PlayerLogic.cycle(PlayerLogic.sleepOptions, sleepMin, dir)
        sleepDeadline = if (sleepMin > 0) SystemClock.uptimeMillis() + sleepMin * 60_000L else 0L
    }
    private fun fireSleep() {
        sleepDeadline = 0L; sleepMin = 0
        try { player?.pause() } catch (_: Throwable) { }
        saveNow(); stateTv.text = "😴"; showControls()
    }
    private fun pressAB() { ab = PlayerLogic.abPress(ab, posMs()) }
    private fun toggleLoop() { loopOne = !loopOne; try { player?.repeatMode = if (loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF } catch (_: Throwable) { } }
    private fun toggleAutoNext() { autoNext = !autoNext; Cfg.put("player_autonext", autoNext) }

    // ===== بطاقة «التالي» =====
    private fun showNextUp(n: VideoItem) {
        nextUpItem = n; nextUpOn = true; nextUpDeadline = SystemClock.uptimeMillis() + 5000L
        nextTv.text = "التالي: ${n.name}\nبعد 5 ث · OK تشغيل الآن · Back إلغاء"; nextTv.visibility = View.VISIBLE; showControls()
    }
    private fun hideNextUp() { nextUpOn = false; nextUpItem = null; nextTv.visibility = View.GONE }
    private fun playNextNow() { hideNextUp(); val n = queue?.next(); if (n != null) start(n) }

    // ===== اللوحة =====
    private fun openPanel() {
        if (!prepared) return
        panelOpen = true; panelIdx = panelIdx.coerceIn(0, rows.size - 1); panelHideAt = SystemClock.uptimeMillis() + 10_000L
        panel.visibility = View.VISIBLE; refreshPanel(); showControls()
    }
    private fun closePanel() { panelOpen = false; panel.visibility = View.GONE }
    private fun refreshPanel() {
        val st = PlayerLogic.panelStart(rows.size, panelIdx, PANEL_ROWS)
        for (i in rows.indices) {
            val vis = i >= st && i < st + PANEL_ROWS
            rowViews[i].visibility = if (vis) View.VISIBLE else View.GONE
            if (vis) rowViews[i].text = rows[i].label()
            rowViews[i].background = if (i == panelIdx) ui.box(0xFF2A2F38.toInt(), th.primary, 10) else null
        }
        panelFoot.text = "▲▼ تنقّل (${panelIdx + 1}/${rows.size}) · ◀ ▶ تغيير · OK تبديل · Back إغلاق"
    }
    private fun panelKey(k: Int, down: Boolean, repeat: Int): Boolean {
        val n = rows.size
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) panelIdx = (panelIdx + n - 1) % n }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) panelIdx = (panelIdx + 1) % n }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) rows[panelIdx].change(-1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) rows[panelIdx].change(1) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (down && repeat == 0) rows[panelIdx].change(1) }
            KeyEvent.KEYCODE_MENU -> { if (down) { closePanel(); return true } }
            else -> return false
        }
        panelHideAt = SystemClock.uptimeMillis() + 10_000L; refreshPanel(); return true
    }

    /** يستهلك مفاتيح التحكم فقط. BACK مش هنا إطلاقًا — بيمر على BackRouter (D-002). الصوت (VOLUME) بيعدّي للنظام. */
    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return false
        if (panelOpen && panelKey(k, down, e.repeatCount)) return true
        when (k) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) { if (nextUpOn) playNextNow() else if (tvDigits.isNotEmpty()) tvJump() else toggle() }; return true
            }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down && e.repeatCount == 0) toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down && prepared && player?.isPlaying == false) toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down && prepared && player?.isPlaying == true) toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { if (down) close(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) seekBy(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) seekBy(-1); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (down && e.repeatCount == 0) openPanel(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { if (down && e.repeatCount == 0) changeSub(1); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { if (down && e.repeatCount == 0) changeAudio(1); return true }
            KeyEvent.KEYCODE_INFO -> { if (down) showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (down && e.repeatCount == 0) { hideNextUp(); jump(queue?.next()) }; return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (down && e.repeatCount == 0) { hideNextUp(); jump(queue?.prev()) }; return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if (down && e.repeatCount == 0) tvDigit(k - KeyEvent.KEYCODE_0); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { if (down && e.repeatCount == 0) tvDigit(k - KeyEvent.KEYCODE_NUMPAD_0); return true }
        }
        return false
    }
}
