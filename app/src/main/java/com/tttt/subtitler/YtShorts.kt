@file:OptIn(UnstableApi::class)

package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as MPlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bumptech.glide.Glide
import org.schabi.newpipe.extractor.Page

/**
 * (v348) «الشورتس»: صفحة فيديوهات قصيرة بالسحب لفوق/لتحت (زي يوتيوب).
 *  - مشغّل ExoPlayer واحد على TextureView جوه الصفحة (مفيش شاشة مشغّل منفصلة)، الفيديو بيتكرر لحد ما تسحب.
 *  - السحب لفوق = التالي، لتحت = اللي قبله، والصفحة بتنزلق (حركة خروج ودخول). لمسة = إيقاف/تشغيل.
 *  - المصدر: نتايج بحث يوتيوب (#shorts + قنوات من سجلك) مفلترة بالمدة (≤ 3 دقايق) — مش «فيد الشورتس» الرسمي بتاع يوتيوب.
 *  - الرابط بيتجاب بـ YtNp.pick (NewPipe) وبيتحمّل مسبقًا للفيديو اللي بعده عشان السحب يبقى أسرع.
 */
class YtShorts(
    private val act: Activity, private val ui: Ui, private val th: Theme,
    private val onOpen: (NpItem) -> Unit,           // ⤢ فتح في المشغّل الكامل
    private val onSave: (NpItem) -> Boolean         // 🔖 حفظ/شيل (بيرجّع true لو اتحفظ)
) {
    val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
    private val h = Handler(Looper.getMainLooper())
    private val list = ArrayList<NpItem>()
    private val seen = HashSet<String>()
    private var idx = 0
    private var gen = 0
    private var loading = false
    private var animating = false
    private var started = false
    private var muted = false
    private var player: ExoPlayer? = null
    private var vw = 0; private var vh = 0

    // مصدر الفيديوهات: كل استعلام وصفحته التالية
    private val queries = ArrayList<String>()
    private val pages = HashMap<String, Page?>()
    private val started0 = HashSet<String>()
    private var qPos = 0

    // ───────── الواجهة ─────────
    private val page = FrameLayout(act)
    private val tex = TextureView(act)
    private val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
    private val statusTv: TextView = ui.text("", 14f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), 0, ui.dp(24), 0) }
    private val titleTv: TextView = ui.text("", 14f, Color.WHITE, true).apply { maxLines = 3; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private val chanTv: TextView = ui.text("", 12f, 0xFFDDDDDD.toInt(), true).apply { setSingleLine(); setShadowLayer(6f, 0f, 0f, Color.BLACK) }
    private val pauseMark: TextView = ui.text("⏸", 46f, Color.WHITE, true).apply { gravity = Gravity.CENTER; visibility = View.GONE; alpha = 0.85f }
    private val prog = View(act).apply { setBackgroundColor(th.primary) }
    private lateinit var saveBtn: TextView
    private lateinit var muteBtn: TextView

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.duration > 0 && root.width > 0) {
                val w = (root.width * (p.currentPosition.toFloat() / p.duration.toFloat())).toInt().coerceIn(0, root.width)
                (prog.layoutParams as FrameLayout.LayoutParams).width = w; prog.requestLayout()
            }
            h.postDelayed(this, 250L)
        }
    }

    init {
        page.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        page.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        page.addView(statusTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        page.addView(pauseMark, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val info = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(10), ui.dp(70), ui.dp(16)) }
        info.addView(chanTv, LinearLayout.LayoutParams(-1, -2)); info.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        page.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        fun side(t: String, f: () -> Unit) = ui.text(t, 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setOnClickListener { f() }
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x88000000.toInt()); setShape(android.graphics.drawable.GradientDrawable.OVAL) }
        }
        saveBtn = side("🔖") { cur()?.let { v -> val on = onSave(v); paintSave(on); Notice.show(act, if (on) "🔖 اتحفظ" else "اتشال من المحفوظات", 1500L) } }
        muteBtn = side("🔊") { muted = !muted; player?.volume = if (muted) 0f else 1f; muteBtn.text = if (muted) "🔇" else "🔊" }
        val openBtn = side("⤢") { cur()?.let { v -> pause(); onOpen(v) } }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        for (b in listOf(saveBtn, muteBtn, openBtn)) col.addView(b, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { topMargin = ui.dp(12) })
        page.addView(col, FrameLayout.LayoutParams(ui.dp(56), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ui.dp(6) })
        page.addView(prog, FrameLayout.LayoutParams(0, ui.dp(3), Gravity.BOTTOM or Gravity.START))

        val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { toggle(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y; val dx = e2.x - e1.x
                if (Math.abs(dy) > ui.dp(50) && Math.abs(dy) > Math.abs(dx) && Math.abs(vy) > 500f) { go(if (dy < 0) 1 else -1); return true }
                return false
            }
        })
        page.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) applySize() }
    }

    // ───────── واجهة للشاشة ─────────
    fun start() {
        if (!started) { started = true; buildQueries(); more(true) }
        else resume()
        h.removeCallbacks(tick); h.post(tick)
    }
    fun pause() { player?.playWhenReady = false; h.removeCallbacks(tick) }
    fun resume() { if (list.isNotEmpty()) { player?.playWhenReady = true; pauseMark.visibility = View.GONE; h.removeCallbacks(tick); h.post(tick) } }
    fun release() {
        h.removeCallbacksAndMessages(null); gen++
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    private fun cur(): NpItem? = list.getOrNull(idx)
    private fun paintSave(on: Boolean) { saveBtn.text = if (on) "✅" else "🔖" }
    private fun toggle() {
        val p = player ?: return
        val now = !p.playWhenReady; p.playWhenReady = now
        pauseMark.visibility = if (now) View.GONE else View.VISIBLE
    }

    // ───────── السحب ─────────
    private fun go(delta: Int) {
        if (animating) return
        val n = idx + delta
        if (n < 0) { bounce(1f); return }
        if (n >= list.size) { more(false); bounce(-1f); return }
        animating = true
        val hgt = root.height.toFloat().coerceAtLeast(1f)
        val dir = if (delta > 0) -1f else 1f
        page.animate().translationY(dir * hgt).alpha(0.4f).setDuration(170L).withEndAction {
            idx = n; bindCur()
            page.translationY = -dir * hgt; page.alpha = 1f
            page.animate().translationY(0f).setDuration(200L).withEndAction { animating = false }.start()
        }.start()
    }
    private fun bounce(dir: Float) {
        animating = true
        page.animate().translationY(dir * ui.dp(40)).setDuration(110L).withEndAction {
            page.animate().translationY(0f).setDuration(140L).withEndAction { animating = false }.start()
        }.start()
    }

    // ───────── عرض الفيديو الحالي ─────────
    private fun bindCur() {
        val v = cur() ?: return
        val my = ++gen
        try { player?.stop() } catch (_: Throwable) {}
        vw = 0; vh = 0
        titleTv.text = v.title; chanTv.text = v.channel
        paintSave(YtStore.contains(YtStore.SAVED, v))
        pauseMark.visibility = View.GONE
        thumb.visibility = View.VISIBLE
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().into(thumb) else Glide.with(act).clear(thumb) } catch (_: Throwable) {}
        statusTv.text = "⏳"; statusTv.visibility = View.VISIBLE
        Thread {
            try {
                YtNp.init()
                val pk = YtNp.pick(v.id, Cfg.int("yt_maxh", 0).let { if (it <= 0 || it > 720) 720 else it })
                    ?: throw RuntimeException("مفيش رابط تشغيل")
                h.post { if (my == gen) play(pk) }
            } catch (e: Throwable) {
                h.post { if (my == gen) failed((e.message ?: "فشل").take(80)) }
            }
        }.start()
        // تحميل مسبق لرابط اللي بعده (بيتكاش في NewPipe)
        for (k in 1..2) list.getOrNull(idx + k)?.let { nx -> Thread { try { YtNp.init(); YtNp.pick(nx.id, 720) } catch (_: Throwable) {} }.start() }
        if (idx >= list.size - 3) more(false)
    }

    private fun failed(msg: String) {
        statusTv.text = "⚠ $msg\nهنعدّي للتالي…"
        h.postDelayed({ if (cur() != null && statusTv.visibility == View.VISIBLE) go(1) }, 1400L)
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(act).build()
        p.repeatMode = MPlayer.REPEAT_MODE_ONE
        p.volume = if (muted) 0f else 1f
        p.setVideoTextureView(tex)
        p.addListener(object : MPlayer.Listener {
            override fun onRenderedFirstFrame() { thumb.visibility = View.GONE; statusTv.visibility = View.GONE }
            override fun onVideoSizeChanged(vs: VideoSize) { vw = vs.width; vh = vs.height; applySize() }
            override fun onPlayerError(error: PlaybackException) { failed("اتعذّر التشغيل") }
        })
        player = p
        return p
    }

    private fun play(pk: YtPick) {
        val p = ensurePlayer()
        val f = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("User-Agent" to pk.ua)).setAllowCrossProtocolRedirects(true)
        val au = pk.audio
        val src = if (!au.isNullOrBlank()) {
            val pf = ProgressiveMediaSource.Factory(f)
            MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(pk.url)), pf.createMediaSource(MediaItem.fromUri(au)))
        } else if (pk.h == 0 || pk.url.contains("m3u8")) DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(pk.url))
        else ProgressiveMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(pk.url))
        p.setMediaSource(src); p.prepare(); p.playWhenReady = true
    }

    /** فيديو طولي بيملا الشاشة (قص بسيط)، وغير الطولي بيتوسّط بنسبته من غير تمطيط */
    private fun applySize() {
        val p = player ?: return
        val w = root.width; val hh = root.height
        if (w <= 0 || hh <= 0 || vw <= 0 || vh <= 0) return
        val lp = tex.layoutParams as FrameLayout.LayoutParams
        if (vh.toFloat() / vw >= 1.3f) {
            lp.width = -1; lp.height = -1
            p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        } else {
            lp.width = w; lp.height = (w.toFloat() * vh / vw).toInt().coerceAtMost(hh)
            p.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        }
        tex.layoutParams = lp
    }

    // ───────── المصدر ─────────
    private fun buildQueries() {
        queries.clear()
        val hist = YtStore.read(YtStore.HIST).map { it.channel }.filter { it.isNotBlank() }.distinct().take(4)
        for (c in hist) queries.add("#shorts $c")
        queries.addAll(listOf("#shorts", "shorts ترند", "shorts مضحك", "shorts معلومات", "shorts طبخ", "shorts رياضة"))
    }

    /** بيجيب دفعة جديدة (استعلام واحد أو أكتر لحد ما يلاقي فيديوهات قصيرة جديدة) */
    private fun more(first: Boolean) {
        if (loading) return
        loading = true
        if (first) { statusTv.text = "⏳"; statusTv.visibility = View.VISIBLE }
        Thread {
            val got = ArrayList<NpItem>()
            var err: String? = null
            try {
                YtNp.init()
                var tries = 0
                while (got.size < 6 && tries++ < 6 && queries.isNotEmpty()) {
                    val q = queries[qPos % queries.size]; qPos++
                    try {
                        val r = if (!started0.contains(q)) { started0.add(q); YtNp.searchFirst(q, "videos") }
                                else { val pg = pages[q]; if (pg == null) null else YtNp.searchMore(q, "videos", pg) }
                        if (r == null) continue
                        pages[q] = r.next
                        for (v in r.items) if (v.kind == 0 && v.dur in 1L..180L && v.id.isNotBlank() && seen.add(v.id)) got.add(v)
                    } catch (e: Throwable) { err = e.message }
                }
            } catch (e: Throwable) { err = e.message }
            h.post {
                loading = false
                val wasEmpty = list.isEmpty()
                list.addAll(got)
                if (wasEmpty && list.isNotEmpty()) { idx = 0; bindCur() }
                else if (wasEmpty) {
                    started = false   // الدخول الجاي للشورتس يجرّب من الأول
                    statusTv.text = "⚠ ما لقيتش شورتس" + (if (err != null) "\n" + err!!.take(80) else "") + "\n\nاختار «الشورتس» تاني للمحاولة"
                }
            }
        }.start()
    }
}
