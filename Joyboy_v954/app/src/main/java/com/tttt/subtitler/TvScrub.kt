package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * (v569) شريط التقديم/التأخير على التلفزيون بالريموت العادي: بيظهر أول ما تدوس يمين/شمال (والشريط الكبير مخفي)
 * ويعرض: الدقيقة اللي هتروحلها (يسار) · مدة الفيديو كله (يمين) · شريط تقدّم بمؤشر + علامة المكان اللي كنت فيه · الفرق (+30ث / −1:00).
 * الضغط المتكرر بيحرّك المؤشر بس (من غير ما يعمل seek في كل دوسة)، والـ seek بيتنفّذ لما تسيب الزرار شوية أو تدوس OK.
 * الكلاس ده عرض بس — المنطق (التجميع والتثبيت) في Player.tvSeek.
 */
class TvScrubOverlay(act: Activity, ui: Ui, private val accent: Int) {
    val view = LinearLayout(act)
    private val delta = TextView(act)
    private val pos = TextView(act)
    private val total = TextView(act)
    private val bar = Bar(act, accent)

    init {
        view.apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; visibility = View.GONE
            isFocusable = false; isClickable = false
            setPadding(ui.dp(22), ui.dp(14), ui.dp(22), ui.dp(16))
            background = GradientDrawable().apply { setColor(0xE6101216.toInt()); cornerRadius = ui.dp(18).toFloat(); setStroke(ui.dp(1), 0x33FFFFFF) }
        }
        delta.apply { textSize = 22f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; text = "" }
        for (t in listOf(pos, total)) t.apply { textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; text = "00:00" }
        total.setTextColor(0xFFB8BDC6.toInt())
        view.addView(delta, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        row.addView(pos, LinearLayout.LayoutParams(ui.dp(92), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, ui.dp(34), 1f))
        row.addView(total, LinearLayout.LayoutParams(ui.dp(92), -2).apply { marginStart = ui.dp(6) })
        view.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE

    /** [target] المكان الجديد · [origin] المكان اللي بدأت منه · [dur] مدة الفيديو (0 = مجهولة) */
    fun show(target: Long, origin: Long, dur: Long) {
        pos.text = PlayerLogic.clock(target)
        total.text = if (dur > 0) PlayerLogic.clock(dur) else "--:--"
        val d = target - origin
        delta.text = if (d == 0L) "" else (if (d > 0) "⏩ +" else "⏪ −") + span(Math.abs(d))
        bar.frac = if (dur > 0) (target.toFloat() / dur).coerceIn(0f, 1f) else 0f
        bar.orig = if (dur > 0) (origin.toFloat() / dur).coerceIn(0f, 1f) else -1f
        bar.invalidate()
        view.visibility = View.VISIBLE
    }

    fun hide() { view.visibility = View.GONE }

    private fun span(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) PlayerLogic.clock(ms) else "$s ث"
    }

    private class Bar(ctx: Context, private val accent: Int) : View(ctx) {
        var frac = 0f
        var orig = -1f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isFocusable = false; isClickable = false }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val th = 8f * d; val cy = height / 2f; val w = width.toFloat()
            p.color = 0x55FFFFFF; c.drawRoundRect(RectF(0f, cy - th / 2, w, cy + th / 2), th / 2, th / 2, p)
            p.color = accent; c.drawRoundRect(RectF(0f, cy - th / 2, w * frac, cy + th / 2), th / 2, th / 2, p)
            if (orig >= 0f) { p.color = 0xFFFFFFFF.toInt(); val x = (w * orig).coerceIn(2f * d, w - 2f * d); c.drawRect(x - 1.5f * d, cy - 9f * d, x + 1.5f * d, cy + 9f * d, p) }   // علامة «كنت هنا»
            p.color = Color.WHITE; c.drawCircle((w * frac).coerceIn(10f * d, w - 10f * d), cy, 11f * d, p)
        }
    }
}


/**
 * (v809) لقطة معاينة فوق شريط التقدم وإنت بتسحب (زي يوتيوب): للملفات اللي على الموبايل وروابط الفيديو المباشرة (mp4…).
 * روابط البث (m3u8/mpd) وصفحات يوتيوب ما بتطلعش لقطات — بيفضل الوقت بس. الاستخراج على thread لوحده وآخر طلب هو اللي بيتنفّذ.
 */
class ScrubThumbs(private val act: Activity) {
    private val iv = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        val d = act.resources.displayMetrics.density
        background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = 10f * d; setStroke((2 * d).toInt(), 0xFFFFFFFF.toInt()) }
        clipToOutline = true
    }
    private var pop: PopupWindow? = null
    private var mmr: android.media.MediaMetadataRetriever? = null
    private var openedFor: String? = null
    private var failedFor: String? = null
    @Volatile private var want = -1L
    @Volatile private var running = false
    @Volatile private var closed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val w = (160 * act.resources.displayMetrics.density).toInt()
    private val h = (90 * act.resources.displayMetrics.density).toInt()

    private fun usable(src: String?): Boolean {
        if (src.isNullOrBlank()) return false
        val l = src.lowercase()
        if (l.startsWith("http")) return !(l.contains(".m3u8") || l.contains(".mpd") || l.contains("youtube.com/") || l.contains("youtu.be/"))
        return true
    }

    /** [anchor] الشريط · [frac] مكان الصباع 0..1 · [ms] الوقت المقابل */
    fun update(anchor: View, frac: Float, ms: Long) {
        val src = PlayTarget.nowSrc
        if (closed || !usable(src) || failedFor == src) return
        want = ms
        try {
            val x = (frac.coerceIn(0f, 1f) * anchor.width - w / 2f).toInt().coerceIn(0, (anchor.width - w).coerceAtLeast(0))
            val y = -(anchor.height + h + (10 * act.resources.displayMetrics.density).toInt())
            val p = pop
            if (p == null || !p.isShowing) {
                val np = PopupWindow(iv, w, h, false).apply { isTouchable = false; isFocusable = false; elevation = 12f }
                np.showAsDropDown(anchor, x, y); pop = np
            } else p.update(anchor, x, y, w, h)
        } catch (_: Throwable) {}
        kick(src!!)
    }

    private fun kick(src: String) {
        if (running) return
        running = true
        Thread {
            try {
                while (!closed) {
                    val t = want; if (t < 0) break
                    want = -1L
                    try {
                        if (openedFor != src) {
                            try { mmr?.release() } catch (_: Throwable) {}
                            val m = android.media.MediaMetadataRetriever()
                            if (src.startsWith("http")) m.setDataSource(src, HashMap(PlayTarget.nowHeaders.let { h0 -> HashMap<String, String>().apply { h0["ua"]?.let { put("User-Agent", it) }; h0["ref"]?.let { put("Referer", it) }; h0["cookie"]?.let { put("Cookie", it) } } }))
                            else if (src.startsWith("content:") || src.startsWith("file:")) m.setDataSource(act, android.net.Uri.parse(src))
                            else m.setDataSource(src)
                            mmr = m; openedFor = src
                        }
                        val bmp = mmr?.getFrameAtTime(t * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bmp != null) {
                            val sc = Bitmap.createScaledBitmap(bmp, w, h, true)
                            if (sc !== bmp) bmp.recycle()
                            main.post { if (!closed) iv.setImageBitmap(sc) }
                        }
                    } catch (e: Throwable) {
                        failedFor = src; openedFor = null
                        try { mmr?.release() } catch (_: Throwable) {}
                        mmr = null
                        main.post { hide() }
                        break
                    }
                }
            } finally { running = false }
        }.also { it.isDaemon = true }.start()
    }

    fun hide() { want = -1L; try { pop?.dismiss() } catch (_: Throwable) {}; pop = null }
    fun close() { closed = true; hide(); Thread { try { mmr?.release() } catch (_: Throwable) {}; mmr = null }.also { it.isDaemon = true }.start() }
}
